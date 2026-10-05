package com.github.tvbox.osc.cast;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.server.LanCastRelayRules;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.util.MediaRelayCleanup;
import com.github.tvbox.osc.util.OkGoHelper;

import org.brotli.dec.BrotliInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Request;
import okhttp3.ResponseBody;

/**
 * A short-lived, media-only HTTP endpoint for DLNA renderers. TVs fetch the exact media URL
 * pushed by the phone; they never receive a LAN-management session or pairing code.
 */
public final class CastMediaRelay {
    private static volatile CastMediaRelay activeRelay;
    private static final Pattern PATH = Pattern.compile(
            "^/media/([0-9a-f]{64})/([0-9a-f]{32})/stream\\.[a-z0-9]{1,8}$");
    private static final Pattern RANGE = Pattern.compile("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicLong GENERATIONS = new AtomicLong();
    private static final long IDLE_MS = TimeUnit.MINUTES.toMillis(20);
    private static final long MAX_LIFE_MS = TimeUnit.HOURS.toMillis(6);
    private static final int MAX_PLAYLIST_BYTES = 4 * 1024 * 1024;
    // A four MiB VOD manifest can contain well over 4096 short segments. Keep the
    // registry bounded, but allow long ordinary movies to register in full.
    static final int MAX_TARGETS = 16384;
    static final int MAX_TARGET_URL_CHARS = 8 * 1024 * 1024;
    private static final ScheduledExecutorService EXPIRY = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mbox-cast-media-expiry");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Session current;
    private volatile MediaServer server;
    private ScheduledFuture<?> expiryTask;

    /** Immutable global entry for a successfully submitted DLNA cast; contains no media URL. */
    public static final class ActiveCast {
        public final long generation;
        public final String deviceName;
        public final boolean stopping;

        private ActiveCast(Session session, RendererBinding renderer) {
            generation = renderer.generation;
            deviceName = renderer.device.name;
            stopping = session.stopping;
        }
    }

    private static final class RendererBinding {
        final long generation;
        final DlnaController.Device device;

        RendererBinding(long generation, DlnaController.Device device) {
            this.generation = generation;
            this.device = device;
        }
    }

    public long generation() {
        Session session = current;
        return session == null ? -1 : session.generation;
    }

    /** Called on SOAP success, including when the initiating dialog has already closed. */
    public synchronized boolean bindRenderer(long expectedGeneration, DlnaController.Device device) {
        Session session = current;
        if (device == null || activeRelay != this || session == null || session.closed
                || session.generation != expectedGeneration) return false;
        session.renderer = new RendererBinding(expectedGeneration, device);
        try { CastMediaService.start(session.context, expectedGeneration); }
        catch (RuntimeException error) {
            LogStore.fail(Category.PLAYER, "投屏媒体服务: 更新投屏通知失败="
                    + error.getClass().getSimpleName());
        }
        return true;
    }

    public static ActiveCast activeCast() {
        CastMediaRelay relay = activeRelay;
        Session session = relay == null ? null : relay.current;
        RendererBinding renderer = session == null ? null : session.renderer;
        return renderer == null || session.closed || renderer.generation != session.generation
                || relay != activeRelay || session != relay.current ? null : new ActiveCast(session, renderer);
    }

    /** Stops the captured DLNA cast only; local playback and browser casting are independent. */
    public static boolean cancelActiveCast(long expectedGeneration, DlnaController.CastCallback callback) {
        CastMediaRelay relay = activeRelay;
        Session session = relay == null ? null : relay.current;
        if (session == null) return false;
        final RendererBinding renderer;
        synchronized (session) {
            renderer = session.renderer;
            if (renderer == null || renderer.generation != expectedGeneration || session.closed
                    || session.stopping || relay != activeRelay || relay.current != session) return false;
            session.stopping = true;
        }
        LogStore.log(Category.PLAYER, "DLNA 投屏取消: 用户主动结束投屏");
        DlnaController.stopIfCurrent(renderer.device,
                () -> activeRelay == relay && relay.current == session && !session.closed,
                (success, message) -> {
                    boolean released = false;
                    synchronized (relay) {
                        if (relay.current == session && session.generation == expectedGeneration) {
                            relay.closeCurrent(true);
                            released = true;
                        }
                    }
                    if (released) LogStore.log(Category.PLAYER, "DLNA 投屏取消: 本轮媒体代理已关闭");
                    if (callback != null)
                        new Handler(Looper.getMainLooper()).post(() -> callback.onResult(success, message));
                });
        return true;
    }

    /**
     * Return a TV-reachable URL through a separate, capability-scoped listener for this cast.
     * The renderer must not have to fetch the original HTTPS URL or reproduce player headers.
     */
    public synchronized String prepare(Context context, String rawUrl,
                                       Map<String, String> headers) throws IOException {
        return prepare(context, rawUrl, headers, null);
    }

    /** headerOrigin is the source URL whose credentials produced a local purified playlist. */
    public synchronized String prepare(Context context, String rawUrl,
                                       Map<String, String> headers,
                                       String headerOrigin) throws IOException {
        return prepare(context, rawUrl, headers, headerOrigin, null);
    }

    public synchronized String prepare(Context context, String rawUrl,
                                       Map<String, String> headers,
                                       String headerOrigin, String preferredAddress) throws IOException {
        String mediaUrl = CastMediaRules.normalizeFileUri(rawUrl);
        URI uri = CastMediaRules.parse(mediaUrl);
        if (uri == null) throw new IOException("Invalid media URL");
        String scheme = uri.getScheme();
        boolean http = CastMediaRules.isHttp(uri);
        boolean localFile = "file".equalsIgnoreCase(scheme) || "content".equalsIgnoreCase(scheme);
        if (!http && !localFile) throw new IOException("Unsupported media URL scheme");
        if (localFile && context == null) throw new IOException("Context is required for local media");
        if (localFile && CastMediaRules.isPlaylist(mediaUrl, null))
            throw new IOException("Local HLS playlists cannot be cast");
        Map<String, String> snapshot = CastMediaRules.snapshotHeaders(headers);
        if (context == null) throw new IOException("Context is required for media relay");

        // The player's purified playlist lives in a mutable process-wide slot. Pin its contents
        // to this cast session so changing episodes on the phone cannot change the TV's stream.
        String pinnedPlaylist = null;
        if (http && CastMediaRules.isLoopback(uri.getHost())
                && uri.getPort() == RemoteServer.serverPort
                && ("/purify.m3u8".equals(uri.getPath()) || "/m3u8".equals(uri.getPath()))) {
            pinnedPlaylist = RemoteServer.m3u8Content;
            if (pinnedPlaylist == null) throw new IOException("Purified playlist is no longer available");
            if (pinnedPlaylist.getBytes(StandardCharsets.UTF_8).length > MAX_PLAYLIST_BYTES)
                throw new IOException("Purified playlist is too large to cast");
        }
        URI origin = pinnedPlaylist == null ? null : CastMediaRules.parse(headerOrigin);
        String credentialOrigin = CastMediaRules.isHttp(origin) ? origin.toString() : mediaUrl;

        final okhttp3.OkHttpClient scopedClient;
        try {
            okhttp3.OkHttpClient baseClient = OkGoHelper.getMediaRelayClient();
            scopedClient = http ? OkGoHelper.newScopedMediaRelayClient(
                    new CastMediaDns(baseClient.dns(), uri.getHost(),
                            CastMediaRules.isHttp(origin) ? origin.getHost() : null)) : null;
        } catch (RuntimeException error) {
            throw new IOException("Cannot prepare media relay network policy", error);
        }
        boolean retainedClient = false;
        try {
            CastMediaRules.MediaKind mediaKind = pinnedPlaylist != null
                    ? CastMediaRules.MediaKind.HLS
                    : CastMediaRules.mediaKind(mediaUrl, null, null, 0);
            if (http && !mediaKind.known)
                mediaKind = probeHttp(mediaUrl, snapshot, RemoteServer.serverPort, scopedClient);
            if (localFile && !mediaKind.known) mediaKind = probeLocal(context, uri, mediaUrl);
            if (mediaKind == CastMediaRules.MediaKind.INVALID)
                throw new IOException("Media source returned a non-video document");
            if (!mediaKind.known)
                throw new IOException("Media format could not be identified for casting");
            LogStore.log(Category.PLAYER, "投屏媒体准备: 类型=" + mediaKind.extension
                    + "，来源=" + (localFile ? "本地文件" : CastMediaRules.isLoopback(uri.getHost()) ? "本机代理" : "网络"));

            List<String> addresses = RemoteServer.getLanIpv4Addresses();
            if (addresses.isEmpty()) throw new IOException("No reachable LAN IPv4 address");
            String advertisedAddress = preferredAddress != null && addresses.contains(preferredAddress)
                    ? preferredAddress : addresses.get(0);
            CastMediaRelay other = activeRelay;
            if (other != null && other != this) other.closeCurrent(false);
            closeCurrent(false);

            MediaServer started = new MediaServer();
            try {
                started.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                int port = started.getListeningPort();
                if (port <= 0) throw new IOException("Media relay did not bind a port");
                Session session = new Session(context == null ? null : context.getApplicationContext(),
                        "http://" + advertisedAddress + ":" + port,
                        randomHex(32), RemoteServer.serverPort, GENERATIONS.incrementAndGet(), scopedClient);
                String path = session.register(mediaUrl, snapshot, pinnedPlaylist, credentialOrigin,
                        mediaKind, null);
                if (path == null) throw new IOException("Media relay resource limit");
                server = started;
                current = session;
                activeRelay = this;
                expiryTask = EXPIRY.scheduleAtFixedRate(() -> expire(session), 1, 1, TimeUnit.MINUTES);
                try { CastMediaService.start(context, session.generation); }
                catch (RuntimeException error) {
                    stop();
                    throw new IOException("Cannot keep media relay active in background", error);
                }
                LogStore.log(Category.PLAYER, "投屏媒体服务: 临时监听已启动，端口=" + port
                        + "，接收端同网卡=" + advertisedAddress.equals(preferredAddress));
                retainedClient = true;
                return session.baseUrl + path;
            } catch (IOException | RuntimeException error) {
                closeCurrent(false);
                started.stop();
                if (activeRelay == null) {
                    try { CastMediaService.stop(context, -1); } catch (RuntimeException ignored) { }
                }
                if (error instanceof IOException) throw (IOException) error;
                throw new IOException("Media relay failed to start", error);
            }
        } finally {
            if (!retainedClient) MediaRelayCleanup.evictConnections(scopedClient);
        }
    }

    /** Immediately revoke this cast URL and unbind the listener; release streams in the background. */
    public synchronized void stop() {
        closeCurrent(true);
    }

    public synchronized void stop(long expectedGeneration) {
        Session session = current;
        if (session != null && session.generation == expectedGeneration) closeCurrent(true);
    }

    private synchronized void closeCurrent(boolean stopService) {
        Session old = current;
        MediaServer listening = server;
        current = null;
        server = null;
        if (activeRelay == this) activeRelay = null;
        if (expiryTask != null) expiryTask.cancel(false);
        expiryTask = null;
        if (old != null) old.close();
        if (listening != null) listening.stop();
        if (stopService && old != null && old.context != null) {
            try { CastMediaService.stop(old.context, old.generation); }
            catch (RuntimeException error) {
                LogStore.fail(Category.PLAYER, "投屏媒体服务: 关闭通知请求失败="
                        + error.getClass().getSimpleName());
            }
        }
    }

    static boolean hasActiveRelay() {
        CastMediaRelay relay = activeRelay;
        Session session = relay == null ? null : relay.current;
        return session != null && !session.closed;
    }

    static boolean hasActiveRelay(long generation) {
        CastMediaRelay relay = activeRelay;
        Session session = relay == null ? null : relay.current;
        return session != null && session.generation == generation;
    }

    static void stopActiveFromService(long generation) {
        CastMediaRelay relay = activeRelay;
        if (relay == null) return;
        synchronized (relay) {
            Session session = relay.current;
            if (session != null && session.generation == generation) relay.closeCurrent(false);
        }
    }

    private synchronized void expire(Session expected) {
        if (current == expected && expected.expired(System.currentTimeMillis())) stop();
    }

    private static CastMediaRules.MediaKind probeHttp(String sourceUrl, Map<String, String> headers,
                                                      int localPort,
                                                      okhttp3.OkHttpClient client) throws IOException {
        String requestedUrl = sourceUrl;
        for (int redirects = 0; redirects <= 5; redirects++) {
            Request.Builder builder = new Request.Builder().url(requestedUrl).get()
                    .header("Range", "bytes=0-1023")
                    .header("Accept-Encoding", "identity");
            Map<String, String> forward = CastMediaRules.headersForChild(sourceUrl, requestedUrl, headers);
            for (Map.Entry<String, String> entry : forward.entrySet()) {
                try { builder.header(entry.getKey(), entry.getValue()); }
                catch (IllegalArgumentException ignored) { }
            }
            okhttp3.Call call = client.newCall(builder.build());
            call.timeout().timeout(12, TimeUnit.SECONDS);
            try (okhttp3.Response response = call.execute()) {
                String location = response.header("Location");
                if (response.code() >= 300 && response.code() <= 399 && location != null) {
                    okhttp3.HttpUrl next = response.request().url().resolve(location);
                    if (redirects == 5 || next == null
                            || !LanCastRelayRules.allowedRedirect(requestedUrl, next.toString())
                            || !CastMediaRules.allowedChild(requestedUrl, next.toString(), localPort))
                        throw new IOException("Media source redirects outside allowed hosts");
                    requestedUrl = next.toString();
                    continue;
                }
                if (response.code() != 200 && response.code() != 206) {
                    LogStore.log(Category.PLAYER, "投屏媒体探测: 上游 HTTP " + response.code());
                    throw new IOException("Media source returned HTTP " + response.code());
                }
                ResponseBody body = response.body();
                if (body == null) throw new IOException("Media source returned no body");
                byte[] prefix = new byte[1024];
                int count = 0;
                try (InputStream input = decodePlaylist(body.byteStream(), response.header("Content-Encoding"))) {
                    while (count < prefix.length) {
                        int read = input.read(prefix, count, prefix.length - count);
                        if (read <= 0) break;
                        count += read;
                    }
                }
                CastMediaRules.MediaKind kind = CastMediaRules.mediaKind(requestedUrl,
                        response.header("Content-Type"), prefix, count);
                LogStore.log(Category.PLAYER, "投屏媒体探测: 上游 HTTP " + response.code()
                        + "，MIME=" + safeMime(response.header("Content-Type"))
                        + "，识别=" + (kind.known ? kind.extension : kind == CastMediaRules.MediaKind.INVALID
                        ? "非媒体" : "未知"));
                return kind;
            }
        }
        throw new IOException("Media source redirected too many times");
    }

    private static CastMediaRules.MediaKind probeLocal(Context context, URI uri,
                                                       String mediaUrl) throws IOException {
        String mime = null;
        InputStream opened;
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                opened = new FileInputStream(new File(uri));
            } else {
                Uri content = Uri.parse(mediaUrl);
                mime = context.getContentResolver().getType(content);
                opened = context.getContentResolver().openInputStream(content);
            }
        } catch (RuntimeException error) {
            throw new IOException("Local media is unavailable", error);
        }
        if (opened == null) throw new IOException("Local media is unavailable");
        byte[] prefix = new byte[1024];
        int count = 0;
        try (InputStream input = opened) {
            while (count < prefix.length) {
                int read = input.read(prefix, count, prefix.length - count);
                if (read <= 0) break;
                count += read;
            }
        }
        return CastMediaRules.mediaKind(mediaUrl, mime, prefix, count);
    }

    private NanoHTTPD.Response serve(NanoHTTPD.IHTTPSession request) {
        NanoHTTPD.Method method = request.getMethod();
        if (method != NanoHTTPD.Method.GET && method != NanoHTTPD.Method.HEAD)
            return plain(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED);
        String path = request.getUri();
        Matcher matcher = PATH.matcher(path == null ? "" : path);
        if (!matcher.matches()) return plain(NanoHTTPD.Response.Status.NOT_FOUND);
        Session session = current;
        if (session == null || !session.token.equals(matcher.group(1)) || session.expired(System.currentTimeMillis()))
            return plain(NanoHTTPD.Response.Status.FORBIDDEN);
        Target target = session.target(matcher.group(2));
        if (target == null) return plain(NanoHTTPD.Response.Status.NOT_FOUND);
        if (session.firstMediaRequest.compareAndSet(false, true))
            LogStore.log(Category.PLAYER, "投屏媒体读取: 已收到首个 " + method
                    + " 请求，类型=" + target.kind.extension);
        if (target.role == LanCastRelayRules.ResourceKind.SEGMENT
                && session.firstSegmentRequest.compareAndSet(false, true))
            LogStore.log(Category.PLAYER, "投屏媒体分片: 已收到 " + method
                    + " 请求，类型=" + target.kind.extension);
        session.lastUsedAt = System.currentTimeMillis();
        String range = request.getHeaders() == null ? null : request.getHeaders().get("range");
        if (range != null && (range.length() > 80 || !RANGE.matcher(range).matches()))
            return plain(NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE);
        try {
            if (target.localFile) return localResponse(request, session, target, range);
            return httpResponse(request, session, target, range);
        } catch (IOException | RuntimeException error) {
            if (session.firstRelayError.compareAndSet(false, true))
                LogStore.log(Category.PLAYER, "投屏媒体读取: 中转异常="
                        + error.getClass().getSimpleName());
            return plain(NanoHTTPD.Response.Status.INTERNAL_ERROR);
        }
    }

    private NanoHTTPD.Response httpResponse(NanoHTTPD.IHTTPSession request, Session session,
                                            Target target, String range) throws IOException {
        return httpResponse(request, session, target, range, false);
    }

    private NanoHTTPD.Response httpResponse(NanoHTTPD.IHTTPSession request, Session session,
                                            Target target, String range, boolean retriedManifest) throws IOException {
        boolean head = request.getMethod() == NanoHTTPD.Method.HEAD;
        String requestedUrl = target.url;
        if (target.inlinePlaylist != null) {
            byte[] content = target.inlinePlaylist.getBytes(StandardCharsets.UTF_8);
            return playlistResponse(request, session, target.headerOrigin,
                    target.headerOrigin, target.headers, content);
        }
        // HLS must always be rewritten as a complete playlist. A renderer may request a byte
        // range or HEAD even for a manifest; upstream partial XML/text cannot be handed through.
        String upstreamRange = target.kind.playlist || CastMediaRules.isPlaylist(requestedUrl, null)
                ? null : range;
        okhttp3.Response upstream = null;
        for (int redirects = 0; redirects <= 5; redirects++) {
            Request.Builder builder = new Request.Builder().url(requestedUrl);
            builder.get();
            Map<String, String> forward = CastMediaRules.headersForChild(target.url, requestedUrl, target.headers);
            for (Map.Entry<String, String> entry : forward.entrySet()) {
                try { builder.header(entry.getKey(), entry.getValue()); }
                catch (IllegalArgumentException ignored) { }
            }
            if (upstreamRange != null) builder.header("Range", upstreamRange);
            builder.header("Accept-Encoding", "identity");
            upstream = session.client.newCall(builder.build()).execute();
            String location = upstream.header("Location");
            if (upstream.code() < 300 || upstream.code() > 399 || location == null) break;
            okhttp3.HttpUrl next = upstream.request().url().resolve(location);
            upstream.close();
            upstream = null;
            if (redirects == 5 || next == null
                    || !LanCastRelayRules.allowedRedirect(requestedUrl, next.toString())
                    || !CastMediaRules.allowedChild(requestedUrl, next.toString(), session.localPort))
                return plain(NanoHTTPD.Response.Status.FORBIDDEN);
            requestedUrl = next.toString();
        }
        if (upstream == null) return plain(NanoHTTPD.Response.Status.INTERNAL_ERROR);
        int code = upstream.code();
        if (target.role == LanCastRelayRules.ResourceKind.SEGMENT
                && session.firstSegmentResponse.compareAndSet(false, true))
            LogStore.log(Category.PLAYER, "投屏媒体分片: 上游 HTTP " + code
                    + "，MIME=" + safeMime(upstream.header("Content-Type")));
        if (code != 200 && code != 206) {
            if (session.firstRelayError.compareAndSet(false, true))
                LogStore.log(Category.PLAYER, "投屏媒体读取: 上游 HTTP " + code);
            upstream.close();
            NanoHTTPD.Response.Status status = NanoHTTPD.Response.Status.lookup(code);
            return plain(status == null ? NanoHTTPD.Response.Status.INTERNAL_ERROR : status);
        }
        ResponseBody body = upstream.body();
        if (body == null) { upstream.close(); return plain(NanoHTTPD.Response.Status.INTERNAL_ERROR); }
        String sourceUrl = upstream.request().url().toString();
        String mime = upstream.header("Content-Type", target.kind.mime);
        boolean playlist = target.kind.playlist || CastMediaRules.isPlaylist(sourceUrl, mime);
        if (session.firstUpstreamResponse.compareAndSet(false, true))
            LogStore.log(Category.PLAYER, "投屏媒体读取: 上游 HTTP " + code
                    + "，MIME=" + safeMime(mime) + "，清单=" + playlist);
        if (playlist && code == 206) {
            upstream.close();
            return !retriedManifest && upstreamRange != null
                    ? httpResponse(request, session, target, null, true)
                    : plain(NanoHTTPD.Response.Status.INTERNAL_ERROR);
        }
        if (playlist && code == 200) {
            try (okhttp3.Response response = upstream) {
                InputStream decoded = decodePlaylist(body.byteStream(), response.header("Content-Encoding"));
                byte[] bytes = readLimited(decoded);
                if (CastMediaRules.mediaKind(sourceUrl, null, bytes, bytes.length)
                        != CastMediaRules.MediaKind.HLS) {
                    LogStore.log(Category.PLAYER, "投屏媒体读取: HLS 源返回了非清单内容");
                    return plain(NanoHTTPD.Response.Status.INTERNAL_ERROR);
                }
                return playlistResponse(request, session, sourceUrl,
                        sourceUrl, CastMediaRules.headersForChild(target.url, sourceUrl, target.headers), bytes);
            }
        }
        if ("application/octet-stream".equalsIgnoreCase(mime) || "text/plain".equalsIgnoreCase(mime))
            mime = target.kind.mime;
        long length = body.contentLength();
        if (head) {
            String declared = upstream.header("Content-Length");
            if (declared != null) {
                try { length = Long.parseLong(declared); } catch (NumberFormatException ignored) { }
            }
        }
        if (head) {
            upstream.close();
            NanoHTTPD.Response output = NanoHTTPD.newFixedLengthResponse(
                    code == 206 ? NanoHTTPD.Response.Status.PARTIAL_CONTENT : NanoHTTPD.Response.Status.OK,
                    mime, new ByteArrayInputStream(new byte[0]), Math.max(0, length));
            copyHttpHeaders(output, upstream);
            output.setRequestMethod(NanoHTTPD.Method.HEAD);
            return output;
        }
        RelayStream stream = new RelayStream(body.byteStream(), session, upstream,
                target.role == LanCastRelayRules.ResourceKind.SEGMENT
                        && session.firstSegmentStream.compareAndSet(false, true));
        NanoHTTPD.Response.Status status = code == 206
                ? NanoHTTPD.Response.Status.PARTIAL_CONTENT : NanoHTTPD.Response.Status.OK;
        NanoHTTPD.Response output = length >= 0
                ? NanoHTTPD.newFixedLengthResponse(status, mime, stream, length)
                : NanoHTTPD.newChunkedResponse(status, mime, stream);
        copyHttpHeaders(output, upstream);
        return output;
    }

    private NanoHTTPD.Response playlistResponse(NanoHTTPD.IHTTPSession request, Session session,
                                                 String sourceUrl, String headerOrigin,
                                                 Map<String, String> sourceHeaders,
                                                 byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        boolean fragmentedMp4 = text.contains("#EXT-X-MAP:")
                || (text.contains("#EXT-X-PRELOAD-HINT:") && text.contains("TYPE=MAP"));
        int[] counts = new int[4]; // child resources, rejected, inferred suffixes, segments
        final String rewritten;
        try {
            rewritten = LanCastRelayRules.rewriteWithResourceHint(text, sourceUrl, (child, role) -> {
                counts[0]++;
                if (role == LanCastRelayRules.ResourceKind.SEGMENT) counts[3]++;
                if (current != session || !CastMediaRules.allowedChild(sourceUrl, child, session.localPort)) {
                    counts[1]++;
                    return null;
                }
                if (role == LanCastRelayRules.ResourceKind.SEGMENT
                        && !CastMediaRules.mediaKind(child, null, null, 0).known) counts[2]++;
                String path = session.register(child,
                        CastMediaRules.headersForChild(headerOrigin, child, sourceHeaders), null, child,
                        CastMediaRules.hlsChildKind(child, role, fragmentedMp4), role);
                if (path == null) counts[1]++;
                return path == null ? null : session.baseUrl + path;
            });
        } catch (TargetLimitException limit) {
            if (session.firstTargetLimit.compareAndSet(false, true))
                LogStore.log(Category.PLAYER, "投屏媒体清单: 子资源超过上限 " + MAX_TARGETS + "，已拒绝整份清单");
            NanoHTTPD.Response error = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT,
                    "HLS playlist exceeds the media relay resource limit");
            error.addHeader("Cache-Control", "no-store");
            return error;
        }
        boolean firstPlaylist = session.firstPlaylistRewrite.compareAndSet(false, true);
        boolean firstMediaPlaylist = counts[3] > 0
                && session.firstSegmentPlaylistRewrite.compareAndSet(false, true);
        if (firstPlaylist || firstMediaPlaylist)
            LogStore.log(Category.PLAYER, "投屏媒体清单: "
                    + (counts[3] > 0 ? "媒体清单" : "主清单")
                    + "，子资源=" + counts[0] + "，分片=" + counts[3]
                    + "，受限=" + counts[1] + "，推断分片格式=" + counts[2]);
        byte[] result = rewritten.getBytes(StandardCharsets.UTF_8);
        if (result.length > MAX_PLAYLIST_BYTES) return plain(NanoHTTPD.Response.Status.INTERNAL_ERROR);
        NanoHTTPD.Response output = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/vnd.apple.mpegurl",
                new ByteArrayInputStream(result), result.length);
        mediaHeaders(output);
        output.setRequestMethod(request.getMethod());
        return output;
    }

    private NanoHTTPD.Response localResponse(NanoHTTPD.IHTTPSession request, Session session,
                                             Target target, String range) throws IOException {
        InputStream source;
        long length;
        String mime = target.kind.mime;
        if ("file".equalsIgnoreCase(CastMediaRules.parse(target.url).getScheme())) {
            File file;
            try { file = new File(URI.create(target.url)).getCanonicalFile(); }
            catch (IllegalArgumentException error) { return plain(NanoHTTPD.Response.Status.BAD_REQUEST); }
            if (!file.isFile()) return plain(NanoHTTPD.Response.Status.NOT_FOUND);
            length = file.length();
            source = new FileInputStream(file);
        } else {
            Uri uri = Uri.parse(target.url);
            String contentMime = session.context.getContentResolver().getType(uri);
            if (contentMime != null && !contentMime.isEmpty()) mime = contentMime;
            AssetFileDescriptor descriptor = session.context.getContentResolver()
                    .openAssetFileDescriptor(uri, "r");
            if (descriptor == null) return plain(NanoHTTPD.Response.Status.NOT_FOUND);
            length = descriptor.getLength();
            if (length < 0) length = descriptor.getParcelFileDescriptor().getStatSize();
            InputStream opened;
            try { opened = descriptor.createInputStream(); }
            catch (IOException error) { descriptor.close(); throw error; }
            source = new FilterInputStream(opened) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { descriptor.close(); }
                }
            };
        }
        if (CastMediaRules.isPlaylist(target.url, mime)) {
            source.close();
            return plain(NanoHTTPD.Response.Status.BAD_REQUEST);
        }
        if (length < 0 && range != null) {
            source.close();
            return plain(NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE);
        }
        CastMediaRules.ByteRange selected = CastMediaRules.range(range, length);
        if (!selected.valid) {
            source.close();
            NanoHTTPD.Response invalid = plain(NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE);
            if (length >= 0) invalid.addHeader("Content-Range", "bytes */" + length);
            return invalid;
        }
        long count = length < 0 ? -1 : selected.partial ? selected.end - selected.start + 1 : length;
        if (request.getMethod() == NanoHTTPD.Method.HEAD) {
            source.close();
            NanoHTTPD.Response output = NanoHTTPD.newFixedLengthResponse(
                    selected.partial ? NanoHTTPD.Response.Status.PARTIAL_CONTENT
                            : NanoHTTPD.Response.Status.OK,
                    mime, new ByteArrayInputStream(new byte[0]), Math.max(0, count));
            localHeaders(output, selected, length);
            output.setRequestMethod(NanoHTTPD.Method.HEAD);
            return output;
        }
        try {
            skipFully(source, selected.start);
        } catch (IOException error) { source.close(); throw error; }
        RelayStream stream = new RelayStream(source, session, null, false);
        NanoHTTPD.Response output = count >= 0
                ? NanoHTTPD.newFixedLengthResponse(
                        selected.partial ? NanoHTTPD.Response.Status.PARTIAL_CONTENT
                                : NanoHTTPD.Response.Status.OK, mime, stream, count)
                : NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, mime, stream);
        localHeaders(output, selected, length);
        return output;
    }

    private static void localHeaders(NanoHTTPD.Response output, CastMediaRules.ByteRange range, long length) {
        mediaHeaders(output);
        if (length >= 0) output.addHeader("Accept-Ranges", "bytes");
        if (range.partial) output.addHeader("Content-Range",
                "bytes " + range.start + "-" + range.end + "/" + length);
    }

    private static void copyHttpHeaders(NanoHTTPD.Response output, okhttp3.Response source) {
        mediaHeaders(output);
        String contentRange = source.header("Content-Range");
        if (contentRange != null) output.addHeader("Content-Range", contentRange);
        String encoding = source.header("Content-Encoding");
        if (encoding != null) output.addHeader("Content-Encoding", encoding);
        String acceptRanges = source.header("Accept-Ranges");
        if (acceptRanges != null) output.addHeader("Accept-Ranges", acceptRanges);
    }

    private static void mediaHeaders(NanoHTTPD.Response output) {
        output.addHeader("Cache-Control", "no-store");
        output.addHeader("transferMode.dlna.org", "Streaming");
        output.addHeader("contentFeatures.dlna.org",
                "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
    }

    private static String safeMime(String raw) {
        if (raw == null) return "未知";
        String base = raw.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (base.isEmpty() || base.length() > 80 || base.indexOf('/') < 1) return "未知";
        for (int i = 0; i < base.length(); i++) {
            char value = base.charAt(i);
            if (!(value >= 'a' && value <= 'z') && !(value >= '0' && value <= '9')
                    && value != '/' && value != '.' && value != '-' && value != '+') return "未知";
        }
        return base;
    }

    private static NanoHTTPD.Response plain(NanoHTTPD.Response.Status status) {
        NanoHTTPD.Response output = NanoHTTPD.newFixedLengthResponse(status,
                NanoHTTPD.MIME_PLAINTEXT, status.getDescription());
        output.addHeader("Cache-Control", "no-store");
        return output;
    }

    private static InputStream decodePlaylist(InputStream input, String encoding) throws IOException {
        if (encoding == null || encoding.equalsIgnoreCase("identity")) return input;
        if (encoding.equalsIgnoreCase("gzip")) return new GZIPInputStream(input);
        if (encoding.equalsIgnoreCase("br")) return new BrotliInputStream(input);
        throw new IOException("Unsupported playlist compression");
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_PLAYLIST_BYTES) throw new IOException("Playlist too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void skipFully(InputStream input, long bytes) throws IOException {
        while (bytes > 0) {
            long skipped = input.skip(bytes);
            if (skipped <= 0) {
                if (input.read() < 0) throw new IOException("Cannot seek local media");
                skipped = 1;
            }
            bytes -= skipped;
        }
    }

    private static String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        char[] out = new char[bytes * 2];
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes; i++) {
            out[i * 2] = hex[(data[i] >>> 4) & 15];
            out[i * 2 + 1] = hex[data[i] & 15];
        }
        return new String(out);
    }

    private final class MediaServer extends NanoHTTPD {
        MediaServer() { super(0); }
        @Override public Response serve(IHTTPSession session) { return CastMediaRelay.this.serve(session); }
    }

    private static final class Target {
        final String url;
        final Map<String, String> headers;
        final boolean localFile;
        final String inlinePlaylist;
        final String headerOrigin;
        final CastMediaRules.MediaKind kind;
        final LanCastRelayRules.ResourceKind role;
        volatile long registeredAt;

        Target(String url, Map<String, String> headers, String inlinePlaylist, String headerOrigin,
               CastMediaRules.MediaKind kind, LanCastRelayRules.ResourceKind role) {
            this.url = url;
            this.headers = headers;
            this.inlinePlaylist = inlinePlaylist;
            this.headerOrigin = headerOrigin;
            this.kind = kind;
            this.role = role;
            URI uri = CastMediaRules.parse(url);
            String scheme = uri == null ? null : uri.getScheme();
            this.localFile = "file".equalsIgnoreCase(scheme) || "content".equalsIgnoreCase(scheme);
            this.registeredAt = System.currentTimeMillis();
        }
    }

    static final class TargetLimitException extends RuntimeException { }

    static final class Session {
        final Context context;
        final String baseUrl;
        final String token;
        final int localPort;
        final long generation;
        final okhttp3.OkHttpClient client;
        final long createdAt = System.currentTimeMillis();
        final Map<String, Target> targets = new LinkedHashMap<>();
        final Map<String, String> idsByUrl = new HashMap<>();
        int targetUrlChars;
        final Set<Closeable> streams = Collections.newSetFromMap(new ConcurrentHashMap<Closeable, Boolean>());
        final AtomicBoolean firstMediaRequest = new AtomicBoolean();
        final AtomicBoolean firstUpstreamResponse = new AtomicBoolean();
        final AtomicBoolean firstRelayError = new AtomicBoolean();
        final AtomicBoolean firstPlaylistRewrite = new AtomicBoolean();
        final AtomicBoolean firstSegmentPlaylistRewrite = new AtomicBoolean();
        final AtomicBoolean firstSegmentRequest = new AtomicBoolean();
        final AtomicBoolean firstSegmentResponse = new AtomicBoolean();
        final AtomicBoolean firstSegmentStream = new AtomicBoolean();
        final AtomicBoolean firstTargetLimit = new AtomicBoolean();
        volatile long lastUsedAt = createdAt;
        volatile boolean closed;
        volatile RendererBinding renderer;
        volatile boolean stopping;

        Session(Context context, String baseUrl, String token, int localPort, long generation) {
            this(context, baseUrl, token, localPort, generation, null);
        }

        Session(Context context, String baseUrl, String token, int localPort, long generation,
                okhttp3.OkHttpClient client) {
            this.context = context;
            this.baseUrl = baseUrl;
            this.token = token;
            this.localPort = localPort;
            this.generation = generation;
            this.client = client;
        }

        synchronized String register(String url, Map<String, String> headers,
                                     String inlinePlaylist, String headerOrigin,
                                     CastMediaRules.MediaKind kind,
                                     LanCastRelayRules.ResourceKind role) {
            if (closed || CastMediaRules.parse(url) == null) return null;
            String resourceKey = resourceKey(url, role);
            String id = idsByUrl.get(resourceKey);
            Target old = id == null ? null : targets.get(id);
            if (old != null) {
                old.registeredAt = System.currentTimeMillis();
                return path(id, old);
            }
            int addedChars = url.length();
            if (targets.size() >= MAX_TARGETS
                    || targetUrlChars + addedChars > MAX_TARGET_URL_CHARS) {
                long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10);
                java.util.Iterator<Map.Entry<String, Target>> iterator = targets.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, Target> entry = iterator.next();
                    if (entry.getValue().registeredAt < cutoff) {
                        idsByUrl.remove(resourceKey(entry.getValue().url, entry.getValue().role));
                        targetUrlChars -= entry.getValue().url.length();
                        iterator.remove();
                    }
                }
            }
            if (targets.size() >= MAX_TARGETS
                    || targetUrlChars + addedChars > MAX_TARGET_URL_CHARS)
                throw new TargetLimitException();
            id = randomHex(16);
            Target target = new Target(url, headers, inlinePlaylist, headerOrigin, kind, role);
            targets.put(id, target);
            idsByUrl.put(resourceKey, id);
            targetUrlChars += addedChars;
            return path(id, target);
        }

        private static String resourceKey(String url, LanCastRelayRules.ResourceKind role) {
            return url + '\n' + (role == null ? "ROOT" : role.name());
        }

        synchronized Target target(String id) { return targets.get(id); }

        private String path(String id, Target target) {
            return "/media/" + token + "/" + id + "/stream." + target.kind.extension;
        }

        boolean expired(long now) {
            return closed || now - createdAt > MAX_LIFE_MS
                    || (streams.isEmpty() && now - lastUsedAt > IDLE_MS);
        }

        private synchronized boolean addStream(Closeable stream) {
            if (closed) return false;
            streams.add(stream);
            return true;
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            targets.clear();
            idsByUrl.clear();
            targetUrlChars = 0;
            ArrayList<Closeable> toClose = new ArrayList<>(streams);
            streams.clear();
            MediaRelayCleanup.closeResources(toClose, client);
        }
    }

    private final class RelayStream extends FilterInputStream {
        private final Session session;
        private final okhttp3.Response upstream;
        private final boolean reportSegment;
        private long streamedBytes;
        private volatile boolean closed;

        RelayStream(InputStream source, Session session, okhttp3.Response upstream,
                    boolean reportSegment) throws IOException {
            super(source);
            this.session = session;
            this.upstream = upstream;
            this.reportSegment = reportSegment;
            if (!session.addStream(this)) {
                close();
                throw new IOException("Cast stopped");
            }
        }

        private void check() throws IOException {
            if (closed || current != session || session.closed) throw new IOException("Cast stopped");
        }

        @Override public int read() throws IOException {
            check();
            int value = super.read();
            if (value >= 0) streamedBytes++;
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            check();
            int count = super.read(buffer, offset, length);
            if (count > 0) streamedBytes += count;
            return count;
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            session.streams.remove(this);
            try { super.close(); } finally {
                if (upstream != null) upstream.close();
                if (reportSegment) LogStore.log(Category.PLAYER,
                        "投屏媒体分片: 首个请求已传输 " + streamedBytes + " 字节");
            }
        }
    }
}

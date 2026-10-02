package com.github.tvbox.osc.server;

import android.annotation.SuppressLint;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Base64;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.config.LanSessionConfig;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.transfer.ConfigDataExchange;
import com.github.tvbox.osc.transfer.ConfigBundle;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.OkGoHelper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Request;
import org.brotli.dec.BrotliInputStream;

/**
 * @author pj567
 * @date :2021/1/5
 * @description:
 */
public class RemoteServer extends NanoHTTPD {
    private Context mContext;
    public static int serverPort = 9978;
    private boolean isStarted = false;
    private DataReceiver mDataReceiver;
    private ArrayList < RequestProcess > getRequestList = new ArrayList < > ();
    private ArrayList < RequestProcess > postRequestList = new ArrayList < > ();

    public static String m3u8Content;

    /** 每台设备独立的配对会话；踢出时撤销该设备的 Cookie 或请求头令牌。 */
    private volatile String pairingCode;
    private final Map<String, FailedPairing> failedPairings = new ConcurrentHashMap<>();
    private long lastPlaybackAuthDeniedLogAt;
    private final Map<String, LanDevice> devices = new ConcurrentHashMap<>();
    private long lastSessionsSavedAt;
    private static final long ACTIVE_DEVICE_WINDOW_MS = 60000;
    private static final long SESSION_WINDOW_MS = 600000;
    private volatile EpisodeCast episodeCast;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface NextEpisodeHandler {
        boolean playNext();
        default boolean selectEpisode(int index) { return false; }
    }

    private static final class EpisodeCast {
        final String owner;
        final String deviceId;
        final NextEpisodeHandler handler;
        volatile List<String> episodes;
        volatile int selectedIndex;

        EpisodeCast(String owner, String deviceId, List<String> episodes, int selectedIndex,
                    NextEpisodeHandler handler) {
            this.owner = owner;
            this.deviceId = deviceId;
            this.episodes = new ArrayList<>(episodes);
            this.selectedIndex = selectedIndex;
            this.handler = handler;
        }
    }

    private static final Gson GSON = new Gson();
    private static final Pattern CAST_MEDIA_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern CAST_MEDIA_RANGE = Pattern.compile("bytes=(?:\\d+-\\d*|-\\d+)");
    private static final long SLOW_CAST_READ_MS = 3000;

    private static String deviceRef(LanDevice device) {
        return device.id.substring(0, Math.min(8, device.id.length()));
    }

    private static void logMediaFailure(LanDevice device, String reason) {
        synchronized (device) {
            if (device.loggedMediaFailureRevision == device.playbackRevision) return;
            device.loggedMediaFailureRevision = device.playbackRevision;
        }
        LogStore.fail(Category.PLAYER, "局域网投屏取流失败 device=" + deviceRef(device)
                + " revision=" + device.playbackRevision + " reason=" + reason);
    }

    private static void logSlowCastRead(LanDevice device, String stage, long elapsedMs, long bytes) {
        if (elapsedMs < SLOW_CAST_READ_MS) return;
        long now = System.currentTimeMillis();
        synchronized (device) {
            if (now - device.lastSlowMediaLogAt < TimeUnit.MINUTES.toMillis(1)) return;
            device.lastSlowMediaLogAt = now;
        }
        LogStore.log(Category.PLAYER, "局域网投屏取流耗时 device=" + deviceRef(device)
                + " revision=" + device.playbackRevision + " stage=" + stage
                + " elapsedMs=" + elapsedMs + " bytes=" + bytes);
    }

    private synchronized void logPlaybackAuthDenied() {
        long now = System.currentTimeMillis();
        if (now - lastPlaybackAuthDeniedLogAt < 60000) return;
        lastPlaybackAuthDeniedLogAt = now;
        LogStore.fail(Category.SYSTEM, "局域网播放状态鉴权失败 reason=session_missing_or_expired");
    }

    private static final class FailedPairing {
        int count;
        long windowStart;
    }

    public static final class LanDevice {
        public final String id;
        public final String name;
        public final String ip;
        public final String kind;
        public final long connectedAt;
        public volatile long lastSeen;
        private final String token;
        private volatile JsonObject playback;
        private volatile long playbackRevision;
        private volatile long nextRequestedRevision;
        private volatile long loggedMediaFailureRevision = -1;
        private volatile long lastSlowMediaLogAt;
        private volatile long loggedPlaybackEventRevision = -1;
        private final Set<String> loggedPlaybackEvents = ConcurrentHashMap.newKeySet();
        private final Set<String> castProxyPaths = ConcurrentHashMap.newKeySet();
        private final Map<String, CastMedia> castMedia = new ConcurrentHashMap<>();
        private final Map<String, String> castMediaByUrl = new HashMap<>();

        private LanDevice(String token, String name, String ip, String kind) {
            this.id = generateToken();
            this.token = token;
            this.name = name;
            this.ip = ip;
            this.kind = kind;
            this.connectedAt = System.currentTimeMillis();
            this.lastSeen = connectedAt;
        }

        private LanDevice(LanSessionConfig.Record saved) {
            this.id = saved.id;
            this.token = saved.token;
            this.name = saved.name;
            this.ip = saved.ip;
            this.kind = saved.kind;
            this.connectedAt = saved.connectedAt;
            this.lastSeen = saved.lastSeen;
        }

        public String currentTitle() {
            JsonObject state = playback;
            return state != null && state.has("title") ? state.get("title").getAsString() : "";
        }
    }

    private static final class CastMedia {
        final String url;
        final Map<String, String> headers;
        volatile long revision;
        volatile long lastRegisteredAt;

        CastMedia(String url, Map<String, String> headers, long revision) {
            this.url = url;
            this.headers = headers;
            this.revision = revision;
            this.lastRegisteredAt = System.currentTimeMillis();
        }
    }

    private static void clearCastMedia(LanDevice device) {
        synchronized (device) {
            device.castMedia.clear();
            device.castMediaByUrl.clear();
        }
    }

    public List<LanDevice> connectedDevices() {
        long now = System.currentTimeMillis();
        ArrayList<LanDevice> active = new ArrayList<>();
        for (LanDevice device : pairedDevices()) {
            if (now - device.lastSeen <= ACTIVE_DEVICE_WINDOW_MS) active.add(device);
        }
        return active;
    }

    public List<LanDevice> pairedDevices() {
        long now = System.currentTimeMillis();
        ArrayList<LanDevice> paired = new ArrayList<>();
        boolean expired = false;
        for (LanDevice device : devices.values()) {
            if (now - device.lastSeen > SESSION_WINDOW_MS) {
                if (devices.remove(device.token, device)) {
                    expired = true;
                    LogStore.log(Category.SYSTEM, "局域网配对会话过期 device=" + deviceRef(device));
                }
            }
            else paired.add(device);
        }
        if (expired) persistSessions(true);
        paired.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        return paired;
    }

    public boolean kickDevice(String id) {
        if (id == null) return false;
        for (LanDevice device : devices.values()) {
            if (id.equals(device.id)) {
                EpisodeCast cast = episodeCast;
                if (cast != null && id.equals(cast.deviceId)) clearEpisodeCast();
                boolean removed = devices.remove(device.token, device);
                if (removed) {
                    persistSessions(true);
                    LogStore.log(Category.SYSTEM, "局域网设备已踢出 device=" + deviceRef(device));
                }
                return removed;
            }
        }
        return false;
    }

    public void setEpisodeCast(String owner, String deviceId, List<String> episodes, int selectedIndex,
                               NextEpisodeHandler handler) {
        EpisodeCast previous = episodeCast;
        if (previous != null && !previous.deviceId.equals(deviceId)) {
            for (LanDevice device : devices.values()) {
                if (device.id.equals(previous.deviceId)) {
                    device.playback = null;
                    device.castProxyPaths.clear();
                    clearCastMedia(device);
                    break;
                }
            }
        }
        episodeCast = new EpisodeCast(owner, deviceId, episodes, selectedIndex, handler);
        for (LanDevice device : devices.values()) {
            if (device.id.equals(deviceId) && device.playback != null) {
                JsonObject state = device.playback.deepCopy();
                state.addProperty("revision", ++device.playbackRevision);
                for (CastMedia media : device.castMedia.values()) media.revision = device.playbackRevision;
                addEpisodeState(state, episodeCast);
                device.playback = state;
                break;
            }
        }
    }

    public void clearEpisodeCast(String owner) {
        EpisodeCast cast = episodeCast;
        if (cast != null && owner != null && owner.equals(cast.owner)) episodeCast = null;
    }

    public void clearEpisodeCast() {
        episodeCast = null;
    }

    public boolean updateEpisodeCast(String owner, String title, String url, int selectedIndex,
                                     List<String> episodes, Map<String, String> headers) {
        EpisodeCast cast = episodeCast;
        if (cast != null && owner != null && owner.equals(cast.owner)) {
            cast.selectedIndex = selectedIndex;
            if (episodes != null) cast.episodes = new ArrayList<>(episodes);
            return publishBrowserPlayback(cast.deviceId, title, url, headers);
        }
        return false;
    }

    public void markEpisodeAdvancing(String owner) {
        EpisodeCast cast = episodeCast;
        if (cast == null || owner == null || !owner.equals(cast.owner)) return;
        for (LanDevice device : devices.values()) {
            if (cast.deviceId.equals(device.id)) {
                device.nextRequestedRevision = device.playbackRevision;
                return;
            }
        }
    }

    private boolean requestNextEpisode(LanDevice device, String rawRevision) {
        EpisodeCast cast = episodeCast;
        if (device == null || cast == null || !device.id.equals(cast.deviceId) || cast.handler == null) return false;
        long revision;
        try { revision = Long.parseLong(rawRevision); } catch (Exception ignored) { return false; }
        synchronized (device) {
            if (revision != device.playbackRevision) return false;
            if (revision == device.nextRequestedRevision) return true;
            device.nextRequestedRevision = revision;
        }
        FutureTask<Boolean> task = new FutureTask<>(() -> {
            return episodeCast == cast && cast.handler.playNext();
        });
        mainHandler.post(task);
        try {
            boolean accepted = task.get(3, TimeUnit.SECONDS);
            if (!accepted) resetNextRequest(device, revision);
            return accepted;
        } catch (Exception error) {
            mainHandler.removeCallbacks(task);
            task.cancel(false);
            resetNextRequest(device, revision);
            return false;
        }
    }

    private static void resetNextRequest(LanDevice device, long revision) {
        synchronized (device) {
            if (device.nextRequestedRevision == revision) device.nextRequestedRevision = -1;
        }
    }

    private boolean requestSelectedEpisode(LanDevice device, String rawRevision, String rawIndex) {
        EpisodeCast cast = episodeCast;
        if (device == null || cast == null || !device.id.equals(cast.deviceId)
                || cast.handler == null) return false;
        final long revision;
        final int index;
        try {
            revision = Long.parseLong(rawRevision);
            index = Integer.parseInt(rawIndex);
        } catch (Exception ignored) { return false; }
        List<String> episodes = cast.episodes;
        if (index < 0 || index >= episodes.size()) return false;
        synchronized (device) {
            if (revision != device.playbackRevision) return false;
            device.nextRequestedRevision = revision;
        }
        FutureTask<Boolean> task = new FutureTask<>(() -> episodeCast == cast
                && cast.handler.selectEpisode(index));
        mainHandler.post(task);
        try {
            boolean accepted = task.get(3, TimeUnit.SECONDS);
            if (!accepted) resetNextRequest(device, revision);
            return accepted;
        } catch (Exception error) {
            mainHandler.removeCallbacks(task);
            task.cancel(false);
            resetNextRequest(device, revision);
            return false;
        }
    }

    private static void addEpisodeState(JsonObject state, EpisodeCast cast) {
        JsonArray episodes = new JsonArray();
        for (String name : cast.episodes) episodes.add(name);
        state.add("episodes", episodes);
        state.addProperty("selectedIndex", cast.selectedIndex);
    }

    private Response recordPlaybackEvent(LanDevice device, Map<String, String> params) {
        if (device == null || !"browser".equals(device.kind))
            return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
        String event = params.get("event");
        if (!("playing".equals(event) || "autoplay_blocked".equals(event)
                || "media_error".equals(event) || "hls_error".equals(event)
                || "rebuffer".equals(event)
                || "unsupported".equals(event)))
            return createPlainTextResponse(Response.Status.BAD_REQUEST, "invalid event");
        String detail = params.get("detail");
        if (detail == null) detail = "";
        if (detail.length() > 48 || !detail.matches("[A-Za-z0-9_:-]*"))
            return createPlainTextResponse(Response.Status.BAD_REQUEST, "invalid detail");
        long revision;
        try { revision = Long.parseLong(params.get("revision")); }
        catch (Exception ignored) { return createPlainTextResponse(Response.Status.BAD_REQUEST, "invalid revision"); }
        if (revision != device.playbackRevision || device.playback == null)
            return jsonResponse(Response.Status.OK, "{\"ok\":false}");
        synchronized (device) {
            if (device.loggedPlaybackEventRevision != revision) {
                device.loggedPlaybackEventRevision = revision;
                device.loggedPlaybackEvents.clear();
            }
            if (!device.loggedPlaybackEvents.add(event))
                return jsonResponse(Response.Status.OK, "{\"ok\":true}");
        }
        String message = "局域网投屏浏览器 " + event + " device=" + deviceRef(device)
                + " revision=" + revision + (detail.isEmpty() ? "" : " detail=" + detail);
        if ("playing".equals(event)) LogStore.success(Category.PLAYER, message);
        else if ("autoplay_blocked".equals(event)) LogStore.log(Category.PLAYER, message);
        else LogStore.fail(Category.PLAYER, message);
        return jsonResponse(Response.Status.OK, "{\"ok\":true}");
    }

    public String getPairingCode() {
        return pairingCode;
    }

    /** 用户手动更新配对码时撤销旧会话，旧码和已配对令牌立即失效。 */
    public synchronized String rotatePairingCode() {
        pairingCode = SystemConfig.regenerateLanPairingCode();
        devices.clear();
        failedPairings.clear();
        clearEpisodeCast();
        return pairingCode;
    }

    /** 手机端主动发送到已打开控制台的浏览器。 */
    public synchronized boolean publishBrowserPlayback(String deviceId, String title, String url) {
        return publishBrowserPlayback(deviceId, title, url, null);
    }

    public synchronized boolean publishBrowserPlayback(String deviceId, String title, String url,
                                                       Map<String, String> headers) {
        if (url == null || url.trim().isEmpty()) {
            LogStore.fail(Category.PLAYER, "局域网投屏发布失败 reason=empty_url");
            return false;
        }
        String playable = LanCastUrlRules.browserUrl(url, serverPort);
        if (playable == null || playable.length() > 8192) {
            LogStore.fail(Category.PLAYER, "局域网投屏发布失败 reason=unsupported_url");
            return false;
        }
        LanDevice target = null;
        for (LanDevice device : connectedDevices()) {
            if (device.id.equals(deviceId) && "browser".equals(device.kind)) {
                target = device;
                break;
            }
        }
        if (target == null) {
            LogStore.fail(Category.PLAYER, "局域网投屏发布失败 reason=target_offline");
            return false;
        }
        JsonObject state = new JsonObject();
        state.addProperty("revision", ++target.playbackRevision);
        state.addProperty("title", title == null ? "手机推送的视频" : title);
        clearCastMedia(target);
        if (playable.startsWith("/")) {
            state.addProperty("url", playable);
        } else {
            String relay = registerCastMedia(target, playable, headers);
            if (relay == null) return false;
            state.addProperty("url", relay);
        }
        state.addProperty("hls", playable.toLowerCase(Locale.ROOT).contains("m3u8"));
        state.addProperty("nativeVideo", playable.toLowerCase(Locale.ROOT)
                .matches(".*\\.(mp4|m4v|webm|mov|mkv)([?#].*)?$"));
        EpisodeCast cast = episodeCast;
        if (cast != null && target.id.equals(cast.deviceId)) addEpisodeState(state, cast);
        target.castProxyPaths.clear();
        if (playable.startsWith("/") && LanCastUrlRules.isProxyPath(playable.split("\\?", 2)[0])) {
            target.castProxyPaths.add(playable);
        }
        target.playback = state;
        LogStore.success(Category.PLAYER, "局域网投屏已发送 device=" + deviceRef(target)
                + " revision=" + target.playbackRevision
                + " relay=" + !playable.startsWith("/"));
        return true;
    }

    private static String registerCastMedia(LanDevice device, String url, Map<String, String> headers) {
        if (url == null || url.length() > 8192) return null;
        synchronized (device) {
            String previousId = device.castMediaByUrl.get(url);
            CastMedia previous = previousId == null ? null : device.castMedia.get(previousId);
            if (previous != null && previous.revision == device.playbackRevision) {
                previous.lastRegisteredAt = System.currentTimeMillis();
                return "/api/cast/media?id=" + previousId;
            }
            if (device.castMedia.size() >= 4096) {
                long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5);
                for (Map.Entry<String, CastMedia> entry : device.castMedia.entrySet()) {
                    CastMedia media = entry.getValue();
                    if (media.lastRegisteredAt < cutoff && device.castMedia.remove(entry.getKey(), media))
                        device.castMediaByUrl.remove(media.url, entry.getKey());
                }
            }
            if (device.castMedia.size() >= 4096) {
                logMediaFailure(device, "resource_limit");
                return null;
            }
            String id = generateToken();
            device.castMedia.put(id, new CastMedia(url,
                    headers == null ? java.util.Collections.emptyMap() : new HashMap<>(headers),
                    device.playbackRevision));
            device.castMediaByUrl.put(url, id);
            return "/api/cast/media?id=" + id;
        }
    }

    private static Map<String, String> headersForChild(String parentUrl, String childUrl,
                                                       Map<String, String> headers) {
        if (headers.isEmpty()) return headers;
        try {
            java.net.URI parent = new java.net.URI(parentUrl);
            java.net.URI child = new java.net.URI(childUrl);
            if (parent.getHost() != null && parent.getHost().equalsIgnoreCase(child.getHost())
                    && parent.getScheme().equalsIgnoreCase(child.getScheme())
                    && effectivePort(parent) == effectivePort(child)) return headers;
        } catch (Exception ignored) { }
        Map<String, String> safe = new HashMap<>(headers);
        java.util.Iterator<String> keys = safe.keySet().iterator();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key == null || key.equalsIgnoreCase("cookie") || key.equalsIgnoreCase("authorization")
                    || key.equalsIgnoreCase("proxy-authorization")) keys.remove();
        }
        return safe;
    }

    private static int effectivePort(java.net.URI uri) {
        return uri.getPort() != -1 ? uri.getPort()
                : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private LanDevice castProxyViewer(IHTTPSession session) {
        LanDevice device = authorizedDevice(session);
        if (device == null || !"browser".equals(device.kind)) return null;
        String query = session.getQueryParameterString();
        String path = session.getUri() + (query == null || query.isEmpty() ? "" : "?" + query);
        if (device.castProxyPaths.contains(path)) return device;
        logMediaFailure(device, "proxy_not_allowed");
        return null;
    }

    private static boolean isPlaylistMime(String mime, String proxyMode) {
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return type.contains("mpegurl") || type.contains("m3u8")
                || "m3u8".equalsIgnoreCase(proxyMode);
    }

    private static byte[] readCastPlaylist(InputStream stream) throws IOException {
        try (InputStream source = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) {
                if (output.size() + read > 2 * 1024 * 1024) throw new IOException("playlist too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static InputStream decodeCastPlaylist(InputStream stream, String encoding) throws IOException {
        if (encoding == null || "identity".equalsIgnoreCase(encoding)) return stream;
        if ("gzip".equalsIgnoreCase(encoding)) return new GZIPInputStream(stream);
        if ("br".equalsIgnoreCase(encoding)) return new BrotliInputStream(stream);
        throw new IOException("unsupported playlist encoding");
    }

    private static String generateToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public RemoteServer(int port, Context context) {
        super(port);
        mContext = context;
        pairingCode = initialPairingCode();
        restoreSessions();
        addGetRequestProcess();
        addPostRequestProcess();
    }

    /**
     * @param hostname 绑定地址:null/空=全部网卡(局域网可达);"127.0.0.1"=仅本机。
     *                 默认走仅本机绑定(本 App 的所有 clan://localhost、/proxy、/purify.m3u8 均在本机回环),
     *                 局域网共享/远程管理需显式开启 HawkConfig.LAN_SERVER_ENABLE。
     */
    public RemoteServer(String hostname, int port, Context context) {
        super(hostname == null || hostname.isEmpty() ? null : hostname, port);
        mContext = context;
        pairingCode = initialPairingCode();
        restoreSessions();
        addGetRequestProcess();
        addPostRequestProcess();
    }

    private static String initialPairingCode() {
        return SystemConfig.isLanServerEnabled() ? SystemConfig.getOrCreateLanPairingCode()
                : String.format(Locale.ROOT, "%08d", new SecureRandom().nextInt(100000000));
    }

    private void restoreSessions() {
        if (!SystemConfig.isLanServerEnabled()) return;
        long now = System.currentTimeMillis();
        List<LanSessionConfig.Record> stored = LanSessionConfig.load(pairingCode);
        for (LanSessionConfig.Record saved : stored) {
            if (devices.size() >= 32) break;
            if (saved == null || saved.id == null || !saved.id.matches("[0-9a-f]{32}")
                    || saved.token == null || !saved.token.matches("[0-9a-f]{32}")
                    || saved.name == null || saved.name.length() > 32
                    || saved.ip == null || saved.ip.length() > 64
                    || !("browser".equals(saved.kind) || "mbox".equals(saved.kind))
                    || saved.lastSeen <= 0 || saved.lastSeen > now + 60000
                    || now - saved.lastSeen > SESSION_WINDOW_MS) continue;
            devices.put(saved.token, new LanDevice(saved));
        }
        lastSessionsSavedAt = now;
        if (!stored.isEmpty()) LogStore.log(Category.SYSTEM, "局域网配对会话恢复 count=" + devices.size());
    }

    private synchronized void persistSessions(boolean force) {
        if (!SystemConfig.isLanServerEnabled()) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastSessionsSavedAt < 60000) return;
        List<LanSessionConfig.Record> saved = new ArrayList<>();
        for (LanDevice device : devices.values()) {
            if (now - device.lastSeen > SESSION_WINDOW_MS) continue;
            saved.add(new LanSessionConfig.Record(device.id, device.token, device.name,
                    device.ip, device.kind, device.connectedAt, device.lastSeen));
        }
        LanSessionConfig.save(pairingCode, saved);
        lastSessionsSavedAt = now;
    }

    private void addGetRequestProcess() {
        getRequestList.add(new RawRequestProcess(this.mContext, "/", R.raw.index, NanoHTTPD.MIME_HTML));
        getRequestList.add(new RawRequestProcess(this.mContext, "/index.html", R.raw.index, NanoHTTPD.MIME_HTML));
        // 可直达地址共用一张网页外壳；页面切换交给浏览器 History API。
        getRequestList.add(new RawRequestProcess(this.mContext, "/video.html", R.raw.index, NanoHTTPD.MIME_HTML));
        getRequestList.add(new RawRequestProcess(this.mContext, "/cast.html", R.raw.index, NanoHTTPD.MIME_HTML));
        getRequestList.add(new RawRequestProcess(this.mContext, "/files.html", R.raw.index, NanoHTTPD.MIME_HTML));
        getRequestList.add(new RawRequestProcess(this.mContext, "/style.css", R.raw.style, "text/css"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/hls.js", R.raw.hls_js, "application/javascript"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/script.js", R.raw.script, "application/x-javascript"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/favicon.ico", R.drawable.app_icon, "image/x-icon"));
    }

    private void addPostRequestProcess() {
        postRequestList.add(new InputRequestProcess(this));
    }

    @Override
    public void start(int timeout, boolean daemon) throws IOException {
        isStarted = true;
        super.start(timeout, daemon);
    }

    @Override
    public void stop() {
        persistSessions(true);
        super.stop();
        isStarted = false;
        devices.clear();
        clearEpisodeCast();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null || uri.isEmpty()) {
            return getRequestList.get(0).doResponse(session, "", null, null);
        }
        String fileName = uri.trim();
        if (fileName.indexOf('?') >= 0) {
            fileName = fileName.substring(0, fileName.indexOf('?'));
        }
        if (fileName.equals("/token.js")) return createPlainTextResponse(Response.Status.NOT_FOUND, "not found");
        if (session.getMethod() == Method.GET) {
            if (fileName.equals("/api/session")) {
                boolean paired = authorizedDevice(session) != null;
                return jsonResponse(paired ? Response.Status.OK : Response.Status.FORBIDDEN,
                        paired ? "{\"paired\":true}" : "{\"paired\":false}");
            }
            if (fileName.equals("/api/theme")) return serveTheme(session);
            if (fileName.equals("/api/videos")) {
                if (!isAuthorized(session, session.getParms())) return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
                return jsonResponse(Response.Status.OK, LanVideoLibrary.catalog().toString());
            }
            if (fileName.equals("/api/videos/media")) return serveMediaVideo(session);
            if (fileName.equals("/api/playback")) {
                if (!isAuthorized(session, session.getParms())) {
                    logPlaybackAuthDenied();
                    return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
                }
                LanDevice device = authorizedDevice(session);
                JsonObject state = device == null ? null : device.playback;
                return jsonResponse(Response.Status.OK, state == null ? "{\"revision\":0}" : state.toString());
            }
            if (fileName.equals("/api/cast/media")) return serveCastMedia(session);
            if (fileName.equals("/api/lan/catalog")) {
                if (!isAuthorized(session, session.getParms())) return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
                return jsonResponse(Response.Status.OK, ConfigDataExchange.catalog().toString());
            }
            if (fileName.equals("/api/lan/archive")) {
                if (!isAuthorized(session, session.getParms())) return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
                String raw = session.getParms().get("categories");
                if (raw == null || raw.length() > 100) return createPlainTextResponse(Response.Status.BAD_REQUEST, "invalid categories");
                Set<String> selected = new java.util.LinkedHashSet<>(Arrays.asList(raw.split(",")));
                File archive;
                try { archive = new ConfigBundle(mContext).exportSelected(selected); }
                catch (Exception error) { return createPlainTextResponse(Response.Status.BAD_REQUEST,
                        "配置导出失败：" + (error.getMessage() == null ? "请检查所选数据" : error.getMessage())); }
                try {
                    InputStream input = new FileInputStream(archive);
                    InputStream disposable = new FilterInputStream(input) {
                        @Override public void close() throws IOException { super.close(); archive.delete(); }
                    };
                    Response download = newFixedLengthResponse(Response.Status.OK, "application/zip", disposable, archive.length());
                    download.addHeader("Cache-Control", "no-store");
                    download.addHeader("Content-Disposition", "attachment; filename=mbox-config.zip");
                    return download;
                } catch (IOException error) {
                    archive.delete();
                    return createPlainTextResponse(Response.Status.INTERNAL_ERROR, "archive unavailable");
                }
            }
            if (fileName.equals("/api/lan/data")) {
                if (!isAuthorized(session, session.getParms())) return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
                JsonObject data = ConfigDataExchange.exportCategory(session.getParms().get("category"));
                return data == null ? createPlainTextResponse(Response.Status.BAD_REQUEST, "invalid category")
                        : jsonResponse(Response.Status.OK, data.toString());
            }
            if (fileName.equals("/api/lan/theme")) {
                if (!isAuthorized(session, session.getParms())) return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
                File archive = ConfigDataExchange.exportTheme(mContext, session.getParms().get("id"));
                if (archive == null) return createPlainTextResponse(Response.Status.NOT_FOUND, "theme not found");
                try {
                    InputStream source = new FileInputStream(archive);
                    InputStream disposable = new java.io.FilterInputStream(source) {
                        @Override public void close() throws IOException { super.close(); archive.delete(); }
                    };
                    Response download = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", disposable, archive.length());
                    download.addHeader("Cache-Control", "no-store");
                    return download;
                } catch (IOException error) {
                    archive.delete();
                    return createPlainTextResponse(Response.Status.INTERNAL_ERROR, "theme export failed");
                }
            }
            for (RequestProcess process : getRequestList) {
                if (process.isRequest(session, fileName)) {
                    return process.doResponse(session, fileName, session.getParms(), null);
                }
            }
            if (fileName.equals("/proxy")) {
                // 局域网侧仅允许已配对、且当前正在接收本机代理视频的浏览器读取。
                boolean castBrowser = !isLoopbackRequest(session);
                LanDevice castViewer = castBrowser ? castProxyViewer(session) : null;
                if (castBrowser && castViewer == null) {
                    return createPlainTextResponse(NanoHTTPD.Response.Status.FORBIDDEN, "forbidden");
                }
                Map<String, String> params = session.getParms();
                params.putAll(session.getHeaders());
                params.put("request-headers", GSON.toJson(session.getHeaders()));
                if (params.containsKey("do")) {
                    Object[] rs = com.github.catvod.crawler.SpiderApi.proxyLocal(params);
                    // jar 代理方法缺失/未加载时 proxyLocal 返回 null(还有异常吞掉的情况),
                    // 直接返回错误响应, 避免 rs[0] 读 null 数组崩溃
                    if (rs == null || rs.length < 2) {
                        if (castViewer != null) logMediaFailure(castViewer, "proxy_unavailable");
                        return NanoHTTPD.newFixedLengthResponse(
                                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                                "text/plain",
                                "proxy unavailable");
                    }
                    //if (rs[0] instanceof Response) {
                    //    return (Response) rs[0];
                    //}
                    int code = (int) rs[0];
                    String mime = (String) rs[1];
                    // 越界防护:原实现 rs==null||length<2 只保证 rs[0]/rs[1], 后面却直接读 rs[2];
                    // 长度不足 3 或响应体为 null 时返回空响应体
                    if (rs.length < 3 || !(rs[2] instanceof InputStream)) {
                        return NanoHTTPD.newFixedLengthResponse(
                                NanoHTTPD.Response.Status.lookup(code), mime, "");
                    }
                    InputStream stream = (InputStream) rs[2];
                    boolean rewrittenPlaylist = castBrowser && isPlaylistMime(mime, params.get("do"));
                    if (rewrittenPlaylist) {
                        try {
                            byte[] playlist = readCastPlaylist(stream);
                            long castRevision = castViewer.playbackRevision;
                            String rewritten = LanCastUrlRules.rewritePlaylist(
                                    new String(playlist, StandardCharsets.UTF_8), serverPort, local -> {
                                        if (castViewer.playbackRevision == castRevision
                                                && castViewer.castProxyPaths.size() < 4096) {
                                            castViewer.castProxyPaths.add(local);
                                        }
                                    });
                            stream = new ByteArrayInputStream(rewritten.getBytes(StandardCharsets.UTF_8));
                        } catch (IOException error) {
                            return createPlainTextResponse(Response.Status.BAD_REQUEST, "playlist too large");
                        }
                    }
                    Response response = NanoHTTPD.newChunkedResponse(
                            NanoHTTPD.Response.Status.lookup(code),
                            mime,
                            stream);
                    if (castBrowser) response.addHeader("Cache-Control", "no-store");
                    if (rs.length > 3) {
                        try {
                            HashMap<String, String> headers = (HashMap<String, String>) rs[3];
                            for (String key : headers.keySet()) {
                                if (rewrittenPlaylist && ("content-length".equalsIgnoreCase(key)
                                        || "content-encoding".equalsIgnoreCase(key)
                                        || "content-range".equalsIgnoreCase(key))) continue;
                                response.addHeader(key, headers.get(key));
                            }
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                    return response;
                }
            } else if (fileName.equals("/dns-query")) {
                // DoH 转发仅本机使用
                if (!isLoopbackRequest(session)) {
                    return createPlainTextResponse(NanoHTTPD.Response.Status.FORBIDDEN, "forbidden");
                }
                String name = session.getParms().get("name");
                if (name == null || name.trim().isEmpty()) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "missing name");
                }
                byte[] rs = new byte[0];
                try {
                    // okhttp 4 的 DnsOverHttps 不再提供原始 DNS 报文转发,这里改为返回解析结果文本
                    okhttp3.dnsoverhttps.DnsOverHttps doh = OkGoHelper.getDnsOverHttps();
                    if (doh != null) {
                        List<InetAddress> addresses = doh.lookup(name);
                        if (addresses != null && !addresses.isEmpty()) {
                            StringBuilder sb = new StringBuilder();
                            for (InetAddress a : addresses) {
                                if (a == null) continue;
                                if (sb.length() > 0) sb.append("\n");
                                sb.append(a.getHostAddress());
                            }
                            rs = sb.toString().getBytes("UTF-8");
                        }
                    }
                } catch (Throwable th) {
                    rs = new byte[0];
                }
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, new ByteArrayInputStream(rs), rs.length);
            } else if (fileName.equals("/purify.m3u8") || fileName.equals("/m3u8")) {
                // 本机播放器使用；正在接收该视频的已配对浏览器也可读取。
                // 规范路径是 /purify.m3u8:播放器按路径扩展名判定容器类型,没有 .m3u8 后缀的路径
                // 会被 Exo 判成 Progressive 首播失败;/m3u8 保留兼容旧调用方。
                boolean castBrowser = !isLoopbackRequest(session);
                LanDevice castViewer = castBrowser ? castProxyViewer(session) : null;
                if (castBrowser && castViewer == null) {
                    return createPlainTextResponse(NanoHTTPD.Response.Status.FORBIDDEN, "forbidden");
                }
                String content = m3u8Content == null ? "" : m3u8Content;
                if (castBrowser) {
                    long castRevision = castViewer.playbackRevision;
                    content = LanCastUrlRules.rewritePlaylist(content, serverPort, local -> {
                        if (castViewer.playbackRevision == castRevision
                                && castViewer.castProxyPaths.size() < 4096) {
                            castViewer.castProxyPaths.add(local);
                        }
                    });
                }
                Response purify = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                        "application/vnd.apple.mpegurl", content);
                // 名单是全局单槽(每次起播覆盖):禁止播放器缓存,避免 seek/重试拿到上一条清单
                purify.addHeader("Cache-Control", "no-store");
                return purify;
            } else if (fileName.startsWith("/file/")) {
                return serveFileGet(session, fileName.substring(6));
            }
        } else if (session.getMethod() == Method.POST) {
            return serveFilePost(session, fileName);
        }
        //default page: index.html
        return getRequestList.get(0).doResponse(session, "", null, null);
    }

    /** GET /file/<rel>: 本机回环直通，局域网侧文件和目录都需配对。 */
    private Response serveFileGet(IHTTPSession session, String rel) {
        try {
            if (!isAuthorized(session, session.getParms())) {
                return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
            }
            File root = storageRoot();
            File localFile;
            if (rel == null || rel.trim().isEmpty() || rel.equals(".")) {
                localFile = root;
            } else {
                localFile = resolveUnderRoot(rel);
            }
            if (localFile == null) {
                return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid path");
            }
            if (localFile.exists()) {
                if (localFile.isFile()) {
                    return streamLocalFile(session, localFile);
                } else {
                    if (!isAuthorized(session, session.getParms())) {
                        return createPlainTextResponse(NanoHTTPD.Response.Status.FORBIDDEN, "forbidden");
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, fileList(root.getAbsolutePath(), rel == null ? "" : rel));
                }
            } else {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "File not found!");
            }
        } catch (Throwable th) {
            String msg = th.getMessage();
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, msg == null ? "error" : msg);
        }
    }

    /** POST 处理:解析 body 后统一做管理鉴权,再分发到各处理函数 */
    private Response serveFilePost(IHTTPSession session, String fileName) {
        boolean managed = fileName.equals("/action") || fileName.equals("/upload")
                || fileName.equals("/newFolder") || fileName.equals("/delFolder") || fileName.equals("/delFile");
        if (managed && !isAuthorized(session, session.getParms())) {
            return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
        }
        boolean playbackAction = fileName.equals("/api/playback/next")
                || fileName.equals("/api/playback/select")
                || fileName.equals("/api/playback/event");
        if (playbackAction
                && authorizedDevice(session) == null) {
            return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
        }
        if (fileName.equals("/api/pair") || playbackAction) {
            try {
                String length = session.getHeaders().get("content-length");
                if (playbackAction && length == null) {
                    return createPlainTextResponse(Response.Status.BAD_REQUEST, "content length required");
                }
                if (Long.parseLong(length == null ? "0" : length) > 1024) {
                    return createPlainTextResponse(Response.Status.BAD_REQUEST, "request too large");
                }
            } catch (NumberFormatException ignored) {
                return createPlainTextResponse(Response.Status.BAD_REQUEST, "invalid content length");
            }
        }
        Map<String, String> files = new HashMap<>();
        try {
            if (session.getHeaders().containsKey("content-type")) {
                String hd = session.getHeaders().get("content-type");
                if (hd != null) {
                    // cuke: 修正中文乱码问题
                    if (hd.toLowerCase().contains("multipart/form-data") && !hd.toLowerCase().contains("charset=")) {
                        Matcher matcher = Pattern.compile("[ |\t]*(boundary[ |\t]*=[ |\t]*['|\"]?[^\"^'^;^,]*['|\"]?)", Pattern.CASE_INSENSITIVE).matcher(hd);
                        String boundary = matcher.find() ? matcher.group(1) : null;
                        if (boundary != null) {
                            session.getHeaders().put("content-type", "multipart/form-data; charset=utf-8; " + boundary);
                        }
                    }
                }
            }
            session.parseBody(files);
        } catch (IOException IOExc) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + IOExc.getMessage());
        } catch (NanoHTTPD.ResponseException rex) {
            return createPlainTextResponse(rex.getStatus(), rex.getMessage());
        }
        Map<String, String> params = session.getParms();
        if (params == null) params = new HashMap<>();
        if (fileName.equals("/api/pair")) return handlePair(session, params);
        if (fileName.equals("/api/logout")) {
            LanDevice device = authorizedDevice(session);
            if (device == null) return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
            kickDevice(device.id);
            Response response = jsonResponse(Response.Status.OK, "{\"paired\":false}");
            response.addHeader("Set-Cookie", "mbox_lan=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
            return response;
        }
        if (fileName.equals("/api/playback/next")) {
            LanDevice device = authorizedDevice(session);
            if (device == null) return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
            boolean accepted = requestNextEpisode(device, params.get("revision"));
            return jsonResponse(Response.Status.OK, "{\"accepted\":" + accepted + "}");
        }
        if (fileName.equals("/api/playback/select")) {
            LanDevice device = authorizedDevice(session);
            if (device == null) return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
            boolean accepted = requestSelectedEpisode(device, params.get("revision"), params.get("index"));
            return jsonResponse(Response.Status.OK, "{\"accepted\":" + accepted + "}");
        }
        if (fileName.equals("/api/playback/event")) {
            LanDevice device = authorizedDevice(session);
            return recordPlaybackEvent(device, params);
        }
        // 管理/变更类接口统一鉴权:本机(loopback)放行,局域网侧必须携带进程令牌
        for (RequestProcess process : postRequestList) {
            if (process.isRequest(session, fileName)) {
                return process.doResponse(session, fileName, params, files);
            }
        }
        try {
            if (fileName.equals("/upload")) {
                return handleUpload(params, files);
            } else if (fileName.equals("/newFolder")) {
                return handleNewFolder(params);
            } else if (fileName.equals("/delFolder")) {
                return handleDelete(params, true);
            } else if (fileName.equals("/delFile")) {
                return handleDelete(params, false);
            }
        } catch (Throwable th) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "operation failed");
        }
        //default page: index.html(与历史行为一致)
        return getRequestList.get(0).doResponse(session, "", null, null);
    }

    /** /upload: 目标目录限定在外部存储根目录内,文件名必须为单段,zip 解压带 Zip Slip 防护 */
    private Response handleUpload(Map<String, String> params, Map<String, String> files) throws IOException {
        File destDir = resolveUnderRoot(params.get("path"));
        if (destDir == null) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid path");
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "cannot create target dir");
        }
        for (String k : files.keySet()) {
            if (!k.startsWith("files-")) continue;
            String fn = params.get(k);
            String tmpFile = files.get(k);
            if (fn == null || tmpFile == null) continue;
            String safeName = sanitizeFileName(fn);
            if (safeName == null) {
                return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid file name: " + fn);
            }
            File tmp = new File(tmpFile);
            File target = resolveUnderRoot(joinRel(params.get("path"), safeName));
            if (target == null) {
                return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid target path");
            }
            if (target.exists() && !target.delete()) {
                return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "cannot replace " + safeName);
            }
            if (tmp.exists()) {
                if (safeName.toLowerCase().endsWith(".zip")) {
                    unzip(tmp, destDir);
                } else {
                    FileUtils.copyFile(tmp, target);
                }
            }
            if (tmp.exists()) tmp.delete();
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
    }

    /** /newFolder: 目录名必须为单段,目标位置限定在根目录内 */
    private Response handleNewFolder(Map<String, String> params) throws IOException {
        String safeName = sanitizeFileName(params.get("name"));
        if (safeName == null) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid name");
        }
        File parent = resolveUnderRoot(params.get("path"));
        if (parent == null) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid path");
        }
        File file = new File(parent, safeName);
        if (!file.exists()) {
            if (!file.mkdirs()) return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "cannot create folder");
            File flag = new File(file, ".tvbox_folder");
            if (!flag.exists()) flag.createNewFile();
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
    }

    /** /delFolder|/delFile: 拒绝删除根目录/空路径,且目标必须位于外部存储根目录内 */
    private Response handleDelete(Map<String, String> params, boolean folder) throws IOException {
        String path = params.get("path");
        if (path == null) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "missing path");
        }
        String p = path.trim();
        if (p.isEmpty() || p.equals("/") || p.equals(".") || p.equals("..")) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "refuse to delete root");
        }
        File target = resolveUnderRoot(p);
        if (target == null) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid path");
        }
        File rootCanon = storageRoot().getCanonicalFile();
        if (target.equals(rootCanon)) {
            return createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "refuse to delete root");
        }
        if (target.exists()) {
            if (folder) {
                FileUtils.recursiveDelete(target);
            } else {
                target.delete();
            }
            if (target.exists()) return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "cannot delete target");
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
    }

    /** 本机回环判断:NanoHTTPD 的 getRemoteIpAddress 返回对端地址文本 */
    private boolean isLoopbackRequest(IHTTPSession session) {
        String ip = session.getRemoteIpAddress();
        return ip != null && (ip.equals("::1")
                || ip.equals("0:0:0:0:0:0:0:1")
                || ip.startsWith("127."));
    }

    /** 管理接口鉴权:本机直连放行；局域网请求需 Cookie 或 X-TVBox-Token 请求头。 */
    private boolean isAuthorized(IHTTPSession session, Map<String, String> params) {
        if (isLoopbackRequest(session)) return true;
        return authorizedDevice(session) != null;
    }

    private Response serveCastMedia(IHTTPSession session) {
        LanDevice viewer = authorizedDevice(session);
        if (viewer == null || !"browser".equals(viewer.kind))
            return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
        String mediaId = session.getParms() == null ? null : session.getParms().get("id");
        if (mediaId == null || !CAST_MEDIA_ID.matcher(mediaId).matches())
            return createPlainTextResponse(Response.Status.BAD_REQUEST, "invalid media id");
        CastMedia media = viewer.castMedia.get(mediaId);
        if (media == null || media.revision != viewer.playbackRevision)
            return createPlainTextResponse(Response.Status.NOT_FOUND, "media expired");
        final long mediaRevision = media.revision;
        String range = session.getHeaders().get("range");
        okhttp3.Response upstream = null;
        String requestUrl = media.url;
        long requestStartNs = System.nanoTime();
        try {
            for (int redirects = 0; redirects <= 5; redirects++) {
                Request.Builder request = new Request.Builder().url(requestUrl).get();
                Map<String, String> requestHeaders = headersForChild(media.url, requestUrl, media.headers);
                for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
                    String name = header.getKey();
                    String value = header.getValue();
                    if (name == null || value == null || value.length() > 4096
                            || name.equalsIgnoreCase("host") || name.equalsIgnoreCase("connection")
                            || name.equalsIgnoreCase("content-length") || name.equalsIgnoreCase("accept-encoding")
                            || name.equalsIgnoreCase("range")) continue;
                    try { request.header(name, value); } catch (IllegalArgumentException ignored) { }
                }
                if (range != null && range.length() <= 80 && CAST_MEDIA_RANGE.matcher(range).matches())
                    request.header("Range", range);
                // Range 与 Content-Length 都按原始媒体字节计;禁止压缩中途改变字节位置。
                request.header("Accept-Encoding", "identity");
                upstream = OkGoHelper.getMediaRelayClient().newCall(request.build()).execute();
                String location = upstream.header("Location");
                if (upstream.code() < 300 || upstream.code() > 399 || location == null) break;
                okhttp3.HttpUrl next = upstream.request().url().resolve(location);
                upstream.close();
                upstream = null;
                if (redirects == 5 || next == null
                        || !LanCastRelayRules.allowedRedirect(media.url, next.toString())) {
                    logMediaFailure(viewer, "redirect_blocked");
                    return createPlainTextResponse(Response.Status.FORBIDDEN, "media redirect blocked");
                }
                requestUrl = next.toString();
            }
        } catch (Exception error) {
            if (upstream != null) upstream.close();
            logMediaFailure(viewer, "request_" + error.getClass().getSimpleName());
            return createPlainTextResponse(Response.Status.INTERNAL_ERROR, "media unavailable");
        }
        if (upstream == null) {
            logMediaFailure(viewer, "empty_response");
            return createPlainTextResponse(Response.Status.INTERNAL_ERROR, "media unavailable");
        }
        logSlowCastRead(viewer, "upstream_headers",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartNs), 0);
        okhttp3.ResponseBody body = upstream.body();
        if (body == null || !upstream.isSuccessful()) {
            int code = upstream.code();
            upstream.close();
            logMediaFailure(viewer, "upstream_http_" + code);
            Response.Status status = Response.Status.lookup(code);
            return createPlainTextResponse(status == null ? Response.Status.INTERNAL_ERROR : status,
                    "media unavailable");
        }
        String mime = upstream.header("Content-Type", "application/octet-stream");
        boolean playlist = isPlaylistMime(mime, "")
                || media.url.toLowerCase(Locale.ROOT).contains("m3u8");
        if (playlist) {
            try (okhttp3.Response response = upstream) {
                byte[] source = readCastPlaylist(decodeCastPlaylist(body.byteStream(),
                        response.header("Content-Encoding")));
                if (viewer.playbackRevision != mediaRevision || viewer.castMedia.get(mediaId) != media)
                    return createPlainTextResponse(Response.Status.NOT_FOUND, "media expired");
                String rewritten = LanCastRelayRules.rewrite(new String(source, StandardCharsets.UTF_8),
                        response.request().url().toString(), child ->
                                viewer.playbackRevision == mediaRevision
                                        && viewer.castMedia.get(mediaId) == media
                                        ? registerCastMedia(viewer, child,
                                                headersForChild(media.url, child, media.headers)) : null);
                if (viewer.playbackRevision != mediaRevision || viewer.castMedia.get(mediaId) != media)
                    return createPlainTextResponse(Response.Status.NOT_FOUND, "media expired");
                byte[] bytes = rewritten.getBytes(StandardCharsets.UTF_8);
                Response result = newFixedLengthResponse(Response.Status.OK,
                        "application/vnd.apple.mpegurl", new ByteArrayInputStream(bytes), bytes.length);
                result.addHeader("Cache-Control", "no-store");
                return result;
            } catch (IOException error) {
                logMediaFailure(viewer, "playlist_io");
                return createPlainTextResponse(Response.Status.BAD_REQUEST, "playlist unavailable");
            }
        }
        final okhttp3.Response sourceResponse = upstream;
        InputStream stream = new FilterInputStream(body.byteStream()) {
            private long relayedBytes;

            private void requireCurrentCast() throws IOException {
                if (devices.get(viewer.token) != viewer || viewer.playbackRevision != media.revision
                        || viewer.castMedia.get(mediaId) != media)
                    throw new IOException("cast revoked");
            }
            @Override public int read() throws IOException {
                requireCurrentCast();
                try {
                    int value = super.read();
                    if (value >= 0) relayedBytes++;
                    return value;
                } catch (IOException error) {
                    logMediaFailure(viewer, "stream_" + error.getClass().getSimpleName());
                    throw error;
                }
            }
            @Override public int read(byte[] buffer, int off, int len) throws IOException {
                requireCurrentCast();
                long started = System.nanoTime();
                try {
                    int count = super.read(buffer, off, len);
                    if (count > 0) relayedBytes += count;
                    logSlowCastRead(viewer, "upstream_body",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), relayedBytes);
                    return count;
                } catch (IOException error) {
                    logMediaFailure(viewer, "stream_" + error.getClass().getSimpleName());
                    throw error;
                }
            }
            @Override public void close() throws IOException {
                try { super.close(); } finally { sourceResponse.close(); }
            }
        };
        Response.Status status = upstream.code() == 206 ? Response.Status.PARTIAL_CONTENT : Response.Status.OK;
        long size = body.contentLength();
        Response result = size >= 0 ? newFixedLengthResponse(status, mime, stream, size)
                : newChunkedResponse(status, mime, stream);
        String contentRange = upstream.header("Content-Range");
        if (contentRange != null) result.addHeader("Content-Range", contentRange);
        // 个别源站忽略 identity 仍返回压缩体;流未解码,须连同字节范围一起原样交给浏览器。
        String contentEncoding = upstream.header("Content-Encoding");
        if (contentEncoding != null) result.addHeader("Content-Encoding", contentEncoding);
        String acceptRanges = upstream.header("Accept-Ranges");
        if (acceptRanges != null) result.addHeader("Accept-Ranges", acceptRanges);
        result.addHeader("Cache-Control", "no-store");
        return result;
    }

    private LanDevice authorizedDevice(IHTTPSession session) {
        String token = null;
        Map<String, String> headers = session.getHeaders();
        if (headers != null) {
            token = headers.get("x-tvbox-token");
            if (token == null) {
                String cookie = headers.get("cookie");
                if (cookie != null) {
                    for (String item : cookie.split(";")) {
                        String part = item.trim();
                        if (part.startsWith("mbox_lan=")) {
                            token = part.substring("mbox_lan=".length());
                            break;
                        }
                    }
                }
            }
        }
        LanDevice device = token == null ? null : devices.get(token);
        if (device != null) {
            long now = System.currentTimeMillis();
            if (now - device.lastSeen > SESSION_WINDOW_MS) {
                devices.remove(device.token, device);
                persistSessions(true);
                LogStore.log(Category.SYSTEM, "局域网配对会话过期 device=" + deviceRef(device));
                return null;
            }
            device.lastSeen = now;
            persistSessions(false);
        }
        return device;
    }

    /** 按 MediaStore 视频 ID 读取，与 App「我的 → 本地视频」是同一批内容。 */
    private Response serveMediaVideo(IHTTPSession session) {
        if (!isAuthorized(session, session.getParms())) {
            return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
        }
        long id;
        try { id = Long.parseLong(session.getParms().get("id")); }
        catch (Exception ignored) { return createPlainTextResponse(Response.Status.BAD_REQUEST, "invalid video id"); }
        if (id <= 0) return createPlainTextResponse(Response.Status.BAD_REQUEST, "invalid video id");
        Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
        try {
            String name;
            long catalogSize;
            try (Cursor cursor = mContext.getContentResolver().query(uri,
                    new String[]{MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE},
                    null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) {
                    return createPlainTextResponse(Response.Status.NOT_FOUND, "video not found");
                }
                name = cursor.getString(0);
                catalogSize = cursor.getLong(1);
            }
            String mime = mContext.getContentResolver().getType(uri);
            if (mime == null || mime.isEmpty() || mime.equals("application/octet-stream")) mime = mimeForName(name);
            ParcelFileDescriptor descriptor = mContext.getContentResolver().openFileDescriptor(uri, "r");
            if (descriptor == null) return createPlainTextResponse(Response.Status.NOT_FOUND, "video not found");
            long size = descriptor.getStatSize();
            if (size < 0) size = catalogSize;
            if (size < 0) { descriptor.close(); return createPlainTextResponse(Response.Status.INTERNAL_ERROR, "video length unavailable"); }
            InputStream stream = new FilterInputStream(new FileInputStream(descriptor.getFileDescriptor())) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { descriptor.close(); }
                }
            };
            return streamFileResponse(session, stream, size, mime);
        } catch (Exception error) {
            return createPlainTextResponse(Response.Status.NOT_FOUND, "video unavailable");
        }
    }

    /** 浏览器 seek 依赖 Range；文件读取只在 NanoHTTPD 工作线程执行。 */
    private Response streamLocalFile(IHTTPSession session, File file) throws IOException {
        return streamFileResponse(session, new FileInputStream(file), file.length(), mimeForName(file.getName()));
    }

    private static String mimeForName(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4") || name.endsWith(".m4v") ? "video/mp4"
                : name.endsWith(".webm") ? "video/webm"
                : name.endsWith(".mov") ? "video/quicktime"
                : name.endsWith(".mkv") ? "video/x-matroska"
                : name.endsWith(".avi") ? "video/x-msvideo"
                : name.endsWith(".flv") ? "video/x-flv"
                : name.endsWith(".m3u8") ? "application/vnd.apple.mpegurl"
                : name.endsWith(".ts") ? "video/mp2t"
                : "application/octet-stream";
    }

    private Response streamFileResponse(IHTTPSession session, InputStream stream, long size, String mime) throws IOException {
        long from = 0;
        long to = size - 1;
        boolean partial = false;
        String range = session.getHeaders().get("range");
        if (range != null && range.matches("bytes=\\d+-\\d*")) {
            String[] parts = range.substring(6).split("-", 2);
            try {
                from = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) to = Math.min(to, Long.parseLong(parts[1]));
                partial = true;
            } catch (NumberFormatException ignored) { partial = false; }
        }
        if (partial && (from >= size || from > to)) {
            stream.close();
            Response invalid = createPlainTextResponse(Response.Status.RANGE_NOT_SATISFIABLE, "range not satisfiable");
            invalid.addHeader("Content-Range", "bytes */" + size);
            return invalid;
        }
        try {
            long skipped = 0;
            while (skipped < from) {
                long step = stream.skip(from - skipped);
                if (step <= 0) throw new IOException("cannot seek file");
                skipped += step;
            }
        } catch (IOException error) { stream.close(); throw error; }
        LanDevice viewer = authorizedDevice(session);
        InputStream responseStream = viewer == null ? stream : new FilterInputStream(stream) {
            private void requireCurrentSession() throws IOException {
                if (devices.get(viewer.token) != viewer) throw new IOException("device disconnected");
            }
            @Override public int read() throws IOException { requireCurrentSession(); return super.read(); }
            @Override public int read(byte[] buffer, int off, int len) throws IOException {
                requireCurrentSession(); return super.read(buffer, off, len);
            }
        };
        Response result = newFixedLengthResponse(partial ? Response.Status.PARTIAL_CONTENT : Response.Status.OK,
                mime, responseStream, size == 0 ? 0 : to - from + 1);
        result.addHeader("Accept-Ranges", "bytes");
        if (partial) result.addHeader("Content-Range", "bytes " + from + "-" + to + "/" + size);
        result.addHeader("Cache-Control", "no-store");
        return result;
    }


    private Response handlePair(IHTTPSession session, Map<String, String> params) {
        String ip = session.getRemoteIpAddress() == null ? "unknown" : session.getRemoteIpAddress();
        FailedPairing failure = failedPairings.computeIfAbsent(ip, key -> new FailedPairing());
        long now = System.currentTimeMillis();
        synchronized (failure) {
            if (now - failure.windowStart > 60000) { failure.windowStart = now; failure.count = 0; }
            if (failure.count >= 8) return jsonResponse(Response.Status.FORBIDDEN, "{\"error\":\"请稍后重试\"}");
            String code = params.get("code");
            if (code == null || !MessageDigest.isEqual(pairingCode.getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                failure.count++;
                if (failure.count == 1 || failure.count == 8)
                    LogStore.fail(Category.SYSTEM, "局域网配对失败 attempts=" + failure.count);
                return jsonResponse(Response.Status.FORBIDDEN, "{\"error\":\"配对码不正确\"}");
            }
            failure.count = 0;
        }
        String token = generateToken();
        String kind = "mbox".equals(session.getHeaders().get("x-mbox-client")) ? "mbox" : "browser";
        String label = params.get("name");
        if (label == null || label.trim().isEmpty()) {
            String agent = session.getHeaders().get("user-agent");
            label = "mbox".equals(kind) ? "MBox"
                    : agent != null && agent.contains("Windows") ? "Windows 浏览器"
                    : agent != null && agent.contains("Macintosh") ? "Mac 浏览器"
                    : "浏览器";
        }
        label = label.replaceAll("[\\p{Cntrl}]", "").trim();
        if (label.length() > 32) label = label.substring(0, 32);
        if (pairedDevices().size() >= 32) {
            return jsonResponse(Response.Status.SERVICE_UNAVAILABLE, "{\"error\":\"已连接设备过多，请先踢出闲置设备\"}");
        }
        LanDevice device = new LanDevice(token, label, ip, kind);
        devices.put(token, device);
        persistSessions(true);
        LogStore.success(Category.SYSTEM, "局域网配对成功 kind=" + kind
                + " device=" + deviceRef(device));
        Response response = jsonResponse(Response.Status.OK, "{\"paired\":true,\"token\":\"" + token + "\"}");
        response.addHeader("Set-Cookie", "mbox_lan=" + token + "; Path=/; HttpOnly; SameSite=Strict");
        return response;
    }

    private Response serveTheme(IHTTPSession session) {
        if (!isAuthorized(session, session.getParms())) return createPlainTextResponse(Response.Status.FORBIDDEN, "forbidden");
        JsonObject result = new JsonObject();
        result.addProperty("dark", com.github.tvbox.osc.theme.ThemeRuntime.type().isDark());
        com.github.tvbox.osc.bean.theme.ThemePalette palette = com.github.tvbox.osc.theme.ThemeRuntime.runtimePalette();
        JsonObject colors = new JsonObject();
        if (palette != null) {
            for (Map.Entry<String, Integer> e : palette.asMap().entrySet()) {
                colors.addProperty(e.getKey(), com.github.tvbox.osc.bean.theme.ThemeColorPalette.toHex(e.getValue()));
            }
        }
        result.add("colors", colors);
        return jsonResponse(Response.Status.OK, result.toString());
    }

    private static Response jsonResponse(Response.IStatus status, String json) {
        Response response = newFixedLengthResponse(status, "application/json; charset=utf-8", json);
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("X-Content-Type-Options", "nosniff");
        return response;
    }

    /** 外部存储根目录:所有文件类接口的允许范围 */
    private static File storageRoot() {
        return Environment.getExternalStorageDirectory();
    }

    private static String joinRel(String path, String name) {
        if (path == null || path.trim().isEmpty()) return name;
        return path.trim() + "/" + name;
    }

    /** 校验普通文件名:单段、非 . / ..、不含路径分隔符与 NUL,返回清理后的名字;非法返回 null */
    private static String sanitizeFileName(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) return null;
        if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0 || n.indexOf('\0') >= 0) return null;
        return n;
    }

    /**
     * 把相对子路径安全地限定在外部存储根目录内。
     * 拒绝:null、空字符;显式拒绝 ".." 目录穿越与绝对路径;
     * canonical 双重校验可防止符号链接等方式逃出根目录。
     * 注意:入参为空字符串时返回根目录自身(调用方需自行决定是否允许)。
     */
    private File resolveUnderRoot(String relPath) {
        if (relPath == null) return null;
        String p = relPath.trim();
        if (p.indexOf('\0') >= 0) return null;
        p = p.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        File cur = new File(storageRoot().getAbsolutePath());
        String[] parts = p.split("/");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            cur = new File(cur, part);
        }
        try {
            String rootPath = storageRoot().getCanonicalPath();
            String curPath = cur.getCanonicalPath();
            if (!curPath.equals(rootPath) && !curPath.startsWith(rootPath + File.separator)) {
                return null;
            }
            return cur;
        } catch (IOException e) {
            return null;
        }
    }

    public void setDataReceiver(DataReceiver receiver) {
        mDataReceiver = receiver;
    }

    public DataReceiver getDataReceiver() {
        return mDataReceiver;
    }

    public boolean isStarting() {
        return isStarted;
    }

    public String getServerAddress() {
        String ipAddress = getLocalIPAddress(mContext);
        return "http://" + ipAddress + ":" + RemoteServer.serverPort + "/";
    }

    public String getLoadAddress() {
        return "http://127.0.0.1:" + RemoteServer.serverPort + "/";
    }

    public static Response createPlainTextResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text);
    }

    public static Response createJSONResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, "application/json", text);
    }

    @SuppressLint("DefaultLocale")
    public static String getLocalIPAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        if (ipAddress == 0) {
            try {
                Enumeration < NetworkInterface > enumerationNi = NetworkInterface.getNetworkInterfaces();
                while (enumerationNi.hasMoreElements()) {
                    NetworkInterface networkInterface = enumerationNi.nextElement();
                    String interfaceName = networkInterface.getDisplayName();
                    if (interfaceName.equals("eth0") || interfaceName.equals("wlan0")) {
                        Enumeration < InetAddress > enumIpAddr = networkInterface.getInetAddresses();
                        while (enumIpAddr.hasMoreElements()) {
                            InetAddress inetAddress = enumIpAddr.nextElement();
                            if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                                return inetAddress.getHostAddress();
                            }
                        }
                    }
                }
            } catch (SocketException e) {
                e.printStackTrace();
            }
        } else {
            return String.format("%d.%d.%d.%d", (ipAddress & 0xff), (ipAddress >> 8 & 0xff), (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
        }
        return "0.0.0.0";
    }

    /**
     * 本机所有"局域网里别的设备能访问到"的 IPv4 地址(Wi‑Fi/以太网优先,其次是热点等),
     * 按出现顺序去重;取不到返回空表。
     *
     * <p>为什么不复用 {@link #getLocalIPAddress}:那一个只回答"Wi‑Fi 的地址",取不到就退 eth0/wlan0、
     * 最后兜个 {@code 0.0.0.0}(用户没法用它);而实际情形常常是多个可用:同时连 Wi‑Fi 与网线、
     * 或本机开着热点(地址在 ap/swlan 网卡上)。设置页要告诉用户"从别的设备该访问哪个地址",
     * 把可用的都列出来比只给一个更实用 —— 筛掉蜂窝/VPN/Wi‑Fi Direct 等够不到的网卡与非内网地址,
     * 口径见 {@link com.github.tvbox.osc.util.LanAddressRules}(纯逻辑,带单测)。
     */
    public static List < String > getLanIpv4Addresses() {
        List < String > preferred = new ArrayList < > ();
        List < String > others = new ArrayList < > ();
        try {
            Enumeration < NetworkInterface > enumerationNi = NetworkInterface.getNetworkInterfaces();
            while (enumerationNi != null && enumerationNi.hasMoreElements()) {
                NetworkInterface networkInterface = enumerationNi.nextElement();
                if (networkInterface == null) continue;
                try {
                    if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                } catch (Throwable th) {
                    continue;
                }
                String ifaceName = networkInterface.getName();
                for (InetAddress addr : java.util.Collections.list(networkInterface.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address)) continue;
                    String ip = addr.getHostAddress();
                    if (!com.github.tvbox.osc.util.LanAddressRules.isUsableLanIpv4(ifaceName, ip)) continue;
                    if (preferred.contains(ip) || others.contains(ip)) continue;
                    if (com.github.tvbox.osc.util.LanAddressRules.isPreferredInterface(ifaceName)) {
                        preferred.add(ip);
                    } else {
                        others.add(ip);
                    }
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        List < String > out = new ArrayList < > (preferred);
        for (String ip : others) {
            if (!out.contains(ip)) out.add(ip);
        }
        return out;
    }

    String fileTime(long time, String fmt) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        Date date = calendar.getTime();
        SimpleDateFormat sdf = new SimpleDateFormat(fmt);
        return sdf.format(date);
    }

    String fileList(String root, String path) {
        File file = new File(root + "/" + path);
        File[] list = file.listFiles();
        JsonObject info = new JsonObject();
        info.addProperty("remote", getServerAddress().replace("http://", "clan://"));
        info.addProperty("del", 0);
        if (path.isEmpty()) {
            info.addProperty("parent", ".");
        } else {
            info.addProperty("parent", file.getParentFile().getAbsolutePath().replace(root + "/", "").replace(root, ""));
        }
        if (list == null || list.length == 0) {
            info.add("files", new JsonArray());
            return info.toString();
        }
        Arrays.sort(list, new Comparator < File > () {@Override
        public int compare(File o1, File o2) {
            if (o1.isDirectory() && o2.isFile()) return -1;
            return o1.isFile() && o2.isDirectory() ? 1 : o1.getName().compareTo(o2.getName());
        }
        });
        JsonArray result = new JsonArray();
        for (File f: list) {
            if (f.getName().startsWith(".")) {
                if (f.getName().equals(".tvbox_folder")) {
                    info.addProperty("del", 1);
                }
                continue;
            }
            JsonObject fileObj = new JsonObject();
            fileObj.addProperty("name", f.getName());
            fileObj.addProperty("path", f.getAbsolutePath().replace(root + "/", ""));
            fileObj.addProperty("time", fileTime(f.lastModified(), "yyyy/MM/dd aHH:mm:ss"));
            fileObj.addProperty("dir", f.isDirectory() ? 1 : 0);
            result.add(fileObj);
        }
        info.add("files", result);
        return info.toString();
    }

    /**
     * 解压 ZIP 到目标目录。
     * Zip Slip 防护:逐条目做 canonical 包含性校验,任何通过 ../ 或绝对路径
     * 越出 destDir 的条目一律拒绝(抛 SecurityException,整体失败,不落盘)。
     * ZipFile 与输入流均用 try-with-resources 确保关闭。
     */
    void unzip(File zipFilePath, File destDir) throws IOException {
        if (destDir == null) {
            throw new IOException("null dest dir");
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("cannot create dest dir: " + destDir);
        }
        String destCanonical = destDir.getCanonicalPath();
        try (ZipFile zip = new ZipFile(zipFilePath)) {
            Enumeration<? extends ZipEntry> iter = (Enumeration<? extends ZipEntry>) zip.entries();
            while (iter.hasMoreElements()) {
                ZipEntry entry = iter.nextElement();
                String name = entry.getName();
                if (name == null || name.indexOf('\0') >= 0) {
                    throw new SecurityException("Invalid zip entry name");
                }
                File target = new File(destDir, name);
                String targetCanonical = target.getCanonicalPath();
                if (!targetCanonical.equals(destCanonical)
                        && !targetCanonical.startsWith(destCanonical + File.separator)) {
                    throw new SecurityException("Zip entry escapes target directory: " + name);
                }
                if (entry.isDirectory()) {
                    if (!target.exists()) target.mkdirs();
                    File flag = new File(target, ".tvbox_folder");
                    if (!flag.exists()) flag.createNewFile();
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (InputStream is = zip.getInputStream(entry)) {
                        extractFile(is, target);
                    }
                }
            }
        }
    }

    void extractFile(InputStream inputStream, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (dst.exists() && !dst.delete()) {
            throw new IOException("cannot replace existing file: " + dst);
        }
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dst))) {
            byte[] bytesIn = new byte[2048];
            int len;
            while ((len = inputStream.read(bytesIn)) > 0) {
                bos.write(bytesIn, 0, len);
            }
        }
    }

}

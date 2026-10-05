package com.github.tvbox.osc.cast;

import com.github.tvbox.osc.util.LanAddressRules;
import com.github.tvbox.osc.server.LanCastRelayRules;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** The small, testable policy surface for the temporary DLNA media endpoint. */
final class CastMediaRules {
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,80}");
    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,8}");

    private CastMediaRules() { }

    static URI parse(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty() || rawUrl.length() > 8192) return null;
        try {
            URI uri = new URI(rawUrl.trim());
            return uri.getFragment() == null && uri.getRawUserInfo() == null ? uri : null;
        } catch (Exception ignored) { return null; }
    }

    static String normalizeFileUri(String rawUrl) {
        if (rawUrl == null) return null;
        String trimmed = rawUrl.trim();
        return trimmed.regionMatches(true, 0, "file:", 0, 5)
                ? trimmed.replace(" ", "%20") : trimmed;
    }

    static boolean isHttp(URI uri) {
        if (uri == null || uri.getHost() == null) return false;
        String scheme = lower(uri.getScheme());
        return "http".equals(scheme) || "https".equals(scheme);
    }

    static boolean isLoopback(String host) {
        if (host == null) return false;
        String value = lower(host);
        return value.startsWith("127.") || "localhost".equals(value) || "localhost.".equals(value)
                || "::1".equals(value) || "[::1]".equals(value)
                || "0:0:0:0:0:0:0:1".equals(value)
                || LanCastRelayRules.isLoopbackLiteral(value);
    }

    /** A playlist cannot turn its media capability into access to the app's LAN APIs. */
    static boolean allowedChild(String parentUrl, String childUrl, int localPort) {
        URI parent = parse(parentUrl);
        URI child = parse(childUrl);
        if (!isHttp(parent) || !isHttp(child)) return false;
        String host = child.getHost();
        boolean local = isLoopback(host) || LanAddressRules.isPrivateIpv4(host)
                || LanCastRelayRules.isRestrictedIpLiteral(host);
        if (!local) return true; // LanCastRelayRules separately rejects new private hosts.
        if (!sameOrigin(parent, child)) return false;
        if (isLoopback(host) || child.getPort() == localPort) {
            if (child.getPort() != localPort) return false;
            String path = child.getPath();
            return "/proxy".equals(path) || "/purify.m3u8".equals(path)
                    || "/m3u8".equals(path) || "/api/videos/media".equals(path);
        }
        return true; // The user-selected LAN media host, at another port.
    }

    static Map<String, String> snapshotHeaders(Map<String, String> input) {
        if (!hasHeaders(input)) return Collections.emptyMap();
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null || !HEADER_NAME.matcher(key).matches()
                    || value.length() > 4096 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                    || isHopByHop(key)) continue;
            copy.put(key, value);
            if (copy.size() == 32) break;
        }
        return Collections.unmodifiableMap(copy);
    }

    static Map<String, String> headersForChild(String parentUrl, String childUrl,
                                                Map<String, String> headers) {
        if (headers.isEmpty()) return headers;
        URI parent = parse(parentUrl);
        URI child = parse(childUrl);
        if (sameOrigin(parent, child)) return headers;
        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = lower(entry.getKey());
            if ("user-agent".equals(key) || "accept".equals(key) || "accept-language".equals(key))
                safe.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(safe);
    }

    static boolean isPlaylist(String url, String mime) {
        String type = lower(mime);
        if (type != null && (type.contains("mpegurl") || type.contains("vnd.apple.mpegurl"))) return true;
        URI uri = parse(url);
        return uri != null && uri.getPath() != null && lower(uri.getPath()).endsWith(".m3u8");
    }

    static String extension(String url) {
        URI uri = parse(url);
        if (uri == null || uri.getPath() == null) return "bin";
        String path = lower(uri.getPath());
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash) return "bin";
        String ext = path.substring(dot + 1);
        return SAFE_EXTENSION.matcher(ext).matches() ? ext : "bin";
    }

    static String fileMime(String url) {
        String ext = extension(url);
        switch (ext) {
            case "m3u8": return "application/vnd.apple.mpegurl";
            case "mp4": case "m4v": return "video/mp4";
            case "ts": case "m2t": case "m2ts": case "mts": case "mpegts": return "video/mp2t";
            case "m4s": case "cmfv": return "video/iso.segment";
            case "cmfa": return "audio/iso.segment";
            case "m4a": return "audio/mp4";
            case "vtt": case "webvtt": return "text/vtt";
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "avi": return "video/x-msvideo";
            case "flv": return "video/x-flv";
            case "mp3": return "audio/mpeg";
            case "aac": return "audio/aac";
            default: return "application/octet-stream";
        }
    }

    /** Classify an extensionless source from bounded response bytes before advertising it to DLNA. */
    static MediaKind mediaKind(String url, String contentType, byte[] prefix, int length) {
        int count = prefix == null ? 0 : Math.max(0, Math.min(length, prefix.length));
        int start = 0;
        if (count >= 3 && (prefix[0] & 0xff) == 0xef && (prefix[1] & 0xff) == 0xbb
                && (prefix[2] & 0xff) == 0xbf) start = 3;
        while (start < count && (prefix[start] == ' ' || prefix[start] == '\r'
                || prefix[start] == '\n' || prefix[start] == '\t')) start++;
        if (asciiAt(prefix, start, count, "#EXTM3U")) return MediaKind.HLS;
        if (asciiAtIgnoreCase(prefix, start, count, "<!doctype html")
                || asciiAtIgnoreCase(prefix, start, count, "<html")
                || (start < count && (prefix[start] == '{' || prefix[start] == '[')))
            return MediaKind.INVALID;
        if (count >= 12 && asciiAt(prefix, 4, count, "ftyp"))
            return new MediaKind("mp4", "video/mp4", false, true);
        if (count >= 4 && (prefix[0] & 0xff) == 0x1a && (prefix[1] & 0xff) == 0x45
                && (prefix[2] & 0xff) == 0xdf && (prefix[3] & 0xff) == 0xa3) {
            String marker = new String(prefix, 0, Math.min(count, 64), java.nio.charset.StandardCharsets.ISO_8859_1)
                    .toLowerCase(Locale.ROOT);
            return marker.contains("webm") ? new MediaKind("webm", "video/webm", false, true)
                    : new MediaKind("mkv", "video/x-matroska", false, true);
        }
        if (count > 188 && (prefix[0] & 0xff) == 0x47 && (prefix[188] & 0xff) == 0x47)
            return new MediaKind("ts", "video/mp2t", false, true);
        if (asciiAt(prefix, 0, count, "FLV")) return new MediaKind("flv", "video/x-flv", false, true);

        String mime = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (mime.contains("mpegurl") || mime.contains("m3u8")) return MediaKind.HLS;
        switch (mime) {
            case "video/mp4": return new MediaKind("mp4", "video/mp4", false, true);
            case "video/x-matroska": return new MediaKind("mkv", "video/x-matroska", false, true);
            case "video/webm": return new MediaKind("webm", "video/webm", false, true);
            case "video/mp2t": return new MediaKind("ts", "video/mp2t", false, true);
            case "video/x-msvideo": return new MediaKind("avi", "video/x-msvideo", false, true);
            case "video/x-flv": return new MediaKind("flv", "video/x-flv", false, true);
            case "audio/mpeg": return new MediaKind("mp3", "audio/mpeg", false, true);
            case "audio/aac": return new MediaKind("aac", "audio/aac", false, true);
            case "text/html": case "application/json": return MediaKind.INVALID;
            default: break;
        }
        String ext = extension(url);
        String knownMime = fileMime(url);
        if (!"application/octet-stream".equals(knownMime))
            return new MediaKind(ext, knownMime, "m3u8".equals(ext), true);
        return MediaKind.UNKNOWN;
    }

    /** FFmpeg checks the suffix of HLS segment URLs before it fetches their content. */
    static MediaKind hlsChildKind(String url, LanCastRelayRules.ResourceKind role,
                                  boolean fragmentedMp4) {
        if (role == LanCastRelayRules.ResourceKind.PLAYLIST) return MediaKind.HLS;
        MediaKind inferred = mediaKind(url, null, null, 0);
        if (role == LanCastRelayRules.ResourceKind.KEY)
            return inferred.known && !inferred.playlist ? inferred
                    : new MediaKind("key", "application/octet-stream", false, true);
        if (role == LanCastRelayRules.ResourceKind.INIT)
            return inferred.known && !inferred.playlist ? inferred
                    : new MediaKind("mp4", "video/mp4", false, true);
        if (role == LanCastRelayRules.ResourceKind.SEGMENT) {
            if (inferred.known && !inferred.playlist) return inferred;
            return fragmentedMp4 ? new MediaKind("m4s", "video/iso.segment", false, true)
                    : new MediaKind("ts", "video/mp2t", false, true);
        }
        return inferred;
    }

    static final class MediaKind {
        static final MediaKind HLS = new MediaKind("m3u8", "application/vnd.apple.mpegurl", true, true);
        static final MediaKind UNKNOWN = new MediaKind("bin", "application/octet-stream", false, false);
        static final MediaKind INVALID = new MediaKind("", "", false, false);
        final String extension;
        final String mime;
        final boolean playlist;
        final boolean known;

        MediaKind(String extension, String mime, boolean playlist, boolean known) {
            this.extension = extension;
            this.mime = mime;
            this.playlist = playlist;
            this.known = known;
        }
    }

    private static boolean asciiAt(byte[] bytes, int offset, int length, String value) {
        if (bytes == null || offset + value.length() > length) return false;
        for (int i = 0; i < value.length(); i++) {
            if (bytes[offset + i] != (byte) value.charAt(i)) return false;
        }
        return true;
    }

    private static boolean asciiAtIgnoreCase(byte[] bytes, int offset, int length, String value) {
        if (bytes == null || offset + value.length() > length) return false;
        for (int i = 0; i < value.length(); i++) {
            int actual = bytes[offset + i] & 0xff;
            if (Character.toLowerCase((char) actual) != value.charAt(i)) return false;
        }
        return true;
    }

    static ByteRange range(String raw, long length) {
        if (raw == null || raw.isEmpty()) return new ByteRange(true, false, 0, length - 1);
        if (length <= 0 || !raw.startsWith("bytes=") || raw.indexOf(',') >= 0)
            return new ByteRange(false, true, 0, 0);
        String[] parts = raw.substring(6).split("-", -1);
        if (parts.length != 2) return new ByteRange(false, true, 0, 0);
        try {
            long start;
            long end;
            if (parts[0].isEmpty()) {
                long suffix = Long.parseLong(parts[1]);
                if (suffix <= 0) return new ByteRange(false, true, 0, 0);
                start = Math.max(0, length - suffix);
                end = length - 1;
            } else {
                start = Long.parseLong(parts[0]);
                end = parts[1].isEmpty() ? length - 1 : Math.min(length - 1, Long.parseLong(parts[1]));
            }
            return new ByteRange(start >= 0 && start <= end && start < length, true, start, end);
        } catch (NumberFormatException ignored) { return new ByteRange(false, true, 0, 0); }
    }

    static final class ByteRange {
        final boolean valid;
        final boolean partial;
        final long start;
        final long end;

        ByteRange(boolean valid, boolean partial, long start, long end) {
            this.valid = valid;
            this.partial = partial;
            this.start = start;
            this.end = end;
        }
    }

    private static boolean hasHeaders(Map<String, String> headers) {
        return headers != null && !headers.isEmpty();
    }

    private static boolean sameOrigin(URI left, URI right) {
        return isHttp(left) && isHttp(right)
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && port(left) == port(right);
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isHopByHop(String key) {
        switch (lower(key)) {
            case "host": case "connection": case "content-length": case "transfer-encoding":
            case "accept-encoding": case "range": case "proxy-connection": case "keep-alive":
            case "te": case "trailer": case "upgrade": return true;
            default: return false;
        }
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}

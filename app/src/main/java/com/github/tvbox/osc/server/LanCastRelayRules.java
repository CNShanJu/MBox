package com.github.tvbox.osc.server;

import java.net.InetAddress;
import java.net.URI;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将 HLS 清单中的媒体、子清单和密钥限制到当前配对设备的视频通道。 */
public final class LanCastRelayRules {
    private static final Pattern ATTRIBUTE_URI = Pattern.compile("URI=\"([^\"]+)\"");

    public enum ResourceKind { PLAYLIST, SEGMENT, INIT, KEY, OTHER }

    private LanCastRelayRules() { }

    public static String rewrite(String playlist, String baseUrl, Function<String, String> register) {
        return rewriteWithHint(playlist, baseUrl, (url, playlistChild) -> register.apply(url));
    }

    /** The hint marks extensionless variant/rendition URLs as child playlists. */
    public static String rewriteWithHint(String playlist, String baseUrl,
                                         BiFunction<String, Boolean, String> register) {
        return rewriteWithResourceHint(playlist, baseUrl,
                (url, kind) -> register.apply(url, kind == ResourceKind.PLAYLIST));
    }

    /** Preserve the HLS role of extensionless children for renderers that validate URL suffixes. */
    public static String rewriteWithResourceHint(String playlist, String baseUrl,
                                                  BiFunction<String, ResourceKind, String> register) {
        URI base;
        try { base = new URI(baseUrl); }
        catch (Exception ignored) { return playlist; }
        StringBuilder result = new StringBuilder(playlist.length() + 128);
        String[] lines = playlist.split("\n", -1);
        boolean nextPlaylist = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean cr = line.endsWith("\r");
            if (cr) line = line.substring(0, line.length() - 1);
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                ResourceKind attributeKind = attributeKind(trimmed);
                Matcher matcher = ATTRIBUTE_URI.matcher(line);
                StringBuffer rewritten = new StringBuffer();
                while (matcher.find()) {
                    String media = relayUrl(base, matcher.group(1), attributeKind, register);
                    matcher.appendReplacement(rewritten, Matcher.quoteReplacement(
                            "URI=\"" + media + "\""));
                }
                matcher.appendTail(rewritten);
                line = rewritten.toString();
                if (trimmed.startsWith("#EXT-X-STREAM-INF:")) nextPlaylist = true;
            } else if (!trimmed.isEmpty()) {
                line = relayUrl(base, trimmed,
                        nextPlaylist ? ResourceKind.PLAYLIST : ResourceKind.SEGMENT, register);
                nextPlaylist = false;
            }
            result.append(line);
            if (cr) result.append('\r');
            if (i + 1 < lines.length) result.append('\n');
        }
        return result.toString();
    }

    private static ResourceKind attributeKind(String tag) {
        if (tag.startsWith("#EXT-X-MEDIA:") || tag.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                || tag.startsWith("#EXT-X-RENDITION-REPORT:")) return ResourceKind.PLAYLIST;
        if (tag.startsWith("#EXT-X-KEY:") || tag.startsWith("#EXT-X-SESSION-KEY:"))
            return ResourceKind.KEY;
        if (tag.startsWith("#EXT-X-MAP:") || (tag.startsWith("#EXT-X-PRELOAD-HINT:")
                && tag.contains("TYPE=MAP"))) return ResourceKind.INIT;
        if (tag.startsWith("#EXT-X-PART:") || tag.startsWith("#EXT-X-PRELOAD-HINT:"))
            return ResourceKind.SEGMENT;
        return ResourceKind.OTHER;
    }

    public static boolean allowedRedirect(String originalUrl, String targetUrl) {
        try {
            URI source = new URI(originalUrl);
            URI target = new URI(targetUrl);
            String scheme = target.getScheme();
            return source.getHost() != null && target.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && allowedChildHost(source.getHost(), target.getHost());
        } catch (Exception ignored) { return false; }
    }

    private static String relayUrl(URI base, String raw, ResourceKind kind,
                                   BiFunction<String, ResourceKind, String> register) {
        URI resolved;
        try {
            resolved = base.resolve(raw);
        } catch (Exception ignored) {
            return "about:blank";
        }
        String scheme = resolved.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme)) || resolved.getHost() == null
                || !allowedChildHost(base.getHost(), resolved.getHost()))
            return "about:blank";
        // A resource-capacity failure from register must abort the whole manifest. Converting
        // it to about:blank would create an apparently valid playlist that fails mid-playback.
        String relay = register.apply(resolved.toString(), kind);
        return relay == null ? "about:blank" : relay;
    }

    private static boolean allowedChildHost(String parentHost, String childHost) {
        if (childHost.equalsIgnoreCase(parentHost)) return true;
        String host = childHost.toLowerCase(java.util.Locale.ROOT).replace("[", "").replace("]", "");
        if (host.equals("localhost") || host.equals("localhost.") || host.endsWith(".local")
                || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1")) return false;
        // Check the parsed address, not only its spelling: IPv4-mapped IPv6 such as
        // ::ffff:192.168.1.1 must not turn a public playlist into a LAN request.
        InetAddress literal = numericLiteral(host);
        if (literal != null) return publicLiteral(literal);
        if (host.contains(":") || host.matches("[0-9.]+")) return false;
        return true;
    }

    /** Only parses numeric IP literals; this policy never performs a DNS lookup. */
    private static InetAddress numericLiteral(String raw) {
        if (raw == null) return null;
        String host = raw.replace("[", "").replace("]", "");
        boolean ipv6 = host.indexOf(':') >= 0;
        if (ipv6 ? !host.matches("[0-9a-fA-F:.]+")
                : !host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))
            return null;
        try {
            if (ipv6) return InetAddress.getByName(host); // A colon makes this a numeric IPv6 literal.
            String[] parts = host.split("\\.");
            byte[] bytes = new byte[4];
            for (int i = 0; i < bytes.length; i++) {
                int part = Integer.parseInt(parts[i]);
                if (part > 255) return null;
                bytes[i] = (byte) part;
            }
            return InetAddress.getByAddress(bytes);
        } catch (Exception ignored) { return null; }
    }

    public static boolean isLoopbackLiteral(String host) {
        InetAddress address = numericLiteral(host);
        return address != null && (address.isLoopbackAddress()
                || isMappedIpv4(address.getAddress()) && (address.getAddress()[12] & 0xff) == 127);
    }

    public static boolean isRestrictedIpLiteral(String host) {
        InetAddress address = numericLiteral(host);
        if (address != null) return !publicLiteral(address);
        return host != null && host.replace("[", "").replace("]", "").matches("[0-9.]+");
    }

    /** Classify an address returned by the same DNS resolver OkHttp will use to connect. */
    public static boolean isPublicAddress(InetAddress address) {
        return address != null && publicLiteral(address);
    }

    private static boolean publicLiteral(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (isMappedIpv4(bytes)) {
            byte[] ipv4 = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
            return publicIpv4(ipv4);
        }
        if (bytes.length == 4) return publicIpv4(bytes);
        return bytes.length == 16 && !address.isAnyLocalAddress()
                && !address.isLoopbackAddress() && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress() && !address.isMulticastAddress()
                && (bytes[0] & 0xfe) != 0xfc;
    }

    private static boolean isMappedIpv4(byte[] bytes) {
        if (bytes.length != 16 || (bytes[10] & 0xff) != 0xff
                || (bytes[11] & 0xff) != 0xff) return false;
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        return true;
    }

    private static boolean publicIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first > 0 && first < 224 && first != 10 && first != 127
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31)
                && !(first == 192 && second == 168)
                && !(first == 198 && (second == 18 || second == 19));
    }
}

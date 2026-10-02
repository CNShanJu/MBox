package com.github.tvbox.osc.server;

import java.net.URI;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将 HLS 清单中的媒体、子清单和密钥限制到当前配对设备的视频通道。 */
final class LanCastRelayRules {
    private static final Pattern ATTRIBUTE_URI = Pattern.compile("URI=\"([^\"]+)\"");

    private LanCastRelayRules() { }

    static String rewrite(String playlist, String baseUrl, Function<String, String> register) {
        URI base;
        try { base = new URI(baseUrl); }
        catch (Exception ignored) { return playlist; }
        StringBuilder result = new StringBuilder(playlist.length() + 128);
        String[] lines = playlist.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean cr = line.endsWith("\r");
            if (cr) line = line.substring(0, line.length() - 1);
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                Matcher matcher = ATTRIBUTE_URI.matcher(line);
                StringBuffer rewritten = new StringBuffer();
                while (matcher.find()) {
                    String media = relayUrl(base, matcher.group(1), register);
                    matcher.appendReplacement(rewritten, Matcher.quoteReplacement(
                            "URI=\"" + media + "\""));
                }
                matcher.appendTail(rewritten);
                line = rewritten.toString();
            } else if (!trimmed.isEmpty()) {
                line = relayUrl(base, trimmed, register);
            }
            result.append(line);
            if (cr) result.append('\r');
            if (i + 1 < lines.length) result.append('\n');
        }
        return result.toString();
    }

    static boolean allowedRedirect(String originalUrl, String targetUrl) {
        try {
            URI source = new URI(originalUrl);
            URI target = new URI(targetUrl);
            String scheme = target.getScheme();
            return source.getHost() != null && target.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && allowedChildHost(source.getHost(), target.getHost());
        } catch (Exception ignored) { return false; }
    }

    private static String relayUrl(URI base, String raw, Function<String, String> register) {
        try {
            URI resolved = base.resolve(raw);
            String scheme = resolved.getScheme();
            if (scheme == null || !("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme)) || resolved.getHost() == null
                    || !allowedChildHost(base.getHost(), resolved.getHost()))
                return "about:blank";
            String relay = register.apply(resolved.toString());
            return relay == null ? "about:blank" : relay;
        } catch (Exception ignored) {
            return "about:blank";
        }
    }

    private static boolean allowedChildHost(String parentHost, String childHost) {
        if (childHost.equalsIgnoreCase(parentHost)) return true;
        String host = childHost.toLowerCase(java.util.Locale.ROOT).replace("[", "").replace("]", "");
        if (host.equals("localhost") || host.equals("localhost.") || host.endsWith(".local")
                || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1")) return false;
        if (host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            String[] parts = host.split("\\.");
            try {
                int first = Integer.parseInt(parts[0]);
                int second = Integer.parseInt(parts[1]);
                return first > 0 && first < 224 && first != 10 && first != 127
                        && !(first == 100 && second >= 64 && second <= 127)
                        && !(first == 169 && second == 254)
                        && !(first == 172 && second >= 16 && second <= 31)
                        && !(first == 192 && second == 168)
                        && !(first == 198 && (second == 18 || second == 19));
            } catch (Exception ignored) { return false; }
        }
        if (host.contains(":")) return !host.startsWith("fe80:") && !host.startsWith("fc")
                && !host.startsWith("fd") && !host.equals("0:0:0:0:0:0:0:1");
        return true;
    }
}

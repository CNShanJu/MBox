package com.github.tvbox.osc.server;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/** 将本机播放器代理地址转换成已配对浏览器能访问的同源地址。 */
final class LanCastUrlRules {
    private LanCastUrlRules() { }

    static String browserUrl(String rawUrl, int serverPort) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return null;
        String url = rawUrl.trim();
        try {
            URI parsed = new URI(url);
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            if (scheme == null || host == null || !("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme))) return null;
            if (!isLoopback(host)) return url;
            if (!"http".equalsIgnoreCase(scheme)) return null;
            if (parsed.getPort() != serverPort || parsed.getRawUserInfo() != null) return null;
            String path = parsed.getRawPath();
            if (!isSharedMediaPath(path)) return null;
            return path + (parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery());
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean isProxyPath(String path) {
        return "/proxy".equals(path) || "/purify.m3u8".equals(path) || "/m3u8".equals(path);
    }

    private static boolean isSharedMediaPath(String path) {
        return isProxyPath(path) || "/api/videos/media".equals(path) || path.startsWith("/file/");
    }

    private static boolean isLoopback(String host) {
        return host.startsWith("127.") || "localhost".equalsIgnoreCase(host)
                || "localhost.".equalsIgnoreCase(host) || "::1".equals(host)
                || "[::1]".equals(host) || "0:0:0:0:0:0:0:1".equals(host);
    }

    static String rewritePlaylist(String playlist, int serverPort) {
        return rewritePlaylist(playlist, serverPort, ignored -> { });
    }

    static String rewritePlaylist(String playlist, int serverPort, Consumer<String> onLocalMedia) {
        if (playlist == null || playlist.isEmpty()) return playlist;
        Pattern localUrl = Pattern.compile("https?://(?:127\\.\\d+\\.\\d+\\.\\d+|localhost\\.?|\\[::1\\]):"
                + serverPort + "/[^\\s\\\"']+", Pattern.CASE_INSENSITIVE);
        Matcher matcher = localUrl.matcher(playlist);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            String browser = browserUrl(matcher.group(), serverPort);
            if (browser != null && isProxyPath(browser.split("\\?", 2)[0])) onLocalMedia.accept(browser);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(browser == null ? matcher.group() : browser));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }
}

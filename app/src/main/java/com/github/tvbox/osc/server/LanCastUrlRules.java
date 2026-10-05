package com.github.tvbox.osc.server;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.function.BiFunction;
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
            if (!isSharedMediaPath(path) || !isSafeMediaPath(parsed)) return null;
            return path + (parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery());
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean isProxyPath(String path) {
        return "/proxy".equals(path) || "/purify.m3u8".equals(path) || "/m3u8".equals(path);
    }

    static boolean isPlaylistUrl(String url) {
        try {
            String path = new URI(url).getPath();
            return path != null && (path.toLowerCase(Locale.ROOT).endsWith(".m3u8")
                    || "/m3u8".equals(path));
        } catch (Exception ignored) { return false; }
    }

    private static boolean isSharedMediaPath(String path) {
        return isProxyPath(path) || "/api/videos/media".equals(path)
                || path != null && path.startsWith("/file/");
    }

    private static boolean isSafeMediaPath(URI uri) {
        String rawPath = uri.getRawPath();
        if (rawPath == null || !rawPath.equals(uri.normalize().getRawPath())) return false;
        String lower = rawPath.toLowerCase(Locale.ROOT);
        return !lower.contains("%2e") && !lower.contains("%2f")
                && !lower.contains("%5c") && !rawPath.contains("\\");
    }

    private static boolean isLoopback(String host) {
        return host.startsWith("127.") || "localhost".equalsIgnoreCase(host)
                || "localhost.".equalsIgnoreCase(host) || "::1".equals(host)
                || "[::1]".equals(host) || "0:0:0:0:0:0:0:1".equals(host);
    }

    /** Rewrite every HLS child into a registered browser relay URL or fail the whole manifest. */
    static String rewriteForBrowser(String playlist, String baseUrl, int serverPort,
                                    BiFunction<String, LanCastRelayRules.ResourceKind, String> register) {
        if (playlist == null || register == null || serverPort < 1 || serverPort > 65535)
            throw new IllegalStateException("invalid browser playlist request");
        return LanCastRelayRules.rewriteWithUriPolicy(playlist, baseUrl, (base, raw, kind) -> {
            URI child = resolveBrowserChild(base, raw, serverPort);
            String relay = register.apply(child.toString(), kind);
            if (relay == null || relay.trim().isEmpty()
                    || "about:blank".equalsIgnoreCase(relay.trim()))
                throw new IllegalStateException("cast resource registration failed");
            return relay;
        });
    }

    private static URI resolveBrowserChild(URI base, String raw, int serverPort) {
        URI reference;
        try { reference = new URI(raw); }
        catch (Exception error) { throw new IllegalStateException("invalid playlist child URL", error); }
        URI child;
        String localOrigin = "http://127.0.0.1:" + serverPort;
        if (reference.getScheme() == null && reference.getRawAuthority() == null
                && raw.startsWith("/") && isSharedMediaPath(reference.getRawPath())) {
            child = URI.create(localOrigin).resolve(reference);
        } else if (reference.getScheme() == null && reference.getRawAuthority() != null
                && isLoopbackHost(reference.getHost()) && reference.getPort() == serverPort
                && isSharedMediaPath(reference.getRawPath())) {
            child = URI.create("http:" + raw);
        } else if (reference.getScheme() == null && reference.getRawAuthority() == null
                && "".equals(reference.getRawPath()) && reference.getRawQuery() != null) {
            // URI.resolve("?x") drops the last path component on Java. HLS query-only
            // children address the current manifest path with a different query.
            String baseText = base.toString();
            int query = baseText.indexOf('?');
            int fragment = baseText.indexOf('#');
            int end = query < 0 ? baseText.length() : query;
            if (fragment >= 0 && fragment < end) end = fragment;
            child = URI.create(baseText.substring(0, end) + raw);
        } else {
            child = base.resolve(reference);
        }
        String scheme = child.getScheme();
        if (child.getHost() == null || !("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme)))
            throw new IllegalStateException("unsupported playlist child URL");
        if (isLoopbackHost(child.getHost())) {
            String local = browserUrl(child.toString(), serverPort);
            if (local == null || !local.startsWith("/"))
                throw new IllegalStateException("blocked local playlist child URL");
        } else if (!LanCastRelayRules.allowedRedirect(base.toString(), child.toString())) {
            throw new IllegalStateException("blocked playlist child host");
        }
        return child;
    }

    private static boolean isLoopbackHost(String host) {
        return host != null && (isLoopback(host) || LanCastRelayRules.isLoopbackLiteral(host));
    }

    /** Snapshot forwarding headers; credentials follow only their origin or this app's media proxy. */
    static Map<String, String> headersForChild(String headerOrigin, String child,
                                               Map<String, String> headers, int serverPort) {
        Map<String, String> snapshot = headers == null ? new HashMap<>() : new HashMap<>(headers);
        if (snapshot.isEmpty()) return snapshot;
        String local = browserUrl(child, serverPort);
        if (local != null && local.startsWith("/")) return snapshot;
        try {
            URI parentUri = new URI(headerOrigin);
            URI childUri = new URI(child);
            if (parentUri.getHost() != null && childUri.getHost() != null
                    && parentUri.getHost().equalsIgnoreCase(childUri.getHost())
                    && parentUri.getScheme() != null
                    && parentUri.getScheme().equalsIgnoreCase(childUri.getScheme())
                    && effectivePort(parentUri) == effectivePort(childUri)) return snapshot;
        } catch (Exception ignored) { }
        snapshot.keySet().removeIf(key -> key == null || key.equalsIgnoreCase("cookie")
                || key.equalsIgnoreCase("authorization")
                || key.equalsIgnoreCase("proxy-authorization"));
        return snapshot;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort()
                : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
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

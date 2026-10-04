package xyz.doikki.videoplayer.player;

import android.util.Log;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;

import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 播放内核错误统一记录：业务日志可筛选，关闭业务日志时仍保留脱敏故障摘要。 */
public final class PlaybackErrorReporter {
    private static final String TAG = "MBoxPlayer";
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:https?|rtsp|rtmp|ftp|file|content)://[^\\s\\\"'<>]+"
    );
    private static final Pattern QUERY_VALUE = Pattern.compile(
            "([?&][A-Za-z][A-Za-z0-9_.%-]*=)[^\\s&#,，。；;\\[\\]()\\\"'<>]*"
    );
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)\\b(authorization|set-cookie|cookie|token|sign|signature|password|secret|api[_-]?key)\\s*[:=][^\\r\\n]*"
    );
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,，。；;]+");

    private PlaybackErrorReporter() {
    }

    /** 只记录协议、主机和端口；本机代理可额外记录固定路由，永不记录鉴权路径或查询。 */
    public static String source(String path) {
        if (path == null || path.isEmpty()) return "未知来源";
        String target = proxyTarget(path);
        if (target != null) return "真实地址=" + origin(target) + "，代理=" + proxyRoute(path);
        try {
            URI uri = URI.create(path);
            String marker = localRouteMarker(uri.getScheme(), uri.getHost(), uri.getRawPath());
            return origin(uri) + marker;
        } catch (RuntimeException ignored) {
            return "非标准地址";
        }
    }

    private static String proxyTarget(String path) {
        try {
            URI uri = URI.create(path);
            if (!"/proxy".equals(localRouteMarker(uri.getScheme(), uri.getHost(), uri.getRawPath()))) return null;
            String query = uri.getRawQuery();
            if (query == null) return null;
            for (String item : query.split("&")) {
                if (!item.startsWith("url=")) continue;
                String target = URLDecoder.decode(item.substring(4), "UTF-8");
                return target.isEmpty() ? null : target;
            }
        } catch (Exception ignored) {
            // 非标准源地址仍可播放，但不能把原文写入诊断日志。
        }
        return null;
    }

    private static String proxyRoute(String path) {
        try {
            URI uri = URI.create(path);
            return origin(uri) + localRouteMarker(uri.getScheme(), uri.getHost(), uri.getRawPath());
        } catch (RuntimeException ignored) {
            return "本机代理";
        }
    }

    private static String origin(String address) {
        try {
            return origin(URI.create(address));
        } catch (RuntimeException ignored) {
            return "非标准地址";
        }
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isEmpty()) return "非标准地址";
        scheme = scheme.toLowerCase(Locale.ROOT);
        if ("file".equals(scheme)) return "file:本地资源";
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return scheme + ":地址已隐藏";
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = '[' + host + ']';
        int port = uri.getPort();
        return scheme + "://" + host + (port < 0 ? "" : ":" + port);
    }

    static String localRouteMarker(String scheme, String host, String encodedPath) {
        if (!"http".equalsIgnoreCase(scheme)
                || !("127.0.0.1".equalsIgnoreCase(host)
                || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host) || "[::1]".equals(host))) return "";
        if ("/proxy".equals(encodedPath) || "/purify.m3u8".equals(encodedPath)
                || "/m3u8".equals(encodedPath)) return encodedPath;
        return "";
    }

    public static void failure(String engine, String operation, String source, String reason) {
        String detail = line(engine, operation, source, reason);
        LogStore.fail(Category.PLAYER, detail);
        Log.e(TAG, detail);
    }

    public static void failure(String engine, String operation, String source, Throwable error) {
        failure(engine, operation, source, cause(error));
    }

    /** 自动重试前的取流失败也可在业务日志“仅失败”中找到。 */
    public static void retrying(String engine, String source, String reason) {
        String detail = line(engine, "播放出错，准备重试", source, reason);
        LogStore.fail(Category.PLAYER, detail);
        Log.e(TAG, detail);
    }

    public static String cause(Throwable error) {
        if (error == null) return "未知异常";
        StringBuilder result = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 3; depth++, current = current.getCause()) {
            if (depth > 0) result.append(" <- ");
            result.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                result.append(": ").append(safe(message));
            }
        }
        return result.toString();
    }

    private static String line(String engine, String operation, String source, String reason) {
        return safeDiagnosticText(engine) + " " + safeDiagnosticText(operation) + " ["
                + (source == null ? "未知来源" : safeDiagnosticText(source))
                + "] " + safe(reason);
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) return "未知原因";
        return safeDiagnosticText(value);
    }

    /** 清理异常或第三方返回文案中的地址与鉴权值，供其他诊断入口复用。 */
    public static String safeDiagnosticText(String value) {
        if (value == null) return "未知";
        Matcher urls = URL.matcher(value);
        StringBuffer result = new StringBuffer();
        while (urls.find()) {
            urls.appendReplacement(result, Matcher.quoteReplacement(source(urls.group())));
        }
        urls.appendTail(result);
        String text = QUERY_VALUE.matcher(result.toString()).replaceAll("$1[已隐藏]");
        text = BEARER.matcher(text).replaceAll("Bearer [已隐藏]");
        text = CREDENTIAL.matcher(text).replaceAll("$1=[已隐藏]");
        text = text.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() <= 240 ? text : text.substring(0, 240) + "…";
    }
}

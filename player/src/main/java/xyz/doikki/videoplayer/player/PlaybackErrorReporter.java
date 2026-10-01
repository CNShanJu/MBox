package xyz.doikki.videoplayer.player;

import android.net.Uri;
import android.util.Log;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;

import java.util.regex.Pattern;

/** 播放内核错误统一记录：业务日志可筛选，关闭业务日志时仍保留原始错误日志。 */
public final class PlaybackErrorReporter {
    private static final String TAG = "MBoxPlayer";
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:https?|rtsp|rtmp|file|content)://[^\\s\\\"'<>]+"
    );

    private PlaybackErrorReporter() {
    }

    /** 只保留协议、主机和端口，避免把播放地址中的鉴权参数写入日志。 */
    public static String source(String path) {
        if (path == null || path.isEmpty()) return "未知来源";
        try {
            Uri uri = Uri.parse(path);
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isEmpty()) return "未知协议";
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return scheme;
            int port = uri.getPort();
            return scheme + "://" + host + (port < 0 ? "" : ":" + port);
        } catch (RuntimeException ignored) {
            return "未知来源";
        }
    }

    public static void failure(String engine, String operation, String source, String reason) {
        String detail = line(engine, operation, source, reason);
        LogStore.fail(Category.PLAYER, detail);
        Log.e(TAG, detail);
    }

    public static void failure(String engine, String operation, String source, Throwable error) {
        failure(engine, operation, source, cause(error));
    }

    /** 自动重试也留下错误原因，便于区分“首次失败后恢复”和“从未发生错误”。 */
    public static void retrying(String engine, String source, String reason) {
        String detail = line(engine, "播放出错，准备重试", source, reason);
        LogStore.log(Category.PLAYER, detail);
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
        return engine + " " + operation + " [" + (source == null ? "未知来源" : source)
                + "] " + safe(reason);
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) return "未知原因";
        String text = URL.matcher(value).replaceAll("[地址]")
                .replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() <= 240 ? text : text.substring(0, 240) + "…";
    }
}

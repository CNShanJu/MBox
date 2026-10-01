package com.github.tvbox.osc.download.internal;

import java.io.IOException;

import javax.net.ssl.SSLException;

/**
 * 下载错误分类(模块内部共用,不对外暴露)。
 * <p>
 * 为什么要共用一份:同一个判断在"调度器决定重试次数"和"分段循环决定是否跳过单片"两处都要用 ——
 * 断网/超时这类错误必须整任务退避重试(跳过单片没意义,后面每片都会失败),
 * 而 HTTP 状态码/内容异常这类"分片级"错误只该跳过该片、交给补片重试。
 */
public final class DownloadErrors {

    /**
     * 单片失败信息里的措辞:该分片在源侧已失效(HTTP 404/410)。
     * 只用于说明"这一片"下不来,<strong>不代表</strong>整集没救(其它片可能只是抖动)。
     */
    public static final String SEGMENT_GONE_TEXT = "源侧已失效";

    /**
     * 整集失败信息里的标记:本次缺片<strong>全部</strong>已确认源侧永久失效,补片/换线路/重新解析地址都不可能补齐。
     * 调度器只认这一个标记 —— 见到它就不再重新解析地址、也不再整任务重试(见 {@link #isPermanentlyGone(Throwable)})。
     * 注意:单片措辞({@link #SEGMENT_GONE_TEXT})会被拼进"最后错误: …"里,两者必须不同字面量,
     * 否则"混合缺失(还有可补救的临时失败)"会被误判成永久失效。
     */
    public static final String ALL_SEGMENTS_GONE = "缺片均为源侧永久失效";

    /**
     * "本线路整体不可用"的失败信息标记(连续多片下载失败,见 DownloadExecutor 的
     * {@code MAX_CONSECUTIVE_SEGMENT_FAIL} 分支)。本地重试/重新解析地址都是同一个结果,
     * 真正能救回来的是换线路重下(4.8②);拼这条信息时请用本常量,不要另写字面量。
     */
    public static final String ROUTE_SUSPECT_TEXT = "该线路的分片地址可能已失效";

    private DownloadErrors() {
    }

    public static final class HttpFailure extends IOException {
        public final int code;
        public HttpFailure(int code, String resource) { super(resource + " HTTP " + code); this.code = code; }
    }

    public static final class SessionExpired extends IOException {
        public SessionExpired() { super("鉴权续期失败，请重新登录或刷新源后继续（已保留进度）"); }
    }

    public static final class LayoutChanged extends IOException {
        public LayoutChanged() { super("播放清单的分片布局或密钥已改变，已暂停以保留进度，请换源重下"); }
    }

    public static final class MergeFailure extends IOException {
        public MergeFailure(String reason, Throwable cause) { super(reason, cause); }
    }
    public enum Kind { NETWORK, AUTHENTICATION, STORAGE, SOURCE, MERGE, UNKNOWN }
    public static Kind classify(Throwable th) {
        if (isStorageError(th)) return Kind.STORAGE;
        if (isAuthentication(th) || th instanceof SessionExpired) return Kind.AUTHENTICATION;
        if (isNetworkError(th)) return Kind.NETWORK;
        if (th instanceof MergeFailure) return Kind.MERGE;
        int code = httpCode(th);
        if (code == 404 || code == 410 || isRouteSuspect(th) || th instanceof LayoutChanged
                || reasonOf(th).contains("不是视频") || reasonOf(th).contains("解析失败")) return Kind.SOURCE;
        return Kind.UNKNOWN;
    }

    public static int httpCode(Throwable th) {
        for (Throwable c = th; c != null; c = c.getCause()) {
            if (c instanceof HttpFailure) return ((HttpFailure) c).code;
            String msg = c.getMessage();
            if (msg != null) {
                java.util.regex.Matcher m = HTTP_CODE.matcher(msg);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        }
        return 0;
    }
    private static final java.util.regex.Pattern HTTP_CODE = java.util.regex.Pattern.compile("(?i)HTTP\\s*(\\d{3})");

    public static boolean isAuthentication(Throwable th) { int c = httpCode(th); return c == 401 || c == 403; }

    public static boolean isStorageError(Throwable th) {
        String msg = reasonOf(th);
        return msg.contains("空间不足") || msg.contains("No space left") || msg.contains("ENOSPC")
                || msg.contains("EACCES") || msg.contains("Permission denied");
    }

    /**
     * 本线路是否"整体不可用":连续多片下载失败,或整集缺片全是源侧永久失效(HTTP 404/410)。
     * 调用方(调度器)据此决定换线路而不是继续原地重试。
     */
    public static boolean isRouteSuspect(Throwable th) {
        return isPermanentlyGone(th) || messageContains(th, ROUTE_SUSPECT_TEXT);
    }

    private static boolean messageContains(Throwable th, String needle) {
        String msg = th == null ? null : th.getMessage();
        return msg != null && msg.contains(needle);
    }

    /** 分片在源侧永久失效(HTTP 404/410):带分片序号,便于上层记住"死片"、后续不再重复请求 */
    public static final class SegmentGoneException extends IOException {
        private final int segmentIndex;

        public SegmentGoneException(int segmentIndex, String message) {
            super(message);
            this.segmentIndex = segmentIndex;
        }

        /** 分片序号(-1=未知) */
        public int getSegmentIndex() {
            return segmentIndex;
        }
    }

    /** 是否"单片源侧永久失效"({@link SegmentGoneException}):上层据此跳过该片、不再重复请求 */
    public static boolean isSegmentGone(Throwable th) {
        return th instanceof SegmentGoneException;
    }

    /**
     * 整集失败是否"缺片全是源侧永久失效"类错误(见 {@link #ALL_SEGMENTS_GONE})。
     * 调用方据此跳过重新解析地址与整任务重试 —— 那些动作拿到的还是同一个 404。
     */
    public static boolean isPermanentlyGone(Throwable th) {
        return messageContains(th, ALL_SEGMENTS_GONE);
    }

    /** 是否网络类错误(断网/超时/无法连接/服务端中途断连/SSL):由调度器按网络重试策略处理 */
    public static boolean isNetworkError(Throwable th) {
        Throwable c = th;
        while (c != null) {
            if (c instanceof java.net.SocketTimeoutException
                    || c instanceof java.net.ConnectException
                    || c instanceof java.net.UnknownHostException
                    || c instanceof java.net.SocketException
                    || c instanceof SSLException) {
                return true;
            }
            // okio/服务器中途关闭连接:流被 close 后 read 抛 IOException("closed"),
            // 或 "unexpected end of stream" / "stream closed",本质都是网络层断连,按网络错误处理
            // (若为本方暂停导致的 close, 调用方会用 isTaskStopped/中断检查先拦住)
            if (c instanceof IOException) {
                String msg = c.getMessage();
                if (msg != null && (msg.equals("closed")
                        || msg.contains("unexpected end of stream")
                        || msg.contains("stream closed")
                        || msg.contains("Connection reset"))) {
                    return true;
                }
            }
            c = c.getCause();
        }
        return false;
    }

    /** 异常的可读原因(空则退化到类名),用于拼失败提示 */
    public static String reasonOf(Throwable th) {
        if (th == null) return "未知";
        String msg = th.getMessage();
        return msg == null || msg.isEmpty() ? th.getClass().getSimpleName() : msg;
    }
}

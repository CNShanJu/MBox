package com.github.tvbox.osc.log.internal;

/**
 * 崩溃捕获（internal：仅供 log 模块内部使用，勿被外部模块引用）。
 * <p>
 * 只做三件事：
 * <ul>
 *   <li>注册默认 UncaughtExceptionHandler（保留原 handler 链式转交）；</li>
 *   <li>过滤无害系统异常（魅族系统线程等，与崩溃页过滤器保持一致）；</li>
 *   <li>把崩溃文本交给 {@link Sink}（由 LogStore 负责结构化落库 + flush）。</li>
 * </ul>
 * 安装一次，安装时记录 prev；异常时先过滤，非无害则记录后转交 prev。
 */
public final class CrashReporter {

    /** 崩溃文本接收方（LogStore 实现：结构化日志 + 立即 flush） */
    public interface Sink {
        void recordCrash(String detail, String reason);
    }

    private CrashReporter() {
    }

    /**
     * 安装崩溃捕获（幂等语义由调用方保证：App 启动调一次）。
     *
     * @param sink 记录崩溃的接收方（不应抛异常；异常被吞掉并仍转交 prev）
     */
    public static void install(Sink sink) {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                // 魅族系统内部无害异常(com.meizu.internal.picker 等)不记入业务日志——系统 bug 非 App 问题,
                // 与 App 崩溃页过滤器(吞掉不弹页)保持一致; 其余异常记日志
                if (isBenignSystemThrowable(t, e)) {
                    return;
                }
                String text = buildCrashText(e);
                sink.recordCrash(text, text);
            } catch (Throwable ignored) {
            }
            if (prev != null) {
                prev.uncaughtException(t, e);
            }
        });
    }

    /** 魅族系统内部无害异常(系统线程/内部 picker NPE 等),不应记日志也不应触发崩溃处理 */
    private static boolean isBenignSystemThrowable(Thread thread, Throwable throwable) {
        try {
            if (thread != null && "ContentCapture".equals(thread.getName())) {
                return true;
            }
            Throwable t = throwable;
            while (t != null) {
                for (StackTraceElement el : t.getStackTrace()) {
                    String cls = el.getClassName();
                    if (cls.startsWith("com.meizu.internal.") || cls.startsWith("com.meizu.picker.")) {
                        return true;
                    }
                }
                t = t.getCause();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String buildCrashText(Throwable e) {
        StringBuilder sb = new StringBuilder("未捕获异常: " + e);
        for (StackTraceElement el : e.getStackTrace()) {
            sb.append('\n').append("    at ").append(el);
            if (sb.length() > 4000) break;
        }
        return sb.toString();
    }
}

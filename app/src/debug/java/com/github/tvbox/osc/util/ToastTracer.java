package com.github.tvbox.osc.util;

/**
 * Toast 溯源（仅 debug 构建编译）：toast 触发时打印调用栈到 logcat，定位"不知道哪冒出来的 toast"。
 * 本类只存在于 debug source set——release 编译用 {@link ToastTracer}（空实现）替换，产物零残留。
 */
final class ToastTracer {

    private static final String TAG = "ToastTrace";

    private ToastTracer() {
    }

    /** 打印 toast 消息与触发调用栈（最多 12 帧） */
    static void log(CharSequence msg) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder("[toast] ").append(msg).append("\n触发位置:\n");
        int kept = 0;
        for (StackTraceElement e : st) {
            String cls = e.getClassName();
            if (cls.startsWith("com.github.tvbox.osc.util.AppBubble")) continue; // 跳过本类
            if (cls.startsWith("com.github.tvbox.osc.util.ToastTracer")) continue;
            if (cls.startsWith("java.lang.Thread")) continue;
            sb.append("    at ").append(cls).append('.').append(e.getMethodName())
                    .append('(').append(e.getFileName()).append(':').append(e.getLineNumber()).append(")\n");
            if (++kept >= 12) break;
        }
        android.util.Log.w(TAG, sb.toString());
    }
}

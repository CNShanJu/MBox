package com.github.tvbox.osc.util;

import android.util.Log;

/**
 * 统一日志门面(android.util.Log 包装)。
 * <p>
 * 历史说明:曾向 EventBus 空投 {@code LogEvent}(无任何订阅者),已随 LogEvent 清理移除,
 * 本类保持纯 Logcat 输出。文件级运行日志见 AppLog(与其同驻 core-storage util)。
 *
 * @author pj567
 * @date :2020/12/18
 */
public class LOG {
    private static String TAG = "TVBox";

    public static void e(Throwable t) {
        Log.e(TAG, t.getMessage(), t);
    }

    public static void e(String tag, Throwable t) {
        Log.e(tag, t.getMessage(), t);
    }

    public static void e(String msg) {
        Log.e(TAG, "" + msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
    }
}

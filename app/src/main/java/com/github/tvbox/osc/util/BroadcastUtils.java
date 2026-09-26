package com.github.tvbox.osc.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;

/**
 * 广播注册工具
 * <p>
 * Android 13(API 33)起,动态注册广播必须显式指定 RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED,
 * 否则抛 SecurityException。本工具统一按"应用内部/系统广播"以 RECEIVER_NOT_EXPORTED 注册。
 */
public class BroadcastUtils {

    private BroadcastUtils() {
    }

    /** 以 RECEIVER_NOT_EXPORTED 注册广播(应用内部/系统广播,不接收外部应用广播) */
    public static void registerReceiverNotExported(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    /** 以 RECEIVER_EXPORTED 注册广播(供系统级组件如画中画/通知操作按钮投递,Android 13+ 必须导出才能收到) */
    public static void registerReceiverExported(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }
}

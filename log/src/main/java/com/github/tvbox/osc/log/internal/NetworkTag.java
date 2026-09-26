package com.github.tvbox.osc.log.internal;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * 日志里的"当时网络状态"标注（internal：仅供 log 模块内部使用，勿被外部模块引用）。
 * <p>
 * 为什么需要：断网时网络请求失败会以 {@code UnknownHostException: Unable to resolve host} 的面目出现，
 * 看上去像"域名/服务器坏了"——实测为此排查绕了一大圈（先怀疑 DoH、再怀疑域名被拦，其实是手机没网）。
 * 错误条目与日志文件头部带上"当时的网络状态"，一眼就能区分"当时没网"和"服务端/域名真的有问题"。
 * <p>
 * 依赖方向：:log 不依赖任何业务模块，故这里直接用 framework 的 ConnectivityManager（context 由
 * {@link LogcatCapture#setAppContext} / LogStore.init 注入后传入），不引用 :common 的 SystemStateMonitor。
 */
public final class NetworkTag {

    public static final String NONE = "无网络";
    private static final String UNKNOWN = "未知";

    private NetworkTag() {
    }

    /** 人类可读的网络状态：WIFI / 移动数据 / 其他 / 无网络 / 未知 */
    public static String name(Context context) {
        try {
            if (context == null) return UNKNOWN;
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return UNKNOWN;
            Network network = cm.getActiveNetwork();
            if (network == null) return NONE;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return UNKNOWN;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WIFI";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "移动数据";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "有线";
            return "其他";
        } catch (Throwable th) {
            return UNKNOWN;
        }
    }

    /**
     * 错误条目后缀：<b>只在无网络时</b>返回非空（形如 {@code " [网络=无网络]"}）。
     * 正常网络下不标注 —— 否则每条错误都挂个"网络正常"只是噪音，真正有用的信息是"这条错是在断网时发生的"。
     */
    public static String offlineSuffix(Context context) {
        return NONE.equals(name(context)) ? " [网络=无网络]" : "";
    }
}

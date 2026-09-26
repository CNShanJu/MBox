package com.github.tvbox.osc.download.internal;

import java.io.File;

/**
 * 下载配置门面:所有页面(下载页、设置页、将来新页面)统一通过本类读写下载相关配置,
 * 避免"设置改了但下载页不知情"的分叉。内部委托 {@link DownloadManager} 存取。
 * <p>
 * 单一事实源:仅 WiFi / 并发数 / 保存目录 只存一份,各页面调用同一接口。
 */
public class DownloadConfig {

    private DownloadConfig() {
    }

    /** 是否仅 WiFi 下载(默认开启;移动网络下下载前强提醒确认) */
    public static boolean isWifiOnly() {
        return DownloadManager.get().isWifiOnly();
    }

    public static void setWifiOnly(boolean wifiOnly) {
        DownloadManager.get().setWifiOnly(wifiOnly);
    }

    /** 最大并发下载数(1-5) */
    public static int getMaxConcurrent() {
        return DownloadManager.get().getMaxConcurrent();
    }

    /** 设置最大并发数(1-5),触发重新调度 */
    public static void setMaxConcurrent(int n) {
        DownloadManager.get().setMaxConcurrent(n);
    }

    /** 当前网络是否为移动网络(蜂窝) */
    public static boolean isMobileNetwork() {
        return DownloadManager.isMobileNetwork();
    }

    /** 下载保存根目录 */
    public static File getSaveDir() {
        return DownloadManager.getSaveDir();
    }
}

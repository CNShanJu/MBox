package com.github.tvbox.osc.state;

/**
 * 系统状态快照：页面打开时一次取全量，免轮询。
 */
public class SystemState {

    /** 网络：NONE / WIFI / CELLULAR */
    public String network = "NONE";

    /** 是否前台 */
    public boolean appForeground = true;

    /** 屏幕是否亮 */
    public boolean screenOn = true;

    /** 屏幕方向：PORTRAIT / LANDSCAPE / UNKNOWN */
    public String orientation = "UNKNOWN";

    /** 是否低电量 */
    public boolean batteryLow = false;

    /** 是否充电中 */
    public boolean charging = false;

    /** 电池百分比(0-100;-1 未知) */
    public int batteryPercent = -1;

    /** 可用磁盘字节（根目录粗略值） */
    public long freeDiskBytes = 0;
}

package com.github.tvbox.osc.state;

import com.github.tvbox.osc.log.SubType;

/**
 * 系统模块日志小类型（state 模块自持，归属 大类型=系统）。
 */
public enum SystemSubType implements SubType {
    NETWORK("network", "网络"),
    FOREGROUND("foreground", "前后台"),
    SCREEN("screen", "锁屏"),
    ORIENTATION("orientation", "横竖屏"),
    BATTERY("battery", "电量"),
    DISK("disk", "磁盘"),
    PERMISSION("permission", "权限"),
    TIME("time", "时间"),
    BOOT("boot", "启动");

    private final String code;
    private final String label;

    SystemSubType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String label() {
        return label;
    }
}

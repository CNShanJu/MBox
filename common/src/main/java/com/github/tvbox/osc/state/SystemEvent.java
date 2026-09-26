package com.github.tvbox.osc.state;

/**
 * 系统状态事件：type + value + timestamp。
 */
public class SystemEvent {

    /** 事件类型（见 {@link SystemStateMonitor} 常量） */
    public final String type;

    /** 事件值（如 "WIFI" / "CELLULAR" / "ON" / "PORTRAIT" / 数字字符串） */
    public final String value;

    public final long timestamp;

    public SystemEvent(String type, String value) {
        this.type = type;
        this.value = value;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "SystemEvent{" + type + "=" + value + ", ts=" + timestamp + '}';
    }
}

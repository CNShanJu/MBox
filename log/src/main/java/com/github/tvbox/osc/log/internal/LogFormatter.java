package com.github.tvbox.osc.log.internal;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogEntry;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 日志展示格式化（internal：仅供 log 模块内部使用，勿被外部模块引用）。
 * <ul>
 *   <li>formatEntry：日志页/导出的一行展示格式；</li>
 *   <li>levelName：级别常量 → 名称；</li>
 *   <li>truncate：入库前长度截断；</li>
 *   <li>appVersion：应用版本号（行首展示用，由 LogStore#setAppVersion 委托写入）。</li>
 * </ul>
 */
public final class LogFormatter {

    public static final int MAX_DETAIL = 2048;
    public static final int MAX_REASON = 2048;

    private static final String[] LEVEL_NAMES = {"DEBUG", "INFO", "WARN", "ERROR"};

    /** 应用版本号（App 启动 init 时注入, 日志行首/导出头展示, 排查问题定位版本） */
    private static volatile String appVersion = "";

    private LogFormatter() {
    }

    public static void setAppVersion(String version) {
        appVersion = version == null ? "" : version;
    }

    public static String getAppVersion() {
        return appVersion;
    }

    /** 级别常量 → 名称 */
    public static String levelName(int level) {
        return LEVEL_NAMES[Math.max(0, Math.min(3, level))];
    }

    /** 日志页一行展示：[版本] [时间] [大类型] [小类型] 干了啥 ✓/✗ */
    public static String formatEntry(LogEntry e) {
        StringBuilder sb = new StringBuilder(96);
        if (appVersion != null && !appVersion.isEmpty()) {
            sb.append('[').append(appVersion).append("] ");
        }
        sb.append('[').append(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date(e.timestamp))).append("] ");
        sb.append('[').append(categoryLabel(e.category)).append("] ");
        String sub = e.subTypeLabel != null && !e.subTypeLabel.isEmpty() ? e.subTypeLabel : e.subTypeCode;
        sb.append('[').append(sub).append("] ");
        sb.append(e.detail == null ? "" : e.detail);
        if ("SUCCESS".equals(e.result)) {
            sb.append("  ✓");
        } else if ("FAILURE".equals(e.result)) {
            sb.append("  ✗");
        }
        return sb.toString();
    }

    private static String categoryLabel(String categoryName) {
        if (categoryName == null) return "";
        for (Category c : Category.values()) {
            if (c.name().equals(categoryName)) return c.label();
        }
        return categoryName;
    }

    public static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}

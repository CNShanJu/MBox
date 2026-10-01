package com.github.tvbox.osc.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 拒绝错误偏移的 206，防止认证地址续期后拼接错误内容。 */
public final class DirectResumePolicy {
    private static final Pattern RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)");
    private DirectResumePolicy() {}
    public static boolean matches(String contentRange, long offset) {
        if (contentRange == null) return false;
        Matcher match = RANGE.matcher(contentRange.trim());
        if (!match.matches()) return false;
        try {
            long start = Long.parseLong(match.group(1)), end = Long.parseLong(match.group(2));
            return start == offset && end >= start && (match.group(3).equals("*")
                    || Long.parseLong(match.group(3)) > end);
        } catch (NumberFormatException e) { return false; }
    }
    public static String validator(String etag, String modified) {
        return etag != null && !etag.startsWith("W/") ? etag : modified;
    }
}

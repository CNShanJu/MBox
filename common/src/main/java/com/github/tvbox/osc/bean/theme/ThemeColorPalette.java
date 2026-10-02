package com.github.tvbox.osc.bean.theme;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 不可变的主题颜色与透明度调色板:资源语义名 → ARGB。 */
public class ThemeColorPalette {

    public static final String[] RESOURCE_NAMES = {
            "bg_body", "bg_surface", "bg_card", "bg_float",
            "text_main", "text_sub", "text_hint", "text_main_half", "text_disable",
            "text_accent", "text_highlight", "color_highlight", "select_fill", "press_overlay",
            "btn_confirm_bg", "btn_confirm_text",
            "btn_cancel_bg", "btn_plain_text", "btn_select_bg", "btn_select_text",
            "btn_select_stroke", "btn_stroke",
            "switch_track_on", "switch_track_off", "switch_thumb",
            "download_active", "download_done"
    };

    private static final int FALLBACK = 0xFF1F2937;
    private final Map<String, Integer> values;

    public ThemeColorPalette(Map<String, Integer> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public int get(String resourceName, int fallback) {
        Integer value = values.get(resourceName);
        return value == null ? fallback : value;
    }

    public int get(String resourceName) {
        return get(resourceName, FALLBACK);
    }

    public boolean has(String resourceName) {
        return values.containsKey(resourceName);
    }

    public Map<String, Integer> asMap() {
        return values;
    }

    public String fingerprint() {
        return Integer.toHexString(values.hashCode());
    }

    public static int parseColor(String value, int fallback) {
        if (value == null) return fallback;
        String hex = value.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        try {
            if (hex.length() == 6) return (int) (0xFF000000L | Long.parseLong(hex, 16));
            if (hex.length() == 8) return (int) Long.parseLong(hex, 16);
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public static int parsePercent(String value, int fallback) {
        if (value == null) return fallback;
        try {
            int percent = Integer.parseInt(value.trim());
            return Math.max(0, Math.min(100, percent));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static String toHex(int argb) {
        return ((argb >>> 24) & 0xFF) == 0xFF
                ? String.format(java.util.Locale.ROOT, "#%06X", argb & 0xFFFFFF)
                : String.format(java.util.Locale.ROOT, "#%08X", argb);
    }

    public static int withAlpha(int color, int alphaPercent) {
        int percent = Math.max(0, Math.min(100, alphaPercent));
        float ratio = percent / 100f;
        int alpha = (int) Math.round((double) ratio * 255d);
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    public static int alphaPercentOf(int argb) {
        return Math.round(((argb >>> 24) & 0xFF) / 255f * 100f);
    }
}

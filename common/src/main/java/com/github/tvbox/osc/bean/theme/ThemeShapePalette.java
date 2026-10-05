package com.github.tvbox.osc.bean.theme;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 不可变的主题形状调色板。所有数值单位固定为 dp。 */
public final class ThemeShapePalette {

    public static final String RADIUS_BACKGROUND = "radius_background";
    public static final String RADIUS_DIALOG = "radius_dialog";
    public static final String RADIUS_CARD = "radius_card";
    public static final String RADIUS_BTN = "radius_btn";
    public static final String RADIUS_WIDGET_BTN = "radius_widget_btn";
    public static final String RADIUS_SEARCH = "radius_search";
    public static final String COMMON_CORNERS = "common_corners";
    /** **缩略图专用圆角**(下载页小封面 / 本地视频小图);刻意不进主题编辑器界面 */
    public static final String RADIUS_THUMB = "radius_thumb";
    public static final String STROKE_WIDGET_BTN = "stroke_widget_btn";

    private static final Map<String, Float> DEFAULT_RADII;
    private static final Map<String, Float> DEFAULT_STROKES;

    static {
        LinkedHashMap<String, Float> radii = new LinkedHashMap<>();
        radii.put(RADIUS_BACKGROUND, 26f);
        radii.put(RADIUS_DIALOG, 16f);
        radii.put(RADIUS_CARD, 16f);
        radii.put(RADIUS_BTN, 12f);
        radii.put(RADIUS_WIDGET_BTN, 12f);
        radii.put(RADIUS_SEARCH, 16f);
        radii.put(COMMON_CORNERS, 12f);
        // 仅在内置资产不可用时兜底；主题缺键正常继承 theme_radii.json。
        radii.put(RADIUS_THUMB, 8f);
        DEFAULT_RADII = Collections.unmodifiableMap(radii);

        LinkedHashMap<String, Float> strokes = new LinkedHashMap<>();
        strokes.put(STROKE_WIDGET_BTN, 0.5f);
        DEFAULT_STROKES = Collections.unmodifiableMap(strokes);
    }

    private final Map<String, Float> radiiDp;
    private final Map<String, Float> strokesDp;
    private final String fingerprint;

    public ThemeShapePalette(Map<String, ? extends Number> radii,
                             Map<String, ? extends Number> strokes) {
        LinkedHashMap<String, Float> cleanRadii = new LinkedHashMap<>();
        for (Map.Entry<String, Float> entry : DEFAULT_RADII.entrySet()) {
            cleanRadii.put(entry.getKey(), sanitizeRadius(entry.getKey(), number(radii, entry.getKey()), entry.getValue()));
        }
        LinkedHashMap<String, Float> cleanStrokes = new LinkedHashMap<>();
        for (Map.Entry<String, Float> entry : DEFAULT_STROKES.entrySet()) {
            cleanStrokes.put(entry.getKey(), sanitizeStroke(entry.getKey(), number(strokes, entry.getKey()), entry.getValue()));
        }
        radiiDp = Collections.unmodifiableMap(cleanRadii);
        strokesDp = Collections.unmodifiableMap(cleanStrokes);
        fingerprint = Integer.toHexString(31 * radiiDp.hashCode() + strokesDp.hashCode());
    }

    public static ThemeShapePalette defaults() {
        return new ThemeShapePalette(DEFAULT_RADII, DEFAULT_STROKES);
    }

    public float radiusDp(String key) {
        Float value = radiiDp.get(key);
        return value == null ? 0f : value;
    }

    public float strokeDp(String key) {
        Float value = strokesDp.get(key);
        return value == null ? 0f : value;
    }

    public float radiusPx(String key, float density) {
        return radiusDp(key) * Math.max(0f, density);
    }

    public float strokePx(String key, float density) {
        return strokeDp(key) * Math.max(0f, density);
    }

    public Map<String, Float> radiiDp() {
        return radiiDp;
    }

    public Map<String, Float> strokesDp() {
        return strokesDp;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public static float defaultRadius(String key) {
        Float value = DEFAULT_RADII.get(key);
        return value == null ? 0f : value;
    }

    public static float defaultStroke(String key) {
        Float value = DEFAULT_STROKES.get(key);
        return value == null ? 0f : value;
    }

    public static boolean isRadiusKey(String key) {
        return DEFAULT_RADII.containsKey(key);
    }

    public static boolean isStrokeKey(String key) {
        return DEFAULT_STROKES.containsKey(key);
    }

    public static float maxOf(String key) {
        if (RADIUS_WIDGET_BTN.equals(key)) return 17f;
        if (isRadiusKey(key)) return 64f;
        if (isStrokeKey(key)) return 8f;
        return 0f;
    }

    public static boolean isValid(String key, float value) {
        return Float.isFinite(value) && value >= 0f && value <= maxOf(key)
                && (isRadiusKey(key) || isStrokeKey(key));
    }

    /**
     * 规范一个圆角值:合法就用,超上限就**夹到上限**,不合法(负数/NaN)才回到默认。
     *
     * <p><b>为什么超限要"夹"而不是"回默认"</b>(2026-10-01 静态检查):
     * 构建期 {@code app/build.gradle#readRadii} 对同一个键是把超限值**夹到上限**
     * ({@code radius_widget_btn ≤ 17}),而这里原来回退到**默认值**(12)—— 同一个 chip,
     * 编译期那份轮廓是 17dp、换肤后却是 12dp,两套轮廓。口径统一成"夹到上限"后,
     * 编译期与运行时对同一份主题文件给出同一个值。
     */
    private static float sanitizeRadius(String key, Float value, float fallback) {
        if (value == null) return fallback;
        if (!Float.isFinite(value) || value < 0f) return fallback;
        return Math.min(value, maxOf(key));
    }

    private static float sanitizeStroke(String key, Float value, float fallback) {
        if (value == null) return fallback;
        if (!Float.isFinite(value) || value < 0f) return fallback;
        return Math.min(value, maxOf(key));
    }

    private static Float number(Map<String, ? extends Number> values, String key) {
        if (values == null) return null;
        Number value = values.get(key);
        return value == null ? null : value.floatValue();
    }
}

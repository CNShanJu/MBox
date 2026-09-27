package com.github.tvbox.osc.bean.theme;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一套<b>解析好的</b>主题调色板:资源名 → ARGB(纯数据,不依赖 Android)。
 *
 * <p>"资源名"就是 {@code build.gradle} 的派生表写进 {@code res/values/theme_colors.xml} 的那些名字
 * (见 {@link #RESOURCE_NAMES}),也就是布局里 {@code @color/xxx} 最终指到的东西。
 * 运行时换肤层拿这张表去覆盖视图颜色;设置页拿它做预览/取色。
 *
 * <p>为什么要有"派生":主题文件里只写人看得懂的概念(25 个键),与主色同值的别名不占配置位 ——
 * 比如勾选框填充、无背景按钮文字、选中按钮填充在设计上就等于主色,按钮描边就是主色 @40%。
 * 派生规则与 {@code app/build.gradle} 的 {@code derivePalette} <b>必须一致</b>,
 * 由 JVM 单测(ThemeDerivationParityTest)对比 Gradle 生成的派生结果钉住。
 */
public final class ThemePalette {

    /**
     * 全部资源名(顺序与 {@code build.gradle} 的 {@code derivePalette} 返回顺序一致)。
     * 新增派生名时必须同步改这里 + {@code derivePalette} + {@code ThemeColorAliases}(app 侧别名表),
     * 单测会核对三者的覆盖关系。
     *
     * <p><b>不在这里的名字</b>:{@code text_danger} / {@code swipe_red} / {@code swipe_red_text} ——
     * 危险红与危险文字色已固定成 {@code res/values/colors.xml} 里的字面量,不再随主题走;
     * {@code accent_on_dark} —— 已按用户口径移除(直播页选中态改走 {@code btn_select_bg} / {@code btn_select_text});
     * {@code success} 是**可配置键**,不是资源名(它派生出 {@code switch_track_on} / {@code download_done})。
     *
     * <p>两个"面"的资源名(颜色都取 {@code bg_surface},只差透明度):
     * 页面层 = {@code bg_surface}(卡片/标题栏/底栏/搜索框)与 {@code bg_card}(列表行/筛选块/海报占位),跟 {@code bg_card_alpha};
     * 浮层 = {@code bg_float}(弹窗·抽屉·气泡·悬浮钮),跟 {@code bg_float_alpha}。
     */
    public static final String[] RESOURCE_NAMES = {
            "bg_body",
            "bg_surface",
            "bg_card",
            "bg_float",
            "text_main",
            "text_sub",
            "text_hint",
            "text_disable",
            "text_accent",
            "text_highlight",
            "color_highlight",
            "select_fill",
            "btn_confirm_bg",
            "btn_confirm_text",
            "btn_confirm_stroke",
            "btn_cancel_bg",
            "btn_plain_text",
            "btn_select_bg",
            "btn_select_text",
            "btn_stroke",
            "switch_track_on",
            "switch_track_off",
            "switch_thumb",
            "download_active",
            "download_done"
    };

    /** 缺省色(解析失败时的兜底,与内置亮色主题同值;正常不会走到) */
    private static final int FALLBACK = 0xFF1F2937;

    private final Map<String, Integer> values;

    public ThemePalette(Map<String, Integer> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** 取资源名的 ARGB;没有这个名字时返回 fallback */
    public int get(String resourceName, int fallback) {
        Integer v = values.get(resourceName);
        return v == null ? fallback : v;
    }

    /** 取资源名的 ARGB;没有时返回缺省色 */
    public int get(String resourceName) {
        return get(resourceName, FALLBACK);
    }

    /** 是否含该资源名 */
    public boolean has(String resourceName) {
        return values.containsKey(resourceName);
    }

    /** 只读视图(调试/日志用) */
    public Map<String, Integer> asMap() {
        return values;
    }

    // ------------------------------------------------------------------
    // 值解析(与 build.gradle 的 parseColor/percent/toHex 同口径,纯函数便于单测)
    // ------------------------------------------------------------------

    /**
     * 解析 {@code #RRGGBB} / {@code #AARRGGBB}(也接受不带 # 的写法);非法值返回 fallback。
     * 与 Gradle 侧一致:6 位补全 FF,8 位按 AARRGGBB 原样。
     */
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

    /** 解析透明度百分比(0-100);非法值返回 fallback,越界夹到区间内 */
    public static int parsePercent(String value, int fallback) {
        if (value == null) return fallback;
        try {
            int p = Integer.parseInt(value.trim());
            return Math.max(0, Math.min(100, p));
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 0xAARRGGBB → {@code #RRGGBB}(不透明)或 {@code #AARRGGBB}(带透明度);与 Gradle 的 toHex 一致 */
    public static String toHex(int argb) {
        return ((argb >>> 24) & 0xFF) == 0xFF
                ? String.format(java.util.Locale.ROOT, "#%06X", argb & 0xFFFFFF)
                : String.format(java.util.Locale.ROOT, "#%08X", argb);
    }

    /**
     * 把颜色的不透明度换成 alphaPercent(0-100),保留 RGB;与 Gradle 的 withAlpha 一致。
     *
     * <p><b>必须先把百分比转成 float、再在 double 里乘 255</b>:Gradle(Groovy)对两个 Float 相乘会把它们
     * 提升成 double 再算,而 Java 直接用 {@code p / 100f * 255f} 走 float 乘法,在 x.5 边界上会多进一位 ——
     * 例如 90%:Gradle 得 {@code 0.9f→229.49999…→229},float 乘法先舍入到 {@code 229.5f} 再
     * {@code Math.round} 得 230。两边差 1 个 alpha 字节,症状是"自定义主题比内置主题透一点点",
     * 由 {@code ThemeDerivationParityTest} 逮到(内置暗色的面板透明度取 90 时当场变红)。
     */
    public static int withAlpha(int color, int alphaPercent) {
        int p = Math.max(0, Math.min(100, alphaPercent));
        float ratio = p / 100f;
        int a = (int) Math.round((double) ratio * 255d);
        return (a << 24) | (color & 0xFFFFFF);
    }

    /** 取颜色的不透明度(0-100),四舍五入(取色板编辑已有颜色时用) */
    public static int alphaPercentOf(int argb) {
        return Math.round(((argb >>> 24) & 0xFF) / 255f * 100f);
    }
}

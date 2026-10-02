package com.github.tvbox.osc.theme;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.theme.ThemePalette;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code @color/xxx} 资源 id → 调色板资源名 的对应表(运行时换肤的"字典")。
 *
 * <p>布局/drawable 里写的是 {@code @color/text_foreground} 这类<b>别名</b>,
 * 别名最终指到 {@code theme_colors.xml} 里生成的概念名({@code text_main} 等)。
 * 编译期这份指向关系被烤进了资源表,运行时改不了;所以要让自定义主题生效,
 * 必须知道"这个颜色 id 对应调色板里的哪个概念名",再拿运行时的值覆盖上去。
 *
 * <p>为什么是<b>手写表 + 覆盖校验脚本</b>而不是自动扫:资源 id → 名字的映射要在编译期拿到
 * (运行时反射查名字既慢又不稳),而漏项不会报任何错(只是那一处颜色不跟着主题走)。
 * 所以配套 {@code scripts/check-theme-res-coverage.mjs} 做离线校验:把 res 下所有
 * {@code @color/*} 的引用扫一遍,凡是"最终指向主题概念"的名字都必须在这张表里,
 * 否则脚本报错。改 colors.xml 加别名时会立刻被拦住。
 *
 * <p><b>刻意不在表里</b>的颜色(它们不该随主题变):{@code white}/{@code black}、
 * <b>危险操作三色</b>({@code text_danger} / {@code swipe_red} / {@code swipe_red_text} —— 已固定成
 * {@code res/values/colors.xml} 的字面量)、Material 的 error 角色、海报角标深底与半透明黑遮罩、
 * 播放器 OSD、{@code warn}/{@code orange} 这类语义警示色 —— 详见 res/values/colors.xml 的说明。
 */
public final class ThemeColorAliases {

    private static volatile Map<Integer, String> MAP;

    private ThemeColorAliases() {
    }

    /** 全部"随主题走"的颜色资源 id → 概念名 */
    public static Map<Integer, String> all() {
        Map<Integer, String> m = MAP;
        if (m != null) return m;
        synchronized (ThemeColorAliases.class) {
            if (MAP != null) return MAP;
            Map<Integer, String> map = new HashMap<>();
            // 直接同名的:用 R.color.<name> 逐个登记(编译期校验,写错名字编译不过)
            put(map, R.color.bg_body, "bg_body");
            put(map, R.color.bg_surface, "bg_surface");
            put(map, R.color.bg_card, "bg_card");
            put(map, R.color.bg_float, "bg_float");
            put(map, R.color.text_main, "text_main");
            put(map, R.color.text_sub, "text_sub");
            put(map, R.color.text_hint, "text_hint");
            put(map, R.color.text_main_half, "text_main_half");
            put(map, R.color.text_disable, "text_disable");
            put(map, R.color.text_accent, "text_accent");
            put(map, R.color.text_highlight, "text_highlight");
            put(map, R.color.color_highlight, "color_highlight");
            put(map, R.color.select_fill, "select_fill");
            put(map, R.color.press_overlay, "press_overlay");
            put(map, R.color.btn_confirm_bg, "btn_confirm_bg");
            put(map, R.color.btn_confirm_text, "btn_confirm_text");
            put(map, R.color.btn_cancel_bg, "btn_cancel_bg");
            put(map, R.color.btn_plain_text, "btn_plain_text");
            put(map, R.color.btn_select_bg, "btn_select_bg");
            put(map, R.color.btn_select_text, "btn_select_text");
            put(map, R.color.btn_select_stroke, "btn_select_stroke");
            put(map, R.color.btn_stroke, "btn_stroke");
            put(map, R.color.switch_track_on, "switch_track_on");
            put(map, R.color.switch_track_off, "switch_track_off");
            put(map, R.color.switch_thumb, "switch_thumb");
            put(map, R.color.download_active, "download_active");
            put(map, R.color.download_done, "download_done");
            // 别名
            put(map, R.color.windowBackground, "bg_body");
            put(map, R.color.colorPrimary, "text_accent");
            put(map, R.color.text_foreground, "text_main");
            put(map, R.color.text_sub_foreground, "text_sub");
            put(map, R.color.disable_text, "text_disable");
            put(map, R.color.bg_gray, "bg_card");

            put(map, R.color.gray_darker, "text_sub");
            put(map, R.color.fab_stroke, "btn_stroke");
            put(map, R.color.md_primary, "text_accent");
            put(map, R.color.md_on_primary, "btn_select_text");
            put(map, R.color.md_primary_container, "bg_card");
            put(map, R.color.md_on_primary_container, "text_accent");
            put(map, R.color.md_secondary, "text_sub");
            put(map, R.color.md_on_secondary, "btn_select_text");
            put(map, R.color.md_secondary_container, "bg_card");
            put(map, R.color.md_on_secondary_container, "text_accent");
            put(map, R.color.md_tertiary, "text_sub");
            put(map, R.color.md_on_tertiary, "btn_select_text");
            put(map, R.color.md_tertiary_container, "bg_card");
            put(map, R.color.md_on_tertiary_container, "text_accent");
            put(map, R.color.md_surface, "bg_body");
            put(map, R.color.md_on_surface, "text_main");
            put(map, R.color.md_surface_variant, "bg_card");
            put(map, R.color.md_on_surface_variant, "text_sub");
            MAP = Collections.unmodifiableMap(map);
            return MAP;
        }
    }

    /** 该颜色 id 是否随主题走 */
    public static boolean isThemed(int colorRes) {
        return all().containsKey(colorRes);
    }

    /** 该颜色 id 对应的概念名(不随主题走时返回 null) */
    public static String paletteNameOf(int colorRes) {
        return all().get(colorRes);
    }

    /** 表里的名字必须都能在调色板里取到(启动自检,防手写表里出现调色板没有的概念名) */
    public static boolean namesResolvable(ThemePalette palette) {
        if (palette == null) return false;
        for (String name : all().values()) {
            if (!palette.has(name)) return false;
        }
        return true;
    }

    /**
     * 登记一条"颜色资源 → 概念名"。
     * <p><b>注意</b>:{@code scripts/check-theme-res-coverage.mjs} 会解析本文件里的
     * {@code put(map, R.color.X, "name")} 行来做覆盖校验,所以这里必须保持这种写法(一行一条,
     * 第一个参数是 {@code R.color.名字},第二个是概念名字符串),不要改写成循环/数组。
     */
    private static void put(Map<Integer, String> map, int resId, String name) {
        map.put(resId, name);
    }
}

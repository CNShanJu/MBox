package com.github.tvbox.osc.bean.theme;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主题文件的可配置键 → {@link ThemePalette} 的派生。
 *
 * <p><b>这份规则必须与 {@code app/build.gradle} 的 {@code derivePalette} 完全一致</b>:
 * 内置主题在构建期被 Gradle 派生进 {@code res/values/theme_colors.xml}(首帧就是对的),
 * 自定义主题在运行期由本类派生(覆盖编译期的默认值)。两边一旦漂移,用户就会看到
 * "自定义主题的按钮描边/选中态/弹窗底色跟内置主题不是一个算法算出来的"。
 * 钉住它的是 JVM 单测 {@code ThemeDerivationParityTest}:拿 Gradle 生成的派生结果
 * ({@code assets/theme/theme_derived_*.json})与本类对同一份输入算出的结果逐键比对。
 *
 * <p>规则表(与 Gradle 侧逐行对应):
 * <ul>
 *   <li><b>颜色只有一个,透明度两档</b>:{@code bg_surface}(页面卡片与内容块、标题栏、底栏、搜索框)、
 *       {@code bg_card}(海报占位、筛选块,**以及卡片里面的行**)取 {@code bg_surface} 的颜色 + {@code bg_card_alpha};
 *       {@code bg_float}(弹窗·抽屉·气泡·悬浮钮)取同一个颜色 + {@code bg_float_alpha} ——
 *       颜色相同才不会同屏深浅不一,透明度分两层(**页面层 / 浮层**);</li>
 *   <li>同值别名(不占配置):{@code color_highlight} / {@code select_fill} /
 *       {@code btn_plain_text} / {@code text_accent}
 *       = {@code brand}(文字主色,<b>强制纯色</b>);{@code btn_select_bg}/{@code btn_select_stroke}
 *       = {@code brand};{@code btn_select_text} = 主按钮文字;
 *       {@code text_sub} = {@code text_disable} = {@code brand} <b>@60%</b>(次要 / 禁用两级由主色算出来);
 *       {@code switch_track_on} = {@code download_done} = {@code success}(正向状态色);</li>
 *   <li>{@code btn_stroke} = {@code btn_cancel_bg}(无文字容器与输入框的边框色;历史资源名,值同源);</li>
 *   <li>{@code text_danger} / {@code swipe_red} / {@code swipe_red_text} <b>不在这里</b>:
 *       它们已固定成 {@code res/values/colors.xml} 的字面量,不随主题走;
 *       {@code accent_on_dark} 已移除(直播页选中态走 {@code btn_select_bg} / {@code btn_select_text});</li>
 *   <li>缺键兜底取<b>同类型内置主题</b>的对应值(不是写死的字面量;透明度也取同类型内置主题的
 *       透明度,见 {@link ThemePalette#alphaPercentOf}),这样手写的残缺主题包
 *       只会"少改一个颜色",不会整块变成另一个主题的配色。</li>
 * </ul>
 */
public final class ThemePaletteFactory {

    private ThemePaletteFactory() {
    }

    /**
     * 由主题文件的可配置键派生完整调色板。
     *
     * @param input   主题文件的键值(值形如 {@code #RRGGBB}/{@code #AARRGGBB} 或 {@code "60"});
     *                缺键/非法值按 {@code builtin} 兜底
     * @param builtin <b>同类型</b>内置主题的调色板(兜底来源,也是 @40% 这类派生的取值依据)
     */
    public static ThemePalette derive(Map<String, String> input, ThemePalette builtin) {
        Map<String, String> in = input == null ? new LinkedHashMap<String, String>() : input;
        if (builtin == null) builtin = emptyDefaults();

        // ---- 源头 ----
        // 文字主色**强制纯色**(用户口径:"文字主色不允许设置透明度,只能纯色"):它是正文、空心/纯文字
        // 按钮的文字、勾选框与进度条填充,还是下面次要/禁用两级的计算来源 —— 带透明度整片会发虚。
        int brand = ThemePalette.withAlpha(color(in, "brand", builtin.get("color_highlight")), 100);
        // 次要文字 / 禁用文字不再单独配:两级同一个值 = 文字主色 @60%(用户口径:"这两块的文字颜色
        // 通过计算获得,其值为文字主色透明度 60%")。资源名 text_sub / text_disable 仍然生成,
        // 布局与代码里那 90 多处引用一处都不用改。
        int textSub = ThemePalette.withAlpha(brand, 60);
        // 占位/提示文字(搜索框里的「搜索」这类)同样不再单独配(用户口径:"搜索框里的提示文本颜色
        // 没走文字主色透明度那种"):取文字主色 @40% —— 内置主题下与原来的 #611C1B1F 基本同观感
        int textHint = ThemePalette.withAlpha(brand, 40);
        // 「文字主色 50%」:链接/地址这类要压一档又要与主色同系的文字(订阅管理里的订阅地址用它)。
        // 与 text_sub(60%)分开是因为用户对链接明确要求 50%。
        int textMainHalf = ThemePalette.withAlpha(brand, 50);
        // 按钮两族的口径:
        //   ① 纯色按钮:底与描边同取不透明的 brand;
        //   ② 空心按钮:无填充、只有 1dp 描边,描边与文字同取主色 brand;
        //      btn_cancel_bg 仅供无文字容器与输入框使用;
        //   ③ 小组件按钮:未选中 = 只有主色描边 + 主色文字;
        //      选中 = 主色填充与描边 + 主按钮文字。
        int confirmText = color(in, "btn_confirm_text", builtin.get("btn_confirm_text"));
        int highlightText = color(in, "text_highlight", builtin.get("text_highlight"));
        int containerStroke = color(in, "btn_cancel_bg", builtin.get("btn_stroke"));
        int bodyFallback = builtin.get("bg_body");
        // 面:**颜色只有一个**(bg_surface),页面层与浮层共用;**透明度是两档,分层不同**
        // (用户口径,原来接反过一次):
        //   bg_card_alpha  → **页面层**:bg_surface(页面卡片/标题栏/底栏/搜索框)、
        //                    bg_card(海报占位/筛选块,**以及卡片里面的行** —— 弹窗列表行等);
        //   bg_float_alpha → **浮层**:bg_float(弹窗·抽屉·气泡·首页直播/筛选悬浮钮·更新悬浮圈)。
        // 一句话:页面上的东西(含卡片里的 item)跟 bg_card_alpha;浮起来的那一层跟 bg_float_alpha。
        int surfaceColor = color(in, "bg_surface", builtin.get("bg_surface"));
        // 透明度缺键时取"同类型内置主题"的对应透明度(而不是写死的 92/100):
        // 否则手写的残缺主题包在暗色下会突然变成 92% 的面,与内置暗色完全不是一个观感
        int success = color(in, "success", builtin.get("download_done"));

        int cardBg = ThemePalette.withAlpha(
                surfaceColor,
                percent(in, "bg_card_alpha", ThemePalette.alphaPercentOf(builtin.get("bg_card"))));
        int floatBg = ThemePalette.withAlpha(
                surfaceColor,
                percent(in, "bg_float_alpha", ThemePalette.alphaPercentOf(builtin.get("bg_float"))));

        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("bg_body", color(in, "bg_body", bodyFallback));
        // 页面层:页面卡片/标题栏/底栏/搜索框同一个值
        out.put("bg_surface", cardBg);
        // 页面层的小块:海报占位/筛选块/卡片里的行
        out.put("bg_card", cardBg);
        // 浮层:弹窗·抽屉·气泡·悬浮钮同一个值(颜色同上,只有透明度不同)
        out.put("bg_float", floatBg);
        // (原 card_category_bg / card_category_text 已随主题键一起移除 —— 它们从未接到任何组件)

        // 正文颜色与主色**共用**同一个值(用户口径:"正文颜色和主题主色共用,移除正文颜色的key"):
        // 主题文件里已没有 text_main 这个键,资源名 text_main 仍由 brand 派生 ——
        // 于是"改主色 = 正文文字一起变",按钮/chip/描边与正文天然同色
        out.put("text_main", brand);
        out.put("text_sub", textSub);
        out.put("text_hint", textHint);
        out.put("text_main_half", textMainHalf);
        out.put("text_disable", textSub);
        // 标题与普通选中文字始终随文字主色；高亮文字仍独立可配。
        out.put("text_accent", brand);
        out.put("text_highlight", highlightText);

        out.put("color_highlight", brand);
        out.put("select_fill", brand);
        out.put("press_overlay", ThemePalette.withAlpha(brand, 24));
        out.put("btn_confirm_text", confirmText);
        // 无文字容器与输入框的描边色;空心按钮的描边和文字都走文字主色
        out.put("btn_cancel_bg", containerStroke);
        out.put("btn_plain_text", brand);
        out.put("btn_select_bg", brand);
        out.put("btn_select_text", confirmText);
        out.put("btn_select_stroke", brand);
        // btn_stroke 是无文字描边的历史资源名,与 btn_cancel_bg 同值
        out.put("btn_stroke", containerStroke);

        out.put("switch_track_on", success);
        // 「开关-关」不再单独配(2026-10-01):派生自开关开启色的 **30% 透明**;
        // 注意 withAlpha 这版吃的是**百分比**(0-100),生成侧(Gradle withAlpha)吃的是小数(0.30f)——
        // 两边写错单位会被 ThemeDerivationParityTest 当场拦下。
        out.put("switch_track_off", ThemePalette.withAlpha(success, 30));
        out.put("switch_thumb", color(in, "switch_thumb", builtin.get("switch_thumb")));
        out.put("download_active", color(in, "download_active", builtin.get("download_active")));
        out.put("download_done", success);

        return new ThemePalette(out);
    }

    /** 内置主题调色板缺失时的兜底表(仅极端情况下用到;与 Gradle 的 defaultLightColors 同值口径) */
    private static ThemePalette emptyDefaults() {
        Map<String, Integer> d = new LinkedHashMap<>();
        d.put("bg_body", 0xFFFAF8FF);
        d.put("bg_surface", 0xFFECECF4);
        // 两档透明度的兜底值(颜色同上;与内置亮色主题文件同值):
        //   页面层 60%(bg_surface / bg_card)、浮层 92%(bg_float)
        d.put("bg_card", 0x99ECECF4);
        d.put("bg_float", 0xEBECECF4);
        d.put("text_main", 0xFF050505);
        d.put("text_sub", 0xFF787878);
        d.put("text_hint", 0x611C1B1F);
        d.put("text_disable", 0xFFB3B3B3);
        d.put("text_highlight", 0xFF1890FF);
        d.put("color_highlight", 0xFF1F2937);
        d.put("btn_confirm_text", 0xFFFFFFFF);
        d.put("btn_select_text", 0xFFFFFFFF);
        d.put("download_done", 0xFF08CA2C);
        d.put("switch_thumb", 0xFFFFFFFF);
        d.put("download_active", 0xFF037AFF);
        return new ThemePalette(d);
    }

    private static int color(Map<String, String> in, String key, int fallback) {
        return ThemePalette.parseColor(in.get(key), fallback);
    }

    private static int percent(Map<String, String> in, String key, int fallback) {
        return ThemePalette.parsePercent(in.get(key), fallback);
    }
}

package com.github.tvbox.osc.bean.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 调色板取值/写法换算的单测(纯 JVM)。
 *
 * <p>这些换算是"主题文件 ↔ 界面 ↔ 运行时颜色"的公共口径:
 * 解析错一位就会让用户填的 {@code #RRGGBB} 落到界面上是另一个色,
 * 而 {@code toHex} 写错会让"导出的主题再导入回来"变样。
 */
public class ThemePaletteTest {

    @Test
    public void parseColorAcceptsSixAndEightDigitsWithOrWithoutHash() {
        assertEquals(0xFF112233, ThemePalette.parseColor("#112233", 0));
        assertEquals(0x80112233, ThemePalette.parseColor("#80112233", 0));
        assertEquals(0xFF112233, ThemePalette.parseColor("112233", 0));
        assertEquals(0xFF112233, ThemePalette.parseColor("  #112233 ", 0));
    }

    @Test
    public void parseColorFallsBackOnGarbage() {
        assertEquals(0xFFAABBCC, ThemePalette.parseColor(null, 0xFFAABBCC));
        assertEquals(0xFFAABBCC, ThemePalette.parseColor("", 0xFFAABBCC));
        assertEquals(0xFFAABBCC, ThemePalette.parseColor("#12345", 0xFFAABBCC));
        assertEquals(0xFFAABBCC, ThemePalette.parseColor("#GGGGGG", 0xFFAABBCC));
        assertEquals(0xFFAABBCC, ThemePalette.parseColor("#1122334", 0xFFAABBCC));
    }

    @Test
    public void toHexKeepsAlphaOnlyWhenNeeded() {
        assertEquals("#112233", ThemePalette.toHex(0xFF112233));
        assertEquals("#80112233", ThemePalette.toHex(0x80112233));
        assertEquals("#00112233", ThemePalette.toHex(0x00112233));
    }

    @Test
    public void hexRoundTripIsStable() {
        for (int argb : new int[]{0xFF000000, 0xFFFFFFFF, 0x611C1B1F, 0x99E6E1E5, 0x00000000}) {
            assertEquals(argb, ThemePalette.parseColor(ThemePalette.toHex(argb), 0));
        }
    }

    @Test
    public void withAlphaMatchesTheBuildScriptRule() {
        // 与 build.gradle 的 withAlpha + toHex 同口径:40% → 0x66(102);
        // 不透明时不写多余的 FF(toHex 只在需要时带 AA,与生成出来的 theme_colors.xml 一致)
        assertEquals("#661F2937", ThemePalette.toHex(ThemePalette.withAlpha(0xFF1F2937, 40)));
        assertEquals("#00ECECF4", ThemePalette.toHex(ThemePalette.withAlpha(0xFFECECF4, 0)));
        assertEquals("#ECECF4", ThemePalette.toHex(ThemePalette.withAlpha(0xFFECECF4, 100)));
        // 越界夹到区间内
        assertEquals("#ECECF4", ThemePalette.toHex(ThemePalette.withAlpha(0xFFECECF4, 130)));
        assertEquals("#00ECECF4", ThemePalette.toHex(ThemePalette.withAlpha(0xFFECECF4, -3)));
        // x.5 边界:90% 在 Gradle(double 乘)里是 229(0xE5),float 乘法会先舍成 229.5f 再进一位到 230。
        // 这条就是为它钉的 —— 内置暗色把面板透明度设成 90 时,ThemeDerivationParityTest 曾因此变红。
        assertEquals("#E5ECECF4", ThemePalette.toHex(ThemePalette.withAlpha(0xFFECECF4, 90)));
    }

    @Test
    public void percentParsingIsClamped() {
        assertEquals(60, ThemePalette.parsePercent("60", 0));
        assertEquals(0, ThemePalette.parsePercent("-5", 99));
        assertEquals(100, ThemePalette.parsePercent("100", 0));
        assertEquals(100, ThemePalette.parsePercent("120", 0));
        assertEquals(92, ThemePalette.parsePercent("abc", 92));
        assertEquals(92, ThemePalette.parsePercent(null, 92));
    }

    @Test
    public void alphaPercentOfRoundTrips() {
        assertEquals(100, ThemePalette.alphaPercentOf(0xFF112233));
        assertEquals(0, ThemePalette.alphaPercentOf(0x00112233));
        // 0x99 = 153 ≈ 60%
        assertEquals(60, ThemePalette.alphaPercentOf(0x99112233));
    }

    /** 派生:缺键一律取"同类型内置主题"的值,而不是写死的字面量 */
    @Test
    public void deriveFallsBackToTheGivenBuiltinPalette() {
        ThemePalette builtin = new ThemePalette(new java.util.LinkedHashMap<String, Integer>() {{
            put("bg_body", 0xFF101010);
            put("color_highlight", 0xFFABCDEF);
            put("btn_select_text", 0xFF000000);
        }});
        ThemePalette derived = ThemePaletteFactory.derive(new java.util.LinkedHashMap<String, String>(), builtin);
        assertEquals("缺 bg_body 时应取内置主题的值", 0xFF101010, derived.get("bg_body"));
        assertEquals("缺 brand 时应取内置主题的主色", 0xFFABCDEF, derived.get("color_highlight"));
        assertTrue("主色上的文字应取内置主题的 btn_select_text", derived.has("btn_select_text"));
    }

    /**
     * 颜色一个、透明度两档,而且要接在正确的层上(用户口径,曾经接反过一次):
     * <ul>
     *   <li><b>页面层</b>(bg_surface / bg_card:页面卡片·标题栏·底栏·搜索框·海报占位·卡片里的行)
     *       跟 {@code bg_card_alpha};</li>
     *   <li><b>浮层</b>(bg_float:弹窗·抽屉·气泡·首页直播/筛选悬浮钮)跟 {@code bg_float_alpha};</li>
     *   <li>两层的<b>颜色是同一个</b>(都取 bg_surface),只有透明度不同。</li>
     * </ul>
     */
    @Test
    public void surfaceColorIsSharedButTransparencySplitsByLayer() {
        java.util.Map<String, String> in = new java.util.LinkedHashMap<>();
        in.put("bg_surface", "#223344");
        in.put("bg_card_alpha", "50");
        in.put("bg_float_alpha", "80");
        ThemePalette p = ThemePaletteFactory.derive(in, null);
        // 页面层:颜色同上 + bg_card_alpha
        assertEquals(0x80223344, p.get("bg_surface"));
        assertEquals(0x80223344, p.get("bg_card"));
        // 浮层:颜色同上 + bg_float_alpha(与页面层**不同**才是对的)
        assertEquals(0xCC223344, p.get("bg_float"));
        assertNotEquals("浮层不该跟页面层同透明度(曾经接反过)", p.get("bg_surface"), p.get("bg_float"));
    }

    /** 别名必须与主色同值(勾选框填充/无背景按钮文字/选中按钮填充 = 主色) */
    @Test
    public void brandAliasesShareTheBrandColor() {
        java.util.Map<String, String> in = new java.util.LinkedHashMap<>();
        in.put("brand", "#3366FF");
        in.put("brand_text", "#001100");
        ThemePalette p = ThemePaletteFactory.derive(in, null);
        assertEquals(0xFF3366FF, p.get("color_highlight"));
        assertEquals(0xFF3366FF, p.get("select_fill"));
        assertEquals(0xFF3366FF, p.get("btn_plain_text"));
        assertEquals(0xFF3366FF, p.get("btn_select_bg"));
        assertEquals(0xFF001100, p.get("btn_select_text"));
        assertEquals("#663366FF", ThemePalette.toHex(p.get("btn_stroke")));
        assertFalse("描边不该是不透明的主色", (p.get("btn_stroke") >>> 24) == 0xFF);
    }
}

package com.github.tvbox.osc.bean.theme;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 两档透明度的派生口径(纯 JVM)。
 *
 * <p>为什么单独钉一下:真机自检提示里报的是"面"与"浮层"的最终 ARGB,用户看到两者相同时
 * 需要一句话回答"是派生错了,还是你两档本来就设成一样"。这里把口径写成断言:
 * <b>卡片透明度 → bg_surface / bg_card(页面层);浮层透明度 → bg_float(弹起那一层)</b>,
 * 两者互不影响,且百分比按 round(p/100*255) 换成 alpha 字节。
 *
 * <p>顺带把"两档曾经接反过"这件事钉死(用户口径:面板那档管弹窗/抽屉/气泡,
 * 卡片那档管页面上的卡片/标题栏/底栏/搜索框)。
 */
public class ThemeAlphaScopeTest {

    private static Map<String, String> input(String surface, String cardAlpha, String floatAlpha) {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("bg_surface", surface);
        in.put("bg_card_alpha", cardAlpha);
        in.put("bg_float_alpha", floatAlpha);
        return in;
    }

    @Test
    public void cardAndFloatAlphaAreIndependent() {
        ThemePalette p = ThemePaletteFactory.derive(input("#FF0000", "100", "10"), null);
        assertEquals("卡片那一档 = 100% → 完全不透明", 0xFFFF0000, p.get("bg_surface"));
        assertEquals("卡片那一档同样作用于 bg_card(卡片里的行/海报占位)", 0xFFFF0000, p.get("bg_card"));
        assertEquals("浮层那一档 = 10% → alpha ≈ 26(0x1A)", 0x1AFF0000, p.get("bg_float"));
    }

    @Test
    public void floatAlphaDoesNotLeakIntoSurfaceLayer() {
        // 只改浮层档:页面层必须一动不动(接反过的症状就是这条)
        ThemePalette p = ThemePaletteFactory.derive(input("#FF0000", "100", "60"), null);
        assertEquals(0xFFFF0000, p.get("bg_surface"));
        assertEquals(0xFFFF0000, p.get("bg_card"));
        // 60% → round(0.6 * 255) = 153 = 0x99
        assertEquals(0x99FF0000, p.get("bg_float"));
    }

    @Test
    public void surfaceColorIsSharedWhileAlphaDiffers() {
        // 颜色只有一个(页面层与浮层同色,避免同屏深浅不一),差别只在 alpha
        ThemePalette p = ThemePaletteFactory.derive(input("#123456", "100", "50"), null);
        assertEquals(0xFF123456, p.get("bg_surface"));
        assertEquals(0x80123456, p.get("bg_float")); // round(0.5*255)=128=0x80
    }
}

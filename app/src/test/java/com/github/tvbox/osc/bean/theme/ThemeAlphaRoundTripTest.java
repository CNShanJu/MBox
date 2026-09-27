package com.github.tvbox.osc.bean.theme;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 复现"编辑页里设 卡片透明度=100 / 浮层透明度=10,真机上却看着是半透明"这条链路:
 * 编辑页 → {@link ThemeDef#setColor} → {@link ThemeJson#toJson} → 重新读回 → 派生调色板。
 *
 * <p>用真机反馈反推:卡片与底栏(都是 {@code bg_surface} 的纯色/形状底)表现成"白 + 有透明度",
 * 说明派生出来的 {@code bg_surface}/{@code bg_card} 的 alpha 不是 100 —— 要么两档透明度在某一环被串了,
 * 要么写盘/读回时值丢了。这里把整条链路钉住,任何一环串了都当场红。
 */
public class ThemeAlphaRoundTripTest {

    @Test
    public void editorValuesSurviveJsonRoundTrip() {
        ThemeDef d = new ThemeDef();
        d.setType(ThemeType.BRIGHT);
        d.setName("123");
        d.setId("t1");
        d.setColor("bg_surface", "#FF0000");
        d.setColor("bg_card_alpha", "100");
        d.setColor("bg_float_alpha", "10");

        String json = ThemeJson.toJson(d);
        ThemeJson.Result r = ThemeJson.parse(json);
        assertEquals("主题文件应当能读回", null, r.error);
        ThemeDef back = r.def;
        assertEquals("卡片透明度写盘/读回后应当还是 100", "100", back.color("bg_card_alpha"));
        assertEquals("浮层透明度写盘/读回后应当还是 10", "10", back.color("bg_float_alpha"));
        assertEquals("底色写盘/读回后应当还是红", "#FF0000", back.color("bg_surface").toUpperCase(java.util.Locale.ROOT));

        ThemePalette p = ThemePaletteFactory.derive(back.colors(), null);
        assertEquals("卡片层必须不透明(alpha=FF)", 0xFFFF0000, p.get("bg_surface"));
        assertEquals("卡片层必须不透明(alpha=FF)", 0xFFFF0000, p.get("bg_card"));
        assertEquals("浮层才是 10%(alpha=1A)", 0x1AFF0000, p.get("bg_float"));
    }

    @Test
    public void defaultAlphaKeysAreNotSwappedInSpec() {
        // 两档透明度的键必须各自独立存在,且标签不许写反(写反过一次,用户当场发现)
        ThemeKey card = ThemeSpec.byKey("bg_card_alpha");
        ThemeKey flo = ThemeSpec.byKey("bg_float_alpha");
        assertEquals(true, card != null && card.isAlpha());
        assertEquals(true, flo != null && flo.isAlpha());
        assertEquals("卡片透明度", card.label);
        assertEquals("浮层透明度", flo.label);
    }
}

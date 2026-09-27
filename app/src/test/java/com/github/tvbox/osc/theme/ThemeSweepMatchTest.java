package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.github.tvbox.osc.bean.theme.ThemePalette;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "补色兜底只换还是内置那一档的颜色"这条判据的单测(JVM,不碰 Android)。
 * <p>
 * 这条判据是整类补色的安全阀:
 * <ul>
 *   <li>漏判(该换没换)→ 用户口径的"有的变了、有的没变"(例如这次的搜索框提示文字);</li>
 *   <li>误判(不该换却换了)→ 代码/布局里<b>有意</b>设的颜色(如写死字面量的危险红)被涂成主题色,
 *       而且是在视图树上就地改,用户完全不知道为什么某个颜色不对。</li>
 * </ul>
 * 两头都不好查,所以把"命中内置文字档才返回键、其余一律 null"固定住。
 */
public class ThemeSweepMatchTest {

    private static ThemePalette builtin() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("text_main", 0xFF1C1B1F);
        m.put("text_sub", 0x991C1B1F);
        m.put("text_hint", 0x611C1B1F);
        m.put("text_disable", 0x991C1B1F);
        m.put("text_accent", 0xFF1C1B1F);
        m.put("text_highlight", 0xFF0A59F7);
        m.put("color_highlight", 0xFF1C1B1F);
        m.put("btn_select_text", 0xFFFFFFFF);
        return new ThemePalette(m);
    }

    @Test
    public void matchesEachBuiltinTextTier() {
        ThemePalette b = builtin();
        assertEquals("text_main", ThemeSweep.builtinTextKeyOf(0xFF1C1B1F, b));
        assertEquals("text_hint", ThemeSweep.builtinTextKeyOf(0x611C1B1F, b));
    }

    @Test
    public void alphaIsPartOfTheIdentity() {
        // 关键:同色但不同透明度是不同档(主色 @40% 与主色不透明),不能混为一谈 ——
        // 混了就会把"提示文字"和"正文"当成同一档,换色时把提示文字涂成正文字色
        ThemePalette b = builtin();
        assertNull(ThemeSweep.builtinTextKeyOf(0x661C1B1F, b));
        assertNull(ThemeSweep.builtinTextKeyOf(0x991C1B20, b));
    }

    @Test
    public void leavesForeignColorsAlone() {
        ThemePalette b = builtin();
        // 危险红、纯白、纯黑这类"有意写死的颜色"必须原样放过
        assertNull(ThemeSweep.builtinTextKeyOf(0xFFE53935, b));
        assertNull(ThemeSweep.builtinTextKeyOf(0xFFFFFFFF & 0x00FFFFFF, b));
        assertNull(ThemeSweep.builtinTextKeyOf(0xFF000000, b));
        // 已经是自定义主题色的(假定 #FF2E7D32)也不该再被"命中"
        assertNull(ThemeSweep.builtinTextKeyOf(0xFF2E7D32, b));
    }

    @Test
    public void noBuiltinMeansNoChange() {
        // builtin 取不到时(主题存储异常)必须一律不换色,而不是拿 fallback 乱涂
        assertNull(ThemeSweep.builtinTextKeyOf(0xFF1C1B1F, null));
    }

    @Test
    public void firstMatchWinsDeterministically() {
        // text_main/text_accent/color_highlight 在内置里同值:命中哪一个都得是"同一档同色",
        // 值取出来一样,所以顺序不影响结果 —— 但必须是稳定的(同一输入两次调用结果一致)
        ThemePalette b = builtin();
        String first = ThemeSweep.builtinTextKeyOf(0xFF1C1B1F, b);
        assertEquals(first, ThemeSweep.builtinTextKeyOf(0xFF1C1B1F, b));
    }
}

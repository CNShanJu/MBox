package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 内置亮/暗主题的取色也必须由<b>运行时调色板</b>供色(2026-10-02,魅族 Flyme 强制深色)。
 *
 * <p>背景:编译期颜色资源带 {@code -night} 限定符,系统/OEM 会把进程 {@code uiMode} 翻成夜间。
 * 布局属性({@code ThemeInflaterFactory})与配方 drawable({@code ThemeDrawableFactory})一直走运行时调色板,
 * 所以页面主体不受影响;而<b>代码取色</b>({@code ContextCompat.getColor} / {@code getColorStateList} /
 * {@code getDrawable})以及用 <b>Application 上下文</b>建的窗口(气泡/Toast/通知/应用上下文 inflate 的弹窗)
 * 原来只在自定义主题下被接管 —— 内置主题时落到 {@code values-night} 上,
 * 用户口径就是"只有弹窗/浮层/气泡/通知这类新窗口变深,页面主体还是浅色"。
 *
 * <p>所以这几条通道的门槛必须是"运行时调色板装配好没有",不能是"是不是自定义主题"。
 */
public class ThemeBuiltinRuntimeColorContractTest {

    private static File file(String relative) {
        File direct = new File(relative);
        return direct.isFile() ? direct : new File("../app/" + relative);
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(file(relative).toPath()), StandardCharsets.UTF_8);
    }

    /** 三条取色/取底通道都不得再按"仅自定义主题"放行 */
    @Test
    public void colorChannelsGateOnRuntimePaletteNotCustomTheme() throws Exception {
        String wrapper = read("src/main/java/com/github/tvbox/osc/theme/ThemeContextWrapper.java");
        assertTrue("换肤包装的门槛必须是“运行时调色板已装配”",
                wrapper.contains("return ThemeRuntime.runtimePalette() != null;"));
        assertFalse("不得再用“是否自定义主题”当取色门槛(那会把内置主题放回 -night 资源)",
                wrapper.contains("ThemeRuntime.active()"));

        String resources = read("src/main/java/com/github/tvbox/osc/theme/ThemeResources.java");
        assertTrue("ThemeResources 必须用 runtimePalette()",
                resources.contains("return ThemeRuntime.runtimePalette();"));
        assertFalse("不得再用仅自定义主题的 palette() 取色",
                resources.contains("return ThemeRuntime.palette();"));

        String drawables = read("src/main/java/com/github/tvbox/osc/theme/ThemeDrawables.java");
        assertFalse("取底/取状态色通道不得再用仅自定义主题的 palette()",
                drawables.contains("ThemeRuntime.palette()"));
        assertTrue(drawables.contains("ThemeRuntime.runtimePalette()"));
    }

    /** 应用上下文也要有取色通道(气泡/Toast/通知/应用上下文弹窗),但不能归一明暗位 */
    @Test
    public void applicationContextIsThemedButKeepsSystemNightBit() throws Exception {
        String app = read("src/main/java/com/github/tvbox/osc/base/App.java");
        assertTrue("应用上下文必须包一层取色通道",
                app.contains("ThemeContextWrapper.wrap(base, false)"));
        assertTrue("应用级 Resources 也要包(幂等 + 缓存)",
                app.contains("ThemeContextWrapper.wrapResources(base, false)"));
    }

    /** 明暗位归一必须显式选择:Activity/弹窗一侧 true,应用级 false(否则"跟随系统"自锁) */
    @Test
    public void wrapperNormalizesNightBitOnlyWhenAskedFor() throws Exception {
        String res = read("src/main/java/com/github/tvbox/osc/theme/ThemeResources.java");
        assertTrue(res.contains("public ThemeResources(Resources base, boolean normalizeNight)"));
        assertTrue("默认 true 给 Activity/弹窗一侧",
                res.contains("this(base, true)"));
        assertTrue("归一是把配置里的明暗位换成我们自己的类型",
                res.contains("UI_MODE_NIGHT_MASK"));
    }
}

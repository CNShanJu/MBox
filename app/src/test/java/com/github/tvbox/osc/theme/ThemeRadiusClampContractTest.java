package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.theme.ThemeDef;
import com.github.tvbox.osc.bean.theme.ThemeShapePalette;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 「圆角值口径」绊线(纯 JVM;真机观感由用户人工验证)。
 *
 * <p>钉住 2026-10-01 静态检查发现的两条真 bug:
 * <ol>
 *   <li><b>超上限值"夹"还是"回默认"必须与构建期一致</b>:{@code app/build.gradle#readRadii}
 *       对 {@code radius_widget_btn} 超限值是**夹到 17**,而运行期调色板原来**回退默认 12** ——
 *       同一个 chip,编译期轮廓 17dp、换肤后 12dp,两套轮廓。现在两边都夹到上限。</li>
 *   <li><b>均匀圆角不能被当成直角</b>:{@code ThemeDrawables#radiiOf} 原来在 API 24+ 直接返回
 *       {@code getCornerRadii()},而框架对"四角同值"会退化成标量、该方法返回 null ——
 *       于是旧重建路径把均匀圆角**清成 0**,走旧资源的组件换主题后突然变直角。</li>
 * </ol>
 */
public class ThemeRadiusClampContractTest {

    private static File file(String... candidates) {
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return new File(candidates[0]);
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** 调色板/主题定义对超上限的圆角必须夹到上限,不能回退默认(否则与编译期资源不一致) */
    @Test
    public void outOfRangeRadiusIsClampedNotResetToDefault() {
        float max = ThemeShapePalette.maxOf(ThemeShapePalette.RADIUS_WIDGET_BTN);
        float def = ThemeShapePalette.defaultRadius(ThemeShapePalette.RADIUS_WIDGET_BTN);
        assertTrue("radius_widget_btn 的上限应当小于默认 26 一类的超大值", max > 0f);

        // 调色板构造:喂一个远超上限的值
        java.util.Map<String, Float> radii = new java.util.HashMap<>();
        radii.put(ThemeShapePalette.RADIUS_WIDGET_BTN, 26f);
        ThemeShapePalette palette = new ThemeShapePalette(radii, new java.util.HashMap<>());
        assertEquals("超上限必须夹到上限(与 build.gradle 的 readRadii 同口径),而不是回默认 "
                        + def, max, palette.radiusDp(ThemeShapePalette.RADIUS_WIDGET_BTN), 0.01f);

        // 主题定义写入路径同理
        ThemeDef def2 = new ThemeDef();
        def2.setRadius(ThemeShapePalette.RADIUS_WIDGET_BTN, 26f);
        assertEquals("ThemeDef.setRadius 也要夹到上限", max,
                def2.radius(ThemeShapePalette.RADIUS_WIDGET_BTN), 0.01f);

        // 合法值原样保留
        def2.setRadius(ThemeShapePalette.RADIUS_WIDGET_BTN, 15f);
        assertEquals(15f, def2.radius(ThemeShapePalette.RADIUS_WIDGET_BTN), 0.01f);

        // 负数/NaN 才回默认
        def2.setRadius(ThemeShapePalette.RADIUS_WIDGET_BTN, -3f);
        assertEquals(def, def2.radius(ThemeShapePalette.RADIUS_WIDGET_BTN), 0.01f);
    }

    /** 均匀圆角必须能从标量读回四角,不能被当成"直角" */
    @Test
    public void uniformRadiusIsNotTreatedAsSquare() throws Exception {
        String src = read(file("src/main/java/com/github/tvbox/osc/theme/ThemeDrawables.java",
                "../app/src/main/java/com/github/tvbox/osc/theme/ThemeDrawables.java"));
        int from = src.indexOf("private static float[] radiiOf(");
        assertTrue("找不到 radiiOf,绊线要跟着改", from > 0);
        int to = src.indexOf("public static ColorStateList rebuildColorStateList(");
        assertTrue("radiiOf 之后找不到下一个方法,绊线要跟着改", to > from);
        String body = src.substring(from, to);
        assertTrue("radiiOf 必须在 getCornerRadii() 为 null 时回落到标量 getCornerRadius() —— "
                        + "否则均匀圆角会被当成直角清零:\n" + body,
                body.contains("getCornerRadius()"));
        assertFalse("radiiOf 不许再'直接 return getCornerRadii()'(均匀圆角时它是 null)",
                body.contains("return g.getCornerRadii();"));
    }
}

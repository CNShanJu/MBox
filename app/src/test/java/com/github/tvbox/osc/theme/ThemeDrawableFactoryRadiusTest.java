package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.github.tvbox.osc.bean.theme.ThemeShapePalette;
import com.github.tvbox.osc.bean.theme.ThemeShapeRecipe;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ThemeDrawableFactory} 的圆角算术**在 JVM 里**跑一遍:输入取真实的
 * {@code theme_shapes_runtime.json} 配方与构建期 dimen 表,density 固定为 3。
 *
 * <p>背景:真机日志(tag=MBoxRadius)实锤重建那条路把圆角放大了<b>整数倍</b> ——
 * {@code 换肤重建=TL=48dp/144px TR=48dp BR=16dp BL=16dp}(编译期那份是 16dp);
 * 而原有绊线全是"读 XML 正则",没有一条真正调用过工厂方法,所以整套单测全绿却带着这个 bug。
 * 本类只做一件事:把工厂真正算出来的八个半径值与 dimen 表比。
 */
public class ThemeDrawableFactoryRadiusTest {

    private static File repoRoot() {
        File parent = new File("..");
        return new File(parent, "app").isDirectory() ? parent : new File(".");
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** 构建期真实 dimen 表(dp):生成资源里那份就是编进包里的值 */
    private static Map<String, Float> dimens() throws Exception {
        Map<String, Float> out = new HashMap<>();
        Matcher m = Pattern.compile("<dimen\\s+name=\"([^\"]+)\">\\s*([0-9.]+)dp\\s*</dimen>")
                .matcher(read(new File(repoRoot(), "app/build/generated/theme_colors/values/theme_radii.xml")));
        while (m.find()) out.put(m.group(1), Float.parseFloat(m.group(2)));
        assertNotNull("找不到 radius_dialog(生成资源没跑?)", out.get("radius_dialog"));
        return out;
    }

    /** 用构建期配方的真实结构造一条 recipe(与 {@code ThemeDrawableFactory.parse} 同口径) */
    private static ThemeShapeRecipe recipe(JsonObject json, String id) {
        String shape = json.has("shape") ? json.get("shape").getAsString() : "";
        String radius = json.has("radius") ? json.get("radius").getAsString() : "";
        Map<String, String> cornerTokens = new LinkedHashMap<>();
        Map<String, Float> cornerDp = new LinkedHashMap<>();
        JsonObject corners = json.getAsJsonObject("corners");
        if (corners != null) {
            for (Map.Entry<String, JsonElement> e : corners.entrySet()) {
                if (e.getValue().getAsJsonPrimitive().isString()) {
                    cornerTokens.put(e.getKey(), e.getValue().getAsString());
                } else {
                    String name = e.getKey().endsWith("Dp")
                            ? e.getKey().substring(0, e.getKey().length() - 2) : e.getKey();
                    cornerDp.put(name, e.getValue().getAsFloat());
                }
            }
        }
        return new ThemeShapeRecipe(id, shape, radius, cornerTokens, cornerDp, "", 0f, "",
                new LinkedHashMap<String, ThemeShapeRecipe.PaintState>(), null);
    }

    private static JsonObject runtimeRecipes() throws Exception {
        return JsonParser.parseString(read(new File(repoRoot(),
                "app/build/generated/theme_assets/theme/theme_shapes_runtime.json"))).getAsJsonObject();
    }

    /**
     * 对称配方(唯一一份居中弹窗底 {@code bg_dialog}、chip 底 {@code selector_widget_btn}):
     * 工厂算出来的 8 个半径必须都等于 {@code 圆角档 dp × density},一个都不能再额外缩放。
     *
     * <p>真机实测口径(2026-10-01):编译期那份 XML 由 aapt2 解析 {@code @dimen} 后框架**不再**换算;
     * 而代码 {@code new GradientDrawable() + setCornerRadii} 那份会被框架按本机密度再处理一遍,
     * 直接喂 dp 会比编译期那份小约一个 density(26dp:编译期几何 50px vs 喂 dp 只有 19px)。
     * 所以要乘一次 density —— 但**只能乘一次**(乘两次就会画出"上圆下不圆"那种放大形态)。
     */
    @Test
    public void symmetricRecipesFollowTheDpTimesDensityContract() throws Exception {
        Map<String, Float> dp = dimens();
        dp.put("radius_dialog", 26f);
        dp.put("radius_btn", 26f);
        dp.put("radius_widget_btn", 12f);
        ThemeShapePalette shapes = new ThemeShapePalette(dp, dp);
        JsonObject recipes = runtimeRecipes();

        assertUniform("bg_dialog", "radius_dialog", shapes, recipes, 2.95f);
        assertUniform("selector_widget_btn", "radius_widget_btn", shapes, recipes, 2.95f);
        assertUniform("theme_btn_primary", "radius_btn", shapes, recipes, 2.95f);
        // 同一个配方换一个密度,算出来的值必须严格按比例走(证明确实只乘了一次)
        assertRatio("bg_dialog", shapes, recipes, 1f, 3f);
    }

    /** 唯一允许不规则角的那一份(底部面板):上两角 = radius_dialog,下两角 = 0 */
    @Test
    public void bottomPanelKeepsAuthoredAsymmetryWithoutScaling() throws Exception {
        Map<String, Float> dp = dimens();
        dp.put("radius_widget_btn", 12f);
        ThemeShapePalette shapes = new ThemeShapePalette(dp, dp);
        ThemeShapeRecipe r = recipe(runtimeRecipes().getAsJsonObject("bg_bottom_dialog"), "bg_bottom_dialog");
        float[] radii = ThemeDrawableFactory.cornerRadiiForTest(r, shapes, 2.95f, false);
        float expected = shapes.radiusPx("radius_dialog", 2.95f);
        assertEquals("底部面板左上角", expected, radii[0], 0.01f);
        assertEquals("底部面板右上角", expected, radii[2], 0.01f);
        assertEquals("底部面板右下角必须是 0", 0f, radii[4], 0.01f);
        assertEquals("底部面板左下角必须是 0", 0f, radii[6], 0.01f);
    }

    private static void assertUniform(String id, String radiusKey, ThemeShapePalette shapes,
                                      JsonObject recipes, float density) {
        ThemeShapeRecipe r = recipe(recipes.getAsJsonObject(id), id);
        float[] radii = ThemeDrawableFactory.cornerRadiiForTest(r, shapes, density, false);
        float expected = shapes.radiusPx(radiusKey, density);
        for (int i = 0; i < 8; i++) {
            assertEquals(id + " 的第 " + (i / 2) + " 个角半径不对(密度该乘一次且只乘一次)"
                            + " 期望 " + expected + "(dp×density),实际 " + radii[i],
                    expected, radii[i], 0.01f);
        }
    }

    /** 两个密度下算出的半径必须严格成比例(斜率就是 dp 值)—— 钉死"只乘一次密度" */
    private static void assertRatio(String id, ThemeShapePalette shapes, JsonObject recipes,
                                    float d1, float d2) {
        ThemeShapeRecipe r = recipe(recipes.getAsJsonObject(id), id);
        float a = ThemeDrawableFactory.cornerRadiiForTest(r, shapes, d1, false)[0];
        float b = ThemeDrawableFactory.cornerRadiiForTest(r, shapes, d2, false)[0];
        assertEquals(id + " 在两个密度下的半径不成比例(说明密度被乘了不止一次)",
                a * (d2 / d1), b, 0.01f);
        assertEquals("斜率必须等于圆角档的 dp 值", shapes.radiusDp("radius_dialog"), b / d2, 0.01f);
    }
}

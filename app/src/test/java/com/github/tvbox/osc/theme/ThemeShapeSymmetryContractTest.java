package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「四角圆角」的运行时算术 vs 构建期 XML 的<b>对称性</b>绊线(纯 JVM;真机观感由用户人工验证)。
 *
 * <p>钉住的问题(用户口径:"弹窗的上下圆角不对,上部分圆角比下部分的大得多")。
 * 现状是同一份配方有<b>两条渲染路径</b>,四角算术各写一遍:
 * <ul>
 *   <li>构建期:{@code build.gradle#writeThemeShapes} 生成 {@code <corners>} XML,由框架 native 解析;</li>
 *   <li>运行期:{@link ThemeDrawableFactory#cornerRadii} 生成 {@code float[8]},交给
 *       {@code GradientDrawable.setCornerRadii} —— 数组顺序必须是 TL, TR, BR, BL
 *       (每角一组 x/y),这是 {@code Path.addRoundRect} 的口径,写错就是"上面圆、下面方"。</li>
 * </ul>
 * 原有绊线只断言了 XML 里引用的 dimen 名({@code DialogPanelRadiusContractTest})与令牌存在性
 * ({@code ThemeShapeRecipeParityTest}),<b>没有任何一条比对运行期算术</b> ——
 * 数组索引或角键顺序改错时,全套单测依旧全绿。本类补上这一格。
 *
 * <p>两条断言:
 * <ol>
 *   <li>运行期四角必须与构建期 XML 逐个角一致({@code radius} 标量展开成四角同值,
 *       {@code corners} 按 topLeft/topRight/bottomRight/bottomLeft ↔ TL/TR/BR/BL 配对);</li>
 *   <li>除白名单里"本来就该只圆一半"的三份底(底部面板 / 右侧抽屉 / 播放器控制面板)外,
 *       其余配方的四角必须<b>同值</b> —— 居中弹窗与按钮组件不该出现上下不一致。</li>
 * </ol>
 */
public class ThemeShapeSymmetryContractTest {

    /**
     * 刻意只圆一半的底。这份清单是<b>唯一</b>允许四角不同值的入口,新增必须写明理由。
     */
    private static final String[] ASYMMETRIC = {
            "bg_bottom_dialog",   // 底部面板:只圆上面两角(贴在屏幕底边)
            "bg_drawer",          // 右侧抽屉:只圆左边两角(贴在屏幕右边)
            "bg_playing_control", // 播放器控制面板:底部升起,同底部面板
    };

    private static File repoRoot() {
        File parent = new File("..");
        return new File(parent, "app").isDirectory() ? parent : new File(".");
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** 生成的 drawable XML 里 {@code <corners>} 的四角值(px,顺序 TL, TR, BR, BL);没有 corners 则全 0 */
    private static float[] xmlCorners(File xml, Map<String, Float> dimens) throws Exception {
        String text = read(xml);
        float[] out = new float[4];
        Matcher block = Pattern.compile("<corners([^>]*?)/?>", Pattern.DOTALL).matcher(text);
        if (!block.find()) return out;
        String attrs = block.group(1);
        String[] names = {"topLeftRadius", "topRightRadius", "bottomRightRadius", "bottomLeftRadius"};
        float uniform = -1f;
        Matcher u = Pattern.compile("android:radius=\"([^\"]+)\"").matcher(attrs);
        if (u.find()) uniform = cornerValue(u.group(1), dimens);
        for (int i = 0; i < names.length; i++) {
            Matcher m = Pattern.compile("android:" + names[i] + "=\"([^\"]+)\"").matcher(attrs);
            out[i] = m.find() ? cornerValue(m.group(1), dimens) : (uniform >= 0 ? uniform : 0f);
        }
        return out;
    }

    /** 生成期的圆角有两种写法:{@code @dimen/radius_dialog} 或字面量 {@code 0dp};都换算成 dp */
    private static float cornerValue(String value, Map<String, Float> dimens) {
        String v = value.trim();
        if (v.startsWith("@dimen/")) {
            Float resolved = dimens.get(v.substring("@dimen/".length()));
            assertTrue("生成期引用了不存在的 dimen:" + value, resolved != null);
            return resolved;
        }
        assertTrue("生成期圆角只应写 @dimen/ 或 dp 字面量,实际是:" + value, v.endsWith("dp"));
        return Float.parseFloat(v.substring(0, v.length() - 2));
    }

    /** 生成期 XML 在"面"层面只出现一次:单 state 的 shape 直接是根,多 state 的取第一个 shape */
    private static File panelXml(File generatedDir, String recipeId) {
        return new File(generatedDir, recipeId + ".xml");
    }

    /**
     * 运行期算术:{@link ThemeDrawableFactory#cornerRadii} 对 {@code radius} 走"四角同值",
     * 对 {@code corners} 走 topLeft/topRight/bottomRight/bottomLeft 的 <b>TL, TR, BR, BL</b> 顺序。
     * 这里按同一份配方把四角复算出来(与 {@code ThemeDrawableFactory} 的映射一一对应),单位 dp。
     */
    private static float[] runtimeCorners(JsonObject recipe, Map<String, Float> radii) {
        float[] out = new float[4];
        if (recipe.has("radius") && recipe.get("radius").isJsonPrimitive()) {
            JsonElement r = recipe.get("radius");
            if (!r.getAsJsonPrimitive().isString()) return out;
            float v = radii.get(r.getAsString());
            for (int i = 0; i < 4; i++) out[i] = v;
            return out;
        }
        JsonObject corners = recipe.getAsJsonObject("corners");
        if (corners == null) return out;
        String[] names = {"topLeft", "topRight", "bottomRight", "bottomLeft"};
        for (int i = 0; i < names.length; i++) {
            JsonElement token = corners.get(names[i]);
            if (token != null && token.isJsonPrimitive() && token.getAsJsonPrimitive().isString()) {
                out[i] = radii.get(token.getAsString());
                continue;
            }
            JsonElement literal = corners.get(names[i] + "Dp");
            if (literal != null && literal.isJsonPrimitive()
                    && literal.getAsJsonPrimitive().isNumber()) {
                out[i] = literal.getAsFloat();
            }
        }
        return out;
    }

    /** 生成期圆角 dimen 表(构建期 {@code theme_radii.xml},即真正编进包里的那份值) */
    private static Map<String, Float> dimenTable() throws Exception {
        File xml = new File(repoRoot(), "app/build/generated/theme_colors/values/theme_radii.xml");
        assertTrue("缺少生成的 theme_radii.xml:" + xml, xml.isFile());
        Map<String, Float> out = new java.util.HashMap<>();
        Matcher m = Pattern.compile("<dimen\\s+name=\"([^\"]+)\">\\s*([0-9.]+)dp\\s*</dimen>")
                .matcher(read(xml));
        while (m.find()) out.put(m.group(1), Float.parseFloat(m.group(2)));
        assertTrue("theme_radii.xml 一个圆角档都没解析到", out.size() >= 5);
        return out;
    }

    @Test
    public void runtimeCornerMathMatchesGeneratedXmlForEveryRecipe() throws Exception {
        File root = repoRoot();
        JsonObject recipes = JsonParser.parseString(
                read(new File(root, "app/src/main/assets/theme/theme_shapes.json"))).getAsJsonObject();
        File generatedDir = new File(root, "app/build/generated/theme_shapes/drawable");
        Map<String, Float> radii = dimenTable();

        int checked = 0;
        for (Map.Entry<String, JsonElement> entry : recipes.entrySet()) {
            String id = entry.getKey();
            JsonObject recipe = entry.getValue().getAsJsonObject();
            File xml = panelXml(generatedDir, id);
            assertTrue("缺少构建期 drawable:" + xml, xml.isFile());

            float[] fromXml = xmlCorners(xml, radii);
            float[] fromRuntime = runtimeCorners(recipe, radii);
            for (int i = 0; i < 4; i++) {
                assertEquals("配方 " + id + " 第 " + i + " 角(TL,TR,BR,BL 顺序)运行期与构建期不一致 —— "
                                + "这正是「上面圆、下面方」的形态。fromXml=" + java.util.Arrays.toString(fromXml)
                                + " fromRuntime=" + java.util.Arrays.toString(fromRuntime),
                        fromXml[i], fromRuntime[i], 0.01f);
            }
            checked++;
        }
        assertTrue("一个配方都没扫到,绊线失效了", checked > 0);
    }

    @Test
    public void onlyTheDrawerFamilyMayHaveAsymmetricCorners() throws Exception {
        File root = repoRoot();
        JsonObject recipes = JsonParser.parseString(
                read(new File(root, "app/src/main/assets/theme/theme_shapes.json"))).getAsJsonObject();
        Map<String, Float> radii = dimenTable();

        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : recipes.entrySet()) {
            String id = entry.getKey();
            if (isAsymmetric(id)) continue;
            float[] c = runtimeCorners(entry.getValue().getAsJsonObject(), radii);
            if (c[0] != c[2] || c[0] != c[3] || c[0] != c[1]) {
                bad.add(id + " → TL=" + c[0] + " TR=" + c[1] + " BR=" + c[2] + " BL=" + c[3]);
            }
        }
        if (!bad.isEmpty()) {
            throw new AssertionError("以下配方的四角不一致,但不在「只圆一半」白名单里 —— "
                    + "居中弹窗与按钮组件必须四角同值:\n  - " + String.join("\n  - ", bad)
                    + "\n若确实只有一半该圆,请加进 ThemeShapeSymmetryContractTest.ASYMMETRIC 并写明理由。");
        }
    }

    private static boolean isAsymmetric(String id) {
        for (String a : ASYMMETRIC) {
            if (a.equals(id)) return true;
        }
        return false;
    }
}

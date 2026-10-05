package com.github.tvbox.osc.bean.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 圆角继承必须经过新建、保存、重读和旧文件升级后仍跟随实际内置配置。 */
public class ThemeRadiusInheritanceTest {

    private static final float EPSILON = 0.0001f;

    private static JsonObject asset(String relative) throws Exception {
        File file = new File("src/main/assets/theme/" + relative);
        if (!file.isFile()) file = new File("app/src/main/assets/theme/" + relative);
        if (!file.isFile()) file = new File("../app/src/main/assets/theme/" + relative);
        assertTrue("找不到主题资产: " + file, file.isFile());
        return JsonParser.parseString(new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static ThemeShapePalette builtinShapes() throws Exception {
        JsonObject values = asset("radius/theme_radii.json");
        Map<String, Float> radii = new LinkedHashMap<>();
        Map<String, Float> strokes = new LinkedHashMap<>();
        for (ThemeSpec.ShapeKey key : ThemeSpec.shapeKeys()) {
            assertTrue("圆角资产缺少 " + key.key, values.has(key.key));
            Map<String, Float> target = key.kind == ThemeSpec.ShapeKey.Kind.RADIUS
                    ? radii : strokes;
            target.put(key.key, values.get(key.key).getAsFloat());
        }
        return new ThemeShapePalette(radii, strokes);
    }

    private static ThemeDef blank() throws Exception {
        JsonObject values = asset("themes/bright/default.json");
        Map<String, String> colors = new LinkedHashMap<>();
        for (ThemeKey key : ThemeSpec.all()) {
            if (values.has(key.key)) colors.put(key.key, values.get(key.key).getAsString());
        }
        return ThemeDef.blank(ThemeType.BRIGHT, colors);
    }

    private static ThemeDef parse(String json) {
        ThemeJson.Result result = ThemeJson.parse(json);
        assertNull("主题解析失败: " + result.error, result.error);
        assertNotNull(result.def);
        return result.def;
    }

    private static void assertMatches(ThemeShapePalette expected, ThemeDef actual) {
        for (ThemeSpec.ShapeKey key : ThemeSpec.shapeKeys()) {
            float value = key.kind == ThemeSpec.ShapeKey.Kind.RADIUS
                    ? actual.radius(key.key) : actual.stroke(key.key);
            float inherited = key.kind == ThemeSpec.ShapeKey.Kind.RADIUS
                    ? expected.radiusDp(key.key) : expected.strokeDp(key.key);
            assertEquals("形状项 " + key.key + " 必须继承当前内置配置", inherited, value, EPSILON);
        }
    }

    private static void assertNoOverrides(ThemeDef def) {
        assertTrue("继承圆角不能被固化为主题覆盖值", def.radii().isEmpty());
        assertTrue("继承描边不能被固化为主题覆盖值", def.strokes().isEmpty());
    }

    private static Map<String, Float> oldAutomaticRadii() {
        Map<String, Float> values = new LinkedHashMap<>();
        values.put("radius_background", 26f);
        values.put("radius_dialog", 16f);
        values.put("radius_card", 16f);
        values.put("radius_btn", 12f);
        values.put("radius_widget_btn", 12f);
        values.put("radius_search", 16f);
        values.put("common_corners", 12f);
        values.put("radius_thumb", 8f);
        return values;
    }

    private static String themeJson(int schema, Map<String, Float> radii, Float stroke) {
        JsonObject root = new JsonObject();
        root.addProperty("kind", ThemeDef.KIND);
        root.addProperty("schema", schema);
        root.addProperty("type", "bright");
        JsonObject colors = new JsonObject();
        colors.addProperty("bg_body", "#141414");
        colors.addProperty("brand", "#EEEEEE");
        colors.addProperty("btn_confirm_bg", "#6671E5");
        root.add("colors", colors);
        JsonObject radiusValues = new JsonObject();
        for (Map.Entry<String, Float> entry : radii.entrySet()) {
            radiusValues.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("radii", radiusValues);
        JsonObject strokes = new JsonObject();
        if (stroke != null) strokes.addProperty("stroke_widget_btn", stroke);
        root.add("strokes", strokes);
        return root.toString();
    }

    @Test
    public void newThemeInheritsActualAssetThroughCopyAndJsonRoundTrip() throws Exception {
        ThemeShapePalette builtin = builtinShapes();
        ThemeDef def = blank();
        assertNoOverrides(def);
        def.materializeShapes(builtin);
        assertNoOverrides(def);
        assertMatches(builtin, def);

        ThemeDef copied = def.copy();
        assertNoOverrides(copied);
        assertMatches(builtin, copied);
        JsonObject saved = JsonParser.parseString(ThemeJson.toJson(copied)).getAsJsonObject();
        assertEquals("继承圆角的 JSON 不应保存旧快照", 0,
                saved.getAsJsonObject("radii").size());
        assertEquals("继承描边的 JSON 不应保存旧快照", 0,
                saved.getAsJsonObject("strokes").size());

        ThemeDef reopened = parse(saved.toString());
        reopened.materializeShapes(builtin);
        assertNoOverrides(reopened);
        assertMatches(builtin, reopened);
    }

    @Test
    public void inheritedThemeUsesLaterBuiltinValuesAfterReopening() throws Exception {
        ThemeShapePalette builtin = builtinShapes();
        ThemeDef def = blank();
        def.materializeShapes(builtin);
        ThemeDef reopened = parse(ThemeJson.toJson(def));

        Map<String, Float> radii = new LinkedHashMap<>(builtin.radiiDp());
        radii.put("radius_dialog", 7.25f);
        ThemeShapePalette updated = new ThemeShapePalette(radii,
                Collections.singletonMap("stroke_widget_btn", 0.25f));
        reopened.materializeShapes(updated);
        assertNoOverrides(reopened);
        assertMatches(updated, reopened);
    }

    @Test
    public void explicitSingleOverridesSurviveCopySaveAndBuiltinChanges() throws Exception {
        ThemeShapePalette builtin = builtinShapes();
        ThemeDef def = blank();
        def.materializeShapes(builtin);
        def.setRadius("radius_dialog", 7f);
        def.setStroke("stroke_widget_btn", 1.25f);
        ThemeDef copied = def.copy();
        assertEquals(7f, copied.radius("radius_dialog"), EPSILON);
        assertEquals(1.25f, copied.stroke("stroke_widget_btn"), EPSILON);
        assertEquals(builtin.radiusDp("radius_btn"), copied.radius("radius_btn"), EPSILON);

        ThemeDef reopened = parse(ThemeJson.toJson(copied));
        Map<String, Float> changed = new LinkedHashMap<>(builtin.radiiDp());
        changed.put("radius_dialog", 19f);
        changed.put("radius_btn", 9f);
        reopened.materializeShapes(new ThemeShapePalette(changed,
                Collections.singletonMap("stroke_widget_btn", 0.25f)));
        assertEquals("明确设置的圆角不随内置值变化", 7f,
                reopened.radius("radius_dialog"), EPSILON);
        assertEquals("明确设置的描边不随内置值变化", 1.25f,
                reopened.stroke("stroke_widget_btn"), EPSILON);
        assertEquals("其他圆角继续继承更新值", 9f,
                reopened.radius("radius_btn"), EPSILON);
        assertEquals(1, reopened.radii().size());
        assertEquals(1, reopened.strokes().size());
    }

    @Test
    public void completeOldAutomaticSnapshotMigratesToInheritanceWithoutLosingStroke() throws Exception {
        ThemeShapePalette builtin = builtinShapes();
        for (int schema = 1; schema <= 5; schema++) {
            ThemeDef def = parse(themeJson(schema, oldAutomaticRadii(), 1.25f));
            assertTrue("schema " + schema + " 的旧自动圆角应移除覆盖", def.radii().isEmpty());
            def.materializeShapes(builtin);
            for (Map.Entry<String, Float> entry : builtin.radiiDp().entrySet()) {
                assertEquals("schema " + schema + " 的 " + entry.getKey(), entry.getValue(),
                        def.radius(entry.getKey()), EPSILON);
            }
            assertEquals("迁移圆角不能清掉用户的描边设置", 1.25f,
                    def.stroke("stroke_widget_btn"), EPSILON);
            ThemeDef reopened = parse(ThemeJson.toJson(def));
            reopened.materializeShapes(builtin);
            assertTrue(reopened.radii().isEmpty());
            assertEquals(1.25f, reopened.stroke("stroke_widget_btn"), EPSILON);
        }
    }

    @Test
    public void partialLegacyOverridesArePreserved() throws Exception {
        ThemeDef def = parse(themeJson(5,
                Collections.singletonMap("radius_dialog", 16f), null));
        ThemeShapePalette builtin = builtinShapes();
        def.materializeShapes(builtin);
        assertEquals("单项恰好等于旧默认仍属于显式覆盖", 16f,
                def.radius("radius_dialog"), EPSILON);
        assertEquals(1, def.radii().size());
        assertEquals(builtin.radiusDp("radius_card"), def.radius("radius_card"), EPSILON);
    }

    @Test
    public void legacySnapshotWithOneChangedRadiusIsPreservedAsExplicit() throws Exception {
        Map<String, Float> values = oldAutomaticRadii();
        values.put("radius_card", 19f);
        ThemeDef def = parse(themeJson(5, values, null));
        def.materializeShapes(builtinShapes());
        assertEquals("有任何改动的完整圆角配置均应保留", values, def.radii());
        for (Map.Entry<String, Float> entry : values.entrySet()) {
            assertEquals(entry.getValue(), def.radius(entry.getKey()), EPSILON);
        }
    }

    @Test
    public void schemaSixCanExplicitlyChooseTheEntireOldRadiusSet() throws Exception {
        Map<String, Float> values = oldAutomaticRadii();
        ThemeDef def = parse(themeJson(6, values, null));
        def.materializeShapes(builtinShapes());
        assertEquals("新格式中的完整旧值也是明确配置，不再迁移", values, def.radii());
        ThemeDef reopened = parse(ThemeJson.toJson(def));
        reopened.materializeShapes(builtinShapes());
        assertEquals(values, reopened.radii());
    }

    @Test
    public void invalidSetterRemovesOverrideAndRestoresCurrentInheritedValue() throws Exception {
        ThemeShapePalette builtin = builtinShapes();
        ThemeDef def = blank();
        def.materializeShapes(builtin);
        def.setRadius("radius_dialog", 3f);
        def.setStroke("stroke_widget_btn", 2f);
        def.setRadius("radius_dialog", -1f);
        def.setStroke("stroke_widget_btn", Float.NaN);
        assertNoOverrides(def);
        assertMatches(builtin, def);

        def.setRadius("radius_btn", 4f);
        def.setRadius("radius_btn", Float.POSITIVE_INFINITY);
        assertNoOverrides(def);
        assertEquals("无效值恢复当前内置配置，不能回旧硬编码默认", builtin.radiusDp("radius_btn"),
                def.radius("radius_btn"), EPSILON);
    }
}

package com.github.tvbox.osc.bean.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主题 JSON 编解码的单测:这层是"自己存的 / 导出给别人 / 别人导进来"的公共格式,
 * 一旦把字段写错或解析太严,症状是"导出的主题自己再导入回来就变样了"。
 */
public class ThemeJsonTest {

    private static File repoFile(String relative) {
        File f = new File(relative);
        return f.exists() ? f : new File("../" + relative);
    }

    private static Map<String, String> builtinInput(String assetName) throws Exception {
        File f = repoFile("src/main/assets/theme/" + assetName);
        JsonObject o = JsonParser.parseString(
                new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : o.keySet()) {
            if ("type".equals(key) || "desc".equals(key)) continue;
            out.put(key, o.get(key).getAsString());
        }
        return out;
    }

    private static ThemeDef sample() throws Exception {
        ThemeDef def = ThemeDef.blank(ThemeType.DARK, builtinInput("theme_colors_night.json"));
        def.setId("t_abc");
        def.setName("暗夜紫");
        def.setColor("bg_body", "#101018");
        def.setColor("brand", "#7C4DFF");
        def.getBackground().setMode(ThemeDef.Background.MODE_IMAGE);
        def.getBackground().setRef("theme_bg/0123456789abcdef0123456789abcdef.webp");
        return def;
    }

    @Test
    public void roundTripKeepsEveryField() throws Exception {
        ThemeDef src = sample();
        ThemeJson.Result r = ThemeJson.parse(ThemeJson.toJson(src));
        assertNull("自己写出来的主题必须能自己读回来: " + r.error, r.error);
        assertNotNull(r.def);
        ThemeDef back = r.def;

        assertEquals(src.getId(), back.getId());
        assertEquals(src.getName(), back.getName());
        assertEquals(src.getType(), back.getType());
        assertEquals(src.backgroundRef(), back.backgroundRef());
        for (ThemeKey k : ThemeSpec.all()) {
            assertEquals("键 " + k.key + " 往返后变了", src.color(k.key), back.color(k.key));
        }
        for (ThemeSpec.ShapeKey key : ThemeSpec.shapeKeys()) {
            float expected = key.kind == ThemeSpec.ShapeKey.Kind.RADIUS
                    ? src.radius(key.key) : src.stroke(key.key);
            float actual = key.kind == ThemeSpec.ShapeKey.Kind.RADIUS
                    ? back.radius(key.key) : back.stroke(key.key);
            assertEquals("形状 " + key.key + " 往返后变了", expected, actual, 0.0001f);
        }
        assertTrue("警告不该出现在自产主题上: " + r.warnings, r.warnings.isEmpty());
    }

    @Test
    public void outputUsesTypeWordsMatchingBuiltinFiles() throws Exception {
        JsonObject o = JsonParser.parseString(ThemeJson.toJson(sample())).getAsJsonObject();
        assertEquals(ThemeDef.KIND, o.get("kind").getAsString());
        assertEquals(ThemeDef.SCHEMA, o.get("schema").getAsInt());
        assertEquals("type 必须与内置主题文件同词(bright/dark),否则两边不能互用",
                "dark", o.get("type").getAsString());
        JsonObject colors = o.getAsJsonObject("colors");
        assertTrue("颜色必须收在 schema 3 的 colors 对象里",
                colors.has("bg_body") && colors.has("success"));
        assertTrue("透明度写数字,便于手改",
                colors.get("bg_float_alpha").getAsJsonPrimitive().isNumber());
        assertTrue("圆角与描边必须分组输出", o.has("radii") && o.has("strokes"));
        assertTrue("dp 数值不带单位后缀",
                o.getAsJsonObject("radii").get("radius_dialog").getAsJsonPrimitive().isNumber());
    }

    @Test
    public void builtinThemeFileCanBeImportedAsIs() throws Exception {
        // 用户拿内置主题文件当模板改,是最可能的使用方式:desc 是"给内置文件看的说明",
        // 导入时应静默忽略(不该弹一堆"不认识的项"),其余照常读进来
        String raw = new String(Files.readAllBytes(
                repoFile("src/main/assets/theme/theme_colors.json").toPath()), StandardCharsets.UTF_8);
        ThemeJson.Result r = ThemeJson.parse(raw);
        assertNull("内置主题文件必须能被导入: " + r.error, r.error);
        assertEquals(ThemeType.BRIGHT, r.def.getType());
        assertTrue("缺 kind/id/name 不算错(导入时补)", r.def.getId().isEmpty());
        assertTrue("desc 应静默忽略,不该报警告: " + r.warnings, r.warnings.isEmpty());
        assertEquals("内置主题文件里的每个键都要被读进来",
                "#faf8ff".toUpperCase(), r.def.color("bg_body").toUpperCase());
    }

    @Test
    public void rejectsForeignJson() {
        assertNotNull("不是 MBox 主题的文件必须拒绝",
                ThemeJson.parse("{\"kind\":\"other\",\"bg_body\":\"#000000\"}").error);
        assertNotNull("没有可识别颜色项的要拒绝", ThemeJson.parse("{\"kind\":\"mbox-theme\"}").error);
        assertNotNull("非 JSON 要拒绝", ThemeJson.parse("not json at all").error);
        assertNotNull("空内容要拒绝", ThemeJson.parse("   ").error);
        assertNotNull("顶层数组要拒绝", ThemeJson.parse("[1,2,3]").error);
    }

    @Test
    public void rejectsFutureSchema() {
        ThemeJson.Result r = ThemeJson.parse("{\"kind\":\"mbox-theme\",\"schema\":99,\"bg_body\":\"#000000\"}");
        assertNotNull("更高版本要拒绝(而不是装作能读)", r.error);
        assertTrue(r.error.contains("版本"));
    }

    @Test
    public void tolerantAboutUnknownAndMissingKeys() throws Exception {
        // 缺键:不算错,交给 materialize 用同类型内置主题补齐
        ThemeJson.Result r = ThemeJson.parse("{\"kind\":\"mbox-theme\",\"type\":\"dark\",\"bg_body\":\"#123456\"}");
        assertNull(r.error);
        assertEquals("#123456", r.def.color("bg_body"));
        assertEquals("", r.def.color("brand"));
        assertTrue("补齐后必须是完整主题",
                r.def.materialize(builtinInput("theme_colors_night.json")).size() == ThemeSpec.size() - 1);

        // 不认识的键:只警告
        ThemeJson.Result r2 = ThemeJson.parse(
                "{\"kind\":\"mbox-theme\",\"bg_body\":\"#123456\",\"my_color\":\"#FFFFFF\"}");
        assertNull(r2.error);
        assertTrue("不认识的键要给出警告: " + r2.warnings, r2.warnings.size() == 1);
        assertTrue(r2.warnings.get(0).contains("my_color"));
    }

    @Test
    public void imageBackgroundWithoutRefFallsBackToDefault() {
        ThemeJson.Result r = ThemeJson.parse("{\"kind\":\"mbox-theme\",\"bg_body\":\"#123456\","
                + "\"background\":{\"mode\":\"image\"}}");
        assertNull(r.error);
        assertFalse("声明了图片却没有引用:退回跟随默认,不能留空引用让页面加载空路径",
                r.def.getBackground().isImage());
        assertEquals(ThemeDef.Background.MODE_DEFAULT, r.def.getBackground().getMode());
        assertTrue(r.warnings.toString(), r.warnings.size() == 1);
    }

    @Test
    public void solidAndDefaultBackgroundsSurviveRoundTrip() throws Exception {
        ThemeDef def = sample();
        def.getBackground().setMode(ThemeDef.Background.MODE_SOLID);
        def.getBackground().setRef("");
        ThemeDef back = ThemeJson.parse(ThemeJson.toJson(def)).def;
        assertEquals(ThemeDef.Background.MODE_SOLID, back.getBackground().getMode());
        assertFalse(back.hasBackgroundImage());

        def.getBackground().setMode(ThemeDef.Background.MODE_DEFAULT);
        back = ThemeJson.parse(ThemeJson.toJson(def)).def;
        assertEquals(ThemeDef.Background.MODE_DEFAULT, back.getBackground().getMode());
    }

    /** 背景图的摆放(缩放/位置/不透明度/遮罩)跟着主题走:必须往返不丢 */
    @Test
    public void backgroundPlacementSurvivesRoundTrip() throws Exception {
        ThemeDef def = sample();
        def.getBackground().setMode(ThemeDef.Background.MODE_IMAGE);
        def.getBackground().setRef("theme_bg/abcdef.webp");
        def.getBackground().setZoom(1.75f);
        def.getBackground().setAnchorX(0.25f);
        def.getBackground().setAnchorY(0.8f);
        def.getBackground().setAlpha(70);
        def.getBackground().setScrim(false);

        ThemeDef back = ThemeJson.parse(ThemeJson.toJson(def)).def;
        assertEquals(1.75f, back.getBackground().getZoom(), 0.0001f);
        assertEquals(0.25f, back.getBackground().getAnchorX(), 0.0001f);
        assertEquals(0.80f, back.getBackground().getAnchorY(), 0.0001f);
        assertEquals(70, back.getBackground().getAlpha());
        assertFalse("遮罩关掉的状态必须留住", back.getBackground().isScrim());
    }

    /** 老主题包(或纯色/跟随默认)没有摆放字段:按"自动/居中/原图/开遮罩"兜底,不能变成 0/黑 */
    @Test
    public void backgroundPlacementDefaultsWhenAbsent() {
        ThemeJson.Result r = ThemeJson.parse("{\"kind\":\"mbox-theme\",\"bg_body\":\"#123456\","
                + "\"background\":{\"mode\":\"image\",\"ref\":\"theme_bg/x.webp\"}}");
        assertNull(r.error);
        ThemeDef.Background b = r.def.getBackground();
        assertEquals(0f, b.getZoom(), 0.0001f);
        assertEquals(ThemeDef.Background.DEFAULT_ANCHOR, b.getAnchorX(), 0.0001f);
        assertEquals(ThemeDef.Background.DEFAULT_ANCHOR, b.getAnchorY(), 0.0001f);
        assertEquals(ThemeDef.Background.DEFAULT_ALPHA, b.getAlpha());
        assertTrue(b.isScrim());
    }

    @Test
    public void alphaValuesSurviveAsNumbersAndStrings() {
        // 手工改过的文件可能把透明度写成字符串,得认
        ThemeJson.Result r = ThemeJson.parse("{\"kind\":\"mbox-theme\",\"bg_body\":\"#123456\","
                + "\"bg_float_alpha\":\"70\"}");
        assertNull(r.error);
        assertEquals("70", r.def.color("bg_float_alpha"));
        assertEquals(70, ThemePalette.parsePercent(r.def.color("bg_float_alpha"), 0));
    }

    /**
     * 旧键名的老主题包(v1)必须被自动搬到新键名上,而不是"值被丢掉、再被内置主题补齐"。
     *
     * <p>踩过的坑(用户原话):"为啥自定义的主题颜色,值会变?变成和我当前使用的默认主题数据一致" ——
     * 主题文件按键名存值,键名一改,老文件里的值就成了"不认识的项",materialize 再从内置主题取值,
     * 于是自定义主题被悄悄换成了内置主题的配色。
     */
    @Test
    public void migratesLegacyKeyNamesFromOlderSchema() {
        String legacy = "{\"kind\":\"mbox-theme\",\"schema\":1,\"id\":\"t1\",\"name\":\"旧主题\",\"type\":\"dark\","
                + "\"bg_body\":\"#101010\",\"text_main\":\"#FFFFFF\","
                + "\"bg_float\":\"#112233\",\"bg_float_alpha\":\"80\","
                + "\"bg_component\":\"#445566\",\"bg_component_alpha\":\"30\","
                + "\"switch_track_on\":\"#00FF00\","
                + "\"text_highlight\":\"#AAAAAA\",\"text_accent\":\"#0000FF\","
                + "\"accent_on_dark\":\"#1890FF\",\"swipe_red\":\"#E5484D\",\"swipe_red_text\":\"#FFFFFF\","
                + "\"text_danger\":\"#E5484D\"}";
        ThemeJson.Result r = ThemeJson.parse(legacy);
        assertNull(r.error);
        ThemeDef d = r.def;
        // 值必须原样搬过去(而不是变成内置主题的值)
        assertEquals("#112233", d.color("bg_surface"));
        assertEquals("80", d.color("bg_float_alpha"));
        assertEquals("30", d.color("bg_card_alpha"));
        assertEquals("#00FF00", d.color("success"));
        // 老的两个键语义与新版本正好对调:老 highlight(强调) → 新 accent,老 accent(高亮) → 新 highlight
        assertEquals("#AAAAAA", d.color("text_accent"));
        assertEquals("#0000FF", d.color("text_highlight"));
        // 已废弃的键:忽略并给出提示,不写进 def
        assertEquals("", d.color("bg_component"));
        assertTrue("废弃项要说清楚: " + r.warnings, r.warnings.toString().contains("bg_component"));
        assertTrue("旧文件升级要给一条提示: " + r.warnings, r.warnings.toString().contains("旧版本"));
    }

    /** download_done 也是老键名之一(并与 switch_track_on 合并成 success) */
    @Test
    public void migratesLegacyDownloadDoneToSuccess() {
        ThemeJson.Result r = ThemeJson.parse("{\"kind\":\"mbox-theme\",\"schema\":1,"
                + "\"bg_body\":\"#101010\",\"download_done\":\"#00EE00\"}");
        assertNull(r.error);
        assertEquals("#00EE00", r.def.color("success"));
    }

    /** 新文件(没有老键名)不能被当成老文件:那两个名字要按新语义原样读 */
    @Test
    public void currentSchemaKeysAreNotSwapped() {
        ThemeJson.Result r = ThemeJson.parse("{\"kind\":\"mbox-theme\",\"schema\":2,"
                + "\"bg_body\":\"#101010\","
                + "\"text_highlight\":\"#0000FF\",\"text_accent\":\"#AAAAAA\"}");
        assertNull(r.error);
        assertEquals("新语义:highlight=高亮(蓝)", "#0000FF", r.def.color("text_highlight"));
        assertEquals("新语义:accent=强调", "#AAAAAA", r.def.color("text_accent"));
        assertTrue("新文件不该有升级提示: " + r.warnings, r.warnings.isEmpty());
    }

    @Test
    public void schemaThreeValidatesShapesAndMigratesOldFiles() {
        ThemeJson.Result old = ThemeJson.parse("{\"kind\":\"mbox-theme\",\"schema\":2,"
                + "\"bg_body\":\"#101010\"}");
        assertNull(old.error);
        java.util.Map<String, Float> inheritedRadii = new java.util.LinkedHashMap<>();
        inheritedRadii.put(ThemeShapePalette.RADIUS_WIDGET_BTN, 16f);
        old.def.materializeShapes(new ThemeShapePalette(inheritedRadii,
                java.util.Collections.<String, Float>emptyMap()));
        assertEquals("schema 1/2 必须继承调用方给的同类型内置值", 16f,
                old.def.radius("radius_widget_btn"), 0.0001f);

        ThemeJson.Result current = ThemeJson.parse("{\"kind\":\"mbox-theme\",\"schema\":3,"
                + "\"colors\":{\"bg_body\":\"#101010\"},"
                + "\"radii\":{\"radius_widget_btn\":17,\"radius_dialog\":-1,\"future\":3},"
                + "\"strokes\":{\"stroke_widget_btn\":\"bad\"}}");
        assertNull(current.error);
        assertEquals(17f, current.def.radius("radius_widget_btn"), 0.0001f);
        assertEquals(16f, current.def.radius("radius_dialog"), 0.0001f);
        assertEquals(0.5f, current.def.stroke("stroke_widget_btn"), 0.0001f);
        assertTrue(current.warnings.toString(), current.warnings.toString().contains("future"));
        assertTrue(current.warnings.toString(), current.warnings.toString().contains("回退内置值"));
    }

    /**
     * 中间态键名(本仓开发中短暂用过、也按名字存过盘)要无条件按新名读,而且**不能**因此被当成老文件
     * (用中间态写的文件已经是新语义,误判会让 text_highlight/text_accent 被反过来换错)。
     */
    @Test
    public void migratesIntermediateKeyNameWithoutTriggeringSwap() {
        ThemeJson.Result r = ThemeJson.parse("{\"kind\":\"mbox-theme\",\"schema\":2,"
                + "\"bg_body\":\"#101010\",\"bg_panel_alpha\":\"66\","
                + "\"text_highlight\":\"#0000FF\",\"text_accent\":\"#AAAAAA\"}");
        assertNull(r.error);
        assertEquals("中间态 bg_panel_alpha 要落到 bg_float_alpha", "66", r.def.color("bg_float_alpha"));
        assertEquals("#0000FF", r.def.color("text_highlight"));
        assertEquals("#AAAAAA", r.def.color("text_accent"));
    }
}

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
import java.util.Map;

/** 构建期 XML 与运行时配方必须来自同一份 theme_shapes.json。 */
public class ThemeShapeRecipeParityTest {

    private static File root() {
        File parent = new File("..");
        return new File(parent, "app").isDirectory() ? parent : new File(".");
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static JsonObject object(File file) throws Exception {
        return JsonParser.parseString(read(file)).getAsJsonObject();
    }

    @Test
    public void generatedRuntimeTableEqualsAuthoredRecipes() throws Exception {
        File root = root();
        JsonObject source = object(new File(root, "app/src/main/assets/theme/radius/theme_shapes.json"));
        JsonObject runtime = object(new File(root,
                "app/build/generated/theme_assets/theme/radius/theme_shapes_runtime.json"));
        assertEquals("运行时配方与唯一源漂移", source, runtime);
    }

    @Test
    public void everyRecipeHasGeneratedXmlWithMatchingTokens() throws Exception {
        File root = root();
        JsonObject source = object(new File(root, "app/src/main/assets/theme/radius/theme_shapes.json"));
        File generated = new File(root, "app/build/generated/theme_shapes/drawable");
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            File xmlFile = new File(generated, entry.getKey() + ".xml");
            assertTrue("缺少构建期 drawable:" + entry.getKey(), xmlFile.isFile());
            String xml = read(xmlFile);
            JsonObject recipe = entry.getValue().getAsJsonObject();
            assertTokensPresent(recipe, xml, entry.getKey());
        }
    }

    @Test
    public void interactiveRecipesCoverNormalPressedSelectedAndDisabledStates() throws Exception {
        JsonObject source = object(new File(root(), "app/src/main/assets/theme/radius/theme_shapes.json"));
        JsonObject widget = source.getAsJsonObject("selector_widget_btn").getAsJsonObject("states");
        assertTrue(widget.has("default"));
        assertTrue(widget.has("pressed"));
        assertTrue(widget.has("selected"));
        assertTrue(widget.has("disabled"));
        for (String id : new String[]{"theme_btn_primary", "theme_btn_secondary", "theme_btn_ghost"}) {
            JsonObject states = source.getAsJsonObject(id).getAsJsonObject("states");
            assertTrue(id + " 缺 normal/default", states.has("default"));
            assertTrue(id + " 缺 pressed", states.has("pressed"));
            assertTrue(id + " 缺 disabled", states.has("disabled"));
        }
    }

    private static void assertTokensPresent(JsonObject object, String xml, String id) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                assertTokensPresent(value.getAsJsonObject(), xml, id);
            } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String key = entry.getKey();
                // shape 是**结构字段**(rectangle/oval),不是颜色/圆角令牌 ——
                // 它对应 XML 的 android:shape 属性,下面单独校验
                if ("shape".equals(key)) continue;
                String token = value.getAsString();
                if ("transparent".equals(token)) token = "@android:color/transparent";
                else if (!token.startsWith("#")) token = "@" +
                        ((token.startsWith("radius_") || token.startsWith("stroke_")
                                || "common_corners".equals(token)) ? "dimen/" : "color/") + token;
                assertTrue(id + " 的 XML 缺少配方令牌 " + token, xml.contains(token));
            }
        }
    }
}

package com.github.tvbox.osc.bean.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
 * "内置主题的派生值"与"运行期派生算法"必须逐键一致(纯 JVM)。
 *
 * <p>为什么必须有这条:内置主题的颜色在<b>构建期</b>由 {@code app/build.gradle} 的 {@code derivePalette}
 * 算好写进 {@code res/values/theme_colors.xml}(首帧就是对的);用户自定义主题的颜色在<b>运行期</b>
 * 由 {@link ThemePaletteFactory} 按同一套规则重算,再覆盖上去。两处规则一旦漂移,症状是
 * "自定义主题的按钮描边 / 选中态 / 弹窗底色 / 开关关闭色跟内置主题看起来不是一个算法",
 * 而这种漂移<b>不会被任何编译或运行报错发现</b>(两边各自都"自洽")。
 *
 * <p>做法:Gradle 把派生结果同时落成 assets({@code theme_derived_bright/dark.json}),
 * 这里拿它当基准,与运行期算法对同一份输入算出结果逐键比对。
 */
public class ThemeDerivationParityTest {

    private static File repoFile(String relative) {
        // 单测工作目录 = :app 模块目录
        File f = new File(relative);
        if (f.exists()) return f;
        return new File("../" + relative);
    }

    private static String read(File f) throws Exception {
        assertTrue("找不到文件(单测工作目录变了?): " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** 读主题文件里的可配置键(跳过元信息) */
    private static Map<String, String> readInput(String assetName) throws Exception {
        File f = repoFile("src/main/assets/theme/themes/" + assetName);
        JsonObject o = JsonParser.parseString(read(f)).getAsJsonObject();
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : o.keySet()) {
            if ("type".equals(key) || "name".equals(key) || "default".equals(key)
                    || "desc".equals(key) || "background".equals(key)
                    || "splashBackground".equals(key)) continue;
            out.put(key, o.get(key).getAsString());
        }
        return out;
    }

    /** 读 Gradle 生成的派生结果(构建期产物,由 :app:generateThemeColors 写出) */
    private static Map<String, Integer> readDerived(String generatedName) throws Exception {
        File f = repoFile("build/generated/theme_assets/theme/generated/" + generatedName);
        assertTrue("缺少构建期生成的派生结果: " + f.getAbsolutePath()
                + "(请先跑 ./gradlew :app:generateThemeColors;它由 preBuild 自动触发)", f.exists());
        JsonObject o = JsonParser.parseString(read(f)).getAsJsonObject();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String key : o.keySet()) {
            out.put(key, ThemePalette.parseColor(o.get(key).getAsString(), 0));
        }
        return out;
    }

    private void assertParity(String inputAsset, String derivedAsset) throws Exception {
        Map<String, String> input = readInput(inputAsset);
        Map<String, Integer> expected = readDerived(derivedAsset);
        ThemePalette actual = ThemePaletteFactory.derive(input, null);

        assertEquals("派生出的资源名个数应与 Gradle 一致", expected.size(), actual.asMap().size());
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            assertTrue("运行期派生缺少资源名: " + e.getKey(), actual.has(e.getKey()));
            assertEquals("资源名 " + e.getKey() + " 的派生值与 build.gradle 的 derivePalette 不一致"
                            + "(改了一边忘了另一边?)",
                    String.format("#%08X", e.getValue()),
                    String.format("#%08X", actual.get(e.getKey())));
        }
    }

    private static String defaultAsset(ThemeType type) throws Exception {
        File dir = repoFile("src/main/assets/theme/themes/" + type.jsonValue);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        assertNotNull("主题目录不存在: " + dir, files);
        String selected = null;
        for (File file : files) {
            JsonObject json = JsonParser.parseString(read(file)).getAsJsonObject();
            if (!json.get("default").getAsBoolean()) continue;
            assertTrue(type + " 有多份默认预设", selected == null);
            selected = type.jsonValue + "/" + file.getName();
        }
        assertNotNull(type + " 没有默认预设", selected);
        return selected;
    }

    @Test
    public void brightThemeDerivationMatchesBuildScript() throws Exception {
        assertParity(defaultAsset(ThemeType.BRIGHT), "theme_derived_bright.json");
    }

    @Test
    public void darkThemeDerivationMatchesBuildScript() throws Exception {
        assertParity(defaultAsset(ThemeType.DARK), "theme_derived_dark.json");
    }

    @Test
    public void resourceNameListCoversEveryDerivedName() throws Exception {
        Map<String, Integer> expected = readDerived("theme_derived_bright.json");
        for (String name : expected.keySet()) {
            boolean known = false;
            for (String declared : ThemePalette.RESOURCE_NAMES) {
                if (declared.equals(name)) {
                    known = true;
                    break;
                }
            }
            assertTrue("ThemePalette.RESOURCE_NAMES 少了派生出来的资源名: " + name, known);
        }
        assertEquals("RESOURCE_NAMES 里不该有派生表算不出来的名字",
                expected.size(), ThemePalette.RESOURCE_NAMES.length);
    }

    /** 每个内置预设都应能独立使用，且每个明暗目录恰好有一个默认预设。 */
    @Test
    public void builtinThemeFilesCoverEveryConfigurableKey() throws Exception {
        for (ThemeType type : ThemeType.values()) {
            File dir = repoFile("src/main/assets/theme/themes/" + type.jsonValue);
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            assertNotNull("主题目录不存在: " + dir, files);
            assertTrue("主题目录为空: " + dir, files.length > 0);
            int defaults = 0;
            for (File file : files) {
                String asset = type.jsonValue + "/" + file.getName();
                JsonObject json = JsonParser.parseString(read(file)).getAsJsonObject();
                assertEquals(type.jsonValue, json.get("type").getAsString());
                assertTrue(asset + " 缺少显示名称", json.has("name")
                        && !json.get("name").getAsString().trim().isEmpty());
                assertTrue(asset + " 缺少布尔 default", json.has("default")
                        && json.get("default").getAsJsonPrimitive().isBoolean());
                JsonObject desc = json.getAsJsonObject("desc");
                assertNotNull(asset + " 缺少 desc 说明", desc);
                if (json.get("default").getAsBoolean()) defaults++;
                Map<String, String> input = readInput(asset);
                for (ThemeKey k : ThemeSpec.all()) {
                    assertTrue(asset + " 缺少可配置项: " + k.key, input.containsKey(k.key));
                    assertTrue(asset + " 的 desc 缺少说明: " + k.key,
                            desc.has(k.key) && !desc.get(k.key).getAsString().trim().isEmpty());
                }
                if (json.has("background")) {
                    assertTrue(asset + " 的 desc.background 应为对象",
                            desc.has("background") && desc.get("background").isJsonObject());
                    JsonObject background = json.getAsJsonObject("background");
                    JsonObject backgroundDesc = desc.getAsJsonObject("background");
                    assertEquals(asset + " 的背景说明字段与配置不一致",
                            background.keySet(), backgroundDesc.keySet());
                    for (String key : background.keySet()) {
                        assertTrue(asset + " 的 desc.background 缺少说明: " + key,
                                backgroundDesc.get(key).isJsonPrimitive()
                                        && backgroundDesc.get(key).getAsJsonPrimitive().isString()
                                        && !backgroundDesc.get(key).getAsString().trim().isEmpty());
                    }
                }
                assertTrue(asset + " 缺少开屏背景配置", json.has("splashBackground"));
                assertTrue(asset + " 的 desc.splashBackground 应为对象",
                        desc.has("splashBackground") && desc.get("splashBackground").isJsonObject());
                JsonObject splash = json.getAsJsonObject("splashBackground");
                JsonObject splashDesc = desc.getAsJsonObject("splashBackground");
                assertEquals(asset + " 的开屏背景说明字段与配置不一致",
                        splash.keySet(), splashDesc.keySet());
                assertEquals(asset + " 应默认跟随本主题背景色", "theme",
                        splash.get("mode").getAsString());
                assertTrue(asset + " 应默认播放开屏动画",
                        splash.get("lottieOnImage").getAsBoolean());
                for (String key : input.keySet()) {
                    assertNotNull(asset + " 里有 ThemeSpec 不认识的键: " + key,
                            ThemeSpec.byKey(key));
                }
            }
            assertEquals(type + " 只能有一份默认预设", 1, defaults);
        }
    }

    /** 默认预设的 type 字段必须与所在目录一致,它决定"跟随系统"取哪一份 */
    @Test
    public void builtinThemeTypesAreCorrect() throws Exception {
        assertEquals(ThemeType.BRIGHT, ThemeType.fromJson(
                JsonParser.parseString(read(repoFile("src/main/assets/theme/themes/" + defaultAsset(ThemeType.BRIGHT))))
                        .getAsJsonObject().get("type").getAsString()));
        assertEquals(ThemeType.DARK, ThemeType.fromJson(
                JsonParser.parseString(read(repoFile("src/main/assets/theme/themes/" + defaultAsset(ThemeType.DARK))))
                        .getAsJsonObject().get("type").getAsString()));
    }

    /** 莲花预设只改变背景，颜色仍与默认浅色一致，且打包素材确实存在。 */
    @Test
    public void lotusPresetUsesBrightPaletteAndBundledBackground() throws Exception {
        String lotusJson = read(repoFile("src/main/assets/theme/themes/bright/lotus.json"));
        JsonObject lotus = JsonParser.parseString(lotusJson).getAsJsonObject();
        assertEquals("bright", lotus.get("type").getAsString());
        assertEquals(false, lotus.get("default").getAsBoolean());
        assertEquals(readInput("bright/default.json"), readInput("bright/lotus.json"));

        ThemeJson.Result parsed = ThemeJson.parse(lotusJson);
        assertNotNull(parsed.def);
        assertTrue(parsed.def.hasBackgroundImage());
        ThemeDef.Background bg = parsed.def.getBackground();
        String prefix = "file:///android_asset/";
        assertTrue(bg.getRef().startsWith(prefix));
        assertTrue(repoFile("src/main/assets/" + bg.getRef().substring(prefix.length())).isFile());
        assertEquals(0.36f, bg.getZoom(), 0f);
        assertEquals(1f, bg.getAnchorX(), 0f);
        assertEquals(1f, bg.getAnchorY(), 0f);
        assertFalse(bg.isScrim());
    }
}

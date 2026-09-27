package com.github.tvbox.osc.bean.theme;

import static org.junit.Assert.assertEquals;
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

    /** 读主题文件里的 25 个可配置键(跳过 type/desc) */
    private static Map<String, String> readInput(String assetName) throws Exception {
        File f = repoFile("src/main/assets/theme/" + assetName);
        JsonObject o = JsonParser.parseString(read(f)).getAsJsonObject();
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : o.keySet()) {
            if ("type".equals(key) || "desc".equals(key)) continue;
            out.put(key, o.get(key).getAsString());
        }
        return out;
    }

    /** 读 Gradle 生成的派生结果(构建期产物,由 :app:generateThemeColors 写出) */
    private static Map<String, Integer> readDerived(String generatedName) throws Exception {
        File f = repoFile("build/generated/theme_assets/theme/" + generatedName);
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

    @Test
    public void brightThemeDerivationMatchesBuildScript() throws Exception {
        assertParity("theme_colors.json", "theme_derived_bright.json");
    }

    @Test
    public void darkThemeDerivationMatchesBuildScript() throws Exception {
        assertParity("theme_colors_night.json", "theme_derived_dark.json");
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

    /** 两个内置主题文件必须一个不少地覆盖 ThemeSpec 的全部可配置项(加了键别忘了改主题文件) */
    @Test
    public void builtinThemeFilesCoverEveryConfigurableKey() throws Exception {
        for (String asset : new String[]{"theme_colors.json", "theme_colors_night.json"}) {
            Map<String, String> input = readInput(asset);
            for (ThemeKey k : ThemeSpec.all()) {
                assertTrue(asset + " 缺少可配置项: " + k.key, input.containsKey(k.key));
            }
            for (String key : input.keySet()) {
                assertNotNull(asset + " 里有 ThemeSpec 不认识的键(是漏了登记,还是该删?): " + key,
                        ThemeSpec.byKey(key));
            }
        }
    }

    /** 内置主题的 type 字段必须与文件名语义一致(bright/dark),它决定"跟随系统"取哪一份 */
    @Test
    public void builtinThemeTypesAreCorrect() throws Exception {
        assertEquals(ThemeType.BRIGHT, ThemeType.fromJson(
                JsonParser.parseString(read(repoFile("src/main/assets/theme/theme_colors.json")))
                        .getAsJsonObject().get("type").getAsString()));
        assertEquals(ThemeType.DARK, ThemeType.fromJson(
                JsonParser.parseString(read(repoFile("src/main/assets/theme/theme_colors_night.json")))
                        .getAsJsonObject().get("type").getAsString()));
    }
}

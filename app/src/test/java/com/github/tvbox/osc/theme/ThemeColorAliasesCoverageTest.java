package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.theme.ThemePalette;
import com.github.tvbox.osc.bean.theme.ThemePaletteFactory;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "随主题走的颜色资源"必须一条不漏地登记在 {@link ThemeColorAliases}(纯 JVM 绊线)。
 *
 * <p>为什么必须有这条:运行时换肤只能覆盖"它认得的颜色 id"。漏登记的后果不是报错,而是
 * <b>那一处颜色不跟着自定义主题走</b> —— 例如设置页的行文字跟着变了、某个弹窗的副标题还是旧色,
 * 这种"换了一半"的问题在真机上很难一眼定位,也没人会为它写一条构建断言。所以这里把
 * {@code res/values} 与 {@code res/values-night} 下 colors.xml 里所有"最终指向主题概念"的颜色名
 * 逐个解析出来,要求它们都能被 {@link ThemeColorAliases} 查到(通过 R.color 的编译期 id)。
 */
public class ThemeColorAliasesCoverageTest {

    private static File repoFile(String relative) {
        File f = new File(relative);
        return f.exists() ? f : new File("../" + relative);
    }

    /** 解析一个 colors.xml:name → 原始值 */
    private static Map<String, String> parseColors(String relative) throws Exception {
        File f = repoFile(relative);
        assertTrue("找不到资源文件: " + f.getAbsolutePath(), f.exists());
        String xml = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<color\\s+name=\"([^\"]+)\"\\s*>([^<]*)</color>").matcher(xml);
        while (m.find()) {
            out.put(m.group(1), m.group(2).trim());
        }
        return out;
    }

    /** 顺着 @color/x 引用一路找到底(返回 null 表示最终是字面量颜色) */
    private static String resolve(Map<String, String> colors, String name, int depth) {
        if (depth > 8) return null;
        String v = colors.get(name);
        if (v == null) return null;
        if (v.startsWith("@color/")) {
            String next = v.substring("@color/".length()).trim();
            if (colors.containsKey(next)) return resolve(colors, next, depth + 1);
            return next; // 指向生成出来的主题概念名(不带 @color/ 的规则表)
        }
        return null;
    }

    private static final Set<String> PALETTE_NAMES = new HashSet<>(Arrays.asList(ThemePalette.RESOURCE_NAMES));

    /** 通过反射拿 R.color.<name>(编译期字段,单测同样可见) */
    private static int colorId(String name) throws Exception {
        Field f = R.color.class.getField(name);
        return f.getInt(null);
    }

    @Test
    public void everyAliasThatReachesAThemeConceptIsRegistered() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String file : new String[]{"src/main/res/values/colors.xml", "src/main/res/values-night/colors.xml"}) {
            Map<String, String> colors = parseColors(file);
            for (String name : colors.keySet()) {
                String target = resolve(colors, name, 0);
                if (target == null || !PALETTE_NAMES.contains(target)) continue; // 不随主题走(白/黑/错误色等)
                int id;
                try {
                    id = colorId(name);
                } catch (NoSuchFieldException e) {
                    missing.add(name + "(R.color 里没有这个字段?)");
                    continue;
                }
                if (!ThemeColorAliases.isThemed(id)) {
                    missing.add(name + " → " + target);
                }
            }
        }
        assertEquals("这些颜色最终指向主题概念,却没登记进 ThemeColorAliases(自定义主题下它们不会变色):"
                + missing, 0, missing.size());
    }

    @Test
    public void everyPaletteResourceNameIsRegistered() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String name : ThemePalette.RESOURCE_NAMES) {
            int id;
            try {
                id = colorId(name);
            } catch (NoSuchFieldException e) {
                missing.add(name + "(R.color 里没有这个字段;是不是 build.gradle 的派生表改了?)");
                continue;
            }
            if (!ThemeColorAliases.isThemed(id)) missing.add(name);
        }
        assertEquals("派生出来的主题资源本身就是随主题走的,必须都在表里: " + missing, 0, missing.size());
    }

    /** 表里登记的概念名必须都能在调色板里取到(否则运行时会拿到兜底色,等于没换) */
    @Test
    public void everyRegisteredNameExistsInTheDerivedPalette() throws Exception {
        File asset = repoFile("src/main/assets/theme/themes/bright/default.json");
        JsonObject o = JsonParser.parseString(
                new String(Files.readAllBytes(asset.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, String> input = new HashMap<>();
        for (String key : o.keySet()) {
            if ("type".equals(key) || "desc".equals(key)
                    || !o.get(key).isJsonPrimitive()) continue;
            input.put(key, o.get(key).getAsString());
        }
        ThemePalette palette = ThemePaletteFactory.derive(input, null);
        List<String> unknown = new ArrayList<>();
        for (String name : ThemeColorAliases.all().values()) {
            if (!palette.has(name)) unknown.add(name);
        }
        assertEquals("别名表指向了调色板里不存在的概念名(改名时漏改了一处?): " + unknown, 0, unknown.size());
        assertTrue("内置主题调色板必须自洽", ThemeColorAliases.namesResolvable(palette));
    }

    /** 别名表不能有"指向自己"或空值这类脏数据 */
    @Test
    public void aliasTableHasNoBlankTargets() {
        for (Map.Entry<Integer, String> e : ThemeColorAliases.all().entrySet()) {
            String name = e.getValue();
            assertNotNull("颜色 id " + e.getKey() + " 没有对应概念名", name);
            assertTrue("颜色 id " + e.getKey() + " 的概念名是空的", name != null && !name.trim().isEmpty());
            assertTrue("概念名不在派生名单里: " + name, PALETTE_NAMES.contains(name));
        }
    }
}

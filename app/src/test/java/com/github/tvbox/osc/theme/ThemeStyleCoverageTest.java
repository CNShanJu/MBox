package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.theme.ThemePalette;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「颜色写在样式里」的共享样式必须一条不漏地登记进 {@link ThemeStyles}(纯 JVM 绊线)。
 *
 * <p>为什么要有这条:布局里的按钮只有一句 {@code style="@style/BtnSecondary"},颜色全在那个样式里,
 * 而运行时换肤的注入器只看得到<b>布局内联写的</b>属性 —— 样式里的颜色只能靠 {@link ThemeStyles}
 * 这张手写表补。漏一项的后果是静默的:自定义主题下那一处按钮<b>不跟着主题变</b>
 * (用户看到的正是"删除按钮样式和主题都对不上、导出主题还是灰底";全 App 的按钮都吃这张表)。
 *
 * <p>核对方式(与 {@code ThemeColorAliasesCoverageTest} 同一套路:读源文件 + 编译期 R id):
 * <ol>
 *   <li>扫 {@code res/layout/*.xml} 里出现的 {@code style="@style/X"},得到"真在用的样式";</li>
 *   <li>在 {@code res/values/styles.xml} 里把这些样式的<b>声明链</b>(自身 + 文件内父样式)摊平,
 *       取出颜色类属性(textColor/backgroundTint/strokeColor/background/tint);</li>
 *   <li>属性值最终指向主题概念的(直接是 {@code @color/主题键}、或指向 res/color 选择器 /
 *       res/drawable 里含主题色的),都必须在 {@link ThemeStyles} 里按"样式 + 属性名"登记;固定字面量
 *       ({@code @color/text_danger}、{@code swipe_red}、{@code @android:color/transparent})不在其列;</li>
 *   <li>反向也查一遍:表里登记的每一项,样式文件里必须真有这条声明且指向同一个资源名
 *       (防改名/写错,也防"表里留着早已删掉的项")。</li>
 * </ol>
 */
public class ThemeStyleCoverageTest {

    /** 只看这几个属性:样式里可能出现的"颜色类"属性 */
    private static final Set<String> COLOR_ATTRS = new HashSet<>(Arrays.asList(
            "textColor", "textColorHint", "backgroundTint", "background", "strokeColor", "tint"));

    private static final Set<String> PALETTE_NAMES =
            new HashSet<>(Arrays.asList(ThemePalette.RESOURCE_NAMES));

    // ------------------------------------------------------------------
    // 找文件 / 读文件
    // ------------------------------------------------------------------

    private static File repoFile(String relative) {
        File f = new File(relative);
        if (f.exists()) return f;
        f = new File("../app/" + relative);
        return f;
    }

    private static File resDir() {
        File f = repoFile("src/main/res");
        assertTrue("找不到 res 目录: " + f.getAbsolutePath(), f.isDirectory());
        return f;
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // 资源 id ↔ 名字(编译期字段,单测同样可见)
    // ------------------------------------------------------------------

    private static int idOf(Class<?> cls, String name) throws Exception {
        Field f = cls.getField(name);
        return f.getInt(null);
    }

    /** R.<cls> 的 id → 资源名 */
    private static Map<Integer, String> namesOf(Class<?> cls) throws Exception {
        Map<Integer, String> m = new HashMap<>();
        for (Field f : cls.getFields()) {
            if (f.getType() != int.class) continue;
            m.put(f.getInt(null), f.getName());
        }
        return m;
    }

    // ------------------------------------------------------------------
    // colors.xml:别名 → 最终主题概念
    // ------------------------------------------------------------------

    private static Map<String, String> parseColors(String relative) throws Exception {
        File f = repoFile(relative);
        assertTrue("找不到颜色资源文件: " + f.getAbsolutePath(), f.exists());
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("<color\\s+name=\"([^\"]+)\"\\s*>([^<]*)</color>")
                .matcher(read(f));
        while (m.find()) out.put(m.group(1), m.group(2).trim());
        return out;
    }

    /** 顺着 @color/x 引用一路找下去;返回最终的主题概念名(不是主题色则返回 null) */
    private static String paletteTarget(Map<String, String> colors, String name, int depth) {
        if (depth > 8) return null;
        String v = colors.get(name);
        if (v == null) return null;
        if (v.startsWith("@color/")) {
            String next = v.substring("@color/".length()).trim();
            if (colors.containsKey(next)) return paletteTarget(colors, next, depth + 1);
            return next; // 指向生成出来的主题概念名
        }
        return null;
    }

    /** 一个颜色资源名是不是"随主题走"(直接是主题键或其别名,或 res/color 选择器里含主题色) */
    private static boolean isThemedColorName(String name, Map<String, String> colors) {
        // 生成出来的主题色(btn_cancel_bg 这类)不在 colors.xml 里,名字本身就是概念名
        if (!colors.containsKey(name) && PALETTE_NAMES.contains(name)) return true;
        String target = paletteTarget(colors, name, 0);
        if (target != null && PALETTE_NAMES.contains(target)) return true;
        return colorSelectorUsesTheme(name, colors);
    }

    /** res/color/<name>.xml 这种带状态的选择器:里面只要有一处主题色,就得按原 XML 重建 */
    private static boolean colorSelectorUsesTheme(String name, Map<String, String> colors) {
        File f = new File(resDir(), "color/" + name + ".xml");
        if (!f.isFile()) return false;
        return anyThemedColorRef(f, colors);
    }

    /**
     * 一个 drawable(XML)里是否引用了主题色。
     * <p>找不到同名 XML(位图之类的)时返回 false:那些本来就不吃主题。
     */
    private static boolean drawableUsesTheme(String name, Map<String, String> colors) {
        File res = resDir();
        File[] dirs = res.listFiles((d, n) -> n.startsWith("drawable"));
        if (dirs == null) return false;
        for (File dir : dirs) {
            File f = new File(dir, name + ".xml");
            if (f.isFile() && anyThemedColorRef(f, colors)) return true;
        }
        return false;
    }

    private static boolean anyThemedColorRef(File xml, Map<String, String> colors) {
        try {
            Matcher m = Pattern.compile("@color/([A-Za-z0-9_]+)").matcher(read(xml));
            while (m.find()) {
                if (isThemedColorName(m.group(1), colors)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------------
    // styles.xml:样式声明链
    // ------------------------------------------------------------------

    /** 一个样式:父样式名(可能为 null)+ 自己声明的 item(name → 原始值) */
    private static final class StyleDef {
        String parent;
        final Map<String, String> items = new LinkedHashMap<>();
    }

    private static Map<String, StyleDef> parseStyles() throws Exception {
        File f = repoFile("src/main/res/values/styles.xml");
        assertTrue("找不到 styles.xml: " + f.getAbsolutePath(), f.exists());
        String xml = read(f);
        Map<String, StyleDef> out = new LinkedHashMap<>();
        Matcher s = Pattern.compile("<style\\s+name=\"([^\"]+)\"([^>]*)>([\\s\\S]*?)</style>").matcher(xml);
        while (s.find()) {
            StyleDef def = new StyleDef();
            Matcher p = Pattern.compile("parent=\"([^\"]+)\"").matcher(s.group(2));
            if (p.find()) def.parent = p.group(1).replace("@style/", "").trim();
            Matcher i = Pattern.compile("<item\\s+name=\"([^\"]+)\"\\s*>([^<]*)</item>").matcher(s.group(3));
            while (i.find()) {
                String name = i.group(1);
                int colon = name.indexOf(':');
                if (colon > 0) name = name.substring(colon + 1); // android:textColor → textColor
                def.items.put(name, i.group(2).trim());
            }
            out.put(s.group(1), def);
        }
        return out;
    }

    /** 摊平"自身 + 文件内父样式"的声明(子样式覆盖父样式) */
    private static Map<String, String> effectiveItems(Map<String, StyleDef> styles, String name) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> chain = new ArrayList<>();
        String cur = name;
        int guard = 0;
        while (cur != null && guard++ < 16) {
            StyleDef def = styles.get(cur);
            if (def == null) break; // 走到 framework/Material 的样式就停
            chain.add(cur);
            cur = def.parent;
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            out.putAll(styles.get(chain.get(i)).items);
        }
        return out;
    }

    /** 布局里真正用到的样式名 */
    private static Set<String> stylesUsedInLayouts() throws Exception {
        File dir = new File(resDir(), "layout");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".xml"));
        assertTrue("layout 目录里没有 xml?", files != null && files.length > 0);
        Set<String> used = new HashSet<>();
        for (File f : files) {
            Matcher m = Pattern.compile("style=\"@style/([A-Za-z0-9_]+)\"").matcher(read(f));
            while (m.find()) used.add(m.group(1));
        }
        return used;
    }

    // ------------------------------------------------------------------
    // 断言
    // ------------------------------------------------------------------

    /** 样式里声明过、且"最终指向主题"的属性,必须登记在 ThemeStyles 里(并且指向同一个资源) */
    @Test
    public void everyThemedStyleAttrIsRegistered() throws Exception {
        Map<String, String> colors = parseColors("src/main/res/values/colors.xml");
        Map<String, StyleDef> styles = parseStyles();
        Map<Integer, String> styleNames = namesOf(R.style.class);

        List<String> missing = new ArrayList<>();
        for (String styleName : stylesUsedInLayouts()) {
            if (!styles.containsKey(styleName)) continue;
            int styleId;
            try {
                styleId = idOf(R.style.class, styleName);
            } catch (NoSuchFieldException e) {
                missing.add(styleName + "(R.style 里没有这个字段?)");
                continue;
            }
            Map<String, String> items = effectiveItems(styles, styleName);
            for (Map.Entry<String, String> e : items.entrySet()) {
                String attr = e.getKey();
                if (!COLOR_ATTRS.contains(attr)) continue;
                String value = e.getValue();
                String kind;
                String resName;
                boolean themed;
                if (value.startsWith("@color/")) {
                    kind = "color";
                    resName = value.substring("@color/".length()).trim();
                    themed = isThemedColorName(resName, colors);
                } else if (value.startsWith("@drawable/")) {
                    kind = "drawable";
                    resName = value.substring("@drawable/".length()).trim();
                    themed = drawableUsesTheme(resName, colors);
                } else {
                    continue; // 字面量 / @android:color / @dimen / … 不随主题
                }
                if (!themed) continue;
                int wantId = idOf(kind.equals("color") ? R.color.class : R.drawable.class, resName);
                if (!hasRegistered(styleId, attr, wantId)) {
                    missing.add(styleName + " 的 " + attr + " → @" + kind + "/" + resName
                            + "(样式里的颜色不跟主题走,必须登记进 ThemeStyles)");
                }
            }
        }
        assertEquals("这些样式属性最终指向主题,却没登记进 ThemeStyles: " + missing, 0, missing.size());
        assertTrue("R.style 里必须能找到 Btn* 这些共享样式", styleNames.size() > 0);
    }

    /** 反向:表里登记的每一项,样式文件里必须有这条声明且指向同一个资源(防改名/写错/留死项) */
    @Test
    public void registeredEntriesMatchStylesXml() throws Exception {
        Map<String, StyleDef> styles = parseStyles();
        Map<Integer, String> styleNames = namesOf(R.style.class);
        Map<Integer, String> colorNames = namesOf(R.color.class);
        Map<Integer, String> drawableNames = namesOf(R.drawable.class);

        List<String> wrong = new ArrayList<>();
        for (Map.Entry<Integer, List<ThemeStyles.Attr>> e : ThemeStyles.all().entrySet()) {
            String styleName = styleNames.get(e.getKey());
            if (styleName == null) {
                wrong.add("样式 id " + e.getKey() + " 在 R.style 里没有名字");
                continue;
            }
            Map<String, String> items = effectiveItems(styles, styleName);
            for (ThemeStyles.Attr attr : e.getValue()) {
                String declared = items.get(attr.name);
                if (declared == null) {
                    wrong.add(styleName + " 里没有声明 " + attr.name);
                    continue;
                }
                String resName = declared.startsWith("@color/") ? colorNames.get(attr.resId)
                        : declared.startsWith("@drawable/") ? drawableNames.get(attr.resId) : null;
                if (resName == null) {
                    wrong.add(styleName + "." + attr.name + " 的样式值不是 @color/@drawable: " + declared);
                    continue;
                }
                String prefix = declared.substring(0, declared.indexOf('/') + 1); // "@color/" / "@drawable/"
                if (!declared.equals(prefix + resName)) {
                    wrong.add(styleName + "." + attr.name + " 表里登记 " + resName + ",样式里写的是 " + declared);
                }
            }
        }
        assertEquals("ThemeStyles 的登记和 styles.xml 对不上: " + wrong, 0, wrong.size());
    }

    private static boolean hasRegistered(int styleId, String attrName, int resId) {
        for (ThemeStyles.Attr a : ThemeStyles.of(styleId)) {
            if (a.name.equals(attrName) && a.resId == resId) return true;
        }
        return false;
    }
}

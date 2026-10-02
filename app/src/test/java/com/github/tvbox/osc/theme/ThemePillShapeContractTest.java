package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "胶囊/半圆底"的源码级绊线(纯 JVM;真机观感由用户人工验证)。
 *
 * <p>用户口径:"这个按钮样式又出来了……看着奇奇怪怪的,每次都出现,有时候指不定哪里出现"。
 * 规律很简单:<b>圆角超过控件高度的一半时,视觉不会再继续变化</b> ——
 * 已经踩过三次:搜索词 chip(bg_large_round_float 的 18dp 圆角放在 ~28dp 高上)、
 * 播放器面板小按钮(12dp/28dp)、主题弹窗页脚(卡片圆角放在动作行上)。
 *
 * <p>这类问题不会有任何报错,只是"某处看着怪",所以钉成断言:凡是布局里
 * {@code android:background="@drawable/X"} 且自己写了固定高度(或 @dimen)的控件,
 * 只要 X 的圆角超过高度的一半,就在这里红 —— 要么换个小圆角的 drawable,
 * 要么把高度加大,要么确认它本来就该是个药丸(那种情况请显式加进 {@link #ALLOW})。
 */
public class ThemePillShapeContractTest {

    /** 圆角占高度超过这个比例就算"药丸感" */
    /** 半高就是标准胶囊上限;更大的值不再产生视觉变化。 */
    private static final double MAX_RATIO = 0.50;

    /**
     * 已知且有意为之的例外(打印成 "布局:控件" 形式)。
     *
     * <p>判定时逐条看过:
     * <ul>
     *   <li>{@code fragment_home.xml:search} —— 首页搜索框,36dp 高 + {@code radius_search}(18dp)= 50%:
     *       <b>它本来就是"胶囊造型的搜索条",用户明确确认过这个圆角是对的</b>
     *       (2026-09-27 我按"全站统一小圆角"改面板档时误伤了它,已改回并把这条豁免恢复)。
     *       它现在走**独立圆角档** {@code radius_search}(不再是 {@code radius_background}),
     *       所以以后调页面卡片/抽屉面板的圆角不会再牵连到它 —— 这条豁免只为它的"胶囊造型"本身而留。</li>
     * </ul>
     */
    private static final String[] ALLOW = {
            "fragment_home.xml:search",
            // 主题编辑器的圆角色块:它是**故意**把当前圆角档画在 28dp 高的小方块上给用户看的,
            // 圆角档调到 26dp 时这里自然就是"药丸/半圆" —— 那是预览本身要表达的效果,
            // 不是某个组件被画坏了(全站真实的短控件由本测试的其余用例继续守着)。
            "item_theme_color.xml:v_swatch",
            // 「最近热搜」的序号徽标(1..10,2026-10-01 新增):22dp 的序号牌,**本来就该是圆/药丸** ——
            // 圆角跟主题档走(用户把各档调到 26dp 时它就是一个圆点),与 NewBox 搜索页的同款序号牌一致。
            // 这不是"短控件被画成药丸"的事故,故按本测试的口径登记豁免。
            "item_search_hot_rank.xml:tv_rank",
    };

    private static File repoRoot() {
        File f = new File("..");
        return f.exists() ? f : new File(".");
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** dimen 名 → dp 值(读 res/values/dimens.xml 与生成的 theme_radii.xml) */
    private static Map<String, Double> dimens() throws Exception {
        Map<String, Double> map = new HashMap<>();
        List<File> files = new ArrayList<>();
        files.add(new File(repoRoot(), "app/src/main/res/values/dimens.xml"));
        files.add(new File(repoRoot(), "app/build/generated/theme_colors/values/theme_radii.xml"));
        for (File f : files) {
            if (!f.isFile()) continue;
            Matcher m = Pattern.compile("<dimen\\s+name=\"([^\"]+)\">\\s*([0-9.]+)dp\\s*</dimen>")
                    .matcher(read(f));
            while (m.find()) map.put(m.group(1), Double.parseDouble(m.group(2)));
        }
        return map;
    }

    /** 尺寸写法 → dp(dimen 引用/字面量;解析不出返回 null) */
    private static Double dp(String value, Map<String, Double> dimens) {
        if (value == null) return null;
        String v = value.trim();
        if (v.startsWith("@dimen/")) return dimens.get(v.substring("@dimen/".length()));
        if (v.endsWith("dp")) {
            try {
                return Double.parseDouble(v.substring(0, v.length() - 2));
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null; // wrap_content / match_parent / 0dp(由父级决定)不评估
    }

    /**
     * 这份 drawable 的圆角(dp);多层取最大,解析不出返回 null。
     *
     * <p>**ripple 的 mask 不算**:mask 只决定按下时那层高亮的形状,平时这个 View 根本没有可见的底,
     * 谈不上"画成药丸"({@code ripple_round_background} 就是这种:只有 mask,注释里写明高亮要裁成柔和的一条)。
     * 所以先把 mask 那段剥掉再找圆角;剥完什么都不剩 → 这份 drawable 没有可见底 → 返回 null。
     */
    private static Double radiusDp(String drawableName, Map<String, Double> dimens) throws Exception {
        File xml = new File(repoRoot(), "app/src/main/res/drawable/" + drawableName + ".xml");
        if (!xml.isFile()) {
            xml = new File(repoRoot(), "app/build/generated/theme_shapes/drawable/" + drawableName + ".xml");
        }
        if (!xml.isFile()) return null;
        String text = withoutRippleMask(read(xml));
        Double max = null;
        Matcher m = Pattern.compile("android:(?:radius|topLeftRadius|topRightRadius|bottomLeftRadius|bottomRightRadius)=\"([^\"]+)\"")
                .matcher(text);
        while (m.find()) {
            Double d = dp(m.group(1), dimens);
            if (d != null && (max == null || d > max)) max = d;
        }
        return max;
    }

    /** 去掉 {@code <item android:id="@android:id/mask"> … </item>} 那一段(它不是可见的底) */
    private static String withoutRippleMask(String text) {
        Matcher m = Pattern.compile(
                "<item\\b[^>]*/>|<item\\b(?:(?!</?item\\b)[\\s\\S])*?</item>",
                Pattern.DOTALL).matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int from = 0;
        while (m.find()) {
            if (!m.group().contains("@android:id/mask")) continue;
            sb.append(text, from, m.start());
            from = m.end();
        }
        return sb.append(text.substring(from)).toString();
    }

    @Test
    public void backgroundRadiusIsNotPillLikeOnShortViews() throws Exception {
        Map<String, Double> dimens = dimens();
        List<String> bad = new ArrayList<>();
        File dir = new File(repoRoot(), "app/src/main/res/layout");
        File[] layouts = dir.listFiles();
        if (layouts == null) {
            assertTrue("找不到布局目录", false);
            return;
        }
        for (File layout : layouts) {
            if (!layout.getName().endsWith(".xml")) continue;
            String text = read(layout);
            // 逐个元素的开标签(属性值里不会出现 '>')
            Matcher tag = Pattern.compile("<([A-Za-z][\\w.]*)\\b([^>]*)>").matcher(text);
            while (tag.find()) {
                String attrs = tag.group(2);
                String bg = attr(attrs, "android:background");
                if (bg == null || !bg.startsWith("@drawable/")) continue;
                Double h = dp(attr(attrs, "android:layout_height"), dimens);
                if (h == null || h <= 0) continue;
                Double r = radiusDp(bg.substring("@drawable/".length()), dimens);
                if (r == null || r <= 0) continue;
                if (r / h > MAX_RATIO) {
                    String id = attr(attrs, "android:id");
                    String who = layout.getName() + ":" + (id == null ? tag.group(1) : id.replace("@+id/", ""));
                    if (!allowed(who)) {
                        bad.add(String.format("%s  圆角 %.0fdp / 高 %.0fdp = %.0f%%  (%s)",
                                who, r, h, r / h * 100, bg));
                    }
                }
            }
        }
        if (!bad.isEmpty()) {
            fail("以下 " + bad.size() + " 处的底会画成药丸/半圆(圆角 ≥ 高度 "
                    + (int) (MAX_RATIO * 100) + "%):\n  - " + String.join("\n  - ", bad)
                    + "\n处理:换小圆角的 drawable(如 bg_r_common_stroke_primary = 8dp)、或把高度加大、"
                    + "或确认它本来就该是药丸并加进 ThemePillShapeContractTest.ALLOW");
        }
        assertTrue(true);
    }

    private static boolean allowed(String who) {
        for (String a : ALLOW) {
            if (a.equals(who)) return true;
        }
        return false;
    }

    /**
     * 小组件键(chip)的圆角必须跟「键高」配套 —— 这是用户反复看到"上下圆角不一致/像胶囊"的根因。
     *
     * <p>{@code style/WidgetBtn} 的 minHeight 是 34dp,而 {@code radius_widget_btn} 是**全站共用**的一档:
     * 一旦 ≥ 半高(17dp),上下两段圆弧就把直边吃光,GradientDrawable 画出来就是胶囊/半圆 ——
     * 2026-09-27 用户把该档设成 16dp(= 34dp 的 47%)后又复现了一次,口径是
     * "圆角为什么只对顶部有效,底部没生效 / 看着像胶囊"。
     *
     * <p>所以钉两条:① {@code radius_widget_btn ≤ minHeight / 2}(17dp = 标准胶囊);② 边框粗细必须走主题键
     * {@code stroke_widget_btn}(不许在 drawable 里写死 1dp/2mm —— 2mm 那处曾经厚得像块板子)。
     */
    @Test
    public void widgetButtonRadiusAndStrokeFitTheKeyHeight() throws Exception {
        Map<String, Double> dimens = dimens();

        Double minHeight = null;
        Matcher style = Pattern.compile("<style name=\"WidgetBtn\"[^>]*>(.*?)</style>", Pattern.DOTALL)
                .matcher(read(new File(repoRoot(), "app/src/main/res/values/styles.xml")));
        if (style.find()) {
            Matcher item = Pattern.compile("<item name=\"android:minHeight\">([^<]+)</item>").matcher(style.group(1));
            if (item.find()) minHeight = dp(item.group(1).trim(), dimens);
        }
        assertTrue("在 styles.xml 的 WidgetBtn 里找不到 minHeight,这条绊线要跟着改", minHeight != null && minHeight > 0);

        Double radius = dimens.get("radius_widget_btn");
        assertTrue("生成资源里没有 radius_widget_btn(检查 theme_radii.json 与 build.gradle 的 defaultRadii)", radius != null);
        double ratio = radius / minHeight;
        assertTrue(String.format(java.util.Locale.ROOT,
                        "radius_widget_btn 圆角 %.0fdp / 键高 %.0fdp = %.0f%%,超过上限 %.0f%% —— "
                                 + "超过半高不会继续改变视觉,且容易掩盖真实裁剪问题;"
                                 + "0–15dp 为普通圆角,16dp 接近胶囊,17dp 为标准胶囊",
                        radius, minHeight, ratio * 100, MAX_RATIO * 100),
                ratio <= MAX_RATIO);

        assertTrue("生成资源里没有 stroke_widget_btn:小组件键的边框线粗细必须走主题键",
                dimens.containsKey("stroke_widget_btn"));
        for (String drawable : new String[]{"selector_widget_btn", "button_detail_quick_search"}) {
            File file = new File(repoRoot(), "app/src/main/res/drawable/" + drawable + ".xml");
            if (!file.isFile()) {
                file = new File(repoRoot(), "app/build/generated/theme_shapes/drawable/" + drawable + ".xml");
            }
            Matcher w = Pattern.compile("android:width=\"([^\"]+)\"").matcher(
                    read(file));
            while (w.find()) {
                assertTrue(drawable + " 里还有写死的描边宽度 " + w.group(1) + "(应走 @dimen/stroke_widget_btn)",
                        w.group(1).startsWith("@dimen/"));
            }
        }
    }

    /** 取元素开标签里的某个属性值 */
    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile(Pattern.quote(name) + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    /**
     * {@code build.gradle} 的兜底表 {@code defaultRadii} 必须与主题文件同值。
     *
     * <p>为什么钉它:兜底表只在**主题文件缺失/解析失败**时生效 ——
     * 一旦命中,全站圆角整体跳到另一套(而不会有任何报错),现象是"什么都没改,圆角全变了";
     * 2026-09-27 实测两处已经漂了五档(radius_dialog 16 vs 10、radius_btn 16 vs 12、
     * radius_widget_btn 6 vs 12、common_corners 8 vs 12、radius_search 18 vs 16),
     * 而源码注释写的正是"必须与仓库内那份同值"。
     */
    @Test
    public void defaultRadiiMatchesThemeFile() throws Exception {
        Map<String, String> fromFile = new HashMap<>();
        Matcher j = Pattern.compile("\"(\\w+)\"\\s*:\\s*([0-9.]+)")
                .matcher(read(new File(repoRoot(), "app/src/main/assets/theme/radius/theme_radii.json")));
        while (j.find()) fromFile.put(j.group(1), j.group(2));

        String gradle = read(new File(repoRoot(), "app/build.gradle"));
        int start = gradle.indexOf("def defaultRadii = [");
        assertTrue("build.gradle 里找不到 defaultRadii,绊线要跟着改", start > 0);
        int end = gradle.indexOf("\n]", start);
        assertTrue("defaultRadii 块没有闭合", end > start);
        Map<String, String> fromGradle = new HashMap<>();
        Matcher g = Pattern.compile("(?m)^\\s*([A-Za-z_]\\w*)\\s*:\\s*([0-9.]+)")
                .matcher(gradle.substring(start, end));
        while (g.find()) fromGradle.put(g.group(1), g.group(2));

        assertTrue("theme_radii.json 一个圆角档都没解析到", fromFile.size() >= 5);
        assertTrue("defaultRadii 与 theme_radii.json 的键不一致:文件 " + fromFile.keySet()
                        + " / 兜底 " + fromGradle.keySet(),
                fromFile.keySet().equals(fromGradle.keySet()));
        for (Map.Entry<String, String> e : fromFile.entrySet()) {
            assertTrue("圆角兜底值与主题文件不一致:" + e.getKey() + " 文件=" + e.getValue()
                            + " 兜底=" + fromGradle.get(e.getKey())
                            + "(主题文件一旦缺失/解析失败,全站圆角会静默跳到另一套)",
                    e.getValue().equals(fromGradle.get(e.getKey())));
        }
    }
}

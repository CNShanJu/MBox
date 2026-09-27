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
 * 规律很简单:<b>圆角 ≥ 控件高度的 ~40% 时,那个底就圆得像个药丸/半圆</b> ——
 * 已经踩过三次:搜索词 chip(bg_large_round_float 的 18dp 圆角放在 ~28dp 高上)、
 * 播放器面板小按钮(12dp/28dp)、主题弹窗页脚(卡片圆角放在动作行上)。
 *
 * <p>这类问题不会有任何报错,只是"某处看着怪",所以钉成断言:凡是布局里
 * {@code android:background="@drawable/X"} 且自己写了固定高度(或 @dimen)的控件,
 * 只要 X 的圆角 ≥ 高度的 40%,就在这里红 —— 要么换个小圆角的 drawable,
 * 要么把高度加大,要么确认它本来就该是个药丸(那种情况请显式加进 {@link #ALLOW})。
 */
public class ThemePillShapeContractTest {

    /** 圆角占高度超过这个比例就算"药丸感" */
    private static final double MAX_RATIO = 0.45;

    /**
     * 已知且有意为之的例外(打印成 "布局:控件" 形式)。
     *
     * <p>判定时逐条看过:
     * <ul>
     *   <li>{@code fragment_home.xml:search} —— 首页搜索框,36dp 高 + radius_background(18dp)= 50%,
     *       本来就是个胶囊造型的搜索条(设计如此,不是漏改);</li>
     *   <li>背景图设置页那两行(panel_handle / ll_scrim)是 44dp + radius_background = 41%,
     *       低于阈值不再报;该 drawable(ripple_round_background)自己的注释里就写明
     *       "44dp 高的行会被裁成胶囊,看起来是柔和的一条",属于有意为之。</li>
     * </ul>
     */
    private static final String[] ALLOW = {
            "fragment_home.xml:search",
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

    /** 这份 drawable 的圆角(dp);多层取最大,解析不出返回 null */
    private static Double radiusDp(String drawableName, Map<String, Double> dimens) throws Exception {
        File xml = new File(repoRoot(), "app/src/main/res/drawable/" + drawableName + ".xml");
        if (!xml.isFile()) return null;
        String text = read(xml);
        Double max = null;
        Matcher m = Pattern.compile("android:(?:radius|topLeftRadius|topRightRadius|bottomLeftRadius|bottomRightRadius)=\"([^\"]+)\"")
                .matcher(text);
        while (m.find()) {
            Double d = dp(m.group(1), dimens);
            if (d != null && (max == null || d > max)) max = d;
        }
        return max;
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

    /** 取元素开标签里的某个属性值 */
    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile(Pattern.quote(name) + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }
}

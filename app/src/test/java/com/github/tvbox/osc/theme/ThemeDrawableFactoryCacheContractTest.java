package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * {@link ThemeDrawableFactory} 的**取用路径**绊线(纯 JVM;真机观感由用户人工验证)。
 *
 * <p>钉住 2026-10-01 那个真机 bug:工厂把生成好的 drawable 的 {@code ConstantState} 缓存下来,
 * 取用时用 {@code state.newDrawable(resources)} 复用。框架在这条路上会按密度**重缩放圆角**,
 * 而且只缩前四个槽 —— 真机实测 {@code [16×8]} 变成
 * {@code [47,47,47,47,16,16,16,16]},画出来上两角 38px、下两角 11px,
 * 用户口径"弹窗上圆角比下圆角大得多";把圆角档调大后更明显(26dp 的那次读数)。
 *
 * <p>同一个值现造则是均匀的(真机对照:setCornerRadii(16×8) 现造 → 几何 11/11/11/11 ✓,
 * 走 state 复用 → 38/38/11/11 ✗),所以正确做法就是**不缓存 ConstantState、每次现造**。
 * 这条绊线防止以后为了"省一点开销"把缓存加回来。
 */
public class ThemeDrawableFactoryCacheContractTest {

    private static File file(String... candidates) {
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return new File(candidates[0]);
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static String factorySource() throws Exception {
        return read(file("src/main/java/com/github/tvbox/osc/theme/ThemeDrawableFactory.java",
                "../app/src/main/java/com/github/tvbox/osc/theme/ThemeDrawableFactory.java"));
    }

    /** 不许把 ConstantState 缓存起来再复用:那条路会被框架按密度重缩放圆角 */
    @Test
    public void factoryDoesNotCacheAndReuseConstantState() throws Exception {
        String code = withoutComments(factorySource());
        assertFalse("工厂又缓存 ConstantState 了 —— 复用时框架会按密度重缩放圆角"
                        + "(真机实测 [16×8] → [47,47,47,47,16,16,16,16],画成上圆下不圆)。"
                        + "生成 drawable 请每次现造,不要为了缓存把这条 bug 带回来。",
                code.contains("Map<String, Drawable.ConstantState>")
                        || code.contains("CACHE.put(key, state)"));
        assertFalse("不许再用 state.newDrawable(resources) 复用生成 drawable",
                code.contains("state.newDrawable("));
    }

    /** 去掉注释,避免"注释里解释这条坑"反而被绊线命中 */
    static String withoutComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /**
     * 圆角算术必须**恰好乘一次密度**:编译期 XML 那份由 aapt2 解析后框架不再换算,而代码
     * {@code new GradientDrawable()+setCornerRadii} 那份框架会再处理一遍 —— 真机实测
     * 26dp 时编译期几何 50px、喂 dp 只有 19px(约差一个 density)。所以这里**要**用
     * {@code radiusPx}(= dp × density),但不能再额外乘一次(乘两次就是"上圆下不圆"的放大形态)。
     */
    @Test
    public void cornerRadiiApplyDensityExactlyOnce() throws Exception {
        String src = factorySource();
        int from = src.indexOf("private static float[] cornerRadii(");
        assertTrue("找不到 cornerRadii,绊线要跟着改", from > 0);
        int to = src.indexOf("private static boolean hasCorner(");
        assertTrue("cornerRadii 方法体没有闭合", to > from);
        String body = withoutComments(src.substring(from, to));
        assertTrue("uniform 半径必须走 radiusPx(按密度换算一次):\n" + body,
                body.contains("radiusPx("));
        assertFalse("cornerRadii 里不许再自己乘 density —— 那会把密度乘两遍:\n" + body,
                body.contains("* density") || body.contains("* Math.max(0f, density)"));
    }
}

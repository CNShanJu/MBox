package com.github.tvbox.osc.ui.dialog;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「弹窗/抽屉那层面」圆角必须同档的源码级绊线(纯 JVM;真机观感由用户人工验证)。
 *
 * <p><b>钉住的问题</b>(用户口径:"弹窗的圆角和抽屉的圆角,设置出来显示效果不一致"):
 * 抽屉与底部面板的底是**基类代码统一铺**的(`bg_drawer`/`bg_bottom_dialog` → `radius_dialog`),
 * 只有一个来源;而居中弹窗的底是**各布局根自己写**的,于是历史上分裂成两份:
 * {@code bg_dialog}(radius_dialog)与 {@code bg_large_round_popup}(radius_background)——
 * 两份 drawable 除了一行 corners 引用之外完全一样。结果:调面板档,抽屉和一半弹窗变、
 * 另 8 个(确认框/投屏/输入框/下载设置/删除下载/直播接口)不动;调大面板档,反过来。
 *
 * <p><b>现在的口径</b>:弹窗/抽屉那层面只有一档 —— {@code radius_dialog},
 * 居中弹窗面板只有一份底 —— {@code bg_dialog}。本测试钉三条:
 * ① 任何 dialog 布局根上的"面板底"圆角必须是 {@code @dimen/radius_dialog};
 * ② {@code bg_large_round_popup} 不许复活(复活就又是两档);
 * ③ ShadowLayout 形态的面板壳(搜索字幕)也必须走 {@code radius_dialog}。
 * 气泡类浮层(长按动作/上次看到/联想面板)按既有口径走小件档 {@code common_corners},属白名单。
 */
public class DialogPanelRadiusContractTest {

    private static final String PANEL_RADIUS = "@dimen/radius_dialog";

    /** 气泡档浮层:按"同一屏挨着的件必须同档"的口径,它们跟同屏的 chip 一起走 common_corners */
    private static final String[] BUBBLE_DRAWABLES = {"bg_bubble"};

    private static File repoRoot() {
        File f = new File("..");
        return f.exists() ? f : new File(".");
    }

    private static File child(String relative) {
        return new File(repoRoot(), relative);
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile(Pattern.quote(name) + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    /** 布局的根元素(去掉 xml 声明与注释后的第一个开标签):返回 [标签名, 属性串] */
    private static String[] rootTag(String xml) {
        String s = xml.replaceAll("(?s)<\\?xml.*?\\?>", "").replaceAll("(?s)<!--.*?-->", "").trim();
        Matcher m = Pattern.compile("<([A-Za-z][\\w.]*)\\b([^>]*)>").matcher(s);
        if (!m.find()) return null;
        return new String[]{m.group(1), m.group(2)};
    }

    private static List<String> dialogLayouts() throws Exception {
        File dir = child("app/src/main/res/layout");
        File[] files = dir.listFiles((d, name) -> name.startsWith("dialog_") && name.endsWith(".xml"));
        assertTrue("找不到弹窗布局目录:" + dir, files != null && files.length > 0);
        List<String> names = new ArrayList<>();
        for (File f : files) names.add(f.getName());
        return names;
    }

    /** 取一份 drawable XML 里 <corners> 引用的所有 @dimen 档位 */
    private static List<String> cornerDimens(String drawableXml) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(
                "android:(?:radius|topLeftRadius|topRightRadius|bottomLeftRadius|bottomRightRadius)=\"(@dimen/[^\"]+)\"")
                .matcher(drawableXml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * ① dialog 布局根上的"面板底"必须挂面板档 {@code radius_dialog}。
     * <p>只看**根元素**:页面/抽屉里面的小块(common_corners)、纯色底(由壳铺)、没写底的
     * (底部与右侧壳会铺 bg_bottom_dialog/bg_drawer)都不在判定范围内 —— 这条绊线只管"弹窗那层面"。
     */
    @Test
    public void dialogPanelRootBackgroundUsesPanelRadius() throws Exception {
        List<String> bad = new ArrayList<>();
        int scanned = 0;
        for (String layoutName : dialogLayouts()) {
            String[] root = rootTag(read(child("app/src/main/res/layout/" + layoutName)));
            if (root == null) continue;
            String bg = attr(root[1], "android:background");
            if (bg == null || !bg.startsWith("@drawable/")) continue;
            String drawableName = bg.substring("@drawable/".length());
            if (isBubble(drawableName)) continue;
            File drawable = child("app/src/main/res/drawable/" + drawableName + ".xml");
            if (!drawable.isFile()) continue;
            List<String> dimens = cornerDimens(read(drawable));
            if (dimens.isEmpty()) continue; // 不是带圆角的 shape(纯色/selector):由壳或自身颜色决定
            scanned++;
            for (String d : dimens) {
                if (!PANEL_RADIUS.equals(d)) {
                    bad.add(layoutName + " → " + bg + " 的圆角是 " + d);
                }
            }
        }
        assertTrue("一个弹窗面板都没扫到,绊线失效了(布局改名了?)", scanned > 0);
        if (!bad.isEmpty()) {
            fail("弹窗那层面必须与抽屉同档(" + PANEL_RADIUS + "),以下面板挂到了别的圆角档上:\n  - "
                    + String.join("\n  - ", bad)
                    + "\n处理:把布局根的底换成 bg_dialog(弹窗/抽屉面板唯一的一份底);"
                    + "它跟抽屉的 bg_drawer/bg_bottom_dialog 一起走同一个圆角档,改一档全变。");
        }
    }

    /** ② 弹窗面板的底只允许一份:bg_large_round_popup 已被合并进 bg_dialog,不许复活 */
    @Test
    public void popupPanelDrawableStaysSingle() throws Exception {
        File dup = child("app/src/main/res/drawable/bg_large_round_popup.xml");
        assertTrue("bg_large_round_popup.xml 又回来了:它和 bg_dialog 只差一行圆角引用"
                        + "(它挂 radius_background = 页面卡片档),复活就等于弹窗那层面又分两档,"
                        + "弹窗与抽屉的圆角会再次「设置出来不一致」。要用弹窗面板底请用 bg_dialog",
                !dup.isFile());
    }

    /**
     * ③ ShadowLayout 形态的面板壳(搜索字幕弹窗:面板底由布局自己给,不走 drawable)也必须面板档 ——
     * 它原来挂 radius_card(卡片档),于是"搜索字幕"比其它弹窗和抽屉都更圆。
     */
    @Test
    public void shadowShellPanelUsesPanelRadius() throws Exception {
        String src = read(child("app/src/main/res/layout/dialog_search_subtitle.xml"));
        Matcher m = Pattern.compile("app:hl_cornerRadius=\"([^\"]+)\"").matcher(src);
        assertTrue("dialog_search_subtitle 里找不到 ShadowLayout 的 hl_cornerRadius,绊线要跟着改", m.find());
        assertTrue("搜索字幕弹窗的面板壳要跟其它弹窗/抽屉同档(" + PANEL_RADIUS + "),现在是 " + m.group(1),
                PANEL_RADIUS.equals(m.group(1)));
    }

    private static boolean isBubble(String drawableName) {
        for (String b : BUBBLE_DRAWABLES) {
            if (b.equals(drawableName)) return true;
        }
        return false;
    }
}

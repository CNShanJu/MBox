package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * 「tab 指示器(库从属性里取的 drawable)颜色必须由代码按主题给」的源码级绊线
 * (纯 JVM;真机观感由用户人工验证)。
 *
 * <p><b>同一个根因、两处踩到</b>:DslTabLayout 的指示器 drawable 是布局属性
 * ({@code app:tab_indicator_drawable="@drawable/xxx"}),库在 {@code DslTabIndicator.initAttribute}
 * 里用 {@code typedArray.getDrawable()} 取它 —— <b>属性里的 drawable 是 native 取法</b>,
 * 自定义主题的五条换肤通道一条都拦不到({@code ThemeInflaterFactory} 的注入只认**颜色**属性,
 * {@code ThemeResources.getDrawable} 又只给"单色矢量图标"上 tint),于是 drawable 里的
 * {@code @color/xxx} 停在**编译期**那份。现象都是"同一行/同一屏别的都变了、就这一处没变":
 * <ul>
 *   <li>首页顶部导航那条"微笑曲线"({@code indicator_flash}):自定义主题下不跟文字主色
 *       (用户口径"选择主页下面的微笑曲线的颜色没走文字主色")。修法:矢量用库自己的
 *       {@code tabIndicator.indicatorColor} 上一遍 tint(= 与选中文字同源的那份文字主色);</li>
 *   <li>搜索页来源列表选中项的底({@code bg_small_round_float}):自定义主题下停在编译期的
 *       {@code bg_surface}。修法:shape 用 {@link ThemeDrawables#themedDrawable} 取"按主题重建过"
 *       的那份(只换颜色,圆角/描边/尺寸原样保留)。</li>
 * </ul>
 * 两处都必须"代码里补一次取色",光改 drawable 里的颜色资源对自定义主题无效。
 */
public class TabIndicatorColorContractTest {

    private static final String HOME_FRAGMENT =
            "src/main/java/com/github/tvbox/osc/ui/fragment/HomeFragment.kt";
    private static final String SEARCH_ACTIVITY =
            "src/main/java/com/github/tvbox/osc/ui/activity/FastSearchActivity.kt";
    private static final String HOME_LAYOUT = "src/main/res/layout/fragment_home.xml";
    private static final String SEARCH_LAYOUT = "src/main/res/layout/activity_fast_search.xml";
    private static final String CURVE_DRAWABLE = "src/main/res/drawable/indicator_flash.xml";

    private static String read(String relative) throws Exception {
        for (String p : new String[]{relative, "../app/" + relative, "app/" + relative}) {
            File f = new File(p);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        throw new AssertionError("找不到源文件:" + relative);
    }

    private static boolean matches(String src, String regex) {
        return Pattern.compile(regex, Pattern.DOTALL).matcher(src).find();
    }

    /** 曲线与选中文字必须同源:两者都取 {@code R.color.text_foreground}(走换肤过的 Resources) */
    @Test
    public void homeCurveColorComesFromTheSameThemedSourceAsTheTabText() throws Exception {
        String src = read(HOME_FRAGMENT);
        assertTrue("首页顶部导航的选中文字色不在代码里按主题取了(口径被挪走了?)",
                matches(src, "tabSelectColor\\s*=\\s*[^=]{0,240}?R\\.color\\.text_foreground"));
        assertTrue("那条微笑曲线没在代码里按主题取色(tabIndicator.indicatorColor):"
                        + "自定义主题下它会停在内置的文字主色,与同行的选中文字不同色",
                matches(src, "tabIndicator\\s*\\.\\s*indicatorColor\\s*=\\s*[^=]{0,240}?R\\.color\\.text_foreground"));
    }

    /** 内置主题那一档:曲线 drawable 自己的颜色也必须是文字主色(不是 colorPrimary=强调文字) */
    @Test
    public void curveDrawableUsesTextMainColor() throws Exception {
        String xml = read(CURVE_DRAWABLE);
        int fill = xml.indexOf("android:fillColor=");
        assertTrue("indicator_flash 没有 fillColor,绊线要跟着改", fill > 0);
        String value = xml.substring(fill, Math.min(xml.length(), fill + 120));
        assertTrue("曲线的 fillColor 必须是 @color/text_foreground(文字主色):" + value,
                value.contains("@color/text_foreground"));
    }

    /** 元素身份:两处指示器都还挂在各自的 tab 布局上(改元素了这条绊线要跟着改) */
    @Test
    public void bothIndicatorsAreStillWiredInTheirLayouts() throws Exception {
        String home = read(HOME_LAYOUT);
        assertTrue("首页 tab 布局不再挂 indicator_flash",
                home.contains("app:tab_indicator_drawable=\"@drawable/indicator_flash\""));
        String search = read(SEARCH_LAYOUT);
        assertTrue("搜索页来源列表不再挂 bg_small_round_float",
                search.contains("app:tab_indicator_drawable=\"@drawable/bg_small_round_float\""));
    }

    /** 搜索页那个底必须在代码里取"按主题重建过"的那份(shape 走 themedDrawable,颜色/圆角都对) */
    @Test
    public void searchIndicatorBackgroundIsRebuiltForTheTheme() throws Exception {
        String src = read(SEARCH_ACTIVITY);
        assertTrue("搜索页来源列表选中项的底没有按主题重建(tabIndicator.indicatorDrawable):"
                        + "自定义主题下它会停在编译期的 bg_surface",
                matches(src, "tabIndicator\\s*\\.\\s*indicatorDrawable\\s*=\\s*[^=]{0,240}?"
                        + "ThemeDrawables\\.themedDrawable\\(\\s*R\\.drawable\\.bg_small_round_float"));
        assertTrue("搜索页来源列表的文字色不在代码里按主题取了(口径被挪走了?)",
                matches(src, "tabSelectColor\\s*=\\s*[^=]{0,240}?R\\.color\\.text_foreground"));
    }
}

package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * "主题生效"这条链路的源码级绊线(纯 JVM;真机行为由用户人工验证)。
 *
 * <p>钉住两条踩过/易踩的坑:
 * <ul>
 *   <li><b>换肤快照必须重解析</b>:"切主题后重启应用"走的是带标志重载主页,进程没重启,
 *       而换肤层读的是进程启动那一刻的快照 —— 不刷新就会出现"资源已经夜间、界面还按白天那套画"
 *       (跟随系统 + 只配了暗色默认主题时最明显:换肤层压根没介入);</li>
 *   <li><b>「默认主题」只服务「跟随系统」</b>:显式选浅色/深色必须直接落到内置,
 *       否则给某主题设过默认之后,内置那套再也选不回来。</li>
 * </ul>
 */
public class ThemeRuntimeRefreshContractTest {

    private static String read(String... relativeCandidates) throws Exception {
        for (String c : relativeCandidates) {
            File f = new File(c);
            if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        throw new AssertionError("找不到源文件:" + relativeCandidates[0]);
    }

    private static String methodBody(String src, String signature) {
        int i = src.indexOf(signature);
        assertTrue("签名不见了(改名了?绊线要跟着改):" + signature, i > 0);
        int open = src.indexOf('{', i);
        int depth = 0;
        for (int k = open; k < src.length(); k++) {
            char c = src.charAt(k);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(open, k + 1);
            }
        }
        throw new AssertionError("方法体没有闭合:" + signature);
    }

    private static String utils() throws Exception {
        return read("src/main/java/com/github/tvbox/osc/util/Utils.java",
                "../app/src/main/java/com/github/tvbox/osc/util/Utils.java");
    }

    /** 主题生效那一步(夜间模式在这里定)必须顺带重解析换肤快照 */
    @Test
    public void initThemeAlsoRefreshesTheRuntimePalette() throws Exception {
        String body = methodBody(utils(), "public static void initTheme()");
        assertTrue("initTheme 必须调 ThemeRuntime.refresh(),否则同进程换主题后仍按旧调色板画",
                body.contains("ThemeRuntime.refresh()"));
    }

    /** 快照刷新要早于 Resources 包装:包装与否取决于"当前有没有在介入" */
    @Test
    public void activityRefreshesBeforeWrappingResources() throws Exception {
        String src = read("src/main/java/com/github/tvbox/osc/base/BaseActivity.java",
                "../app/src/main/java/com/github/tvbox/osc/base/BaseActivity.java");
        String body = methodBody(src, "protected void attachBaseContext(Context newBase)");
        assertTrue("attachBaseContext 要先刷新快照:" + body, body.contains("ThemeRuntime.refresh()"));
        assertTrue("必须在 wrap 之前刷(否则这次创建的 Activity 整轮沿用旧快照)",
                body.indexOf("ThemeRuntime.refresh()") < body.indexOf("ThemeContextWrapper.wrap("));
    }

    /** 「跟随系统」下手机翻明暗不重启进程:应用级配置变化要重解析快照与主题背景 */
    @Test
    public void appConfigChangeRefreshesThemeAndBackground() throws Exception {
        String src = read("src/main/java/com/github/tvbox/osc/base/App.java",
                "../app/src/main/java/com/github/tvbox/osc/base/App.java");
        String body = methodBody(src, "public void onConfigurationChanged(");
        assertTrue("配置变化要重解析换肤快照", body.contains("ThemeRuntime.refresh()"));
        assertTrue("明暗两套默认主题可以配不同背景,背景也要跟着同步",
                body.contains("applyActiveBackground()"));
    }

    /** 「默认主题」只在跟随系统时参与解析 */
    @Test
    public void onlyFollowSystemConsultsTheDefaultTheme() throws Exception {
        String src = read("../core-storage/src/main/java/com/github/tvbox/osc/storage/theme/ThemeStore.java",
                "core-storage/src/main/java/com/github/tvbox/osc/storage/theme/ThemeStore.java");
        String body = methodBody(src, "public static ThemeDef resolveActive()");
        assertTrue("非「跟随系统」模式必须直接落到内置(否则显式选的浅色/深色被默认主题劫持)",
                body.contains("s.mode != Selection.MODE_FOLLOW_SYSTEM"));
        assertTrue("默认主题只能在跟随系统分支被取用",
                body.indexOf("s.mode != Selection.MODE_FOLLOW_SYSTEM") < body.indexOf("defaultIdOf("));
    }
}

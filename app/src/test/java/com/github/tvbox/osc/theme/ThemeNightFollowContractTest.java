package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 「跟随系统」翻明暗这条链路的源码级绊线(纯 JVM;真机行为由用户人工验证)。
 *
 * <p>钉住的是<b>用户看得见的那半个问题</b>:主页/直播/详情在清单里声明了 {@code uiMode},
 * 系统<b>不会重建</b>它们;而内置主题的颜色是 inflate 那一刻从 {@code values/values-night} 取回的
 * <b>资源</b> —— 光重解析换肤快照(自定义主题那条通道)救不了已经画出来的视图:
 * 底栏、卡片、状态栏图标、弹窗深浅全停在翻明暗之前那一套,观感就是"跟随系统没生效"。
 * 所以这三类页面必须<b>自己重建一次</b>,且只在"跟随系统"模式下做。
 */
public class ThemeNightFollowContractTest {

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

    private static String baseActivity() throws Exception {
        return read("src/main/java/com/github/tvbox/osc/base/BaseActivity.java",
                "../app/src/main/java/com/github/tvbox/osc/base/BaseActivity.java");
    }

    /** "由系统说了算"的判据必须同时要求:模式=跟随系统 + 没选自定义主题 */
    @Test
    public void followsSystemAlsoRequiresNoCustomTheme() throws Exception {
        String src = read("src/main/java/com/github/tvbox/osc/theme/ThemeRuntime.java",
                "../app/src/main/java/com/github/tvbox/osc/theme/ThemeRuntime.java");
        String body = methodBody(src, "public static boolean followsSystem()");
        assertTrue("跟随系统模式才由系统明暗决定:" + body,
                body.contains("MODE_FOLLOW_SYSTEM"));
        assertTrue("选了自定义主题时它的类型就是答案,系统翻明暗不该改界面:" + body,
                body.contains("customId.isEmpty()"));
    }

    /** 主页/直播/详情声明了 uiMode(系统不重建),所以必须自己重建一次 */
    @Test
    public void configChangeRecreatesWhenFollowingSystem() throws Exception {
        String src = baseActivity();
        String body = methodBody(src, "public void onConfigurationChanged(");
        assertTrue("配置变化要走明暗判定:" + body, body.contains("handleSystemNightChange("));

        String handler = methodBody(src, "private void handleSystemNightChange(");
        assertTrue("只有跟随系统才重建(显式浅色/深色、自定义主题不受系统影响):" + handler,
                handler.contains("ThemeRuntime.followsSystem()"));
        assertTrue("播放中的页面可以不重建:" + handler,
                handler.contains("allowRecreateOnNightChange()"));
        assertTrue("挡下的重建要记下来,等空闲补做:" + handler,
                handler.contains("nightRecreatePending"));

        String recreate = methodBody(src, "private void recreateForSystemNight()");
        assertTrue("最终必须重建:内置主题的颜色只在 inflate 那一刻取一次:" + recreate,
                recreate.contains("recreate()"));
    }

    /** 挡下的重建要在回到前台时补上,否则那页会一直停在翻明暗之前那套色 */
    @Test
    public void pendingRecreateIsAppliedOnResume() throws Exception {
        String body = methodBody(baseActivity(), "protected void onResume()");
        assertTrue("onResume 要补做被挡下的重建:" + body, body.contains("nightRecreatePending"));
        assertTrue("补做时照样要确认还在跟随系统:" + body,
                body.contains("ThemeRuntime.followsSystem()"));
    }

    /** 播放中的两页不许被重建掉(正在看的片子/直播不能被翻明暗打断) */
    @Test
    public void playerScreensRefuseRecreateWhilePlaying() throws Exception {
        for (String[] paths : new String[][]{
                {"src/main/java/com/github/tvbox/osc/ui/activity/DetailActivity.java",
                        "../app/src/main/java/com/github/tvbox/osc/ui/activity/DetailActivity.java"},
                {"src/main/java/com/github/tvbox/osc/ui/activity/LiveActivity.java",
                        "../app/src/main/java/com/github/tvbox/osc/ui/activity/LiveActivity.java"}}) {
            String src = read(paths);
            String body = methodBody(src, "protected boolean allowRecreateOnNightChange()");
            assertTrue("播放中的页面要按 isPlaying 拒绝重建:" + paths[0], body.contains("isPlaying()"));
        }
    }

    /** 判据前提:这三个页面确实声明了 uiMode(系统不会替我们重建),绊线失效时这里会红 */
    @Test
    public void swallowedActivitiesStillDeclareUiMode() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml", "../app/src/main/AndroidManifest.xml");
        for (String act : new String[]{".ui.activity.MainActivity", ".ui.activity.LiveActivity",
                ".ui.activity.DetailActivity"}) {
            int i = manifest.indexOf("\"" + act + "\"");
            assertTrue("清单里找不到 " + act, i > 0);
            int start = manifest.lastIndexOf("<activity", i);
            int end = manifest.indexOf("/>", i);          // 全是自闭合标签,没有 </activity>
            assertTrue("标签没闭合:" + act, start > 0 && end > start);
            String block = manifest.substring(start, end);
            assertTrue(act + " 不再声明 uiMode:系统会自己重建它,BaseActivity 里那条重建逻辑要跟着复查",
                    block.contains("uiMode"));
        }
    }
}

package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 换肤装配顺序的源码级绊线(纯 JVM;真机观感由用户人工验证)。
 *
 * <p>为什么钉成断言:布局里的 {@code @color/xxx} 是系统在 native 侧解析的,运行时唯一能改它的通道
 * 就是 {@link ThemeInflaterFactory}(在视图刚创建时按调色板重设属性)。而它<b>只能在视图被创建之前装到
 * LayoutInflater 上</b> —— {@code BaseActivity.onCreate} 里内容布局是先 inflate 的
 * ({@code setContentView} / ViewBinding 的 {@code initVb()}),注入器装晚一步,
 * <b>Activity 自己的那层布局就整个不参与换肤</b>:设置页、主题编辑页、详情页、搜索页……
 * 全都保持内置配色,用户看到的就是"自定义主题没效果"。
 *
 * <p>这类顺序问题不会让任何一次编译或运行失败(界面只是"没变色"),所以只能靠断言守住。
 */
public class ThemeSkinInstallOrderTest {

    private static final String BASE_ACTIVITY = "app/src/main/java/com/github/tvbox/osc/base/BaseActivity.java";

    private static File repoRoot() {
        // 单测工作目录 = :app 模块目录,仓库根在上一级
        File f = new File("..");
        return f.exists() ? f : new File(".");
    }

    private static String read(String relative) throws Exception {
        File f = new File(repoRoot(), relative);
        assertTrue("找不到源文件:" + relative + "(工作目录=" + new File(".").getAbsolutePath() + ")",
                f.isFile());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void inflaterInjectionIsInstalledBeforeContentViewIsInflated() throws Exception {
        String src = read(BASE_ACTIVITY);
        int install = src.indexOf("ThemeRuntime.installInflaterFactory(");
        int applyTo = src.indexOf("ThemeRuntime.applyTo(");
        int setContentView = src.indexOf("setContentView(getLayoutResID())");
        int initVb = src.indexOf("initVb();");

        assertTrue("BaseActivity 必须在 onCreate 里装布局注入器", install > 0);
        assertTrue("BaseActivity 必须设置窗口底色(applyTo)", applyTo > 0);
        assertTrue("解析不到 setContentView 调用,断言该更新了", setContentView > 0);
        assertTrue("解析不到 initVb 调用,断言该更新了", initVb > 0);

        assertTrue("窗口底色必须在内容布局 inflate 之前设置(否则页面根节点会盖住它)",
                applyTo < setContentView);
        assertTrue("布局注入器必须在 setContentView 之前装好 —— 否则 Activity 自己的布局不吃主题"
                        + "(用户口径:\"我自定义的主题没效果\")",
                install < setContentView);
        assertTrue("布局注入器必须在 ViewBinding 的 initVb() 之前装好,否则 ViewBinding 页面不吃主题",
                install < initVb);
    }

    @Test
    public void paletteSnapshotIsInstalledBeforeAnyActivityStarts() throws Exception {
        // 进程启动时就要解析出"这次该按哪套颜色画",否则第一个 Activity 的 attachBaseContext
        // 会拿到"未装配"(ThemeRuntime.active()=false)→ 连 Resources 包装都不做
        String app = read("app/src/main/java/com/github/tvbox/osc/base/App.java");
        assertTrue("App 启动阶段必须装配换肤快照(ThemeRuntime.install)",
                app.contains("ThemeRuntime.install()"));
    }
}

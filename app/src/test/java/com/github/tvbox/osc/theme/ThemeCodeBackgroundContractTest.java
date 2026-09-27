package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * "代码里设底"的源码级绊线(纯 JVM;真机观感由用户人工验证)。
 *
 * <p>为什么钉成断言:布局里的 {@code android:background="@drawable/x"} 会被换肤注入改成主题底,
 * 但代码里再 {@code View.setBackgroundResource(R.drawable.x)} 一次,是**按编译期资源取 drawable**,
 * 等于把主题底又覆盖回内置色。这类覆盖不会编译报错、也不崩,界面只是"这块永远不变色" ——
 * 用户口径就是"透明度和卡片背景还是没生效"(实际上真凶:所有底部弹窗都在
 * {@code AppBottomPopupView} 里被这么覆盖了一次)。
 *
 * <p>规则:凡是在代码里设 {@code R.drawable.X} 且 X 这份 drawable 自身**引用了主题色**的,
 * 必须走 {@link ThemeDrawables#themedDrawable(int, android.content.res.Resources)};
 * 只有不含主题色的底(纯 ripple/纯黑这类)可以直接 {@code setBackgroundResource}。
 */
public class ThemeCodeBackgroundContractTest {

    /** 主题概念色的资源名前缀:drawable 里引用到这些,就说明它是"随主题走的底" */
    private static final String[] THEMED_PREFIXES = {
            "@color/bg_", "@color/text_", "@color/btn_", "@color/switch_",
            "@color/download_", "@color/select_fill", "@color/color_highlight",
            "@color/colorPrimary", "@color/md_", "@color/gray_darker",
    };

    /** 允许直接 setBackgroundResource 的 drawable(不含主题色的纯交互底) */
    private static final String[] ALLOW = {
            "ripple_round_background", "selectable_item_background", "ripple_",
    };

    private static File repoRoot() {
        File f = new File("..");
        return f.exists() ? f : new File(".");
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static List<File> sources() {
        List<File> out = new ArrayList<>();
        collect(new File(repoRoot(), "app/src/main/java"), out);
        return out;
    }

    private static void collect(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collect(f, out);
            } else if (f.getName().endsWith(".java") || f.getName().endsWith(".kt")) {
                out.add(f);
            }
        }
    }

    /** 这份 drawable 自身是否引用主题色 */
    private static boolean themed(String resIdName) throws Exception {
        File xml = new File(repoRoot(), "app/src/main/res/drawable/" + resIdName + ".xml");
        if (!xml.isFile()) return false; // 位图/不存在:没有主题色可言
        String text = read(xml);
        for (String p : THEMED_PREFIXES) {
            if (text.contains(p)) return true;
        }
        return false;
    }

    private static boolean allowed(String name) {
        for (String a : ALLOW) {
            if (name.startsWith(a)) return true;
        }
        return false;
    }

    @Test
    public void codeSetBackgroundOfThemedDrawableGoesThroughThemedDrawable() throws Exception {
        List<String> bad = new ArrayList<>();
        for (File f : sources()) {
            String text = read(f);
            if (!text.contains("setBackgroundResource(")) continue;
            int idx = 0;
            while ((idx = text.indexOf("setBackgroundResource(", idx)) >= 0) {
                idx += "setBackgroundResource(".length();
                int end = text.indexOf(')', idx);
                if (end < 0) break;
                String arg = text.substring(idx, end).trim();
                int r = arg.indexOf("R.drawable.");
                if (r >= 0 && !arg.contains("ThemeDrawables")) {
                    String name = arg.substring(r + "R.drawable.".length()).trim();
                    if (!allowed(name) && themed(name)) {
                        bad.add(f.getName() + " → setBackgroundResource(" + arg + ")");
                    }
                }
            }
        }
        if (!bad.isEmpty()) {
            fail("以下" + bad.size() + "处在代码里直接设了\"随主题走的底\",会把换肤注入的主题底覆盖回内置色,\n"
                    + "请改用 ThemeDrawables.themedDrawable(resId, view.getResources()):\n  - "
                    + String.join("\n  - ", bad));
        }
        assertTrue(true);
    }
}

package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 共享 style 必须通过框架解析结果接入主题，禁止恢复手写覆盖表。 */
public class ThemeStyleCoverageTest {

    private static File file(String relative) {
        File direct = new File(relative);
        return direct.isFile() ? direct : new File("../app/" + relative);
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(file(relative).toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void sharedStylesUseResolvedFrameworkAttributes() throws Exception {
        String inflater = read("src/main/java/com/github/tvbox/osc/theme/ThemeInflaterFactory.java");
        assertTrue(inflater.contains("context.obtainStyledAttributes(attrs, resolvedAttrs)"));
        assertTrue(inflater.contains("android.R.attr.textColor"));
        assertTrue(inflater.contains("android.R.attr.background"));
        assertFalse("不得恢复手写 ThemeStyles 覆盖表", new File(
                file("src/main/java/com/github/tvbox/osc/theme/ThemeInflaterFactory.java").getParentFile(),
                "ThemeStyles.java").exists());
    }

    @Test
    public void threeButtonKindsPointAtGeneratedRecipes() throws Exception {
        String styles = read("src/main/res/values/styles.xml");
        assertTrue(styles.contains("@drawable/theme_btn_primary"));
        assertTrue(styles.contains("@drawable/theme_btn_secondary"));
        assertTrue(styles.contains("@drawable/theme_btn_ghost"));
    }
}

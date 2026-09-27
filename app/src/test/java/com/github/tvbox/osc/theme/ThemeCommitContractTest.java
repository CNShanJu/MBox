package com.github.tvbox.osc.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 主题弹窗"关闭时提交"的源码级绊线(纯 JVM;真机行为由用户人工验证)。
 *
 * <p>钉住的是一条**踩过一次的坑**:编辑页(另一个 Activity)会直接改落盘状态 ——
 * 最典型的是"删掉了正在使用的主题",删除逻辑会把选中项切回该类型的浅色/深色。
 * 如果弹窗关闭时把手头那份**打开时的旧草稿整份写回**,就会把这次修正覆盖掉
 * (用户看到的是"删了暗色主题,却落到了浅色"),而这类问题不会报错、只会"结果不对"。
 *
 * <p>另外钉住"重启判定":只有**生效配色真的变了**才值得重启应用 ——
 * 删一个没在用的主题、新建一个还没选中的主题,重启纯属白等一次。
 */
public class ThemeCommitContractTest {

    private static String picker() throws Exception {
        String[] candidates = {
                "src/main/java/com/github/tvbox/osc/ui/dialog/ThemePickerDialog.java",
                "../app/src/main/java/com/github/tvbox/osc/ui/dialog/ThemePickerDialog.java",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        throw new AssertionError("找不到 ThemePickerDialog.java");
    }

    /** 取出 commitIfDirty 的方法体,避免把别处的调用算进来 */
    private static String commitBody(String src) {
        int i = src.indexOf("public boolean commitIfDirty()");
        assertTrue("commitIfDirty 不见了(改名了?绊线要跟着改)", i > 0);
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
        throw new AssertionError("commitIfDirty 方法体没有闭合");
    }

    @Test
    public void commitAppliesDeletesBeforeWritingTheDraftBack() throws Exception {
        String body = commitBody(picker());
        int deleteAt = body.indexOf("ThemeStore.delete(");
        int commitAt = body.indexOf("draft.commit()");
        assertTrue("提交里必须真的去删主题", deleteAt > 0);
        assertTrue("必须先把删除落盘(它会顺带修正选中项/默认项)", commitAt > deleteAt);
    }

    @Test
    public void commitUsesPersistedStateAsTheBaseForUntouchedFields() throws Exception {
        String body = commitBody(picker());
        assertTrue("用户没在列表里点过选择时,必须以落盘状态为底(否则会覆盖删除顺带做的修正):"
                + "缺少 !selectionTouched 分支", body.contains("!selectionTouched"));
        assertTrue("同上,\"默认主题\"也要以落盘状态为底", body.contains("!defaultTouched"));
        assertTrue("要先读回落盘状态", body.contains("ThemeStore.selection()"));
    }

    @Test
    public void restartDecisionComesFromTheActivePaletteFingerprint() throws Exception {
        String body = commitBody(picker());
        assertTrue("重启判定要看\"生效配色指纹\"(删没在用的主题/新建未选中的主题不该白重启)",
                body.contains("activePaletteFingerprint()"));
        assertTrue("背景是立刻生效的,不受内存调色板限制:提交里要同步一次默认背景",
                body.contains("applyActiveBackground()"));
    }

    @Test
    public void touchedFieldsAreRecordedAtTheInteractionPoints() throws Exception {
        String src = picker();
        assertTrue("点列表项要记下\"用户动过选择\"", src.contains("selectionTouched = true"));
        assertTrue("设为默认要记下\"用户动过默认\"", src.contains("defaultTouched = true"));
        assertFalse("指纹要在打开弹窗那一刻取,不能在提交时才取",
                src.contains("openFingerprint = \"\"")
                        && !src.contains("openFingerprint = ThemeStore.activePaletteFingerprint()"));
    }
}

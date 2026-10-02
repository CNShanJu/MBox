package com.github.tvbox.osc.update.github;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** ReleaseNotes(发版正文清洗 + 跨版本说明汇总)单测:纯文本规则,不依赖 Android。 */
public class ReleaseNotesTest {

    @Test
    public void plainBody_keptAsIs() {
        assertEquals("- 修复播放闪退\n- 新增倍速", ReleaseNotes.userFacing("\n- 修复播放闪退\n- 新增倍速\n"));
    }

    @Test
    public void devFencedBlock_removed() {
        String body = "## 新增\n- 离线下载\n\n<!-- dev -->\n内部:重构爬虫线程模型\n替换 exo 依赖\n<!-- /dev -->\n\n## 修复\n- 播放 401";
        String out = ReleaseNotes.userFacing(body);
        assertFalse(out.contains("爬虫线程"));
        assertFalse(out.contains("exo"));
        assertTrue(out.contains("离线下载"));
        assertTrue(out.contains("播放 401"));
    }

    @Test
    public void devFence_alsoSupportsChineseAndUnclosedMarker() {
        String out = ReleaseNotes.userFacing("用户可见\n<!-- 内部 -->\n提交号 abc123\n打包脚本调整");
        assertEquals("用户可见", out);
    }

    /**
     * 围栏词大小写不敏感(AGENTS §九 约定):客户端必须与 scripts/check-release-notes.js 同规则
     * —— 脚本原来漏了 i 标志,`&lt;!-- DEV --&gt;` 客户端会剔除、脚本却把它当用户可见内容报错。
     */
    @Test
    public void devFence_isCaseInsensitive() {
        assertEquals("用户可见", ReleaseNotes.userFacing(
                "用户可见\n<!-- DEV -->\n依赖重构,门禁调整\n<!-- /DEV -->"));
        assertEquals("用户可见", ReleaseNotes.userFacing(
                "用户可见\n<!-- Internal -->\n提交号 abc1234\n<!-- /internal -->"));
        // 同一行成对写出时同样大小写不敏感
        assertFalse(ReleaseNotes.userFacing("A\n<!-- Dev -->内部:依赖升级<!-- /Dev -->\nB").contains("依赖升级"));
    }

    @Test
    public void htmlComments_droppedEvenWithoutFence() {
        assertEquals("正文", ReleaseNotes.userFacing("<!-- 备注 -->正文"));
    }

    @Test
    public void wholeBodyIsDevContent_becomesEmpty() {
        assertEquals("", ReleaseNotes.userFacing("<!-- dev -->\n只给开发者看\n<!-- /dev -->"));
        assertEquals("", ReleaseNotes.userFacing(null));
    }

    @Test
    public void singleVersion_noteKeptWithoutHeading() {
        String out = ReleaseNotes.aggregate(Collections.singletonList(
                new ReleaseNotes.Note("3.4.5", "## 修复\n- 详情页空白")), false);
        assertEquals("## 修复\n- 详情页空白", out);
    }

    /** 单版本(最常走:跟版升级/ debug 自测)也要去掉正文自带的版本标题与"相比 vX 的更新:"引言 */
    @Test
    public void singleVersion_dropsOwnVersionHeadingAndIntro() {
        String out = ReleaseNotes.aggregate(Collections.singletonList(
                new ReleaseNotes.Note("3.5.5", "## v3.5.5\n\n相比 v3.5.4 的更新:\n- 修复播放闪退")), false);
        assertEquals("- 修复播放闪退", out);
        assertFalse(out.contains("## v3.5.5"));
        assertFalse(out.contains("相比"));
    }

    /** 旧版 CI 会在手写说明前再加一次标题，纯内部版也会剩一行孤立标题。 */
    @Test
    public void workflowPrefixedNotes_doNotDuplicateHeadingOrCountDevOnlyVersion() {
        String newest = "## v3.6.4\n\n## v3.6.4\n\n**新增**\n- 莲花背景";
        String devOnly = "## v3.6.3\n\n<!-- dev -->\n发包流程调整\n<!-- /dev -->";
        String out = ReleaseNotes.aggregate(Arrays.asList(
                new ReleaseNotes.Note("3.6.4", newest),
                new ReleaseNotes.Note("3.6.3", devOnly)), false);
        assertEquals("**新增**\n- 莲花背景", out);
        assertFalse(out.contains("本次升级跨"));
        assertFalse(out.contains("v3.6.4"));
    }

    @Test
    public void workflowPrefixedDevOnlyNote_hasNoUserFacingContent() {
        String body = "## v3.6.3\n\n<!-- dev -->\n发包流程调整\n<!-- /dev -->";
        assertEquals("", ReleaseNotes.aggregate(Collections.singletonList(
                new ReleaseNotes.Note("3.6.3", body)), false));
    }

    /** 没有版本标题、只写了引言时,引言同样要去掉 */
    @Test
    public void singleVersion_dropsIntroWithoutHeading() {
        String out = ReleaseNotes.aggregate(Collections.singletonList(
                new ReleaseNotes.Note("3.5.6", "相比 3.5.5 的更新:\n- 新增投屏")), false);
        assertEquals("- 新增投屏", out);
    }

    /** 行内"提到"围栏写法不算围栏:不能把其后用户可见内容一起吞掉 */
    @Test
    public void inlineFenceMention_doesNotSwallowFollowingContent() {
        String out = ReleaseNotes.userFacing("- 约定:内部内容用 <!-- dev --> 包起来\n- 这条要保留");
        assertTrue("行内提及后的内容被吞了: " + out, out.contains("- 这条要保留"));
        assertFalse(out.contains("<!--"));
    }

    /** 围栏独占一行时仍然整段剔除(含未闭合) */
    @Test
    public void fenceOnOwnLine_stillHidesBlock() {
        assertEquals("- 用户可见",
                ReleaseNotes.userFacing("- 用户可见\n  <!-- dev -->\n提交号 abc123\n<!-- /dev -->"));
        assertEquals("- 用户可见",
                ReleaseNotes.userFacing("- 用户可见\n<!-- dev -->\n提交号 abc123"));
    }

    @Test
    public void crossVersion_sectionsNewestFirstWithIntro() {
        List<ReleaseNotes.Note> notes = Arrays.asList(
                new ReleaseNotes.Note("3.4.5", "## 3.4.5\n\n相比 3.4.4 的更新:\n- 新增离线下载"),
                new ReleaseNotes.Note("3.4.4", "<!-- dev -->\n纯内部重构\n<!-- /dev -->"),
                new ReleaseNotes.Note("3.4.3", "## 3.4.3\n\n相比 3.4.2 的更新:\n- 修复闪退"));
        String out = ReleaseNotes.aggregate(notes, false);
        assertTrue(out.contains("本次升级包含 2 个版本的更新说明"));
        assertFalse(out.contains("v3.4.3 → v3.4.5"));
        assertTrue(out.indexOf("## v3.4.5") < out.indexOf("## v3.4.3"));
        assertFalse(out.contains("纯内部重构"));
        // 正文自带的版本标题与"相比 vX 的更新"引言不再重复
        assertEquals(1, count(out, "## v3.4.5"));
        assertEquals(1, count(out, "## v3.4.3"));
        assertFalse(out.contains("## 3.4.5"));
        assertFalse(out.contains("相比"));
        assertTrue(out.contains("- 修复闪退"));
    }

    private static int count(String text, String token) {
        int n = 0, from = 0;
        while (true) {
            int i = text.indexOf(token, from);
            if (i < 0) return n;
            n++;
            from = i + token.length();
        }
    }

    @Test
    public void otherVersionHeading_notMistakenlyDropped() {
        String out = ReleaseNotes.aggregate(Arrays.asList(
                new ReleaseNotes.Note("3.4.5", "## 新增功能\n- 投屏"),
                new ReleaseNotes.Note("3.4.4", "- 修复")), false);
        assertTrue(out.contains("## 新增功能"));
    }

    @Test
    public void truncated_hintShown() {
        List<ReleaseNotes.Note> notes = Arrays.asList(
                new ReleaseNotes.Note("3.4.9", "- a"),
                new ReleaseNotes.Note("3.4.8", "- b"));
        assertTrue(ReleaseNotes.aggregate(notes, true).contains("以下展示最近的说明"));
    }

    @Test
    public void allBodiesDevOnly_noteIsEmpty() {
        assertEquals("", ReleaseNotes.aggregate(Collections.singletonList(
                new ReleaseNotes.Note("3.4.5", "<!-- dev -->x<!-- /dev -->")), false));
    }
}

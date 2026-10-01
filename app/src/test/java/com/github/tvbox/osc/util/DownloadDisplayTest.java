package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.download.ArchiveItem;
import com.github.tvbox.osc.download.DownloadFacade;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** DownloadDisplay(下载完成列表展示纯工具)单测:格式化/剧集名/清晰度/指纹/删除 */
public class DownloadDisplayTest {

    private static ArchiveItem item(String episodeName, String episodeId) {
        ArchiveItem it = new ArchiveItem();
        it.episodeName = episodeName;
        it.episodeId = episodeId;
        return it;
    }

    @Test
    public void formatSize_units() {
        assertEquals("0KB", DownloadDisplay.formatSize(0));
        assertEquals("1KB", DownloadDisplay.formatSize(1024));
        assertEquals("1023KB", DownloadDisplay.formatSize(1024 * 1024 - 1));
        assertEquals("1MB", DownloadDisplay.formatSize(1024 * 1024));
        assertEquals("1023MB", DownloadDisplay.formatSize(1024L * 1024 * 1024 - 1));
        assertEquals("1.00GB", DownloadDisplay.formatSize(1024L * 1024 * 1024));
    }

    @Test
    public void formatSpeed_units() {
        assertEquals("512B/s", DownloadDisplay.formatSpeed(512));
        assertEquals("1KB/s", DownloadDisplay.formatSpeed(1024));
        assertEquals("1024KB/s", DownloadDisplay.formatSpeed(1024 * 1024 - 1));
        assertEquals("1.0MB/s", DownloadDisplay.formatSpeed(1024 * 1024));
        assertEquals("2.5MB/s", DownloadDisplay.formatSpeed((long) (2.5 * 1024 * 1024)));
    }

    @Test
    public void lastSegment_splitsOnPipe() {
        assertEquals("3", DownloadDisplay.lastSegment("src|vod|3"));
        assertNull(DownloadDisplay.lastSegment("no-pipe"));
        assertNull(DownloadDisplay.lastSegment(""));
        assertNull(DownloadDisplay.lastSegment(null));
    }

    @Test
    public void episodeTitleOf_prefersEpisodeNameAndStripsResolution() {
        assertEquals("第1集", DownloadDisplay.episodeTitleOf(item("第1集_720P", "s|v|1"), "any.mp4"));
        assertEquals("第2集", DownloadDisplay.episodeTitleOf(item(null, "s|v|2"), "any.mp4"));
        // 无 episodeName/episodeId 数字段:回退文件名(去扩展名)
        assertEquals("movie", DownloadDisplay.episodeTitleOf(item(null, null), "movie.mkv"));
        // 保留非清晰度文本
        assertEquals("正片", DownloadDisplay.episodeTitleOf(item("正片_4K", null), "any.mp4"));
    }

    @Test
    public void resolutionOf_parsesLastResolutionSegment() {
        assertEquals("720P", DownloadDisplay.resolutionOf(item("第1集_720P", null), "any.mp4"));
        assertEquals("4K", DownloadDisplay.resolutionOf(item("正片_4K", null), "any.mp4"));
        // episodeName 空时从文件名解析
        assertEquals("1080P", DownloadDisplay.resolutionOf(item(null, null), "movie.1080p.mkv"));
        // 均无清晰度 -> null
        assertNull(DownloadDisplay.resolutionOf(item("第1集", null), "movie.mkv"));
        assertNull(DownloadDisplay.resolutionOf(item(null, null), "movie.mkv"));
    }

    @Test
    public void doneSignature_joinsPathAndSize() {
        List<VideoInfo> files = new ArrayList<>();
        VideoInfo a = new VideoInfo();
        a.setPath("/a/b.mp4");
        a.setSize(100);
        files.add(a);
        VideoInfo b = new VideoInfo();
        b.setPath("/c/d.mkv");
        b.setSize(200);
        files.add(b);
        assertEquals("/a/b.mp4|100;/c/d.mkv|200;", DownloadDisplay.doneSignature(files));
        assertEquals("", DownloadDisplay.doneSignature(new ArrayList<>()));
    }

    @Test
    public void deleteRecursive_removesNested() throws Exception {
        File root = java.nio.file.Files.createTempDirectory("dl-display-test").toFile();
        File nested = new File(root, "nested");
        assertTrue(nested.mkdirs());
        File f1 = new File(nested, "1.txt");
        File f2 = new File(root, "2.txt");
        assertTrue(f1.createNewFile());
        assertTrue(f2.createNewFile());
        DownloadDisplay.deleteRecursive(root);
        assertFalse(root.exists());
        // null / 不存在 不抛
        DownloadDisplay.deleteRecursive(null);
        DownloadDisplay.deleteRecursive(new File(root, "ghost"));
    }

    @Test
    public void buildPercentText_hlsAndBytesAndFail() {
        DownloadTask t = new DownloadTask();
        // HLS: totalSegments>0
        t.totalSegments = 10;
        t.doneSegments = 3;
        t.totalBytes = 1000L * 1024;
        t.downloadedBytes = 300L * 1024;
        assertEquals("分段 3/10  300KB/1000KB (30%)", DownloadDisplay.buildPercentText(t));

        DownloadTask bytes = new DownloadTask();
        bytes.totalBytes = 1024L * 1024;
        bytes.downloadedBytes = 512 * 1024;
        assertEquals("512KB/1MB (50%)", DownloadDisplay.buildPercentText(bytes));
    }

    @Test
    public void buildPercentText_failedAppendsReason() {
        DownloadTask t = new DownloadTask();
        t.totalBytes = 100;
        t.downloadedBytes = 0;
        t.state = DownloadTask.STATE_FAILED;
        t.message = "连接超时";
        String s = DownloadDisplay.buildPercentText(t);
        assertTrue(s, s.contains("失败:连接超时"));
    }

    @Test
    public void buildPercentText_hlsEstimateWhenTotalUnknown() {
        // m3u8 解析出分段数后:分段进度 + 预检估算大小
        DownloadTask t = new DownloadTask();
        t.totalSegments = 10;
        t.doneSegments = 3;
        t.estimatedBytes = 100L * 1024 * 1024;
        assertEquals("分段 3/10 · 约100MB (30%)", DownloadDisplay.buildPercentText(t));
        // 排队中(分段数未解析)也能看到约大小
        DownloadTask queued = new DownloadTask();
        queued.estimatedBytes = 1024L * 1024;
        assertEquals("约1MB (0%)", DownloadDisplay.buildPercentText(queued));
        // 无估算:输出与原实现一致
        DownloadTask none = new DownloadTask();
        assertEquals(" (0%)", DownloadDisplay.buildPercentText(none));
    }

    // ── 状态行文本/色调/阶段判定 ──

    @Test
    public void statusTextOf_mapsStates() {
        assertEquals("失败", DownloadDisplay.statusTextOf(state(DownloadTask.STATE_FAILED, null)));
        assertEquals("已暂停", DownloadDisplay.statusTextOf(state(DownloadTask.STATE_PAUSED, null)));
        assertEquals("网络中断", DownloadDisplay.statusTextOf(state(DownloadTask.STATE_NETWORK_PAUSED, null)));
        assertEquals("排队中", DownloadDisplay.statusTextOf(state(DownloadTask.STATE_SYSTEM_PAUSED, null)));
        assertEquals("等待中", DownloadDisplay.statusTextOf(state(DownloadTask.STATE_WAITING, null)));
        assertEquals("已取消", DownloadDisplay.statusTextOf(state(DownloadTask.STATE_CANCELLED, null)));
        assertEquals("下载中", DownloadDisplay.statusTextOf(state(DownloadTask.STATE_DOWNLOADING, null)));
        // 收尾阶段 message 原样
        assertEquals("文件合并中(45%)",
                DownloadDisplay.statusTextOf(state(DownloadTask.STATE_DOWNLOADING, DownloadFacade.MSG_MERGING + "(45%)")));
        assertEquals("补片中(剩3片)",
                DownloadDisplay.statusTextOf(state(DownloadTask.STATE_DOWNLOADING, DownloadFacade.MSG_REPAIRING + "(剩3片)")));
    }

    @Test
    public void statusToneOf_mapsTones() {
        assertEquals(DownloadDisplay.StatusTone.ERROR, DownloadDisplay.statusToneOf(state(DownloadTask.STATE_FAILED, null)));
        assertEquals(DownloadDisplay.StatusTone.MUTED, DownloadDisplay.statusToneOf(state(DownloadTask.STATE_PAUSED, null)));
        assertEquals(DownloadDisplay.StatusTone.MUTED, DownloadDisplay.statusToneOf(state(DownloadTask.STATE_NETWORK_PAUSED, null)));
        assertEquals(DownloadDisplay.StatusTone.MUTED, DownloadDisplay.statusToneOf(state(DownloadTask.STATE_SYSTEM_PAUSED, null)));
        assertEquals(DownloadDisplay.StatusTone.MUTED, DownloadDisplay.statusToneOf(state(DownloadTask.STATE_WAITING, null)));
        assertEquals(DownloadDisplay.StatusTone.MUTED, DownloadDisplay.statusToneOf(state(DownloadTask.STATE_CANCELLED, null)));
        assertEquals(DownloadDisplay.StatusTone.ACTIVE, DownloadDisplay.statusToneOf(state(DownloadTask.STATE_DOWNLOADING, null)));
    }
    @Test public void failureLoginPauseAndWifiQueueExposeTheirReason() {
        assertEquals("失败：磁盘空间不足", DownloadDisplay.statusTextOf(state(DownloadTask.STATE_FAILED, "磁盘空间不足")));
        assertEquals("已暂停：登录状态已失效", DownloadDisplay.statusTextOf(state(DownloadTask.STATE_PAUSED, "登录状态已失效")));
        assertEquals("已排队，等待 Wi-Fi", DownloadDisplay.statusTextOf(state(DownloadTask.STATE_WAITING, "已排队，等待 Wi-Fi")));
    }

    @Test
    public void stageAndSpeedJudgements() {
        // 合并/补片 message 自带进度
        assertTrue(DownloadDisplay.stageMessageOwnsProgress(DownloadFacade.MSG_MERGING));
        assertTrue(DownloadDisplay.stageMessageOwnsProgress(DownloadFacade.MSG_REPAIRING));
        assertFalse(DownloadDisplay.stageMessageOwnsProgress(DownloadFacade.MSG_VERIFYING));
        assertFalse(DownloadDisplay.stageMessageOwnsProgress(null));
        // 网速仅下载中且非收尾阶段
        DownloadTask dl = state(DownloadTask.STATE_DOWNLOADING, null);
        assertFalse(DownloadDisplay.shouldShowSpeed(dl)); // message null
        dl.message = "";
        assertTrue(DownloadDisplay.shouldShowSpeed(dl));   // 空串非 null:与原实现一致显示
        dl.message = "普通下载中";
        assertTrue(DownloadDisplay.shouldShowSpeed(dl));
        dl.message = DownloadFacade.MSG_MERGING;
        assertFalse(DownloadDisplay.shouldShowSpeed(dl));
        dl.state = DownloadTask.STATE_PAUSED;
        assertFalse(DownloadDisplay.shouldShowSpeed(dl));
    }

    private static DownloadTask state(int state, String msg) {
        DownloadTask t = new DownloadTask();
        t.state = state;
        t.message = msg;
        return t;
    }

    // ── 行文本拼装 ──

    @Test
    public void titleText_appendsEpisodeOnlyWhenDifferent() {
        DownloadTask t = state(DownloadTask.STATE_DOWNLOADING, null);
        t.vodName = "剧A";
        t.episodeName = null;
        assertEquals("剧A", DownloadDisplay.titleText(t));
        t.episodeName = "剧A";
        assertEquals("剧A", DownloadDisplay.titleText(t)); // 与剧名相同不追加
        t.episodeName = "第1集";
        assertEquals("剧A · 第1集", DownloadDisplay.titleText(t));
        t.vodName = null;
        t.episodeName = "";
        assertEquals("", DownloadDisplay.titleText(t));
    }

    @Test
    public void statusLine_composesStatusPercentSpeed() {
        DownloadTask t = state(DownloadTask.STATE_DOWNLOADING, "普通");
        t.totalBytes = 1024L * 1024;
        t.downloadedBytes = 512 * 1024;
        t.speed = 0;
        assertEquals("下载中 · 50%", DownloadDisplay.statusLine(t));
        t.speed = 1024 * 1024;
        assertEquals("下载中 · 50% · 1.0MB/s", DownloadDisplay.statusLine(t));
        // 收尾阶段(message 自带进度)省略整体百分比
        t.message = DownloadFacade.MSG_MERGING;
        t.speed = 0;
        assertEquals(DownloadFacade.MSG_MERGING, DownloadDisplay.statusLine(t));
    }

    @Test
    public void sourceText_and_swipeActionText() {
        assertEquals("来源 未知", DownloadDisplay.sourceText(null));
        assertEquals("来源 未知", DownloadDisplay.sourceText(""));
        assertEquals("来源 测试源", DownloadDisplay.sourceText("测试源"));
        assertEquals("继续", DownloadDisplay.swipeActionText(state(DownloadTask.STATE_PAUSED, null)));
        assertEquals("重试", DownloadDisplay.swipeActionText(state(DownloadTask.STATE_FAILED, null)));
        assertEquals("暂停", DownloadDisplay.swipeActionText(state(DownloadTask.STATE_DOWNLOADING, null)));
    }

    private static void assertTrue(boolean b) {
        org.junit.Assert.assertTrue(b);
    }

    private static void assertTrue(String msg, boolean b) {
        org.junit.Assert.assertTrue(msg, b);
    }

    private static void assertFalse(boolean b) {
        org.junit.Assert.assertFalse(b);
    }
}

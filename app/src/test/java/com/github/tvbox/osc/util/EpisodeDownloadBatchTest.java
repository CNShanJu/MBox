package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** EpisodeDownloadBatch 纯工具方法的 JVM 单测 */
public class EpisodeDownloadBatchTest {

    @Test
    public void resolutionLabel_mapsHeight() {
        assertEquals("4K", EpisodeDownloadBatch.resolutionLabel(new int[]{3840, 2160}));
        assertEquals("2K", EpisodeDownloadBatch.resolutionLabel(new int[]{2560, 1440}));
        assertEquals("1080P", EpisodeDownloadBatch.resolutionLabel(new int[]{1920, 1080}));
        assertEquals("720P", EpisodeDownloadBatch.resolutionLabel(new int[]{1280, 720}));
        assertEquals("480P", EpisodeDownloadBatch.resolutionLabel(new int[]{854, 640}));
        // 低于 500 高度无法归类(实现阈值),返回 null
        assertEquals(null, EpisodeDownloadBatch.resolutionLabel(new int[]{480, 360}));
    }

    @Test
    public void resolutionLabel_unknownReturnsNull() {
        assertEquals(null, EpisodeDownloadBatch.resolutionLabel(null));
        assertEquals(null, EpisodeDownloadBatch.resolutionLabel(new int[]{1920}));
        assertEquals(null, EpisodeDownloadBatch.resolutionLabel(new int[]{0, 0}));
    }

    @Test
    public void containsResolution_detectsLabelInName() {
        assertTrue(EpisodeDownloadBatch.containsResolution("第01集_1080P"));
        assertTrue(EpisodeDownloadBatch.containsResolution("正片4K版"));
        assertFalse(EpisodeDownloadBatch.containsResolution("第01集"));
        assertFalse(EpisodeDownloadBatch.containsResolution(null));
    }

    @Test
    public void resolveEpisodeId_keepsProvided() {
        assertEquals("ep-123",
                EpisodeDownloadBatch.resolveEpisodeId("src", "vod", "f", null, "第一集", "ep-123"));
    }

    @Test
    public void playbackUrlReuseRequiresEpisodeIdEvenForDuplicateNames() {
        assertTrue(EpisodeDownloadBatch.mayReusePlaybackUrl("Episode", "line-a-1", "Episode", "line-a-1"));
        assertFalse(EpisodeDownloadBatch.mayReusePlaybackUrl("Episode", "line-a-2", "Episode", "line-a-1"));
        assertFalse(EpisodeDownloadBatch.mayReusePlaybackUrl("Episode", "line-b-1", "Episode", "line-a-1"));
        assertFalse(EpisodeDownloadBatch.mayReusePlaybackUrl("Episode", null, "Episode", "line-a-1"));
    }

    @Test
    public void countEnqueueOutcome_classifies() {
        EpisodeDownloadBatch.Outcome out = new EpisodeDownloadBatch.Outcome();
        EpisodeDownloadBatch.countEnqueueOutcome(true, 1, out);      // added
        EpisodeDownloadBatch.countEnqueueOutcome(false, 1, out);     // downloadedExisted
        EpisodeDownloadBatch.countEnqueueOutcome(false, 0, out);     // existedInQueue
        assertEquals(1, out.added);
        assertEquals(1, out.downloadedExisted);
        assertEquals(1, out.existedInQueue);
        assertEquals(0, out.failed);
    }

    @Test
    public void toastMessage_noPermissionWins() {
        EpisodeDownloadBatch.Outcome noPerm = new EpisodeDownloadBatch.Outcome();
        noPerm.noPermission = 2;
        assertEquals("保存到公共目录需要授权，也可改用应用私有目录",
                EpisodeDownloadBatch.toastMessage(noPerm));
        // 即使同时有新增/已存在计数,无权限文案仍优先(任务实际未入队)
        noPerm.added = 1;
        noPerm.existedInQueue = 1;
        assertTrue(EpisodeDownloadBatch.toastMessage(noPerm).startsWith("保存到公共目录"));
    }

    @Test
    public void toastMessage_prefersAddedThenDupThenFail() {
        EpisodeDownloadBatch.Outcome addedOnly = new EpisodeDownloadBatch.Outcome();
        addedOnly.added = 3;
        assertTrue(EpisodeDownloadBatch.toastMessage(addedOnly).startsWith("已加入 3 个下载任务"));

        EpisodeDownloadBatch.Outcome addedWithDup = new EpisodeDownloadBatch.Outcome();
        addedWithDup.added = 1;
        addedWithDup.downloadedExisted = 1;
        addedWithDup.existedInQueue = 1;
        assertTrue(EpisodeDownloadBatch.toastMessage(addedWithDup).contains("2 个已存在"));

        EpisodeDownloadBatch.Outcome dupOnly = new EpisodeDownloadBatch.Outcome();
        dupOnly.downloadedExisted = 2;
        dupOnly.existedInQueue = 1;
        assertEquals("2 集已下载,1 集已在任务中", EpisodeDownloadBatch.toastMessage(dupOnly));
        dupOnly.existedInQueue = 0;
        assertEquals("所选剧集均已下载完成", EpisodeDownloadBatch.toastMessage(dupOnly));
        dupOnly.downloadedExisted = 0;
        dupOnly.existedInQueue = 1;
        assertEquals("所选剧集已在下载任务中", EpisodeDownloadBatch.toastMessage(dupOnly));

        EpisodeDownloadBatch.Outcome fail = new EpisodeDownloadBatch.Outcome();
        fail.failed = 2;
        assertTrue(EpisodeDownloadBatch.toastMessage(fail).startsWith("所选剧集解析失败(2 集)"));
        fail.failed = 1;
        assertTrue(EpisodeDownloadBatch.toastMessage(fail).startsWith("该集解析失败"));

        // 全空 → null(调用方给兜底文案)
        assertEquals(null, EpisodeDownloadBatch.toastMessage(new EpisodeDownloadBatch.Outcome()));
    }
}

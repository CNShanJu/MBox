package com.github.tvbox.osc.download.internal;

import com.github.tvbox.osc.bean.DownloadTask;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class DownloadConcurrencyTest {
    private DownloadTask task(String url) {
        DownloadTask task = new DownloadTask(); task.url = url; task.state = DownloadTask.STATE_DOWNLOADING; return task;
    }
    @Test public void chaoxingPolicyCannotBeTriggeredBySpoofedHostOrQuery() {
        assertTrue(DownloadConcurrency.isChaoxing("https://mooc1.chaoxing.com/course"));
        assertTrue(DownloadConcurrency.isChaoxing("https://CHAoxing.cn/video"));
        assertFalse(DownloadConcurrency.isChaoxing("https://chaoxing.com.evil.example/video"));
        assertFalse(DownloadConcurrency.isChaoxing("https://example.com/video?host=chaoxing.com"));
    }
    @Test public void onlyScopedTasksShareTwoSlots() {
        DownloadTask a = task("https://cdn.chaoxing.com/a.m3u8"), b = task("https://cdn.chaoxing.com/b.m3u8");
        DownloadTask candidate = task("https://cdn.example/c.m3u8");
        candidate.episodeRawUrl = "https://mooc1.chaoxing.com/course";
        assertFalse(DownloadConcurrency.canStart(candidate, Arrays.asList(a, b)));
        b.state = DownloadTask.STATE_PAUSED;
        assertTrue(DownloadConcurrency.canStart(candidate, Arrays.asList(a, b)));
        b.state = DownloadTask.STATE_DOWNLOADING;
        assertTrue(DownloadConcurrency.canStart(task("https://other.example/v.mp4"), Arrays.asList(a, b)));
    }
}

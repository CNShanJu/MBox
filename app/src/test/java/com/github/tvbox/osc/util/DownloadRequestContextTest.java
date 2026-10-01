package com.github.tvbox.osc.util;

import com.github.tvbox.osc.download.DownloadRequest;
import com.github.tvbox.osc.download.EnqueueResult;
import com.github.tvbox.osc.bean.DownloadRoute;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import static org.junit.Assert.*;

public class DownloadRequestContextTest {
    @Test public void headersAreCompleteCaseInsensitiveAndDetachedFromPlayback() {
        Map<String, String> playback = new HashMap<>();
        playback.put("User-Agent", "LearningApp/actual"); playback.put("Referer", "https://course.example/page");
        playback.put("Cookie", "session=old"); playback.put("X-Access", "custom");
        Map<String, String> refreshed = DownloadHeaders.merge(playback, Collections.singletonMap("cookie", "session=fresh"));
        assertEquals(4, refreshed.size()); assertEquals("session=fresh", refreshed.get("Cookie"));
        assertEquals("LearningApp/actual", refreshed.get("user-agent"));
        assertEquals("https://course.example/page", refreshed.get("REFERER")); assertEquals("custom", refreshed.get("X-Access"));
        assertEquals("session=old", playback.get("Cookie"));
        DownloadRoute route = new DownloadRoute("flag", "episode", "name");
        DownloadRequest request = new DownloadRequest("https://cdn.example/v.mp4", "s", "f", "raw", "id", null,
                refreshed, "source", "vod", "ep", Collections.singletonList(route));
        playback.clear(); route.episodeRawUrl = "changed";
        assertEquals(4, request.headers.size()); assertEquals("episode", request.altRoutes.get(0).episodeRawUrl);
        assertFalse(DownloadHeaders.mergeForOrigin("https://cdn.example/a", "https://other.example/b",
                refreshed, null).containsKey("Cookie"));
    }
    @Test public void enqueueFailuresAreNotCountedAsDuplicates() {
        EpisodeDownloadBatch.Outcome out = new EpisodeDownloadBatch.Outcome();
        EpisodeDownloadBatch.countEnqueueOutcome(EnqueueResult.of(EnqueueResult.Code.NO_SPACE, "空间不足"), out);
        EpisodeDownloadBatch.countEnqueueOutcome(EnqueueResult.of(EnqueueResult.Code.RESOLVE_FAILED, "解析失败"), out);
        EpisodeDownloadBatch.countEnqueueOutcome(EnqueueResult.of(EnqueueResult.Code.DUPLICATE, "已存在"), out);
        assertEquals(1, out.noSpace); assertEquals(1, out.failed); assertEquals(1, out.existedInQueue);
    }
    @Test public void directResumeRejectsWrongRangesAndWeakEtags() {
        assertTrue(DirectResumePolicy.matches("bytes 20-99/100", 20));
        assertFalse(DirectResumePolicy.matches("bytes 0-99/100", 20));
        assertFalse(DirectResumePolicy.matches("bytes 20-199/100", 20));
        assertFalse(DirectResumePolicy.matches(null, 20));
        assertEquals("date", DirectResumePolicy.validator("W/\"weak\"", "date"));
        assertEquals("\"strong\"", DirectResumePolicy.validator("\"strong\"", "date"));
    }
}

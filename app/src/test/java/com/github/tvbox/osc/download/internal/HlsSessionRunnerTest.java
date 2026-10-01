package com.github.tvbox.osc.download.internal;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class HlsSessionRunnerTest {
    @Test public void expired404RetriesTwiceThenRefreshesOnce() throws Exception {
        AtomicInteger requests = new AtomicInteger(), refresh = new AtomicInteger();
        List<Long> waits = new ArrayList<>();
        HlsSessionRunner runner = new HlsSessionRunner(refresh::incrementAndGet, () -> false, waits::add);
        runner.run(0, () -> { if (requests.incrementAndGet() <= 3) throw new DownloadErrors.HttpFailure(404, "分片"); });
        assertEquals(4, requests.get()); assertEquals(1, refresh.get());
        assertEquals(java.util.Arrays.asList(1000L, 2000L), waits);
    }
    @Test public void authenticationRefreshesWithoutSleepingAndPreservesDoneSegment() throws Exception {
        for (int code : new int[]{401, 403}) {
            AtomicInteger requests = new AtomicInteger(), refresh = new AtomicInteger();
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("hls-session");
            java.nio.file.Path complete = dir.resolve("00000.ts");
            try {
                java.nio.file.Files.write(complete, new byte[]{1, 2, 3});
                HlsSessionRunner runner = new HlsSessionRunner(refresh::incrementAndGet, () -> false,
                        ms -> fail("鉴权失败不能等待普通重试"));
                runner.run(1, () -> { if (requests.incrementAndGet() == 1) throw new DownloadErrors.HttpFailure(code, "分片"); });
                assertEquals(2, requests.get()); assertEquals(1, refresh.get());
                assertArrayEquals(new byte[]{1, 2, 3}, java.nio.file.Files.readAllBytes(complete));
            } finally { java.nio.file.Files.deleteIfExists(complete); java.nio.file.Files.deleteIfExists(dir); }
        }
    }
    @Test public void each404IsConfirmedAgainstANewPlaylistBeforeBecomingGone() throws Exception {
        AtomicInteger requests = new AtomicInteger(), refresh = new AtomicInteger();
        HlsSessionRunner runner = new HlsSessionRunner(refresh::incrementAndGet, () -> false, ms -> {});
        for (int index = 0; index < 2; index++) {
            try { runner.run(index, () -> { requests.incrementAndGet(); throw new DownloadErrors.HttpFailure(404, "分片"); }); fail(); }
            catch (DownloadErrors.SegmentGoneException e) { assertEquals(index, e.getSegmentIndex()); }
        }
        assertEquals(2, refresh.get()); assertEquals(8, requests.get());
    }
    @Test public void expiredLoginPausesAndCancellationPreventsRefresh() throws Exception {
        AtomicInteger refresh = new AtomicInteger();
        HlsSessionRunner runner = new HlsSessionRunner(() -> { refresh.incrementAndGet(); throw new DownloadErrors.SessionExpired(); },
                () -> false, ms -> {});
        try { runner.run(0, () -> { throw new DownloadErrors.HttpFailure(403, "分片"); }); fail(); }
        catch (DownloadErrors.SessionExpired expected) { assertTrue(expected.getMessage().contains("登录")); }
        assertEquals(1, refresh.get());
        HlsSessionRunner cancelled = new HlsSessionRunner(refresh::incrementAndGet, () -> true, ms -> fail());
        try { cancelled.run(0, () -> fail()); fail(); } catch (java.io.IOException expected) { }
        assertEquals(1, refresh.get());
    }
    @Test public void unchanged403AfterRefreshPausesInsteadOfLooping() throws Exception {
        AtomicInteger requests = new AtomicInteger(), refresh = new AtomicInteger();
        HlsSessionRunner runner = new HlsSessionRunner(refresh::incrementAndGet, () -> false, ms -> fail());
        try { runner.run(0, () -> { requests.incrementAndGet(); throw new DownloadErrors.HttpFailure(403, "分片"); }); fail(); }
        catch (DownloadErrors.SessionExpired expected) { }
        assertEquals(2, requests.get()); assertEquals(1, refresh.get());
    }
    @Test public void longSessionCanRenewAgainWhenProgressContinues() throws Exception {
        AtomicInteger refresh = new AtomicInteger();
        HlsSessionRunner runner = new HlsSessionRunner(refresh::incrementAndGet, () -> false, ms -> {});
        for (int index = 0; index < 5; index++) {
            AtomicInteger attempts = new AtomicInteger();
            runner.run(index, () -> { if (attempts.incrementAndGet() == 1) throw new DownloadErrors.HttpFailure(403, "分片"); });
        }
        assertEquals(5, refresh.get());
    }
    @Test public void confirmedPermanentGapsKeepGoneSemanticsRatherThanLoginPause() throws Exception {
        AtomicInteger refresh = new AtomicInteger();
        HlsSessionRunner runner = new HlsSessionRunner(refresh::incrementAndGet, () -> false, ms -> {});
        for (int index = 0; index < 8; index++) {
            try { runner.run(index, () -> { throw new DownloadErrors.HttpFailure(404, "分片"); }); fail(); }
            catch (DownloadErrors.SegmentGoneException expected) { assertEquals(index, expected.getSegmentIndex()); }
        }
        assertEquals(8, refresh.get());
    }
}

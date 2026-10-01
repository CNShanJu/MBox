package com.github.tvbox.osc.download.internal;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import static org.junit.Assert.*;

public class HlsMergerTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    @Test public void initPrecedesSegmentsAndUnapprovedMissingSegmentFails() throws Exception {
        File dir = files.newFolder(), init = new File(dir, "init.mp4"), target = new File(dir, "merged.tmp");
        Files.write(init.toPath(), new byte[]{0});
        Files.write(new File(dir, "00000.ts").toPath(), new byte[]{1});
        Files.write(new File(dir, "00002.ts").toPath(), new byte[]{3});
        HlsMerger.merge(dir, 3, init, target, Collections.singleton(1), () -> false, (b, i) -> {});
        assertArrayEquals(new byte[]{0, 1, 3}, Files.readAllBytes(target.toPath()));
        try { HlsMerger.merge(dir, 3, init, target, Collections.emptySet(), () -> false, (b, i) -> {}); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("缺少分片 1")); }
        assertTrue(new File(dir, "00000.ts").exists());
    }
    @Test public void pauseNeverDeletesCompletedSegments() throws Exception {
        File dir = files.newFolder(), segment = new File(dir, "00000.ts");
        Files.write(segment.toPath(), new byte[]{1});
        try { HlsMerger.merge(dir, 1, null, new File(dir, "merged.tmp"), Collections.emptySet(), () -> true, (b, i) -> fail()); fail(); }
        catch (java.io.IOException expected) { }
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(segment.toPath()));
    }
}

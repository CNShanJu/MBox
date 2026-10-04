package com.github.tvbox.osc.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

public class UpdateDownloadSourcePolicyTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void rangeIgnoringSourceReusesOnlyAnIdenticalSavedPrefix() throws Exception {
        File partial = temp.newFile("partial.apk");
        try (FileOutputStream output = new FileOutputStream(partial)) {
            output.write(new byte[]{1, 2, 3, 4});
        }
        ByteArrayInputStream matching = new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5, 6});
        assertTrue(UpdateDownloadSourcePolicy.consumeMatchingPrefix(partial, matching, 4));
        assertEquals(5, matching.read());
        assertFalse(UpdateDownloadSourcePolicy.consumeMatchingPrefix(partial,
                new ByteArrayInputStream(new byte[]{1, 2, 9, 4, 5}), 4));
        try {
            UpdateDownloadSourcePolicy.consumeMatchingPrefix(partial,
                    new ByteArrayInputStream(new byte[]{1, 2}), 4);
            org.junit.Assert.fail("truncated response must keep the saved partial");
        } catch (EOFException expected) {
            // The caller will retry the same source without deleting its bytes.
        }
    }

    @Test
    public void sameCandidateCanResumeButFallbackMustDiscardItsBytes() {
        UpdateDownloadSourcePolicy policy = new UpdateDownloadSourcePolicy();
        String mirror = "https://gitee.com/release.apk";
        String github = "https://github.com/release.apk";

        assertFalse(policy.mustDiscardPartial(mirror, 0));
        assertFalse(policy.mustDiscardPartial(mirror, 8192));
        assertTrue(policy.mustDiscardPartial(github, 8192));
        assertFalse(policy.mustDiscardPartial(github, 4096));
    }

    @Test
    public void resumingLaterCandidateFirstKeepsItsBytesThenFallsBackSafely() {
        UpdateDownloadSourcePolicy policy = new UpdateDownloadSourcePolicy();
        String mirror = "https://gitee.com/release.apk";
        String github = "https://github.com/release.apk";

        policy.mustDiscardPartial(mirror, 0);
        policy.mustDiscardPartial(github, 1024);
        assertEquals(Arrays.asList(github, mirror),
                policy.orderedCandidates(Arrays.asList(mirror, github), 4096));
        assertFalse(policy.mustDiscardPartial(github, 4096));
        assertTrue(policy.mustDiscardPartial(mirror, 4096));
        assertEquals(Arrays.asList(mirror, github),
                policy.orderedCandidates(Arrays.asList(mirror, github), 2048));
        assertEquals(Arrays.asList(mirror, github),
                policy.orderedCandidates(Arrays.asList(mirror, github), 0));
        assertEquals(Arrays.asList(github),
                policy.orderedCandidates(Arrays.asList(github), 2048));
    }

    @Test
    public void unattributedPartialAfterRestartCannotBeResumed() {
        UpdateDownloadSourcePolicy policy = new UpdateDownloadSourcePolicy();
        assertTrue(policy.mustDiscardPartial("https://github.com/release.apk", 1024));
        policy.reset();
        assertTrue(policy.mustDiscardPartial("https://github.com/release.apk", 2048));
    }

    @Test
    public void partialResponseMustStartAtRequestedByte() {
        assertTrue(UpdateDownloadSourcePolicy.matchesContentRangeStart("bytes 1024-2047/4096", 1024));
        assertTrue(UpdateDownloadSourcePolicy.matchesContentRangeStart("Bytes 1024-2047/*", 1024));
        assertFalse(UpdateDownloadSourcePolicy.matchesContentRangeStart("bytes 0-1023/4096", 1024));
        assertFalse(UpdateDownloadSourcePolicy.matchesContentRangeStart(null, 1024));
        assertFalse(UpdateDownloadSourcePolicy.matchesContentRangeStart("bytes */4096", 1024));
        assertFalse(UpdateDownloadSourcePolicy.matchesContentRangeStart("bytes 1024-999/4096", 1024));
        assertFalse(UpdateDownloadSourcePolicy.matchesContentRangeStart("bytes 1024-2047/2047", 1024));
        assertFalse(UpdateDownloadSourcePolicy.matchesContentRangeStart("bytes 1024-2047/4096", 0));
        assertTrue(UpdateDownloadSourcePolicy.matchesContentRangeStart(
                "bytes 1024-2047/4096", 1024, 4096));
        assertFalse(UpdateDownloadSourcePolicy.matchesContentRangeStart(
                "bytes 1024-2047/4096", 1024, 8192));
        assertFalse(UpdateDownloadSourcePolicy.matchesContentRangeStart(
                "bytes 1024-2047/*", 1024, 4096));
    }
}

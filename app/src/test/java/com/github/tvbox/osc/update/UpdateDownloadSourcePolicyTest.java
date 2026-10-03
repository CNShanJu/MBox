package com.github.tvbox.osc.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public class UpdateDownloadSourcePolicyTest {
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
    }
}

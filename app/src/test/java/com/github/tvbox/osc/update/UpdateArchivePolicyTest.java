package com.github.tvbox.osc.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateArchivePolicyTest {
    @Test
    public void knownVersionRequiresExactApkVersionCode() {
        assertTrue(UpdateArchivePolicy.acceptsVersion(74, 74));
        assertFalse(UpdateArchivePolicy.acceptsVersion(74, 73));
        assertFalse(UpdateArchivePolicy.acceptsVersion(74, 75));
        assertFalse(UpdateArchivePolicy.acceptsVersion(74, -1));
    }

    @Test
    public void unknownProviderVersionStillRequiresParsableApk() {
        assertTrue(UpdateArchivePolicy.acceptsVersion(-1, 74));
        assertFalse(UpdateArchivePolicy.acceptsVersion(-1, -1));
        assertFalse(UpdateArchivePolicy.acceptsVersion(-1, 0));
    }
}

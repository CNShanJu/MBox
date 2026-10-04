package com.github.tvbox.osc.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateDownloadFailurePolicyTest {
    @Test
    public void interruptedTransferKeepsItsSourceAndPartialBytes() {
        assertTrue(UpdateDownloadFailurePolicy.keepSourceForRetry(1024, true));
        assertTrue(UpdateDownloadFailurePolicy.keepSourceForRetry(0, false));
        assertFalse(UpdateDownloadFailurePolicy.keepSourceForRetry(0, true));
    }

    @Test
    public void temporaryHttpFailurePreservesPartialButMissingAssetCanFallBack() {
        assertTrue(UpdateDownloadFailurePolicy.keepSourceForRetry(503, 1024));
        assertTrue(UpdateDownloadFailurePolicy.keepSourceForRetry(429, 1024));
        assertFalse(UpdateDownloadFailurePolicy.keepSourceForRetry(404, 1024));
        assertFalse(UpdateDownloadFailurePolicy.keepSourceForRetry(503, 0));
    }
}

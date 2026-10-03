package com.github.tvbox.osc.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateDownloadEpochTest {
    @Test
    public void cancelledWorkerCannotBecomeCurrentAfterImmediateRestart() {
        UpdateDownloadEpoch epochs = new UpdateDownloadEpoch();
        long original = epochs.next();
        long cancelled = epochs.next();
        long restarted = epochs.next();
        StringBuilder visibleState = new StringBuilder();

        assertFalse(epochs.isCurrent(original));
        assertFalse(epochs.isCurrent(cancelled));
        assertTrue(epochs.isCurrent(restarted));
        assertFalse(epochs.runIfCurrent(original, () -> visibleState.append("old")));
        assertTrue(epochs.runIfCurrent(restarted, () -> visibleState.append("new")));
        assertEquals("new", visibleState.toString());
    }

    @Test
    public void resumedWorkerInvalidatesAnyQueuedCallbackFromPreviousAttempt() {
        UpdateDownloadEpoch epochs = new UpdateDownloadEpoch();
        long beforePause = epochs.next();
        long afterResume = epochs.next();

        assertFalse(epochs.isCurrent(beforePause));
        assertTrue(epochs.isCurrent(afterResume));
        assertFalse(epochs.runIfCurrent(beforePause, () -> {
            throw new AssertionError("stale callback ran");
        }));
    }
}

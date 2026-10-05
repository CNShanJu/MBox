package com.github.tvbox.osc.util.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class PlayParseCoordinatorEpochTest {
    @Test
    public void queuedOldResultCannotStopOrReplaceNewParse() {
        AtomicInteger parseEpoch = new AtomicInteger(7);
        AtomicInteger oldActionCalls = new AtomicInteger();
        AtomicInteger newActionCalls = new AtomicInteger();

        // A worker has already queued the old result when a new episode starts parsing.
        Runnable oldResult = PlayParseCoordinator.guardParseEpoch(
                parseEpoch, 7, oldActionCalls::incrementAndGet);
        parseEpoch.incrementAndGet();
        Runnable newResult = PlayParseCoordinator.guardParseEpoch(
                parseEpoch, 8, newActionCalls::incrementAndGet);

        oldResult.run();
        newResult.run();

        assertEquals(0, oldActionCalls.get());
        assertEquals(1, newActionCalls.get());
    }
}

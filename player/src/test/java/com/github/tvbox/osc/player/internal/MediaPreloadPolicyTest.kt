package com.github.tvbox.osc.player.internal

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPreloadPolicyTest {
    @Test
    fun targetMillisecondsDoNotOverflow() {
        assertEquals(60_000, MediaPreloadPolicy.targetMs(60))
        assertEquals(0, MediaPreloadPolicy.targetMs(0))
        assertEquals(0, MediaPreloadPolicy.targetMs(Int.MIN_VALUE))
        assertEquals(Int.MAX_VALUE, MediaPreloadPolicy.targetMs(Int.MAX_VALUE))
    }

    @Test
    fun timeoutHasSlackAndAFiniteUpperBound() {
        assertEquals(80_000L, MediaPreloadPolicy.timeoutMs(60_000))
        assertEquals(320_000L, MediaPreloadPolicy.timeoutMs(300_000))
        assertEquals(320_000L, MediaPreloadPolicy.timeoutMs(Int.MAX_VALUE))
        assertEquals(20_000L, MediaPreloadPolicy.timeoutMs(-1))
    }

    @Test
    fun reachingTargetReleasesWithoutWaitingForAStateChange() {
        assertTrue(MediaPreloadPolicy.isComplete(60_000L, 60_000, Player.STATE_BUFFERING, true))
        assertTrue(MediaPreloadPolicy.isComplete(61_000L, 60_000, Player.STATE_READY, true))
        assertFalse(MediaPreloadPolicy.isComplete(59_999L, 60_000, Player.STATE_BUFFERING, true))
    }

    @Test
    fun shortReadyTailReleasesOnceLoadingStops() {
        assertTrue(MediaPreloadPolicy.isComplete(3_000L, 60_000, Player.STATE_READY, false))
        assertFalse(MediaPreloadPolicy.isComplete(3_000L, 60_000, Player.STATE_READY, true))
    }

    @Test
    fun initialOrStalledLoadingDoesNotLookLikeAFinishedTail() {
        assertFalse(MediaPreloadPolicy.isComplete(0L, 60_000, Player.STATE_IDLE, false))
        assertFalse(MediaPreloadPolicy.isComplete(3_000L, 60_000, Player.STATE_BUFFERING, false))
    }

    @Test
    fun endedReleasesEvenWhenTheTargetCannotBeReached() {
        assertTrue(MediaPreloadPolicy.isComplete(0L, 60_000, Player.STATE_ENDED, false))
        assertTrue(MediaPreloadPolicy.isComplete(3_000L, 60_000, Player.STATE_ENDED, true))
    }
}

package com.github.tvbox.osc.player.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DoubleTapSeekPolicyTest {
    @Test
    public void adaptiveSideZonesMatchPortraitAndLandscapePlayerBounds() {
        assertEquals(300f, DoubleTapSeekPolicy.sideRadiusX(900, 500), 0.01f);
        assertEquals(285f, DoubleTapSeekPolicy.sideRadiusY(900, 500), 0.01f);
        assertEquals(120f, DoubleTapSeekPolicy.sideRadiusX(360, 250), 0.01f);
        assertEquals(132.5f, DoubleTapSeekPolicy.sideRadiusY(360, 250), 0.01f);
        assertEquals(300f, DoubleTapSeekPolicy.sideWidth(250, 900, 500), 0.01f);
        assertEquals(144.04f, DoubleTapSeekPolicy.sideWidth(0, 900, 500), 0.01f);
        assertEquals(144.04f, DoubleTapSeekPolicy.sideWidth(500, 900, 500), 0.01f);
        assertEquals(269.61f, DoubleTapSeekPolicy.sideWidth(125, 900, 500), 0.01f);
        assertEquals(467.61f, DoubleTapSeekPolicy.sideWidth(0, 2048, 945), 0.01f);
        assertEquals(104.48f, DoubleTapSeekPolicy.sideWidth(0, 945, 2048), 0.01f);
        assertEquals(-1, DoubleTapSeekPolicy.direction(299, 250, 900, 500));
        assertEquals(0, DoubleTapSeekPolicy.direction(300, 250, 900, 500));
        assertEquals(0, DoubleTapSeekPolicy.direction(599, 250, 900, 500));
        assertEquals(1, DoubleTapSeekPolicy.direction(600, 250, 900, 500));
        assertEquals(-1, DoubleTapSeekPolicy.direction(269, 125, 900, 500));
        assertEquals(0, DoubleTapSeekPolicy.direction(270, 125, 900, 500));
        assertEquals(-1, DoubleTapSeekPolicy.direction(0, 0, 900, 500));
        assertEquals(-1, DoubleTapSeekPolicy.direction(0, 0, 360, 250));
        assertEquals(0, DoubleTapSeekPolicy.direction(-1, 250, 900, 500));
        assertEquals(0, DoubleTapSeekPolicy.direction(50, 501, 900, 500));
    }

    @Test
    public void rapidTapsAccumulateEvenWhenPlayerPositionHasNotUpdated() {
        DoubleTapSeekPolicy policy = new DoubleTapSeekPolicy();
        assertEquals(60_000L, policy.seek(1, 50_000L, 120_000L, 100).targetMs);
        DoubleTapSeekPolicy.Result second = policy.seek(1, 50_000L, 120_000L, 450);
        assertEquals(70_000L, second.targetMs);
        assertEquals(2, second.taps);
        assertEquals(60_000L, policy.seek(-1, 50_000L, 120_000L, 700).targetMs);
        assertEquals(85_000L, policy.seek(1, 75_000L, 120_000L, 1_800).targetMs);
    }

    @Test
    public void seekClampsAtBothEndsAndRejectsUnknownDuration() {
        DoubleTapSeekPolicy policy = new DoubleTapSeekPolicy();
        assertNull(policy.seek(1, 0, 0, 100));
        assertEquals(0L, policy.seek(-1, 3_000L, 120_000L, 200).targetMs);
        assertFalse(policy.seek(-1, 0, 120_000L, 300).moved);
        policy.reset();
        DoubleTapSeekPolicy.Result end = policy.seek(1, 117_000L, 120_000L, 400);
        assertEquals(120_000L, end.targetMs);
        assertTrue(end.moved);
        assertFalse(policy.seek(1, 117_000L, 120_000L, 500).moved);
    }
}

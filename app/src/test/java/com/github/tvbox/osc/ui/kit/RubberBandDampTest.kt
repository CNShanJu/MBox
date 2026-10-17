package com.github.tvbox.osc.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RubberBandDampTest {
    private val max = 120f
    private val epsilon = 1e-3f

    @Test
    fun initialSlopeNeverAmplifies() {
        val raw = 0.5f
        val y = RubberBandEngine.dampOffset(raw, max, 2f)
        assertTrue("起始斜率不得超过 1:1(会放大抖动)", y / raw <= 1f + epsilon)
        assertTrue("ratio>1 时起始应有阻尼(斜率明显小于 1)", y / raw < 0.9f)
    }

    @Test
    fun ratioOneIsOneToOneAtStart() {
        val raw = 0.01f
        val y = RubberBandEngine.dampOffset(raw, max, 1f)
        assertEquals("ratio=1 起点应 1:1 跟手", raw, y, 1e-4f)
    }

    @Test
    fun monotonicAndBoundedByMax() {
        var previous = 0f
        var raw = 1f
        while (raw <= 4000f) {
            val y = RubberBandEngine.dampOffset(raw, max, 2f)
            assertTrue("位移必须随拉出量单调不减", y >= previous - epsilon)
            assertTrue("位移必须严格小于上限 maxPx", y < max + epsilon)
            previous = y
            raw += 7f
        }
    }

    @Test
    fun preservesSignAndZero() {
        assertEquals(0f, RubberBandEngine.dampOffset(0f, max, 2f), epsilon)
        assertTrue("正向拉出→正向位移", RubberBandEngine.dampOffset(50f, max, 2f) > 0f)
        assertTrue("负向拉出→负向位移", RubberBandEngine.dampOffset(-50f, max, 2f) < 0f)
        assertEquals("阻尼对正负对称", RubberBandEngine.dampOffset(50f, max, 2f),
            -RubberBandEngine.dampOffset(-50f, max, 2f), epsilon)
    }

    @Test
    fun nonPositiveMaxIsSafeZero() {
        assertEquals(0f, RubberBandEngine.dampOffset(50f, 0f, 2f), epsilon)
        assertEquals(0f, RubberBandEngine.dampOffset(50f, -10f, 2f), epsilon)
    }

    @Test
    fun ratioBelowOneIsClamped() {
        assertEquals(RubberBandEngine.dampOffset(30f, max, 1f),
            RubberBandEngine.dampOffset(30f, max, 0.3f), epsilon)
    }
}

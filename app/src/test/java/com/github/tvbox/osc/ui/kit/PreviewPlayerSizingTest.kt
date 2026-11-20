package com.github.tvbox.osc.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览播放器尺寸策略。
 *
 * 竖屏:上下允许黑边,**左右不能有黑边** —— 高度不得低于"视频按宽铺满所需的高度"
 * (用户口径 2026-11-20);横屏:左栏宽度不超过一半。
 */
class PreviewPlayerSizingTest {

    // 常见手机竖屏 1080x2400 可用区
    private val phoneW = 1080
    private val phoneH = 2300

    // 平板竖屏 1200x1920 可用区
    private val padW = 1200
    private val padH = 1820

    @Test
    fun portraitFallsBackToThirtyPercentWhenVideoSizeUnknown() {
        val expected = (phoneH * PreviewPlayerSizing.PORTRAIT_BASE_RATIO).toDouble()
        assertEquals(expected,
                PreviewPlayerSizing.portraitHeight(phoneW, phoneH, 0f).toDouble(), 1.0)
    }

    @Test
    fun portraitAlwaysLeavesVideoFullWidth() {
        // 核心不变量:只要"按宽铺满"没超过高度上限,算出来的高度就必须够 —— 否则左右出黑边
        val aspects = listOf(16f / 9f, 4f / 3f, 21f / 9f, 2.35f, 1.85f, 1.6f)
        val screens = listOf(phoneW to phoneH, padW to padH, 1440 to 2000)
        for ((w, h) in screens) {
            for (aspect in aspects) {
                val needed = w / aspect
                val ceiling = h * PreviewPlayerSizing.PORTRAIT_MAX_RATIO
                if (needed > ceiling) continue // 已到封顶,极端比例下左右黑边无法避免
                val height = PreviewPlayerSizing.portraitHeight(w, h, aspect)
                assertTrue(
                    "${w}x$h @$aspect: 高度 $height 低于按宽铺满所需 $needed",
                    height + 1f >= needed
                )
            }
        }
    }

    @Test
    fun portraitNeverGoesBelowThirtyPercent() {
        // 下限:任何片源比例下都不低于屏高 30%(用户口径 2026-11-20)
        val base = phoneH * PreviewPlayerSizing.PORTRAIT_BASE_RATIO
        val aspects = listOf(0f, 16f / 9f, 4f / 3f, 21f / 9f, 2.35f, 1f, 9f / 16f, 0.5f)
        for (aspect in aspects) {
            val height = PreviewPlayerSizing.portraitHeight(phoneW, phoneH, aspect)
            assertTrue("aspect=$aspect 高度 $height 低于 30% 下限 $base", height + 1f >= base)
        }
    }

    @Test
    fun portraitKeepsBaseForUltraWideVideo() {
        // 21:9 按宽铺满只要 463px,低于 690 的基准 → 取基准,多出来的高度就是上下黑边
        val base = phoneH * PreviewPlayerSizing.PORTRAIT_BASE_RATIO
        assertEquals(base.toDouble(),
                PreviewPlayerSizing.portraitHeight(phoneW, phoneH, 21f / 9f).toDouble(), 1.0)
    }

    @Test
    fun portraitGrowsOnTabletToKeepVideoFullWidth() {
        // 平板基准只有 546px,16:9 要 675px 才不会左右黑边 → 必须长上去
        val height = PreviewPlayerSizing.portraitHeight(padW, padH, 16f / 9f)
        val needed = padW / (16f / 9f)
        assertTrue("平板 16:9 应长到 $needed 才无左右黑边,实际 $height", height + 1f >= needed)
        assertTrue(height > padH * PreviewPlayerSizing.PORTRAIT_BASE_RATIO)
    }

    @Test
    fun portraitFourThreeAlsoFillsWidth() {
        val height = PreviewPlayerSizing.portraitHeight(phoneW, phoneH, 4f / 3f)
        val needed = phoneW / (4f / 3f)
        assertEquals(needed.toDouble(), height.toDouble(), 1.0)
    }

    @Test
    fun portraitCapsHeightForPortraitVideo() {
        // 9:16 按宽铺满要 1920px(近整屏),会把下方详情全挤走 → 封顶一半
        val height = PreviewPlayerSizing.portraitHeight(phoneW, phoneH, 9f / 16f)
        assertEquals((phoneH * PreviewPlayerSizing.PORTRAIT_MAX_RATIO).toDouble(),
                height.toDouble(), 1.0)
    }

    @Test
    fun portraitNeverExceedsHalfHeight() {
        // 上限:任何片源比例下都不超过屏高 50%(用户口径 2026-11-20)
        val ceiling = phoneH * PreviewPlayerSizing.PORTRAIT_MAX_RATIO
        val aspects = listOf(0f, 16f / 9f, 4f / 3f, 21f / 9f, 1f, 9f / 16f, 0.5f)
        for (aspect in aspects) {
            val height = PreviewPlayerSizing.portraitHeight(phoneW, phoneH, aspect)
            assertTrue("aspect=$aspect 高度 $height 超过 50% 上限 $ceiling", height - 1f <= ceiling)
        }
    }

    @Test
    fun landscapeFallsBackToHalfWidthWhenVideoSizeUnknown() {
        val expected = (phoneH * PreviewPlayerSizing.LANDSCAPE_BASE_RATIO).toDouble()
        assertEquals(expected,
                PreviewPlayerSizing.landscapeWidth(phoneH, phoneW, 0f).toDouble(), 1.0)
    }

    @Test
    fun landscapeWideVideoStopsAtHalfWidth() {
        // 16:9 按屏高铺满要 4089px 宽,但左栏上限就是一半 —— 详情不能被挤到半屏以下
        val width = PreviewPlayerSizing.landscapeWidth(phoneH, phoneW, 16f / 9f)
        assertEquals((phoneH * 0.5f).toDouble(), width.toDouble(), 1.0)
    }

    @Test
    fun landscapeNarrowsForPortraitVideoButStopsAtFloor() {
        // 9:16 竖屏视频按屏高铺满只需 607px 宽,低于 45% 下限 → 停在 45%,别让右栏太宽而左栏太窄
        val width = PreviewPlayerSizing.landscapeWidth(phoneH, phoneW, 9f / 16f)
        assertEquals((phoneH * PreviewPlayerSizing.LANDSCAPE_MIN_RATIO).toDouble(),
                width.toDouble(), 1.0)
    }

    @Test
    fun zeroAvailableSizeYieldsZero() {
        assertEquals(0, PreviewPlayerSizing.portraitHeight(0, phoneH, 16f / 9f))
        assertEquals(0, PreviewPlayerSizing.portraitHeight(phoneW, 0, 16f / 9f))
        assertEquals(0, PreviewPlayerSizing.landscapeWidth(0, phoneW, 16f / 9f))
    }
}

package com.github.tvbox.osc.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TabSwipeHelper.direction] 的 JVM 单测:这是"内容区左右滑动切 tab"的唯一判据
 * (纯位移算术,不需要真机)—— 阈值以内不切、斜着滑不切、明显左右滑才切。
 */
class TabSwipeHelperTest {

    private val min = 100f   // 阈值(px)

    @Test
    fun leftSwipeGoesToNextTab() {
        assertEquals(-1, TabSwipeHelper.direction(-260f, 8f, min))
    }

    @Test
    fun rightSwipeGoesToPreviousTab() {
        assertEquals(1, TabSwipeHelper.direction(260f, -8f, min))
    }

    @Test
    fun shortDragIsIgnored() {
        // 位移不够(小于阈值):小抖动、单击时的轻微位移都不能切页
        assertEquals(0, TabSwipeHelper.direction(-99f, 0f, min))
        assertEquals(0, TabSwipeHelper.direction(0f, 400f, min))
    }

    @Test
    fun verticalScrollIsIgnored() {
        // 竖着滚列表(纵向远大于横向):绝不能切页
        assertEquals(0, TabSwipeHelper.direction(120f, 400f, min))
        assertEquals(0, TabSwipeHelper.direction(-120f, 400f, min))
    }

    @Test
    fun diagonalDragIsIgnored() {
        // 斜着滑(横向只比纵向大一点点,不到 RATIO 倍):仍视为滚动
        assertEquals(0, TabSwipeHelper.direction(160f, 120f, min))
        // 横向明显占优(1.6 倍以上)才算左右滑
        assertEquals(1, TabSwipeHelper.direction(200f, 100f, min))
    }

    @Test
    fun thresholdBoundaryIsInclusive() {
        // 正好达到阈值就认(>=);刚好差一点就不认
        assertEquals(-1, TabSwipeHelper.direction(-100f, 0f, min))
        assertEquals(0, TabSwipeHelper.direction(-99.9f, 0f, min))
    }
}

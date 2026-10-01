package com.github.tvbox.osc.player.controller;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import xyz.doikki.videoplayer.util.PlayerUtils;

/** 纯坐标回归；Android 触摸派发由用户人工验证。 */
public class PlayerGestureBoundsTest {
    @Test
    public void landscapeAcceptsBothHalvesAfterRotation() {
        assertFalse(PlayerUtils.isEdge(540, 1200, 1080, 2400, 120));
        // 转为横屏后，同一手势区域必须采用新尺寸，右半屏不能被当成右边缘。
        assertFalse(PlayerUtils.isEdge(600, 540, 2400, 1080, 120));
        assertFalse(PlayerUtils.isEdge(1800, 540, 2400, 1080, 120));
        assertTrue(PlayerUtils.isEdge(1800, 540, 1080, 2400, 120));
    }

    @Test
    public void onlyRealEdgesAreExcluded() {
        assertTrue(PlayerUtils.isEdge(119, 540, 2400, 1080, 120));
        assertTrue(PlayerUtils.isEdge(2281, 540, 2400, 1080, 120));
        assertTrue(PlayerUtils.isEdge(1200, 119, 2400, 1080, 120));
        assertTrue(PlayerUtils.isEdge(1200, 961, 2400, 1080, 120));
        assertFalse(PlayerUtils.isEdge(120, 120, 2400, 1080, 120));
        assertFalse(PlayerUtils.isEdge(2280, 960, 2400, 1080, 120));
    }

    @Test
    public void embeddedPlayerUsesLocalCoordinates() {
        // 播放器位于窗口内任意位置，局部中心均应有效。
        assertFalse(PlayerUtils.isEdge(320, 180, 640, 360, 40));
        assertFalse(PlayerUtils.isEdge(480, 180, 640, 360, 40));
        assertTrue(PlayerUtils.isEdge(-1, 180, 640, 360, 40));
        assertTrue(PlayerUtils.isEdge(641, 180, 640, 360, 40));
    }

    @Test
    public void unlaidOutViewCannotStartAGesture() {
        assertTrue(PlayerUtils.isEdge(0, 0, 0, 1080, 120));
        assertTrue(PlayerUtils.isEdge(0, 0, 2400, 0, 120));
        assertTrue(PlayerUtils.isEdge(Float.NaN, 540, 2400, 1080, 120));
    }
}

package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** LivePlayerTypes(直播播放器内核映射)单测 */
public class LivePlayerTypesTest {

    @Test
    public void typeIndex_matchesSettingsEnum() {
        assertEquals(0, LivePlayerTypes.typeIndex(0, "软解码"));   // 系统
        assertEquals(1, LivePlayerTypes.typeIndex(1, "硬解码"));   // ijk 硬
        assertEquals(2, LivePlayerTypes.typeIndex(1, "软解码"));   // ijk 软
        assertEquals(3, LivePlayerTypes.typeIndex(2, "软解码"));   // Exo
        assertEquals(0, LivePlayerTypes.typeIndex(9, "x"));        // 未知回系统
    }

    @Test
    public void roundTrip_indexToConfigAndBack() {
        for (int i = 0; i <= 3; i++) {
            int pl = LivePlayerTypes.playerTypeOf(i);
            String codec = LivePlayerTypes.ijkCodecOf(i);
            assertEquals(i, LivePlayerTypes.typeIndex(pl, codec));
        }
    }

    @Test
    public void codecDefaultsForExoAndSystem() {
        assertEquals("软解码", LivePlayerTypes.ijkCodecOf(0));
        assertEquals("软解码", LivePlayerTypes.ijkCodecOf(3)); // Exo 无解码概念,回软
        assertEquals("硬解码", LivePlayerTypes.ijkCodecOf(1));
    }
}

package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** LivePlayerTypes(直播播放器内核映射)单测 */
public class LivePlayerTypesTest {

    @Test
    public void typeIndex_matchesSettingsEnum() {
        assertEquals(0, LivePlayerTypes.typeIndex(0));   // 系统
        assertEquals(1, LivePlayerTypes.typeIndex(2));   // Media3
        assertEquals(0, LivePlayerTypes.typeIndex(9));   // 未知回系统
    }

    @Test
    public void roundTrip_indexToConfigAndBack() {
        for (int i = 0; i <= 1; i++) {
            int pl = LivePlayerTypes.playerTypeOf(i);
            assertEquals(i, LivePlayerTypes.typeIndex(pl));
        }
    }

    @Test
    public void playerTypeOf_defaultsToSystem() {
        assertEquals(LivePlayerTypes.PLAYER_SYSTEM, LivePlayerTypes.playerTypeOf(0));
        assertEquals(LivePlayerTypes.PLAYER_MEDIA3, LivePlayerTypes.playerTypeOf(1));
        assertEquals(LivePlayerTypes.PLAYER_SYSTEM, LivePlayerTypes.playerTypeOf(9));
    }
}

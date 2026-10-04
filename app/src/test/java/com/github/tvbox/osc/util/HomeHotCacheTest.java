package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class HomeHotCacheTest {
    @Test
    public void datesWithDifferentMonthAndDayHaveDifferentKeys() {
        assertEquals("20260111", HomeHotCache.dayKey(2026, 1, 11));
        assertEquals("20261101", HomeHotCache.dayKey(2026, 11, 1));
        assertNotEquals(HomeHotCache.dayKey(2026, 1, 11), HomeHotCache.dayKey(2026, 11, 1));
    }
}

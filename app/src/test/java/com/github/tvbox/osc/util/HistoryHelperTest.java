package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** HistoryHelper(历史条数档位)单测:越界回退首档、档位取值与文案。 */
public class HistoryHelperTest {

    @Test
    public void validIndex_returnsBucket() {
        assertEquals(30, HistoryHelper.getHisNum(0));
        assertEquals(50, HistoryHelper.getHisNum(1));
        assertEquals(70, HistoryHelper.getHisNum(2));
    }

    @Test
    public void outOfRange_fallsBackToFirst() {
        assertEquals(30, HistoryHelper.getHisNum(-1));
        assertEquals(30, HistoryHelper.getHisNum(3));
        assertEquals(30, HistoryHelper.getHisNum(99));
    }

    @Test
    public void bucketName_appendsSuffix() {
        assertEquals("30条", HistoryHelper.getHistoryNumName(0));
        assertEquals("70条", HistoryHelper.getHistoryNumName(2));
        assertEquals("30条", HistoryHelper.getHistoryNumName(100));
    }
}

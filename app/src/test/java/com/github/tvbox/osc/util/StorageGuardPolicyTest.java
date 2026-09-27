package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 下载存储看门狗口径的单测:阈值语义(1GB)与"测量失败不误停"。
 *
 * <p>这条口径的代价是双向的:定太松 → 空间见底还在写,合并/重封装失败(实测 MediaMuxer 报 -1007)
 * 甚至把手机塞满;定太紧或把"测不出来"当成"很低" → 用户的下载会被无故全部暂停。
 * 所以这里既钉阈值,也钉"未知一律放行"。
 */
public class StorageGuardPolicyTest {

    private static final long MB = 1024L * 1024;

    @Test
    public void thresholdIsOneGigabyte() {
        assertEquals("兜底阈值固定 1GB(用户口径)", 1024L * MB, StorageGuardPolicy.LOW_STORAGE_BYTES);
    }

    @Test
    public void lowWhenBelowThreshold() {
        assertTrue(StorageGuardPolicy.isLow(0));
        assertTrue(StorageGuardPolicy.isLow(1));
        assertTrue(StorageGuardPolicy.isLow(999 * MB));
        assertTrue(StorageGuardPolicy.isLow(StorageGuardPolicy.LOW_STORAGE_BYTES - 1));
    }

    @Test
    public void notLowAtOrAboveThreshold() {
        assertFalse(StorageGuardPolicy.isLow(StorageGuardPolicy.LOW_STORAGE_BYTES));
        assertFalse(StorageGuardPolicy.isLow(StorageGuardPolicy.LOW_STORAGE_BYTES + 1));
        assertFalse(StorageGuardPolicy.isLow(20L * 1024 * MB));
    }

    @Test
    public void unknownFreeSpaceNeverPauses() {
        // StatFs 抛异常时按"未知"处理:兜底机制不能因为一次测量失败把所有下载停掉
        assertFalse(StorageGuardPolicy.isLow(StorageGuardPolicy.FREE_UNKNOWN));
        assertFalse(StorageGuardPolicy.isLow(-12345));
    }

    @Test
    public void pausedMessageCarriesBothNumbers() {
        String msg = StorageGuardPolicy.pausedMessage(820 * MB);
        assertTrue("要说清为什么停: " + msg, msg.contains("存储空间不足"));
        assertTrue("要让用户看到还剩多少: " + msg, msg.contains("820.0MB"));
        assertTrue("要说明低于哪个阈值: " + msg, msg.contains("1.00GB"));
        assertTrue("要说明已经暂停(不是失败): " + msg, msg.contains("暂停"));
        // 测量失败的兜底文案不能出现负数/NaN
        assertTrue(StorageGuardPolicy.pausedMessage(StorageGuardPolicy.FREE_UNKNOWN).contains("未知"));
    }

    @Test
    public void formatSizeUsesDotDecimalSeparator() {
        assertEquals("512B", StorageGuardPolicy.formatSize(512));
        assertEquals("2KB", StorageGuardPolicy.formatSize(2048));
        assertEquals("1.5MB", StorageGuardPolicy.formatSize(3 * MB / 2));
        assertEquals("2.00GB", StorageGuardPolicy.formatSize(2L * 1024 * MB));
    }

    // ------------------------------------------------------------------
    // 写入前预检(与看门狗同一把尺子:写完还得留住 1GB)
    // ------------------------------------------------------------------

    @Test
    public void writeCheckKeepsTheSameOneGigabyteFloor() {
        long need = 20 * MB; // 一张背景图转码后的量级
        assertFalse("刚好只剩阈值本身时也不许写(写完就没有余量了)",
                StorageGuardPolicy.canWrite(StorageGuardPolicy.LOW_STORAGE_BYTES, need));
        assertFalse("低于阈值一律不许写", StorageGuardPolicy.canWrite(500 * MB, need));
        assertTrue("够 need + 阈值就放行",
                StorageGuardPolicy.canWrite(StorageGuardPolicy.LOW_STORAGE_BYTES + need, need));
        assertTrue("余量更多当然放行",
                StorageGuardPolicy.canWrite(20L * 1024 * MB, need));
    }

    @Test
    public void writeCheckTreatsUnknownSpaceAsAllowed() {
        // 与看门狗同一口径:一次 statfs 失败不该把用户挡在门外(真写不下时写入方自己会报错)
        assertTrue(StorageGuardPolicy.canWrite(StorageGuardPolicy.FREE_UNKNOWN, 20 * MB));
        assertTrue(StorageGuardPolicy.canWrite(-1, 20 * MB));
        assertTrue("need 为负按 0 处理", StorageGuardPolicy.canWrite(StorageGuardPolicy.LOW_STORAGE_BYTES, -5));
    }

    @Test
    public void insufficientMessageCarriesShortfall() {
        long need = 20 * MB;
        String msg = StorageGuardPolicy.insufficientMessage(600 * MB, need);
        assertTrue("要说清为什么不行: " + msg, msg.contains("存储空间不足"));
        assertTrue("要让用户看到还剩多少: " + msg, msg.contains("600.0MB"));
        assertTrue("要告诉用户需要清理多少: " + msg, msg.contains("还需清理约"));
        // 剩余空间远超需求时不该出现负数(文案只做兜底,正常不会走到)
        assertTrue(StorageGuardPolicy.insufficientMessage(200 * MB, 0).contains("还需清理约"));
    }
}

package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聚合搜索「来源快慢画像 + 本轮分波」单测(纯 JVM,不碰真机):
 * 覆盖页面直接依赖的语义 —— 无画像不推迟、慢源下一轮进第二波、快源按实测耗时排前、
 * 慢而空手归来的才单独重跑(慢但有结果的不能重跑,否则重复上屏)。
 */
public class SearchSourceHealthTest {

    private static final String FAST = "maccms_fast";
    private static final String SLOW = "maccms_slow";
    private static final String NEW = "maccms_new";
    private static final String SLOW_HIT = "maccms_slow_hit";

    /** 没有画像时:全部进第一波,并保持调用方给的顺序(首页源在前) */
    @Test
    public void withoutProfile_keepsOrderInPrimary() {
        SearchSourceHealth health = new SearchSourceHealth();
        SearchSourceHealth.Waves waves = health.split(Arrays.asList(FAST, SLOW, NEW));
        assertEquals(Arrays.asList(FAST, SLOW, NEW), waves.getPrimary());
        assertTrue(waves.getDeferred().isEmpty());
        assertFalse(health.isSlow(SLOW));
        assertEquals(-1L, health.costOf(SLOW));
    }

    /** 上一轮实测 ≥ 阈值即判慢:下一轮进第二波,不再和快源抢槽位 */
    @Test
    public void slowSource_movesToDeferredNextRound() {
        SearchSourceHealth health = new SearchSourceHealth();
        health.beginRound();
        health.onSourceDone(SLOW, 15040L, false);
        health.onSourceDone(FAST, 380L, true);

        SearchSourceHealth.Waves waves = health.split(Arrays.asList(SLOW, FAST, NEW));
        assertEquals(Collections.singletonList(SLOW), waves.getDeferred());
        assertTrue(health.isSlow(SLOW));
        assertFalse(health.isSlow(FAST));
    }

    /** 第一波内部按上次耗时升序:快源先占槽位,首屏结果最快出现 */
    @Test
    public void primary_ordersKnownFastFirstThenUnknown() {
        SearchSourceHealth health = new SearchSourceHealth();
        health.onSourceDone("c", 900L, true);
        health.onSourceDone("a", 100L, true);
        health.onSourceDone("b", 500L, true);

        // n1/n2 没画像:排在有画像的之后,且保持原序
        SearchSourceHealth.Waves waves = health.split(Arrays.asList("c", NEW, "a", "n2", "b"));
        assertEquals(Arrays.asList("a", "b", "c", NEW, "n2"), waves.getPrimary());
    }

    /** 慢源又变快:回到第一波(画像跟着实测走,不永久拉黑) */
    @Test
    public void recoveredSource_returnsToPrimary() {
        SearchSourceHealth health = new SearchSourceHealth();
        health.onSourceDone(SLOW, 9000L, false);
        assertTrue(health.isSlow(SLOW));

        health.onSourceDone(SLOW, 420L, true);
        assertFalse(health.isSlow(SLOW));
        assertEquals(420L, health.costOf(SLOW));
        assertTrue(health.split(Collections.singletonList(SLOW)).getDeferred().isEmpty());
    }

    /** 慢且空手而归 → 本轮第二波单独重跑;慢但带回了结果 → 不重跑(否则同一源重复上屏) */
    @Test
    public void retryOnlySlowSourcesWithoutResult() {
        SearchSourceHealth health = new SearchSourceHealth();
        health.beginRound();
        health.onSourceDone(SLOW, 15010L, false);
        health.onSourceDone(SLOW_HIT, 7200L, true);
        health.onSourceDone(FAST, 200L, false);

        assertEquals(Collections.singletonList(SLOW), health.retryKeys());
    }

    /** 新的一轮只清本轮重试名单,画像留着(否则每轮都从零开始,慢源又会堵一次) */
    @Test
    public void beginRound_clearsRetryButKeepsProfile() {
        SearchSourceHealth health = new SearchSourceHealth();
        health.beginRound();
        health.onSourceDone(SLOW, 15000L, false);
        assertEquals(Collections.singletonList(SLOW), health.retryKeys());

        health.beginRound();
        assertTrue(health.retryKeys().isEmpty());
        assertTrue(health.isSlow(SLOW));
    }

    /** 空 key / 非法耗时(负数)不记账,免得把垃圾数据当画像 */
    @Test
    public void blankKeyAndBadCost_ignored() {
        SearchSourceHealth health = new SearchSourceHealth();
        health.onSourceDone(null, 9000L, false);
        health.onSourceDone("  ", 9000L, false);
        health.onSourceDone(SLOW, -1L, false);

        assertFalse(health.isSlow(SLOW));
        assertTrue(health.split(Arrays.asList(null, " ", SLOW)).getDeferred().isEmpty());
    }

    /** 阈值可注入:测试与后续调参都不必改常量 */
    @Test
    public void customThreshold_isRespected() {
        SearchSourceHealth health = new SearchSourceHealth(1000L);
        health.onSourceDone(SLOW, 1200L, true);
        assertTrue(health.isSlow(SLOW));
        health.onSourceDone(FAST, 900L, true);
        assertFalse(health.isSlow(FAST));
        // 判慢与"要不要重跑"都看同一阈值
        assertEquals(Collections.emptyList(), health.retryKeys());
    }

    /**
     * 画像进程内共享:onSourceDone 来自共享池/回调线程,split/isSlow 来自 UI 线程。
     * 这组并发读写把"必须加锁"钉住(去掉 @Synchronized 会抛 ConcurrentModificationException 或读到脏状态)。
     */
    @Test
    public void concurrentRecordAndRead_isSafe() throws Exception {
        final SearchSourceHealth health = new SearchSourceHealth();
        final int threads = 8;
        final AtomicBoolean failed = new AtomicBoolean(false);
        Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            pool[t] = new Thread(() -> {
                try {
                    for (int i = 0; i < 500; i++) {
                        health.onSourceDone("site_" + id, id % 2 == 0 ? 9000L : 100L, i % 3 != 0);
                        health.split(Arrays.asList("site_0", "site_1", "site_2", "site_3"));
                        health.isSlow("site_" + id);
                        health.costOf("site_" + (id + 1) % threads);
                    }
                } catch (Throwable th) {
                    failed.set(true);
                }
            });
            pool[t].start();
        }
        for (Thread th : pool) {
            th.join();
        }
        assertFalse("并发读写画像不应抛异常", failed.get());
        for (int t = 0; t < threads; t++) {
            assertEquals("偶数号是慢源、奇数号是快源", t % 2 == 0, health.isSlow("site_" + t));
        }
    }
}

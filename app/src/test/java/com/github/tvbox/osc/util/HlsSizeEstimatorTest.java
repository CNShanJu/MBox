package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * m3u8 大小"抽样探测 + 推算"的纯逻辑单测:抽样怎么取、平均值怎么算、推算偏差有多大。
 *
 * <p>这套口径直接决定下载前的磁盘预检与下载页"约大小"(见 DownloadExecutor#estimateHlsBytes),
 * 所以除了边界,还要用"接近真实清单的分布"钉住<b>推算误差</b>:
 * 分片大小天然不均匀(关键帧片能到普通片几倍),纯平均会被单个离群样本带偏 —— 这里同时验证
 * 分层抽样会铺满清单、且截尾平均能把离群样本的影响压住。
 */
public class HlsSizeEstimatorTest {

    // ------------------------------------------------------------------
    // 抽样:取哪几片
    // ------------------------------------------------------------------

    @Test
    public void deterministicPicksMiddleOfEachBucket() {
        // 无随机源时取每段中间那片:分桶 [0,25)/[25,50)/[50,75)/[75,100) → 12/37/62/87
        int[] idx = HlsSizeEstimator.sampleIndices(100, 4, null);
        assertEquals(4, idx.length);
        assertEquals(12, idx[0]);
        assertEquals(37, idx[1]);
        assertEquals(62, idx[2]);
        assertEquals(87, idx[3]);
    }

    @Test
    public void samplesAreStrictlyIncreasingAndInRange() {
        Random rnd = new Random(20260927L);
        for (int trial = 0; trial < 200; trial++) {
            int[] idx = HlsSizeEstimator.sampleIndices(940, 12, rnd);
            assertEquals(12, idx.length);
            int prev = -1;
            for (int i : idx) {
                assertTrue("样本下标必须落在 [0,940) 内: " + i, i >= 0 && i < 940);
                assertTrue("样本下标必须严格递增(不重复、不倒退): " + i + " after " + prev, i > prev);
                prev = i;
            }
        }
    }

    @Test
    public void samplesCoverBothEndsOfThePlaylist() {
        // 分层抽样的意义:样本要铺满整份清单,而不是全挤在一头
        // (纯随机抽样在"前几片是大关键帧、后面都是小片"这类清单上会抽空)
        Random rnd = new Random(7L);
        for (int trial = 0; trial < 200; trial++) {
            int[] idx = HlsSizeEstimator.sampleIndices(940, 12, rnd);
            assertTrue("首段必须有样本: " + idx[0], idx[0] < 940 / 12);
            int lastBucketStart = (int) ((long) (idx.length - 1) * 940 / idx.length);
            assertTrue("末段必须有样本: " + idx[idx.length - 1], idx[idx.length - 1] >= lastBucketStart);
        }
    }

    @Test
    public void samplesNeverExceedSegmentCount() {
        int[] idx = HlsSizeEstimator.sampleIndices(5, 12, new Random(1L));
        assertEquals("清单只有 5 片时按 5 片全取", 5, idx.length);
        for (int i = 0; i < idx.length; i++) {
            assertEquals(i, idx[i]);
        }
        assertEquals(1, HlsSizeEstimator.sampleIndices(1, 12, new Random(1L)).length);
    }

    @Test
    public void invalidInputsYieldNoSamples() {
        assertEquals(0, HlsSizeEstimator.sampleIndices(0, 12, new Random(1L)).length);
        assertEquals(0, HlsSizeEstimator.sampleIndices(-3, 12, new Random(1L)).length);
        assertEquals(0, HlsSizeEstimator.sampleIndices(100, 0, new Random(1L)).length);
    }

    // ------------------------------------------------------------------
    // 平均片大小:怎么算
    // ------------------------------------------------------------------

    @Test
    public void fewSamplesUsePlainMean() {
        assertEquals(200L, HlsSizeEstimator.averageSampleBytes(new long[] { 100, 200, 300 }));
        assertEquals(500L, HlsSizeEstimator.averageSampleBytes(new long[] { 500 }));
    }

    @Test
    public void enoughSamplesTrimOneMaxAndOneMin() {
        // 5 片:去掉 1000(最大)与 10(最小) → (20+30+40)/3 = 30
        assertEquals(30L, HlsSizeEstimator.averageSampleBytes(new long[] { 10, 20, 30, 40, 1000 }));
    }

    @Test
    public void unknownSamplesAreIgnored() {
        // <=0 = 探测失败/服务器没给长度:不能当成"很小的分片"拉低平均值
        assertEquals(150L, HlsSizeEstimator.averageSampleBytes(new long[] { 100, 0, 200, -1 }));
        assertEquals(0L, HlsSizeEstimator.averageSampleBytes(new long[] { 0, -1 }));
        assertEquals(0L, HlsSizeEstimator.averageSampleBytes(new long[0]));
        assertEquals(0L, HlsSizeEstimator.averageSampleBytes(null));
    }

    // ------------------------------------------------------------------
    // 推算:整集有多大
    // ------------------------------------------------------------------

    @Test
    public void estimateMultipliesAverageBySegmentCount() {
        assertEquals(200L * 900, HlsSizeEstimator.estimateTotalBytes(new long[] { 100, 200, 300 }, 900));
    }

    @Test
    public void estimateIsZeroWhenNothingUsable() {
        assertEquals(0L, HlsSizeEstimator.estimateTotalBytes(new long[] { 0, 0 }, 900));
        assertEquals(0L, HlsSizeEstimator.estimateTotalBytes(new long[] { 100 }, 0));
        assertEquals(0L, HlsSizeEstimator.estimateTotalBytes(null, 900));
    }

    @Test
    public void estimateStaysCloseOnRealisticDistribution() {
        // 真实形态:940 片 × 500KB,夹一片 5MB 的大关键帧片(离群样本)
        // 抽样 + 截尾平均后的推算值应贴近真值(误差 5% 以内),否则磁盘预检会误伤/误放
        int total = 940;
        long normal = 500L * 1024;
        long outlier = 5L * 1024 * 1024;
        long truth = normal * (total - 1) + outlier;
        Random rnd = new Random(2026L);
        for (int trial = 0; trial < 50; trial++) {
            int[] idx = HlsSizeEstimator.sampleIndices(total, HlsSizeEstimator.DEFAULT_SAMPLE_COUNT, rnd);
            long[] samples = new long[idx.length];
            for (int k = 0; k < idx.length; k++) {
                samples[k] = idx[k] == 0 ? outlier : normal;
            }
            long est = HlsSizeEstimator.estimateTotalBytes(samples, total);
            long diff = Math.abs(est - truth);
            assertTrue("推算偏差过大: est=" + est + " truth=" + truth,
                    diff * 100 <= truth * 5);
        }
    }
}

package com.github.tvbox.osc.util;

import java.util.Arrays;
import java.util.Random;

/**
 * m3u8 整集大小的"抽样探测 + 推算"(纯计算,可 JVM 单测)。
 *
 * <p>为什么不用"码率 × 时长"当唯一口径:<b>主播放列表里的 {@code BANDWIDTH} 是峰值/声明值</b>,
 * 源站写虚、写平均、写视频轨峰值都常见;媒体播放列表里没有它时,旧口径直接退化成
 * "分片数 × 2MB"这种拍脑袋估值 —— 两者都能偏出成倍误差,而下载前的磁盘预检、
 * 下载页"约大小"都吃这个数。
 *
 * <p>抽样探测的做法:<b>不探测全部分片</b>(几百片就是几百个请求,入队/启动时不可接受),
 * 只按<b>分层抽样</b>取一小撮(默认 {@value #DEFAULT_SAMPLE_COUNT} 片)拿它们的真实字节数,
 * 再用平均大小推算全量。分层而不是纯随机:把清单均分成 N 段、每段里随机取一片 ——
 * 既有随机性,又保证样本铺满整份清单(纯随机会在"前几片是大关键帧、后面都是小片"这类
 * 分布不均的清单上抽空,平均值随样本位置剧烈抖动)。
 *
 * <p>平均值的稳健性:样本足够多({@value #TRIM_MIN_SAMPLES} 片以上)时去掉一个最大与一个最小再取平均
 * (截尾平均)。分片大小天然有长尾(关键帧片能到普通片的几倍),单个离群样本会把平均值整个带偏。
 *
 * <p>数量与耗时的边界由调用方掌握:样本数上限、单请求超时、整体时间预算都在下载执行器里
 * (那里才知道网络有多慢),本类只负责"取哪些片、怎么算"。
 */
public final class HlsSizeEstimator {

    /** 默认抽样片数:12 片已能把"平均片大小"稳到 ±10% 量级,请求开销对一次下载可以忽略 */
    public static final int DEFAULT_SAMPLE_COUNT = 12;

    /** 少于这么多有效样本就不采信推算结果(样本太少,平均值的代表性不够,调用方应退回码率口径) */
    public static final int MIN_USABLE_SAMPLES = 3;

    /** 样本数达到这个量才做截尾平均(样本太少时去掉极值反而降低代表性) */
    public static final int TRIM_MIN_SAMPLES = 5;

    private HlsSizeEstimator() {
    }

    /**
     * 分层抽样:把 {@code [0, total)} 均分成 N 段,每段内随机取一片。
     * <p>
     * 返回值严格递增且互不重复(每段取一片),个数 = {@code min(wantSamples, totalSegments)}。
     *
     * @param totalSegments 清单里的分片总数;≤0 时返回空数组
     * @param wantSamples   期望样本数;≤0 时返回空数组
     * @param rnd           随机源;为 null 时取每段中间那片(纯确定性,便于测试与复现)
     */
    public static int[] sampleIndices(int totalSegments, int wantSamples, Random rnd) {
        if (totalSegments <= 0 || wantSamples <= 0) return new int[0];
        int n = Math.min(wantSamples, totalSegments);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int lo = (int) ((long) i * totalSegments / n);
            int hi = (int) ((long) (i + 1) * totalSegments / n); // 独占上界
            if (hi <= lo) hi = lo + 1;
            if (hi > totalSegments) hi = totalSegments;
            int pick = rnd == null ? (lo + hi) / 2 : lo + rnd.nextInt(hi - lo);
            out[i] = Math.min(totalSegments - 1, Math.max(0, pick));
        }
        return out;
    }

    /**
     * 样本的平均大小(字节)。样本数 ≥ {@link #TRIM_MIN_SAMPLES} 时去掉一个最大与一个最小再平均。
     *
     * @param samples 每片实际字节数;≤0(探测失败/长度未知)的样本一律忽略
     * @return 平均片大小;没有有效样本时返回 0
     */
    public static long averageSampleBytes(long[] samples) {
        long[] valid = positives(samples);
        if (valid.length == 0) return 0;
        int from = 0;
        int to = valid.length; // 独占
        if (valid.length >= TRIM_MIN_SAMPLES) {
            Arrays.sort(valid);
            from = 1;
            to = valid.length - 1;
        }
        long sum = 0;
        for (int i = from; i < to; i++) {
            sum += valid[i];
        }
        return sum / (to - from);
    }

    /**
     * 用抽样平均片大小推算整集大小:{@code 平均片大小 × 分片总数}。
     *
     * @param samples       每片实际字节数(见 {@link #averageSampleBytes})
     * @param totalSegments 清单里的分片总数(含没被抽到的片)
     * @return 推算出的整集字节数;无有效样本或片数非法时返回 0
     */
    public static long estimateTotalBytes(long[] samples, int totalSegments) {
        if (totalSegments <= 0) return 0;
        long avg = averageSampleBytes(samples);
        if (avg <= 0) return 0;
        return avg * (long) totalSegments;
    }

    /** 过滤出 >0 的样本(新数组,不改动入参顺序之外的东西) */
    private static long[] positives(long[] samples) {
        if (samples == null || samples.length == 0) return new long[0];
        int n = 0;
        for (long s : samples) {
            if (s > 0) n++;
        }
        if (n == 0) return new long[0];
        if (n == samples.length) return samples.clone();
        long[] out = new long[n];
        int i = 0;
        for (long s : samples) {
            if (s > 0) out[i++] = s;
        }
        return out;
    }
}

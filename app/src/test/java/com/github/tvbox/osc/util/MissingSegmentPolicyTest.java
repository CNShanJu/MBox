package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * "缺片能否算完成"的判定单测(纯 JVM)。
 * <p>
 * 阈值是刻意的:真实故障现场是"一集 879 片里只有第 486 片在源侧 404",补片 3 轮 + 自动换线路 3 次
 * 都拿不到 —— 这种情况按缺片完成(缺 1 片 ≈ 3 秒),但缺得多就必须失败,绝不能把半截文件当成功。
 */
public class MissingSegmentPolicyTest {

    @Test
    public void realCase_oneDeadSegmentInLongEpisode_allowsGap() {
        // 879 片里缺 1 片:允许(占比 0.11%)
        assertTrue(MissingSegmentPolicy.allowGapCompletion(1, 879));
        // 940 片里缺 3 片:允许(0.32%)—— 绝对上限内、占比也刚好在阈内
        assertTrue(MissingSegmentPolicy.allowGapCompletion(3, 1000));
    }

    @Test
    public void shortVideo_singleMissingSegment_fails() {
        // 100 片(约 5 分钟)缺 1 片 = 1%:超过占比阈值 → 判失败
        assertFalse(MissingSegmentPolicy.allowGapCompletion(1, 100));
        // 边界:1/334 = 0.299% 刚好在阈值内(允许);1/333 = 0.3003% 超出(失败)
        assertTrue(MissingSegmentPolicy.allowGapCompletion(1, 334));
        assertFalse(MissingSegmentPolicy.allowGapCompletion(1, 333));
    }

    @Test
    public void tooManyMissing_fails() {
        assertFalse("超过绝对上限(>3 片)", MissingSegmentPolicy.allowGapCompletion(4, 100000));
        assertFalse("缺一半", MissingSegmentPolicy.allowGapCompletion(50, 100));
    }

    @Test
    public void noMissing_orBadTotal_isNotGapCompletion() {
        assertFalse("没有缺片就不该走缺片完成(正常完成路径)", MissingSegmentPolicy.allowGapCompletion(0, 879));
        assertFalse(MissingSegmentPolicy.allowGapCompletion(1, 0));
        assertFalse(MissingSegmentPolicy.allowGapCompletion(1, -5));
    }

    @Test
    public void allPermanentlyGone_usesRelaxedBudget() {
        // 故障现场:940 片里 8 片在源侧 404(其余 932 片都好),补片 3 轮 + 换线路 3 次全是同一个 404。
        // 放宽档放行(上限 = max(8 片, 总数 1%) = 9 片),用户拿到"缺 8 秒但能看"的成品
        assertTrue("940 片缺 8 片,且已确认全是源侧死片", MissingSegmentPolicy.allowGapCompletion(8, 940, true));
        // 未确认死片(还可能只是抖动/可换线路救回)时,同样数量仍按严格档判失败
        assertFalse("混合缺失不能放宽", MissingSegmentPolicy.allowGapCompletion(8, 940, false));
        // 940 片的放宽上限是 9 片:10 片就超了,不能再用"死片"当借口
        assertFalse(MissingSegmentPolicy.allowGapCompletion(10, 940, true));
        // 更长的剧集上限随之抬到总片数的 1%
        assertTrue(MissingSegmentPolicy.allowGapCompletion(10, 1000, true));
        assertFalse(MissingSegmentPolicy.allowGapCompletion(11, 1000, true));
    }

    @Test
    public void relaxedBudget_stillGuardsSmallAndBrokenEpisodes() {
        // 放宽必须有限度:占比超 1% 的短视频、缺一半、整集全死都不许"缺片完成"
        assertFalse("100 片缺 8 片 = 8%", MissingSegmentPolicy.allowGapCompletion(8, 100, true));
        assertFalse("缺一半", MissingSegmentPolicy.allowGapCompletion(50, 100, true));
        assertFalse("整集都是死片(不是缺片,是源已不可用)", MissingSegmentPolicy.allowGapCompletion(940, 940, true));
        assertFalse("没有缺片就不该走缺片完成", MissingSegmentPolicy.allowGapCompletion(0, 940, true));
        assertFalse(MissingSegmentPolicy.allowGapCompletion(8, 0, true));
    }
}

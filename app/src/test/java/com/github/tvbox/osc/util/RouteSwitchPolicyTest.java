package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 换线路止损判定单测(纯 JVM)。
 * <p>
 * 核心取舍:换线路=整集重下,已经下了不少时不能替用户悄悄扔掉进度 ——
 * 但"刚开下就发现这条线路整体挂了"这种(损失几乎为零)必须能自动换,否则用户白等一场。
 */
public class RouteSwitchPolicyTest {

    @Test
    public void earlyFailure_allowsSwitch() {
        // 刚开始下就连续失败:损失极小,自动换线路
        assertTrue(RouteSwitchPolicy.lossAcceptable(0, 940));
        assertTrue(RouteSwitchPolicy.lossAcceptable(5, 940));
        assertTrue(RouteSwitchPolicy.lossAcceptable(200, 940));
    }

    @Test
    public void lateFailure_keepsProgress() {
        // 940 片已下 201 片(超过绝对上限)或超过 25%:不自动换,保住进度
        assertFalse(RouteSwitchPolicy.lossAcceptable(201, 940));
        assertFalse(RouteSwitchPolicy.lossAcceptable(236, 940)); // 25.1%
        assertFalse(RouteSwitchPolicy.lossAcceptable(900, 940));
    }

    @Test
    public void percentAndCountBothMustHold() {
        // 比例边界用总数 400 片测(25% = 100 片,没触到 200 片的绝对上限)
        assertTrue(RouteSwitchPolicy.lossAcceptable(100, 400));
        assertFalse(RouteSwitchPolicy.lossAcceptable(101, 400));
        // 短视频(80 片):比例上限 20 片,比绝对上限更严 —— 按更严的那条走
        assertTrue(RouteSwitchPolicy.lossAcceptable(20, 80));
        assertFalse(RouteSwitchPolicy.lossAcceptable(21, 80));
        // 长剧(4000 片):比例上限 1000 片,被绝对上限 200 片兜住,避免一次扔掉四分之一部剧
        assertTrue(RouteSwitchPolicy.lossAcceptable(200, 4000));
        assertFalse(RouteSwitchPolicy.lossAcceptable(201, 4000));
        // 940 片:比例上限 235 片,实际仍被绝对上限 200 片压住(按更严的走)
        assertTrue(RouteSwitchPolicy.lossAcceptable(200, 940));
        assertFalse(RouteSwitchPolicy.lossAcceptable(235, 940));
    }

    @Test
    public void unknownTotal_isNotAcceptableForHls() {
        // 总片数未知(还没解析出播放列表):不猜,按"不可换"处理
        assertFalse(RouteSwitchPolicy.lossAcceptable(1, 0));
        assertFalse(RouteSwitchPolicy.lossAcceptable(0, -5));
    }

    @Test
    public void directLink_usesBytes() {
        long mb = 1024L * 1024L;
        assertTrue("刚开始下:可换", RouteSwitchPolicy.lossAcceptableBytes(10 * mb, 2000 * mb));
        assertTrue("总量未知但未超绝对上限:可换", RouteSwitchPolicy.lossAcceptableBytes(50 * mb, 0));
        assertFalse("超过 200MB 绝对上限:不换", RouteSwitchPolicy.lossAcceptableBytes(201 * mb, 8000 * mb));
        assertFalse("超过 25%:不换", RouteSwitchPolicy.lossAcceptableBytes(150 * mb, 400 * mb));
        assertTrue("刚好 25%:可换", RouteSwitchPolicy.lossAcceptableBytes(100 * mb, 400 * mb));
    }
}

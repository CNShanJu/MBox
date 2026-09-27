package com.github.tvbox.osc.util.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * "切换轨道后恢复进度"延迟任务的执行条件单测(JVM,不碰 Android)。
 * <p>
 * 这三条护栏都是"少了不报错、只在真机上偶发"的那一类:少一条 released 检查 = 退出播放页偶发闪退,
 * 少一条 epoch 检查 = 切集后跳到上一集的位置,少一条内核检查 = 操作到已换掉的内核上。
 */
public class TrackRestoreGuardTest {

    @Test
    public void runsWhenNothingChanged() {
        assertTrue(TrackRestoreGuard.shouldRun(false, 3, 3, true));
    }

    @Test
    public void skipsAfterRelease() {
        // 800ms 内退出播放页(onDestroyView → release)后绝不能再碰内核
        assertFalse(TrackRestoreGuard.shouldRun(true, 3, 3, true));
    }

    @Test
    public void skipsAfterEpisodeOrSourceChanged() {
        assertFalse(TrackRestoreGuard.shouldRun(false, 3, 4, true));
        assertFalse(TrackRestoreGuard.shouldRun(false, 0, 1, true));
    }

    @Test
    public void skipsWhenKernelReplacedOrGone() {
        assertFalse(TrackRestoreGuard.shouldRun(false, 3, 3, false));
    }

    @Test
    public void releaseWinsOverEverythingElse() {
        assertFalse(TrackRestoreGuard.shouldRun(true, 3, 3, false));
        assertFalse(TrackRestoreGuard.shouldRun(true, 3, 4, false));
    }
}

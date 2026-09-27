package com.github.tvbox.osc.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 启动崩溃熔断的计数逻辑单测(JVM,不碰 Android)。
 * <p>
 * 这块逻辑决定"连续启动失败到第几次进安全模式",而它一旦算错(例如把窗口外的旧尝试也算进来),
 * 表现就是"正常用户偶发一次崩溃后莫名进安全模式",或反过来"启动死循环永不熔断"——两者都难在真机上
 * 复现定位,所以按纯函数抽出后固定住行为。
 */
public class StartupGuardPolicyTest {

    private static final long WINDOW = 5 * 60_000L;

    @Test
    public void firstEverAttemptCountsAsOne() {
        assertEquals(1, StartupGuard.nextAttemptCount(0, 0L, 1_000_000L, WINDOW));
    }

    @Test
    public void consecutiveAttemptsAccumulateWithinWindow() {
        long now = 1_000_000L;
        assertEquals(2, StartupGuard.nextAttemptCount(1, now - 1_000L, now, WINDOW));
        assertEquals(3, StartupGuard.nextAttemptCount(2, now - 30_000L, now, WINDOW));
    }

    @Test
    public void staleAttemptRestartsCounting() {
        long now = 1_000_000L;
        // 上次尝试在窗口之外(隔天启动/正常重启过):重新从 1 开始,不继承历史计数
        assertEquals(1, StartupGuard.nextAttemptCount(9, now - WINDOW - 1L, now, WINDOW));
        // 边界:恰好等于窗口仍算连续(用 ">" 判定,避免边界抖动把计数清掉)
        assertEquals(10, StartupGuard.nextAttemptCount(9, now - WINDOW, now, WINDOW));
    }

    @Test
    public void clockRollbackDoesNotResetCounting() {
        long now = 1_000_000L;
        // 系统时间被回拨(now < lastAt):now - lastAt 为负,不大于窗口 → 继续累计,不清零
        assertEquals(4, StartupGuard.nextAttemptCount(3, now + 60_000L, now, WINDOW));
    }

    @Test
    public void dirtyNegativeCountIsTreatedAsOne() {
        long now = 1_000_000L;
        // 存储被写坏(负数)时不能让计数永远躲在阈值之下
        assertEquals(2, StartupGuard.nextAttemptCount(-5, now - 1_000L, now, WINDOW));
    }

    @Test
    public void safeModeOnlyFromThirdConsecutiveFailure() {
        assertFalse(StartupGuard.shouldEnterSafeMode(1));
        assertFalse(StartupGuard.shouldEnterSafeMode(2));
        assertTrue(StartupGuard.shouldEnterSafeMode(3));
        assertTrue(StartupGuard.shouldEnterSafeMode(4));
    }

    // ------------------------------------------------------------------
    // 安全模式冷却期:防止"三次崩 → 一次降级启动 → 又崩"的抖动
    // ------------------------------------------------------------------

    private static final long COOLDOWN = 10 * 60_000L;

    @Test
    public void cooldownStartsWhenThresholdReached() {
        long now = 1_000_000L;
        assertEquals(now + COOLDOWN, StartupGuard.nextSafeUntil(3, now, 0L, COOLDOWN));
    }

    @Test
    public void cooldownIsNotExtendedWhileStillInsideIt() {
        long now = 1_000_000L;
        long until = now + COOLDOWN;
        // 冷却期内再崩(计数继续涨,例如 4、5):截止时间保持不变,否则每崩一次续 10 分钟,永远出不来
        assertEquals(until, StartupGuard.nextSafeUntil(4, now + 60_000L, until, COOLDOWN));
        assertEquals(until, StartupGuard.nextSafeUntil(5, now + COOLDOWN - 1L, until, COOLDOWN));
    }

    @Test
    public void cooldownCanRestartAfterItExpired() {
        long now = 1_000_000L;
        long expired = now - 1L;
        assertEquals(now + COOLDOWN, StartupGuard.nextSafeUntil(3, now, expired, COOLDOWN));
    }

    @Test
    public void belowThresholdLeavesCooldownUntouched() {
        long now = 1_000_000L;
        assertEquals(0L, StartupGuard.nextSafeUntil(2, now, 0L, COOLDOWN));
    }

    @Test
    public void safeModeHoldsThroughHealthyBootInsideCooldown() {
        long now = 1_000_000L;
        long until = now + COOLDOWN;
        // 健康启动把计数清零(=0),但仍在冷却期 → 继续按安全模式启动(用户因此拿到一段稳定可用期)
        assertTrue(StartupGuard.isSafeModeActive(0, now + 30_000L, until));
        // 冷却期过后恢复正常路径
        assertFalse(StartupGuard.isSafeModeActive(0, until, until));
        assertFalse(StartupGuard.isSafeModeActive(0, until + 1L, until));
    }

    @Test
    public void safeModeActiveWheneverThresholdReached() {
        long now = 1_000_000L;
        assertTrue(StartupGuard.isSafeModeActive(3, now, 0L));
        assertFalse(StartupGuard.isSafeModeActive(2, now, 0L));
    }
}

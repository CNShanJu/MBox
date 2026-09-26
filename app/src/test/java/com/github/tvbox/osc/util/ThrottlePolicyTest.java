package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 每任务限速的窗口结算单测(纯 JVM)。
 * <p>
 * 回归背景:限速原来把窗口状态写成方法内局部变量、每次调用都重置 → 窗口永远不满 → 永不 sleep,
 * 功能实际是死的。这里锁死"跨调用累积、窗口满了才结算"的口径。
 */
public class ThrottlePolicyTest {

    private static final long LIMIT = 1000; // 1000 B/s

    @Test
    public void unlimitedNeverSleeps() {
        ThrottlePolicy.Decision d = ThrottlePolicy.decide(0, 0, 10_000_000, 5_000);
        assertEquals(0, d.sleepMs);
        assertEquals(0, d.windowBytes);
    }

    @Test
    public void opensWindowAndKeepsAccumulating() {
        // 首次调用:开窗,不睡;窗口状态记下起点与字节
        ThrottlePolicy.Decision d1 = ThrottlePolicy.decide(LIMIT, 0, 100, 1_000);
        assertEquals(0, d1.sleepMs);
        assertEquals(1_000, d1.windowStart);
        assertEquals(100, d1.windowBytes);
        // 窗口未满(只过了 100ms):继续累积,不睡、窗口起点不动 —— 这正是老实现漏掉的一步
        ThrottlePolicy.Decision d2 = ThrottlePolicy.decide(LIMIT, d1.windowStart, d1.windowBytes + 200, 1_100);
        assertEquals(0, d2.sleepMs);
        assertEquals(1_000, d2.windowStart);
        assertEquals(300, d2.windowBytes);
    }

    @Test
    public void withinLimitOpensNewWindowWithoutSleep() {
        // 窗口满 500ms、窗口内只写了 500B(= 1000B/s × 0.5s) → 没超速,开新窗口
        ThrottlePolicy.Decision d = ThrottlePolicy.decide(LIMIT, 1_000, 500, 1_500);
        assertEquals(0, d.sleepMs);
        assertEquals(1_500, d.windowStart);
        assertEquals(0, d.windowBytes);
    }

    @Test
    public void overLimitSleepsProportionally() {
        // 窗口 500ms 内写了 1000B,应写 500B → 超 500B → 按 1000B/s 折算睡 500ms
        ThrottlePolicy.Decision d = ThrottlePolicy.decide(LIMIT, 1_000, 1_000, 1_500);
        assertEquals(500, d.sleepMs);
        assertEquals("睡完开新窗口", 1_500, d.windowStart);
        assertEquals(0, d.windowBytes);
    }

    @Test
    public void sleepIsCapped() {
        // 超得再多,单次也只睡 MAX_SLEEP_MS(否则暂停/取消要等很久)
        ThrottlePolicy.Decision d = ThrottlePolicy.decide(1, 1_000, 1_000_000, 1_500);
        assertEquals(ThrottlePolicy.MAX_SLEEP_MS, d.sleepMs);
    }

    @Test
    public void clockJumpBackwardsStartsFreshWindow() {
        // 系统时间回拨:不能算出负 elapsed 乱睡,开新窗口
        ThrottlePolicy.Decision d = ThrottlePolicy.decide(LIMIT, 10_000, 5_000, 9_000);
        assertEquals(0, d.sleepMs);
        assertEquals(9_000, d.windowStart);
        assertEquals(5_000, d.windowBytes);
    }

    @Test
    public void windowConstantsAreSane() {
        assertTrue("窗口要足够长,避免每读一块就睡", ThrottlePolicy.WINDOW_MS >= 200);
        assertTrue("单次睡眠要封顶,保证暂停/取消响应", ThrottlePolicy.MAX_SLEEP_MS <= 3000);
    }

    @Test
    public void presetLabelsAndIndex() {
        // 设置项文案与档位只有一份事实源(下载设置弹窗 + 全局设置页共用)
        assertEquals("不限速", ThrottlePolicy.label(0));
        assertEquals("512KB/s", ThrottlePolicy.label(512 * 1024));
        assertEquals("1MB/s", ThrottlePolicy.label(1024 * 1024));
        assertEquals("5MB/s", ThrottlePolicy.label(5L * 1024 * 1024));
        // 非整 MB 的档位按 KB/s 显示
        assertEquals("1500KB/s", ThrottlePolicy.label(1500L * 1024));
        for (int i = 0; i < ThrottlePolicy.PRESET_BYTES_PER_SEC.length; i++) {
            assertEquals("第 " + i + " 档要能定位回自己", i,
                    ThrottlePolicy.presetIndex(ThrottlePolicy.PRESET_BYTES_PER_SEC[i]));
        }
        assertEquals("非法/负值按不限速处理", 0, ThrottlePolicy.presetIndex(-1));
        // 夹在档位之间时取最接近的一档:1800KB/s 离 2MB/s(2048KB)最近,而不是 1MB/s
        assertEquals(3, ThrottlePolicy.presetIndex(1800L * 1024));
        assertEquals(2, ThrottlePolicy.presetIndex(900L * 1024));
    }
}

package com.github.tvbox.osc.util;

/**
 * 每任务限速的窗口结算(纯计算,可 JVM 单测)。
 *
 * <p>为什么单独抽出来:原实现把"窗口起点/窗口内字节"写成方法内的局部变量,<b>每次调用都重置</b> ——
 * 于是 {@code elapsed} 永远是 0(小于窗口),每次都直接 return,限速功能实际从未生效(死代码)。
 * 窗口状态必须跨调用保存,所以由调用方持有(任务上的 transient 字段),结算规则放这里以便单测。
 *
 * <p>结算口径:窗口内先放行(不做字节级节流,避免每个 read 都睡),窗口满时比较
 * "窗口内已写字节"与"限额 × 窗口时长应当写出的字节";超出多少就睡多久(按限额折算),
 * 单次睡眠封顶 {@link #MAX_SLEEP_MS}(睡太久会让暂停/取消响应变慢)。
 */
public final class ThrottlePolicy {

    /** 结算窗口(毫秒) */
    public static final long WINDOW_MS = 500;
    /** 单次结算最多睡多久 */
    public static final long MAX_SLEEP_MS = 2000;

    private ThrottlePolicy() {
    }

    /** 结算结果:sleepMs&gt;0 时调用方睡这么久,并把窗口状态更新为 windowStart/windowBytes */
    public static final class Decision {
        public final long sleepMs;
        public final long windowStart;
        public final long windowBytes;

        Decision(long sleepMs, long windowStart, long windowBytes) {
            this.sleepMs = sleepMs;
            this.windowStart = windowStart;
            this.windowBytes = windowBytes;
        }
    }

    /**
     * 结算一次限速窗口。
     *
     * @param limitBytesPerSec 限速(字节/秒),≤0 表示不限速
     * @param windowStart      当前窗口起点(≤0 表示尚未开窗)
     * @param windowBytes      当前窗口内已写字节(含本次写入)
     * @param now              当前时刻(毫秒)
     */
    public static Decision decide(long limitBytesPerSec, long windowStart, long windowBytes, long now) {
        long bytes = Math.max(0, windowBytes);
        if (limitBytesPerSec <= 0) return new Decision(0, now, 0);
        if (windowStart <= 0 || now < windowStart) {
            return new Decision(0, now, bytes); // 开窗:本次只记字节,不睡
        }
        long elapsed = now - windowStart;
        if (elapsed < WINDOW_MS) {
            return new Decision(0, windowStart, bytes); // 窗口未满:先放行,窗口满再结算
        }
        long expect = limitBytesPerSec * elapsed / 1000;
        long over = bytes - expect;
        if (over <= 0) return new Decision(0, now, 0); // 没超速:开新窗口
        long sleep = over * 1000 / limitBytesPerSec;
        if (sleep <= 0) sleep = 1;
        return new Decision(Math.min(sleep, MAX_SLEEP_MS), now, 0);
    }
}

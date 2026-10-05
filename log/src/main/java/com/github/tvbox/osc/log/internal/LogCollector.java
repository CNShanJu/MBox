package com.github.tvbox.osc.log.internal;

import com.github.tvbox.osc.log.LogEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 结构化日志采集队列（internal：仅供 log 模块内部使用，勿被外部模块引用）。
 * <p>
 * 只负责"收拢"：LogStore 门控通过后把就绪的 LogEntry 入队，
 * 满 {@link #BATCH_SIZE} 立即 flush、否则延迟 {@link #FLUSH_DELAY_MS} 后 flush；
 * 实际落库委托 {@link LogRepository}（写通道单线程，天然串行）。
 * 不持有业务门控状态（enabled/minLevel/分类开关在 LogStore）。
 */
public final class LogCollector {

    private static final int BATCH_SIZE = 50;
    private static final long FLUSH_DELAY_MS = 1000;

    private final LogRepository repository;
    private final List<LogEntry> pending = new ArrayList<>();
    private final Object pendingLock = new Object();

    /** 延迟 flush 定时器（批量落库用，非每日清理） */
    private final ScheduledExecutorService flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tvbox-log-flush");
        t.setDaemon(true);
        return t;
    });

    public LogCollector(LogRepository repository) {
        this.repository = repository;
    }

    /** 入队（LogStore 门控通过后才调用） */
    public void offer(LogEntry e) {
        boolean first;
        synchronized (pendingLock) {
            pending.add(e);
            first = pending.size() == 1;
            if (pending.size() >= BATCH_SIZE) {
                flushNow();
                return;
            }
        }
        if (first) {
            flushScheduler.schedule(this::flushNow, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** 立即 flush（崩溃捕获等需要尽快落库的场景调用） */
    public void flushNow() {
        final List<LogEntry> batch;
        synchronized (pendingLock) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
            // 与查询前的 awaitWrites 保持同一顺序：不能先清 pending，后提交写任务。
            repository.insertAllAsync(batch);
        }
    }

    /**
     * 立即 flush 并<b>等它写完</b>（崩溃捕获、进程重启前等场景）。
     * <p>
     * 未捕获异常处理完就会杀进程,异步 flush 大概率来不及 → 首次崩溃库里是空的。
     * 这里阻塞到落库完成或超时(超时只影响日志,不影响崩溃处理本身)。
     *
     * @return true=等待范围内的批次写入成功;false=超时或写库失败
     */
    public boolean flushNowBlocking(long timeoutMs) {
        final List<LogEntry> batch;
        final boolean onWriteThread = repository.isWriteThread();
        synchronized (pendingLock) {
            batch = pending.isEmpty() ? null : new ArrayList<>(pending);
            if (batch != null) {
                pending.clear();
                // 清队列和提交写任务必须连续，查询的写屏障才能看到这批日志。
                if (!onWriteThread) repository.insertAllAsync(batch);
            }
        }
        // 定时 flush 可能已取走 pending，但异步写入仍排在仓储队列里。
        if (batch != null && onWriteThread) {
            boolean inserted = repository.insertAllBlocking(batch, timeoutMs);
            // Also consume a failure from an earlier batch on this same writer thread.
            boolean earlierBatches = repository.awaitWrites(timeoutMs);
            return inserted && earlierBatches;
        }
        return repository.awaitWrites(timeoutMs);
    }
}

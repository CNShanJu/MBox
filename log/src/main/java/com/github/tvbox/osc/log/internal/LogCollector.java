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
        }
        repository.insertAllAsync(batch);
    }
}

package com.github.tvbox.osc.log.internal;

import android.util.Log;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.log.LogConfig;
import com.github.tvbox.osc.log.LogEntry;
import com.github.tvbox.osc.log.LogFilter;
import com.github.tvbox.osc.log.db.LogDatabase;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 结构化业务日志仓储（internal：仅供 log 模块内部使用，勿被外部模块引用）。
 * <p>
 * 只做 Room 相关的最稳定职责：
 * <ul>
 *   <li>批量写入（写走单线程 executor，与 {@link #query} 的读通道分离，避免写突发阻塞查询）；</li>
 *   <li>条件查询 / 任务维度查询（读走独立 executor，同步阻塞至结果，页面调用放后台线程）；</li>
 *   <li>按保留天数清理 / 清空 / 防膨胀 trim；</li>
 *   <li>每日定时清理排定。</li>
 * </ul>
 * 采集队列与批量调度在 {@link LogCollector}，本类不持有业务门控状态。
 * 降级安全：db 为 null（未 init 的 no-op 实例）时查询返回 null、写入空操作。
 */
public final class LogRepository {

    private static final int MAX_ROWS = 20_000;
    private static final long DAY_MS = 24L * 3600 * 1000;
    private static final long QUERY_TIMEOUT_MS = 5_000;

    private final LogDatabase db;

    /** 写通道：所有落库/清理/清空串行，避免 Room 并发写 */
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvbox-log-write");
        t.setDaemon(true);
        return t;
    });

    /** 读通道：与写通道分离，查询不排队等写 */
    private final ExecutorService queryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvbox-log-query");
        t.setDaemon(true);
        return t;
    });

    /** 每日按保留天数清理的定时器（仅真正落库时使用） */
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tvbox-log-cleanup");
        t.setDaemon(true);
        return t;
    });

    /** 每日定时清理是否已排定（防重复排定） */
    private volatile boolean dailyCleanupScheduled = false;

    public LogRepository(@Nullable LogDatabase db) {
        this.db = db;
    }

    /** 降级模式（未 init / no-op）判断 */
    public boolean isNoop() {
        return db == null;
    }

    // ------------------------------------------------------------------
    // 写通道（批量落库）
    // ------------------------------------------------------------------

    /** 批量落库 + 超上限 trim（写 executor 串行执行）；降级模式空操作 */
    public void insertAllAsync(final List<LogEntry> batch) {
        if (db == null || batch == null || batch.isEmpty()) return;
        writeExecutor.execute(() -> {
            try {
                db.logDao().insertAll(batch);
                if (db.logDao().count() > MAX_ROWS) {
                    db.logDao().trimTo(MAX_ROWS);
                }
            } catch (Throwable th) {
                Log.e("LogRepository", "日志落库失败", th);
            }
        });
    }

    /** 按保留天数清理（异步）；降级模式空操作 */
    public void deleteBeforeAsync(int retentionDays) {
        if (db == null) return;
        final long before = System.currentTimeMillis() - Math.max(1, retentionDays) * DAY_MS;
        writeExecutor.execute(() -> {
            try {
                db.logDao().deleteBefore(before);
            } catch (Throwable th) {
                Log.e("LogRepository", "日志清理失败", th);
            }
        });
    }

    /** 清空全部业务日志（异步）；降级模式空操作 */
    public void clearAllAsync() {
        if (db == null) return;
        writeExecutor.execute(() -> {
            try {
                db.logDao().clearAll();
            } catch (Throwable th) {
                Log.e("LogRepository", "日志清空失败", th);
            }
        });
    }

    // ------------------------------------------------------------------
    // 读通道（同步阻塞至结果；页面调用建议放后台线程）
    // ------------------------------------------------------------------

    /** 组合筛选查询；降级模式返回 null */
    @Nullable
    public List<LogEntry> query(final LogFilter f) {
        if (db == null) return null;
        return await(() -> db.logDao().query(
                f.category, f.subType, f.minLevel, f.taskKey,
                f.fromTs, f.toTs, f.keyword, f.limit, f.offset));
    }

    /** 任务维度视图；降级模式返回 null */
    @Nullable
    public List<LogEntry> queryByTask(String taskKey, int limit, int offset) {
        if (db == null) return null;
        return await(() -> db.logDao().queryByTask(taskKey, limit, offset));
    }

    private <T> T await(Callable<T> callable) {
        try {
            Future<T> f = queryExecutor.submit(callable);
            return f.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable th) {
            Log.e("LogRepository", "日志查询失败", th);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 运维：每日定时清理（排定一次，按 LogConfig 保留天数）
    // ------------------------------------------------------------------

    /**
     * 排定每日定时清理(只排一次):按 LogConfig 保留天数删除到期业务日志。
     * 开启日志或应用启动(开启状态)时调用；降级模式（db 为 null）不排定。
     */
    public void scheduleDailyCleanup() {
        if (db == null) return;
        if (dailyCleanupScheduled) return;
        synchronized (this) {
            if (dailyCleanupScheduled) return;
            dailyCleanupScheduled = true;
        }
        try {
            cleanupScheduler.scheduleAtFixedRate(() -> {
                try {
                    deleteBeforeAsync(LogConfig.getRetentionDays());
                } catch (Throwable ignored) {
                }
            }, 0L, 1L, TimeUnit.DAYS);
        } catch (Throwable ignored) {
        }
    }
}

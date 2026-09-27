package com.github.tvbox.osc.download.internal;

import android.util.Log;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.download.DownloadSubType;
import com.github.tvbox.osc.state.StorageSpace;
import com.github.tvbox.osc.util.StorageGuardPolicy;

import java.io.File;

/**
 * 存储看门狗 —— <b>全下载链路唯一的实现</b>(直链、HLS 分片、合并、重封装、调度闸门都走它)。
 *
 * <p>它解决的问题:启动前的磁盘预检({@code DownloadPolicy.checkDiskSpace})只能证明"<b>那一刻</b>"
 * 空间够,而 m3u8 的大小本身是估算;下载真正跑起来后,用户装应用/拍照/其它任务写入都可能把空间吃光。
 * 空间见底时下载还在写:分片写失败、合并/重封装失败(实测 {@code MediaMuxer.stop()} 报
 * {@code Error during stop(), muxer would have stopped already} -1007 就是写到没空间了),
 * 严重时把手机存储塞满导致系统卡死。看门狗是这条链路的<b>最后兜底</b>:
 * 可用空间低于 {@link StorageGuardPolicy#LOW_STORAGE_BYTES}(1GB)时,<b>暂停全部任务</b>,
 * 把已下进度留在磁盘上(暂停不是失败,清理空间后"继续/全部开始"即可续传)。
 *
 * <p>怎么用(两个入口,一个实现):
 * <ul>
 *   <li><b>热路径自检</b> {@link #checkWhileDownloading()}:直链写循环、分片读循环、每片结束、
 *       合并/重封装前调用。测量与 1s 缓存都在<b>中控层</b> {@link StorageSpace}(每块 64KB 调一次
 *       也不会变成"每块都 statfs");一旦发现不足,<b>当场</b>暂停全部并返回 true,调用方立即收尾退出
 *       (不必等调度器下一轮看到状态)。</li>
 *   <li><b>后台巡检</b> {@link #ensureMonitor()}:有任务真正在下载时起一个 2s 一轮的守护线程,
 *       覆盖"连接卡住、一直在等数据、没有字节流过"的空窗(这时热路径根本没机会被调用)。
 *       全部任务停下后线程自行退出,不常驻。</li>
 * </ul>
 *
 * <p>只暂停、不自动恢复:自动恢复会在"刚清出一点空间 → 继续写 → 又见底"之间反复起停(空间刚过线时
 * 任何恢复都只是再来一轮),且启动前的磁盘预检还会再拦一道;由用户清理后显式继续更可预期。
 *
 * <p><b>它是"低存储时该做什么"的唯一执行点</b>(暂停全部任务);"够不够/还剩多少"一律问中控层
 * {@link StorageSpace},阈值一律来自 {@link StorageGuardPolicy} —— 这里不再自己 {@code new StatFs}。
 */
final class StorageWatchdog {

    /** 后台巡检间隔:覆盖"没有字节流过"的空窗,2s 足够及时(大于 {@link StorageSpace#CACHE_TTL_MS},每轮都是新采样) */
    private static final long POLL_MS = 2000L;

    private final DownloadManager dm;

    /** 监控线程的启停判断与"退出前二次确认"共用一把锁,避免"线程退出瞬间新任务启动"漏监控 */
    private final Object monitorLock = new Object();
    private volatile boolean monitorRunning;

    /** 本轮"低存储"是否已经报过(避免每秒刷一条日志;空间恢复后复位) */
    private volatile boolean lowReported;

    StorageWatchdog(DownloadManager dm) {
        this.dm = dm;
    }

    /**
     * 热路径自检:低存储则暂停全部任务。
     *
     * @return true = 已因空间不足暂停(调用方必须立即收尾:写循环 flush 后退出,分片/合并循环直接返回)
     */
    boolean checkWhileDownloading() {
        return pauseAllIfLow();
    }

    /** 当前可用空间是否已低于兜底阈值(带中控层 1s 缓存;测量失败按"不低"处理) */
    boolean isLow() {
        return StorageGuardPolicy.isLow(freeBytes());
    }

    /** 最近一次测得的可用字节(未知为 {@link StorageGuardPolicy#FREE_UNKNOWN});供日志/文案使用 */
    long freeBytes() {
        return StorageSpace.freeBytes(saveDirForProbe());
    }

    /** 任务真正开始下载时调用:确保后台巡检在跑(已在跑则无动作) */
    void ensureMonitor() {
        synchronized (monitorLock) {
            if (monitorRunning) return;
            monitorRunning = true;
            Thread th = new Thread(new Runnable() {
                @Override
                public void run() {
                    monitorLoop();
                }
            }, "tvbox-dl-watchdog");
            th.setDaemon(true);
            th.start();
        }
    }

    /** 后台巡检:有任务在下载时每 {@link #POLL_MS} 检查一次;没有在下载的任务就退出线程 */
    private void monitorLoop() {
        while (true) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                synchronized (monitorLock) {
                    monitorRunning = false;
                }
                return;
            }
            if (!hasActiveDownload()) {
                synchronized (monitorLock) {
                    // 锁内二次确认后再退出:ensureMonitor 的启动判断也在这把锁里,
                    // 两边互斥才不会出现"线程判定空闲正要退出、新任务恰好在那一刻启动"的漏监控
                    if (!hasActiveDownload()) {
                        monitorRunning = false;
                        return;
                    }
                }
            }
            pauseAllIfLow();
        }
    }

    /** 是否存在真正在下载的任务(等待中/暂停中不算:等待任务由调度闸门把关,不需要常驻巡检) */
    private boolean hasActiveDownload() {
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.state == DownloadTask.STATE_DOWNLOADING) return true;
            }
        }
        return false;
    }

    /**
     * 空间不足则暂停全部任务。
     * <p>返回 true 表示"当前确实处于低存储状态"(不管这一轮有没有任务真的被改状态):
     * 热路径据此立即退出。空转(true)时任务可能已经被前一次调用暂停,不会重复写库(改动为 0 时
     * {@code pauseAllStorage} 不落盘/不广播)。
     */
    private boolean pauseAllIfLow() {
        long free = freeBytes();
        if (!StorageGuardPolicy.isLow(free)) {
            lowReported = false;
            return false;
        }
        String msg = StorageGuardPolicy.pausedMessage(free);
        boolean changed = dm.scheduler.pauseAllStorage(msg);
        if (changed || !lowReported) {
            Log.i("TVBox-Download", "存储看门狗:可用 " + free + "B 低于阈值 "
                    + StorageGuardPolicy.LOW_STORAGE_BYTES + "B,暂停全部任务");
            DownloadLog.LOG.warn(DownloadSubType.STORAGE,
                    "存储看门狗:可用空间 " + StorageGuardPolicy.formatSize(free) + "(低于 "
                            + StorageGuardPolicy.formatSize(StorageGuardPolicy.LOW_STORAGE_BYTES)
                            + "),已暂停全部下载;清理空间后点继续即可续传", null);
        }
        lowReported = true;
        return true;
    }

    /**
     * 探测用目录:下载保存根目录(不存在/未初始化时退到应用私有目录)。
     * <p>测量本身交给中控层 {@link StorageSpace}(它按这个路径缓存,换卷不会拿旧值)。
     */
    private File saveDirForProbe() {
        try {
            File dir = DownloadManager.getSaveDir();
            if (dir != null && dir.exists()) return dir;
        } catch (Throwable ignored) {
        }
        android.content.Context ctx = DownloadManager.appContext;
        return ctx == null ? null : ctx.getFilesDir();
    }
}

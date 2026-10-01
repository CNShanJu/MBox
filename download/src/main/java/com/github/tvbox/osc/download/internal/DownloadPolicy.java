package com.github.tvbox.osc.download.internal;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;


import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.state.StorageSpace;
import com.github.tvbox.osc.state.SystemEvent;
import com.github.tvbox.osc.state.SystemStateMonitor;
import com.github.tvbox.osc.config.PrefsDataStore;

import java.io.File;

/**
 * 决策器（Policy-Decider）：并发数 / 仅WiFi / 磁盘水位等策略规则。
 * 只输出"允许/拒绝/限值"，不直接操作任务状态（调度由 DownloadScheduler 执行）。
 */
public class DownloadPolicy {

    /** 最大并发下载数的**上限**(2026-10-01 由 5 收到 3):界面只给 1-3 档,
     *  读回老值 / 越界入参都按它收口(见 getMaxConcurrent/setMaxConcurrent 与构造里的读回)。 */
    static final int MAX_CONCURRENT = 3;

    /** 磁盘空间安全余量:下载完成后至少保留的可用空间(避免手机因空间耗尽卡死/无法开机) */
    static final long MIN_FREE_SPACE = 1536L * 1024 * 1024; // 1.5GB
    /** 磁盘空间不足时的兜底检查:可用空间低于该值直接拒绝(防止极端情况) */
    static final long MIN_ABSOLUTE_FREE = 512L * 1024 * 1024; // 512MB

    private final DownloadManager dm;

    /** 最大并发下载数(**1-3**,2026-10-01 起上限由 5 收到 3:用户口径"下载并发最多设置 3 个,移除 4 和 5") */
    private volatile int maxConcurrent = 3;


    DownloadPolicy(DownloadManager dm) {
        this.dm = dm;
        int savedConcurrent = 3;
        try {
            savedConcurrent = PrefsDataStore.getInt(DownloadManager.HAWK_MAX_CONCURRENT, 3);
        } catch (Throwable ignored) {
        }
        // 老版本允许存到 5:读回来也按新上限(3)收一次,免得界面上最大只有 3、实际还在跑 5
        maxConcurrent = Math.max(1, Math.min(MAX_CONCURRENT, savedConcurrent));
        // Bug1: 订阅全局状态监控(②)的网络事件——仅WiFi开启时切蜂窝/断网 → 暂停全部;
        // WiFi 恢复 → 自动恢复。决策器只下发指令,执行在 Scheduler。
        SystemStateMonitor monitor = SystemStateMonitor.get();
        if (monitor != null) {
            monitor.register((SystemEvent e) -> {
                if (!SystemStateMonitor.TYPE_NETWORK.equals(e.type)) return;
                if (!isWifiOnly()) return;
                if (SystemStateMonitor.VAL_CELLULAR.equals(e.value)
                        || SystemStateMonitor.VAL_NONE.equals(e.value)) {
                    dm.scheduler.pauseAllNetwork();
                } else if (SystemStateMonitor.VAL_WIFI.equals(e.value)) {
                    dm.scheduler.resumeAllNetwork();
                }
            }, SystemStateMonitor.TYPE_NETWORK);
            // Bug4: 存储权限被撤销 → 暂停全部(避免半截文件)
            monitor.register((SystemEvent e) -> {
                if (!SystemStateMonitor.TYPE_PERMISSION.equals(e.type)) return;
                if (SystemStateMonitor.VAL_PERMISSION_REVOKED.equals(e.value)) {
                    dm.scheduler.pauseAllPermission();
                }
            }, SystemStateMonitor.TYPE_PERMISSION);
        }
    }

    int getMaxConcurrent() {
        return maxConcurrent;
    }

    public boolean isAutoResume() {
        return com.github.tvbox.osc.config.PrefsDataStore.getBoolean("download_auto_resume", false);
    }

    public void setAutoResume(boolean enabled) {
        com.github.tvbox.osc.config.PrefsDataStore.put("download_auto_resume", enabled);
    }

    /** 设置最大并发数(1-3;上限见 {@link #MAX_CONCURRENT}),触发重新调度 */
    void setMaxConcurrent(int n) {
        int v = Math.max(1, Math.min(MAX_CONCURRENT, n));
        maxConcurrent = v;
        try {
            PrefsDataStore.put(DownloadManager.HAWK_MAX_CONCURRENT, v);
        } catch (Throwable ignored) {
        }
        dm.notifyChanged();
        dm.wakeWorker();
    }

    /** 是否仅 WiFi 下载(默认开启;开启时蜂窝/断网不启动任务并自动挂起,需改为"Wi-Fi+流量"才允许用流量) */
    boolean isWifiOnly() {
        try {
            return PrefsDataStore.getBoolean(DownloadManager.HAWK_WIFI_ONLY, true);
        } catch (Throwable th) {
            return true;
        }
    }

    /**
     * 全局限速(字节/秒;0=不限速)。UI 设置项,持久化在 PrefsDataStore(KB/s 存 Int)。
     * <p>
     * 注意:限速原先只有"按任务 set 一次"的入口、且调度侧从未调用,加上 throttle() 的实现缺陷,
     * 等于功能不存在。现在由本项统一供值 —— 任务启动时套用,改设置时对运行中任务立即生效。
     */
    long getSpeedLimitBytesPerSec() {
        try {
            return Math.max(0, PrefsDataStore.getInt(DownloadManager.HAWK_SPEED_LIMIT_KBPS, 0)) * 1024L;
        } catch (Throwable th) {
            return 0;
        }
    }

    /** 设置全局限速(字节/秒;0=不限速):落盘 + 立即套用到所有任务(含运行中的) + 通知刷新 */
    void setSpeedLimitBytesPerSec(long bytesPerSecond) {
        long v = Math.max(0, bytesPerSecond);
        try {
            PrefsDataStore.put(DownloadManager.HAWK_SPEED_LIMIT_KBPS, (int) (v / 1024));
        } catch (Throwable ignored) {
        }
        // 立即生效:throttle 每次写入都读 t.speedLimit,改了值当场就按新速度节流
        try {
            synchronized (dm.tasks) {
                for (DownloadTask t : dm.tasks) {
                    if (t != null) t.speedLimit = v;
                }
            }
        } catch (Throwable ignored) {
        }
        dm.notifyChanged();
    }

    void setWifiOnly(boolean wifiOnly) {
        try {
            PrefsDataStore.put(DownloadManager.HAWK_WIFI_ONLY, wifiOnly);
        } catch (Throwable ignored) {
        }
    }

    /** 当前是否处于 WiFi 网络(无活动网络/蜂窝/未知均返回 false) */
    static boolean isWifiActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) DownloadManager.appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Throwable th) {
            return false;
        }
    }

    /** 当前网络是否为移动网络(蜂窝) */
    static boolean isMobileNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) DownloadManager.appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 下载前磁盘空间预检:估算文件大小(直链精确 Content-Length;m3u8 由 DownloadExecutor 抽样探测后
     * 推算,见 :common HlsSizeEstimator),检查保存目录所在磁盘剩余空间。
     *
     * <p>预留口径 = **1× 成品大小**(不做峰值倍数)。原因:m3u8 的大小终究是<b>推算</b>(抽样均值 × 片数),
     * 本身有偏差,再乘 2 会大量误伤(把本来够用的机器拒之门外);而真到合并/重封装阶段空间不够时,
     * 每一处都有自己的把关,退化都是**优雅**的:
     * 合并前按"成品 + {@link #MIN_FREE_SPACE}"再算一次,不够则失败并保留碎片可重试;
     * 重封装前按"源文件 × 2(重打包时 ×3)+ {@link #MIN_ABSOLUTE_FREE}"再算一次,不够先释放本任务碎片目录、
     * 仍不够才回退 `.ts`(成品仍可播)。
     * 下载过程中的兜底另有存储看门狗(可用空间 &lt; 1GB 暂停全部任务,见 StorageWatchdog)。
     *
     * @return null=空间充足;否则返回错误提示文案
     */
    String checkDiskSpace(DownloadTask t) {
        try {
            if (t.totalBytes <= 0 && t.estimatedBytes <= 0) {
                // 大小未知(入队异步预检未完成/失败/重启恢复的旧任务):启动前补一次阻塞探测
                dm.executor.probeSizeBlocking(t);
            }
            long size = t.totalBytes > 0 ? t.totalBytes : t.estimatedBytes;
            if (size <= 0) return null; // 无法确定大小(探测失败/服务器不返回),不阻塞,下载中按实际进度判定
            File dir = new File(t.savePath).getParentFile();
            if (dir == null || !dir.exists()) return null;
            // 测量统一走中控层 StorageSpace(全应用一处 new StatFs);这里保留下载自己的
            // "启动前门槛"口径 MIN_FREE_SPACE(1.5GB),它比看门狗底线(1GB)高一档
            long free = StorageSpace.freeBytes(dir);
            long need = size * SPACE_PEAK_FACTOR;
            long needAfter = free - need; // 预留后的剩余
            if (needAfter < MIN_FREE_SPACE) {
                long needClean = (MIN_FREE_SPACE - needAfter + 1024 * 1024 - 1) / (1024 * 1024);
                return "磁盘空间不足:文件约 " + formatSize(size) + ",完成后可用仅 "
                        + formatSize(Math.max(0, needAfter)) + ",需清理约 " + needClean + "MB";
            }
            return null;
        } catch (Throwable th) {
            return null; // 预检异常不阻塞下载
        }
    }

    /**
     * 空间预检的倍数(1 = 只预留成品大小)。实测口径下 1 就够:分片/合并产物/重封装产物虽然会同时存在,
     * 但每一处收尾环节都有自己的空间把关(合并前、重封装前各算一次,不够就释放碎片或优雅回退),
     * 并且下载过程中还有存储看门狗兜底 —— 不值得为不确定的估算去误伤用户。
     */
    private static final int SPACE_PEAK_FACTOR = 1;

    /** 是否 HLS(m3u8):决定空间预检按几倍成品大小预留 */
    private static boolean isHlsUrl(String url) {
        return url != null && url.toLowerCase(java.util.Locale.ROOT).contains(".m3u8");
    }

    /** 格式化大小(供磁盘空间提示) */
    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return String.format("%.0fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format("%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}

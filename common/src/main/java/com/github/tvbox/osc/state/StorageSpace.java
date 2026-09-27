package com.github.tvbox.osc.state;

import android.os.StatFs;

import com.github.tvbox.osc.util.StorageGuardPolicy;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 存储空间<b>中控层</b>:全应用问"还剩多少 / 够不够写"的唯一入口。
 *
 * <p>为什么要有它:磁盘判定原来散在各处 —— 下载看门狗自己 {@code new StatFs(保存目录)}、
 * 重封装自己算余量、别处还要再写一遍阈值。各处各测各的,同一时刻能给出不同答案(缓存时机不同、
 * 探测目录还可能不是同一个卷),阈值也容易被复制成第二个字面量。这里把三件事收成一份:
 * <ol>
 *   <li><b>测量</b>:{@link #freeBytes()} / {@link #freeBytes(File)} —— {@link StatFs} 的
 *       {@code getAvailableBytes()},带 {@link #CACHE_TTL_MS} 缓存(按探测路径分开缓存,
 *       换存储卷不会拿旧值);测量失败给 {@link StorageGuardPolicy#FREE_UNKNOWN},<b>不当 0</b>。</li>
 *   <li><b>判定</b>:{@link #isLow()} / {@link #canWrite(long)} —— 阈值一律来自
 *       {@link StorageGuardPolicy}(当前 1GB),中控层自己不存第二个数字。</li>
 *   <li><b>文案</b>:{@link #lowMessage()} / {@link #insufficientMessage(long)} ——
 *       全应用同一句话术,不再各写各的。</li>
 * </ol>
 *
 * <p>调用方约定:
 * <ul>
 *   <li><b>写入前预检</b>用 {@link #canWrite(long)}(下载启动、导入图片/主题包这类一次性写入);
 *       它只保证"那一刻"够,持续写入的过程由下载看门狗兜底。</li>
 *   <li><b>持续写入的兜底</b>仍由下载看门狗负责(它会调 {@link #isLow()} 并把任务暂停),
 *       中控层只回答"现在低不低",<b>不做任何暂停/拦截动作</b>(动作留在业务侧,便于各自给文案)。</li>
 *   <li>测量失败(StatFs 异常)一律按"未知":{@link #isLow()} 为 false、
 *       {@link #canWrite(long)} 为 true —— 兜底判定不该因为一次测量失败把用户挡在门外。</li>
 * </ul>
 *
 * <p>纯规则在 {@link StorageGuardPolicy}(可 JVM 单测);本类只做测量与缓存。
 */
public final class StorageSpace {

    /** 可用空间缓存时长:下载热路径调用非常频繁(每 64KB 一次),statfs 至多 1 秒一次 */
    public static final long CACHE_TTL_MS = 1000L;

    /** 探测路径 -> 采样(按路径分开缓存:保存目录切到别的卷时不会拿另一个卷的旧值) */
    private static final Map<String, Sample> CACHE = new HashMap<>();

    private StorageSpace() {
    }

    /** 一次采样 */
    private static final class Sample {
        final long freeBytes;
        final long at;

        Sample(long freeBytes, long at) {
            this.freeBytes = freeBytes;
            this.at = at;
        }
    }

    // ------------------------------------------------------------------
    // 测量
    // ------------------------------------------------------------------

    /** 应用数据目录(内部存储)的可用字节;测量失败返回 {@link StorageGuardPolicy#FREE_UNKNOWN} */
    public static long freeBytes() {
        return freeBytes(null);
    }

    /**
     * 指定卷的可用字节(带 1s 缓存)。
     *
     * @param dir 目标卷上的任意目录/文件;{@code null} 或不存在时退回系统数据目录
     *              (图片、主题、数据库都落在内部存储,退回数据目录是合理兜底)
     * @return 可用字节;测量失败返回 {@link StorageGuardPolicy#FREE_UNKNOWN}(负值)
     */
    public static long freeBytes(File dir) {
        String path = probePath(dir);
        long now = System.currentTimeMillis();
        synchronized (CACHE) {
            Sample cached = CACHE.get(path);
            if (cached != null && now - cached.at < CACHE_TTL_MS) return cached.freeBytes;
        }
        long free;
        try {
            StatFs stat = new StatFs(path);
            free = stat.getAvailableBytes();
        } catch (Throwable th) {
            // 测量失败按"未知":上层据此放行,不能因为一次 statfs 异常把功能全停掉
            free = StorageGuardPolicy.FREE_UNKNOWN;
        }
        synchronized (CACHE) {
            CACHE.put(path, new Sample(free, now));
        }
        return free;
    }

    /**
     * 立刻测一次并刷新缓存(下载看门狗的巡检轮次用:它要的是"这一刻"的值,
     * 不能被热路径刚写进去的缓存挡住)。
     */
    public static long measureNow(File dir) {
        invalidate();
        return freeBytes(dir);
    }

    /** 作废全部缓存(下一次 {@link #freeBytes()} 重新测) */
    public static void invalidate() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static String probePath(File dir) {
        try {
            if (dir != null && dir.exists()) return dir.getAbsolutePath();
        } catch (Throwable ignored) {
        }
        try {
            return android.os.Environment.getDataDirectory().getAbsolutePath();
        } catch (Throwable th) {
            return "/data";
        }
    }

    // ------------------------------------------------------------------
    // 判定(阈值一律来自 StorageGuardPolicy)
    // ------------------------------------------------------------------

    /** 数据目录可用空间是否已低于兜底阈值(未知按"不低") */
    public static boolean isLow() {
        return StorageGuardPolicy.isLow(freeBytes());
    }

    /** 指定卷可用空间是否已低于兜底阈值(未知按"不低") */
    public static boolean isLow(File dir) {
        return StorageGuardPolicy.isLow(freeBytes(dir));
    }

    /** 数据目录此刻能否写下 needBytes(见 {@link StorageGuardPolicy#canWrite}) */
    public static boolean canWrite(long needBytes) {
        return StorageGuardPolicy.canWrite(freeBytes(), needBytes);
    }

    /** 指定卷此刻能否写下 needBytes(见 {@link StorageGuardPolicy#canWrite}) */
    public static boolean canWrite(File dir, long needBytes) {
        return StorageGuardPolicy.canWrite(freeBytes(dir), needBytes);
    }

    // ------------------------------------------------------------------
    // 文案(全应用同一口径)
    // ------------------------------------------------------------------

    /** 空间见底时的通用话术(已带剩余量) */
    public static String lowMessage() {
        return StorageGuardPolicy.pausedMessage(freeBytes());
    }

    /** 写入前预检不通过时的话术(已带剩余量与需清理量) */
    public static String insufficientMessage(long needBytes) {
        return StorageGuardPolicy.insufficientMessage(freeBytes(), needBytes);
    }
}

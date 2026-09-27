package com.github.tvbox.osc.util;

/**
 * 下载存储看门狗的口径(纯计算,可 JVM 单测):<b>剩余空间低于多少就停下载</b>,以及给用户看的话术。
 *
 * <p>为什么要有这条兜底:下载启动前的磁盘预检只能保证"<b>那一刻</b>空间够"(而且 m3u8 的大小本身
 * 就是估算),之后用户可能装应用、拍照、别的下载在写 —— 空间被吃光时下载还在往里写:
 * 轻则分片写入失败、合并/重封装失败,重则把手机存储塞满导致系统卡死/其它应用崩。
 * 看门狗在下载过程中持续盯可用空间,见底就<b>停掉全部任务</b>,把进度留在磁盘上(可持续)。
 *
 * <p>阈值取 {@link #LOW_STORAGE_BYTES}(1GB):比"下载完成后保留"的 {@code DownloadPolicy.MIN_FREE_SPACE}
 * (1.5GB)低一档 —— 后者是"启动前是否允许开始"的门槛,前者是"已经开始的是否必须马上停"的底线,
 * 底线定得比门槛低,才不会把正在正常收尾(合并/重封装要短暂占用 2 倍空间)的任务误停。
 *
 * <p>取不到可用空间(StatFs 异常)时按"<b>不低</b>"处理:看门狗是兜底,不能因为一次测量失败
 * 把所有下载停掉 —— 宁可漏停一次,也不要把用户的下载无故打断(启动前预检仍在把关)。
 */
public final class StorageGuardPolicy {

    /** 兜底阈值:可用空间低于 1GB 即停掉所有下载 */
    public static final long LOW_STORAGE_BYTES = 1024L * 1024 * 1024;

    /** 取不到可用空间时的"未知"标记(统一口径,避免各处用 -1/0 混着表示) */
    public static final long FREE_UNKNOWN = -1L;

    private StorageGuardPolicy() {
    }

    /**
     * 可用空间是否已低于兜底阈值。
     *
     * @param freeBytes 保存目录所在卷的可用字节;{@link #FREE_UNKNOWN} 或其它负值 = 未知
     * @return true = 必须停下载;false = 空间够,或测量失败(未知一律放行)
     */
    public static boolean isLow(long freeBytes) {
        return freeBytes >= 0 && freeBytes < LOW_STORAGE_BYTES;
    }

    /** 已因空间不足暂停时的任务文案(带剩余空间,用户据此知道要清多少) */
    public static String pausedMessage(long freeBytes) {
        String free = freeBytes >= 0 ? formatSize(freeBytes) : "未知";
        return "存储空间不足(剩余 " + free + ",低于 " + formatSize(LOW_STORAGE_BYTES) + "),已暂停下载";
    }

    /**
     * <b>写入前</b>的预检:要写下 {@code needBytes} 时够不够 —— 与 {@link #isLow(long)} 同一把尺子,
     * 即"写完之后还得留住 {@link #LOW_STORAGE_BYTES}(1GB) 的余量"。
     *
     * <p>给"下载启动前预检""导入背景图/主题包"这类一次性写入动作共用;<b>不</b>看内容的成长过程
     * (那由看门狗兜底)。测量失败({@link #FREE_UNKNOWN})一律放行:兜底判定不该因为一次 statfs
     * 失败把用户挡在门外,真写不下时写入方自己会失败并给出原因。
     *
     * @param freeBytes 目标卷可用字节;{@link #FREE_UNKNOWN} 或其它负值 = 未知
     * @param needBytes 本次要写入的字节数(负数按 0 处理)
     */
    public static boolean canWrite(long freeBytes, long needBytes) {
        if (freeBytes < 0) return true;
        if (freeBytes < LOW_STORAGE_BYTES) return false;
        long need = Math.max(0L, needBytes);
        return freeBytes - LOW_STORAGE_BYTES >= need;
    }

    /**
     * 写入前预检不通过时的用户文案(带上"还差多少",用户据此知道清多少)。
     * 未知空间不会走到这里(见 {@link #canWrite}),故不考虑 {@link #FREE_UNKNOWN}。
     */
    public static String insufficientMessage(long freeBytes, long needBytes) {
        long need = Math.max(0L, needBytes);
        long required = LOW_STORAGE_BYTES + need;
        String shortfall = (freeBytes >= 0 && required > freeBytes)
                ? formatSize(required - freeBytes) : formatSize(need);
        return "存储空间不足(剩余 " + formatSize(freeBytes) + ",还需清理约 " + shortfall + ")";
    }

    /**
     * 格式化大小(与下载侧提示同一口径:KB/MB/GB)。
     * <p>显式 {@link java.util.Locale#ROOT}:小数点是句点,不看手机语言脸色(否则德语区会出 "1,5MB",
     * 文案与日志两处对不上)。
     */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.0fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}

package com.github.tvbox.osc.share;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 进度回调载荷(不可变快照)。
 *
 * <p>为什么要 {@link Phase} 而不只是一个百分比:分享链路上"卡住"的原因完全不同,
 * 界面要说的也就不同 ——
 * <ul>
 *   <li>{@link Phase#UPLOADING} 慢 = 网速问题,可以让用户等;</li>
 *   <li>{@link Phase#WAITING_PEER} 是局域网导入特有的"等对端来拿/来传",
 *       此时进度条应该是<b>不确定态</b>,而且要同时把地址告诉用户(见 {@link #message()});</li>
 *   <li>{@link Phase#VERIFYING} 是本地运算(校验和),几毫秒就过,不该显示成卡在 99%。</li>
 * </ul>
 * 只给百分比的话,这三种都变成"98% 不动了"。
 */
public final class ShareProgress {

    public enum Phase {
        /** 组装归档/校验入参 */
        PREPARING("准备中"),
        /** 正在上传到在线平台 */
        UPLOADING("上传中"),
        /** 传完但服务端还没确认(confirm/完成分片),此时链接还不可用 */
        FINALIZING("确认中"),
        /** 局域网:已挂出地址,等对端来下载或上传(不确定态) */
        WAITING_PEER("等待局域网设备"),
        /** 正在从在线链接下载归档 */
        DOWNLOADING("下载中"),
        /** 局域网:正在接收对端上传的字节 */
        RECEIVING("接收中"),
        /** 校验完整性/清单可读性 */
        VERIFYING("校验中"),
        /** 完成(终态) */
        DONE("已完成"),
        /** 已取消(终态) */
        CANCELLED("已取消");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 是否是"总量未知"的等待态(界面应显示不确定进度) */
        public boolean isIndeterminate() {
            return this == WAITING_PEER;
        }

        public boolean isTerminal() {
            return this == DONE || this == CANCELLED;
        }
    }

    private final Phase phase;
    private final long bytes;
    private final long totalBytes;
    private final String message;

    /**
     * @param bytes      已处理字节;&lt;0 表示未知
     * @param totalBytes 总字节;&lt;=0 表示未知
     * @param message    给界面的一句话(可空);{@link Phase#WAITING_PEER} 时通常放"让对端访问的地址"
     */
    public ShareProgress(@NonNull Phase phase, long bytes, long totalBytes, @Nullable String message) {
        this.phase = phase;
        this.bytes = bytes;
        this.totalBytes = totalBytes;
        this.message = message == null ? "" : message;
    }

    @NonNull
    public static ShareProgress of(@NonNull Phase phase) {
        return new ShareProgress(phase, -1L, -1L, null);
    }

    @NonNull
    public static ShareProgress of(@NonNull Phase phase, @Nullable String message) {
        return new ShareProgress(phase, -1L, -1L, message);
    }

    @NonNull
    public Phase phase() {
        return phase;
    }

    public long bytes() {
        return bytes;
    }

    public long totalBytes() {
        return totalBytes;
    }

    @NonNull
    public String message() {
        return message;
    }

    /** 进度百分比 0~100;总量未知返回 -1(界面据此显示不确定态) */
    public int percent() {
        if (totalBytes <= 0 || bytes < 0) return -1;
        long p = bytes * 100L / totalBytes;
        if (p < 0) return 0;
        return (int) Math.min(100L, p);
    }

    @NonNull
    @Override
    public String toString() {
        return "ShareProgress{" + phase + ", " + bytes + "/" + totalBytes
                + (message.isEmpty() ? "" : ", " + message) + "}";
    }
}

package com.github.tvbox.osc.share.transport.lan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 局域网分享会话参数。
 *
 * <p>为什么局域网要有"有效期/次数"这套(在线平台是"过期天数"):局域网分享暴露的是
 * <b>本机端口 + 一份真实配置</b>,而这一般发生在同一间屋子里、几分钟内就传完了。
 * 让它默认长期挂着等于把"导出包可被同网段任意设备拉走"的窗口无限拉长;
 * 所以默认短命({@link #DEFAULT_TTL_MILLIS} = 30 分钟)且拿到一次就够用,
 * 期限到点由服务端自动撤销会话(不依赖界面来收)。
 *
 * <p>不可变,用 {@link Builder} 组装。
 */
public final class LanShareOptions {

    /** 默认会话有效期:30 分钟(够两台设备互传,又不至于把窗口开一整天) */
    public static final long DEFAULT_TTL_MILLIS = 30L * 60L * 1000L;

    /** 默认下载次数上限:0=不限(有效期本身已经把窗口限住了,再加次数上限反而容易误伤"重试一次") */
    public static final int DEFAULT_MAX_DOWNLOADS = 0;

    /** 默认上传大小上限:与"设置+Room+主题"归档的实际量级对齐(8 MB),防对端推超大文件写满磁盘 */
    public static final long DEFAULT_MAX_UPLOAD_BYTES = 8L * 1024 * 1024;

    private final long sessionTtlMillis;
    private final int maxDownloads;
    private final long maxUploadBytes;
    private final boolean allowImport;

    private LanShareOptions(Builder b) {
        this.sessionTtlMillis = b.sessionTtlMillis;
        this.maxDownloads = b.maxDownloads;
        this.maxUploadBytes = b.maxUploadBytes;
        this.allowImport = b.allowImport;
    }

    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /** 导出用默认:30 分钟有效、不限次数、同时接受对端上传(exchange 一次到位) */
    @NonNull
    public static LanShareOptions exportDefault() {
        return new Builder().build();
    }

    /** 只等对端上传(纯导入),不挂下载 */
    @NonNull
    public static LanShareOptions importOnly() {
        return new Builder().allowImport(true).build();
    }

    /** 会话有效期(毫秒);&lt;=0 用 {@link #DEFAULT_TTL_MILLIS} */
    public long sessionTtlMillis() {
        return sessionTtlMillis > 0 ? sessionTtlMillis : DEFAULT_TTL_MILLIS;
    }

    /** 下载次数上限;0=不限 */
    public int maxDownloads() {
        return maxDownloads;
    }

    /** 对端上传大小上限(字节);&lt;=0 用 {@link #DEFAULT_MAX_UPLOAD_BYTES} */
    public long maxUploadBytes() {
        return maxUploadBytes > 0 ? maxUploadBytes : DEFAULT_MAX_UPLOAD_BYTES;
    }

    /** 本会话是否接受对端上传 */
    public boolean allowImport() {
        return allowImport;
    }

    public static final class Builder {
        private long sessionTtlMillis = DEFAULT_TTL_MILLIS;
        private int maxDownloads = DEFAULT_MAX_DOWNLOADS;
        private long maxUploadBytes = DEFAULT_MAX_UPLOAD_BYTES;
        private boolean allowImport = true;

        /** 会话有效期(毫秒);<=0 用默认 */
        @NonNull
        public Builder sessionTtlMillis(long ttl) {
            this.sessionTtlMillis = ttl;
            return this;
        }

        /** 下载次数上限;<=0 不限 */
        @NonNull
        public Builder maxDownloads(int max) {
            this.maxDownloads = max;
            return this;
        }

        /** 对端上传大小上限(字节);<=0 用默认 */
        @NonNull
        public Builder maxUploadBytes(long bytes) {
            this.maxUploadBytes = bytes;
            return this;
        }

        /** 是否接受对端上传 */
        @NonNull
        public Builder allowImport(boolean allow) {
            this.allowImport = allow;
            return this;
        }

        @NonNull
        public LanShareOptions build() {
            return new LanShareOptions(this);
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "LanShareOptions{ttl=" + sessionTtlMillis() + "ms, maxDownloads=" + maxDownloads
                + ", maxUpload=" + maxUploadBytes() + ", allowImport=" + allowImport + "}";
    }
}

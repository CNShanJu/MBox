package com.github.tvbox.osc.share;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 一次导出请求:要传什么包 + 平台侧的可选参数。
 *
 * <p>参数为什么要"按平台钳制"而不是直接透传:<b>同一个天数在各平台含义不同</b> ——
 * storage.to 匿名上传只允许 1~7 天(不给就用它的默认 3 天,而账号用户能更长),
 * 局域网会话的有效期是我们自己的概念(默认几十分钟,对端拿完就撤)。
 * 所以业务只说意图(如"尽量短命、别超过 3 天"),由各平台用
 * {@link ShareLimits#clampExpiryDays(int)} 钳到自己的合法区间,而不是让界面为每个平台记一套数字。
 *
 * <p>不可变,用 {@link Builder} 组装。
 */
public final class ShareRequest {

    /** 不指定有效期:用平台默认 */
    public static final int EXPIRY_PLATFORM_DEFAULT = 0;
    /** 不限制下载次数 */
    public static final int MAX_DOWNLOADS_UNLIMITED = 0;

    private final SharePackage pkg;
    private final int expiryDays;
    private final int maxDownloads;
    private final boolean allowImport;
    private final String title;

    private ShareRequest(Builder b) {
        this.pkg = b.pkg;
        this.expiryDays = b.expiryDays;
        this.maxDownloads = b.maxDownloads;
        this.allowImport = b.allowImport;
        this.title = b.title == null ? "" : b.title;
    }

    @NonNull
    public static Builder builder(@NonNull SharePackage pkg) {
        return new Builder(pkg);
    }

    /** 最简用法:默认有效期、不限下载、同时允许对端上传导入 */
    @NonNull
    public static ShareRequest of(@NonNull SharePackage pkg) {
        return new Builder(pkg).build();
    }

    @NonNull
    public SharePackage pkg() {
        return pkg;
    }

    /** 有效期(天);{@link #EXPIRY_PLATFORM_DEFAULT}=用平台默认。平台实现须经 {@link ShareLimits} 钳制 */
    public int expiryDays() {
        return expiryDays;
    }

    /** 下载次数上限;{@link #MAX_DOWNLOADS_UNLIMITED}=不限。1 表示阅后即焚(平台支持时) */
    public int maxDownloads() {
        return maxDownloads;
    }

    /**
     * 是否同时开放"对端上传导入"。只有局域网平台会用到:导出时会挂出一个下载地址,
     * 若为 true 则同一个会话也接受对端上传(用于"两台设备互传",免去先导出再导入两步)。
     * 在线平台忽略本字段(它们没有双向会话的概念)。
     */
    public boolean allowImport() {
        return allowImport;
    }

    /** 归档标题/备注(部分平台会用作集合名),可空 */
    @NonNull
    public String title() {
        return title;
    }

    public static final class Builder {
        private final SharePackage pkg;
        private int expiryDays = EXPIRY_PLATFORM_DEFAULT;
        private int maxDownloads = MAX_DOWNLOADS_UNLIMITED;
        private boolean allowImport = true;
        private String title;

        Builder(SharePackage pkg) {
            this.pkg = pkg;
        }

        /** 有效期(天);<=0 用平台默认 */
        @NonNull
        public Builder expiryDays(int days) {
            this.expiryDays = days;
            return this;
        }

        /** 下载次数上限;<=0 不限;1=阅后即焚(平台不支持时降级为"忽略") */
        @NonNull
        public Builder maxDownloads(int max) {
            this.maxDownloads = max;
            return this;
        }

        /** 局域网:同一会话是否也接受对端上传(默认 true) */
        @NonNull
        public Builder allowImport(boolean allow) {
            this.allowImport = allow;
            return this;
        }

        @NonNull
        public Builder title(@Nullable String v) {
            this.title = v;
            return this;
        }

        @NonNull
        public ShareRequest build() {
            if (pkg == null) throw new IllegalStateException("ShareRequest 必须带 SharePackage");
            return new ShareRequest(this);
        }
    }
}

package com.github.tvbox.osc.share;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 导出结果:一条可分享出去的引用,以及它的生命周期信息。
 *
 * <p>字段为什么这么设计:
 * <ul>
 *   <li>{@link #pageUrl()} 和 {@link #directUrl()} 必须分开。storage.to 已经下线了 {@code /r/} 直链,
 *       分享出去的 {@code https://storage.to/xxxx} 是<b>下载页</b>而不是文件本身 —— 人点开能下载,
 *       程序直接 GET 拿不到字节。局域网反过来:给的是直连下载地址,没有"页面"。
 *       把两者混成一个 url,导入侧就没法判断"这个引用要不要先解析一步";</li>
 *   <li>{@link #ownerToken()} 是<b>管理凭据</b>(改有效期/下载次数上限/删除),不是给用户看的,
 *       也<b>绝不能拼进分享链接</b>:拿到它的人能删掉你的文件。所以它单独一个字段,并且
 *       {@link #toString()} 里已脱敏;</li>
 *   <li>{@link #expiresAtMillis()} 是服务端返回的真实到期时间(不是本地"现在+3天"算的),
 *       展示"还能用几天"要以它为准。</li>
 * </ul>
 *
 * <p>不可变,用 {@link Builder} 组装(字段多,构造器位置参数容易写反)。
 */
public final class ShareLink {

    private final SharePlatform platform;
    private final String pageUrl;
    private final String directUrl;
    private final String fileId;
    private final String collectionId;
    private final String ownerToken;
    private final long expiresAtMillis;
    private final int maxDownloads;
    private final long sizeBytes;
    private final String fileName;
    private final String note;

    private ShareLink(Builder b) {
        this.platform = b.platform;
        this.pageUrl = b.pageUrl == null ? "" : b.pageUrl;
        this.directUrl = b.directUrl == null ? "" : b.directUrl;
        this.fileId = b.fileId == null ? "" : b.fileId;
        this.collectionId = b.collectionId == null ? "" : b.collectionId;
        this.ownerToken = b.ownerToken == null ? "" : b.ownerToken;
        this.expiresAtMillis = b.expiresAtMillis;
        this.maxDownloads = b.maxDownloads;
        this.sizeBytes = b.sizeBytes;
        this.fileName = b.fileName == null ? "" : b.fileName;
        this.note = b.note == null ? "" : b.note;
    }

    @NonNull
    public static Builder builder(@NonNull SharePlatform platform) {
        return new Builder(platform);
    }

    @NonNull
    public SharePlatform platform() {
        return platform;
    }

    /** 给人打开的分享页地址(在线平台是下载页;局域网/本地为空或同 {@link #directUrl()}) */
    @NonNull
    public String pageUrl() {
        return pageUrl;
    }

    /** 可直接取字节的直链;空串=没有直链(必须先解析分享页,见 {@link #hasDirectUrl()}) */
    @NonNull
    public String directUrl() {
        return directUrl;
    }

    /** 服务端侧文件 id(在线平台用于后续改配置/删除;局域网为会话 id) */
    @NonNull
    public String fileId() {
        return fileId;
    }

    /** 集合 id(一次导出多个文件被打成集合时非空) */
    @NonNull
    public String collectionId() {
        return collectionId;
    }

    /**
     * 管理凭据(改有效期/下载上限/删除)。<b>不要拼进分享链接、不要落日志</b>。
     * 空串=平台不提供(如本地文件/局域网会话)。
     */
    @NonNull
    public String ownerToken() {
        return ownerToken;
    }

    /** 服务端给出的到期时间(毫秒);0=不过期或不适用 */
    public long expiresAtMillis() {
        return expiresAtMillis;
    }

    /** 下载次数上限(1=阅后即焚);0=不限 */
    public int maxDownloads() {
        return maxDownloads;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    @NonNull
    public String fileName() {
        return fileName;
    }

    /** 补充说明(如"直链已下线,请用分享页下载"),给界面提示用 */
    @NonNull
    public String note() {
        return note;
    }

    /** 是否有可直接取字节的直链 */
    public boolean hasDirectUrl() {
        return !directUrl.isEmpty();
    }

    /** 是否有可给人打开的页面地址 */
    public boolean hasPageUrl() {
        return !pageUrl.isEmpty();
    }

    /** 是否已到期(未声明到期时间的按不过期处理) */
    public boolean isExpired() {
        return expiresAtMillis > 0 && System.currentTimeMillis() >= expiresAtMillis;
    }

    /** 用户分享时该复制的字符串:优先页面地址(人可读),没有才退直链 */
    @NonNull
    public String shareableText() {
        if (hasPageUrl()) return pageUrl;
        return directUrl;
    }

    /** 界面展示用(不含 ownerToken) */
    @NonNull
    @Override
    public String toString() {
        return "ShareLink{platform=" + platform.id() + ", page=" + pageUrl
                + ", direct=" + (directUrl.isEmpty() ? "-" : "yes")
                + ", expiresAt=" + expiresAtMillis + ", maxDownloads=" + maxDownloads
                + ", ownerToken=" + (ownerToken.isEmpty() ? "-" : "<hidden>") + "}";
    }

    /** 组装器(字段多,避免位置参数写反;不校验组合合法性,由平台实现保证) */
    public static final class Builder {
        private final SharePlatform platform;
        private String pageUrl;
        private String directUrl;
        private String fileId;
        private String collectionId;
        private String ownerToken;
        private long expiresAtMillis;
        private int maxDownloads;
        private long sizeBytes;
        private String fileName;
        private String note;

        Builder(SharePlatform platform) {
            this.platform = platform;
        }

        @NonNull
        public Builder pageUrl(@Nullable String v) {
            this.pageUrl = v;
            return this;
        }

        @NonNull
        public Builder directUrl(@Nullable String v) {
            this.directUrl = v;
            return this;
        }

        @NonNull
        public Builder fileId(@Nullable String v) {
            this.fileId = v;
            return this;
        }

        @NonNull
        public Builder collectionId(@Nullable String v) {
            this.collectionId = v;
            return this;
        }

        @NonNull
        public Builder ownerToken(@Nullable String v) {
            this.ownerToken = v;
            return this;
        }

        @NonNull
        public Builder expiresAtMillis(long v) {
            this.expiresAtMillis = v;
            return this;
        }

        @NonNull
        public Builder maxDownloads(int v) {
            this.maxDownloads = v;
            return this;
        }

        @NonNull
        public Builder sizeBytes(long v) {
            this.sizeBytes = v;
            return this;
        }

        @NonNull
        public Builder fileName(@Nullable String v) {
            this.fileName = v;
            return this;
        }

        @NonNull
        public Builder note(@Nullable String v) {
            this.note = v;
            return this;
        }

        @NonNull
        public ShareLink build() {
            return new ShareLink(this);
        }
    }
}

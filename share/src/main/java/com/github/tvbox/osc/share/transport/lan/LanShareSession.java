package com.github.tvbox.osc.share.transport.lan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 一个已挂出的局域网分享会话(不可变快照)。
 *
 * <p>{@link #token()} 是本会话的<b>唯一凭据</b>:服务端每条 {@code /share/...} 路径都校验它。
 * 因此它同时是"链接"和"密码"——<b>可以</b>给对端(不给就对端打不开),
 * 但不要写进业务日志的常规字段里(见 {@link ShareLink} 对 ownerToken 的同类处理)。
 * 与会话有效期绑定:过期即失效。
 *
 * <p>{@link #downloadUrl()} / {@link #importUrl()} 已由 :app 侧按本机可达地址拼好
 * (多网卡时取首选那个),模块不自己拼 IP —— 取本机局域网地址是 Android 平台知识,
 * 属于 :app 的职责(见 LanShareHost 的注释)。
 */
public final class LanShareSession {

    private final String sessionId;
    private final String token;
    private final String fileName;
    private final long sizeBytes;
    private final String downloadUrl;
    private final String importUrl;
    private final long expiresAtMillis;
    private final boolean allowImport;
    private final int maxDownloads;

    public LanShareSession(@Nullable String sessionId, @NonNull String token,
                           @Nullable String fileName, long sizeBytes,
                           @Nullable String downloadUrl, @Nullable String importUrl,
                           long expiresAtMillis, boolean allowImport, int maxDownloads) {
        this.sessionId = sessionId == null ? "" : sessionId;
        this.token = token == null ? "" : token;
        this.fileName = fileName == null ? "" : fileName;
        this.sizeBytes = sizeBytes;
        this.downloadUrl = downloadUrl == null ? "" : downloadUrl;
        this.importUrl = importUrl == null ? "" : importUrl;
        this.expiresAtMillis = expiresAtMillis;
        this.allowImport = allowImport;
        this.maxDownloads = maxDownloads;
    }

    /** 服务端内部会话 id(撤销时用;对端看不到) */
    @NonNull
    public String sessionId() {
        return sessionId;
    }

    /** 会话令牌(对端访问凭据) */
    @NonNull
    public String token() {
        return token;
    }

    /** 对端将下载到的文件名 */
    @NonNull
    public String fileName() {
        return fileName;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    /** 对端下载地址;<b>空串</b>=本会话不提供下载(纯接收导入) */
    @NonNull
    public String downloadUrl() {
        return downloadUrl;
    }

    /** 对端上传页地址;<b>空串</b>=本会话不接受上传 */
    @NonNull
    public String importUrl() {
        return importUrl;
    }

    /** 会话到期时间(毫秒,本地时钟) */
    public long expiresAtMillis() {
        return expiresAtMillis;
    }

    public boolean allowImport() {
        return allowImport;
    }

    /** 下载次数上限;0=不限 */
    public int maxDownloads() {
        return maxDownloads;
    }

    public boolean canDownload() {
        return !downloadUrl.isEmpty();
    }

    public boolean canImport() {
        return allowImport && !importUrl.isEmpty();
    }

    /** 是否已过期(未声明到期时间按不过期处理) */
    public boolean isExpired() {
        return expiresAtMillis > 0 && System.currentTimeMillis() >= expiresAtMillis;
    }

    /** 剩余有效毫秒数;&lt;0 表示已过期;0 表示无期限 */
    public long remainingMillis() {
        if (expiresAtMillis <= 0) return 0L;
        return expiresAtMillis - System.currentTimeMillis();
    }

    /** 给用户看的一行:优先给出对端该访问的地址 */
    @NonNull
    public String hint() {
        if (canDownload()) return downloadUrl;
        if (canImport()) return importUrl;
        return "";
    }

    @NonNull
    @Override
    public String toString() {
        return "LanShareSession{id=" + sessionId + ", file=" + fileName + ", size=" + sizeBytes
                + ", expiresAt=" + expiresAtMillis + ", token=" + (token.isEmpty() ? "-" : "<hidden>") + "}";
    }
}

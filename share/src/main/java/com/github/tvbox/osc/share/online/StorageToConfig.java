package com.github.tvbox.osc.share.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.config.PrefsDataStore;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;

import java.security.SecureRandom;

/**
 * storage.to 在线分享的配置门面(配置门面模式,与 SystemConfig/LiveConfig 等一致:
 * 数据自持 + 模块内持久化 + 变更订阅)。
 *
 * <p>为什么基址与开关要<b>可配</b>而不是写死常量:
 * <ol>
 *   <li><b>换平台/换域名不用发版</b>:官方改域名、被墙需要走镜像、企业自建反代,
 *       都只是改一个键。这是"在线平台将来不可用能提前换掉"最省事的那一半 ——
 *       换了基址 API 完全一致时,连 {@code ShareTransport} 实现都不用动;</li>
 *   <li><b>能整体关掉</b>:用户不想把配置传到第三方时,一键让在线平台报不可用
 *       ({@link #isEnabled()} 参与 {@code StorageToTransport.availability()}),
 *       注册表会自动把默认平台让给局域网/本地文件,界面不用改逻辑;</li>
 *   <li><b>匿名身份令牌持久化</b>:storage.to 用 {@code X-Visitor-Token} 把同一台设备的
 *       上传归到一起(它<b>不是</b>账号、不是鉴权凭据,只是随机标识,丢了不影响已上传文件的可用性)。
 *       本机只存一份,不要每次上传新生成 —— 那会让服务端的"你最近的上传"变得毫无意义。</li>
 * </ol>
 *
 * <p>注意:<b>不要</b>把用户身份信息(设备名/账号/手机号)塞进 visitor token,
 * 它会被送到第三方服务;它必须是纯粹随机的、不可关联到人的值。
 */
public final class StorageToConfig {

    /** 官方基址(不要带 {@code /api};接口路径在 StorageToApi 里统一拼 {@code /api/...}) */
    public static final String DEFAULT_BASE_URL = "https://storage.to";

    /** 匿名上传默认有效期(天):文档「限制一览」——默认 3 天 */
    public static final int DEFAULT_EXPIRY_DAYS = 3;

    /**
     * 匿名上传有效期上限(天):文档写明"通过 expiry_days 最长 7 天",更长需要付费账号。
     * <p>注:官方 CLI 的 README 曾写 1~7 天,与文档一致;文档另注明完整 API 的
     * {@code /file|collection/{id}/expiry} 传 {@code null} 表示永久,但<b>仅高级版</b>,
     * 本项目按匿名客户端处理,不提供该选项。
     */
    public static final int MAX_ANONYMOUS_EXPIRY_DAYS = 7;

    /** 匿名上传单文件上限(字节):25 GB(付费 100 GB) */
    public static final long MAX_FILE_BYTES = 25L * 1024 * 1024 * 1024;

    /**
     * 匿名每日文件数上限:<b>50 个 / 滚动 24 小时</b>(每设备或每 IP)。
     * <p>依据官方 API 文档「身份验证 / 限制一览」(2026-09 核对);
     * 官方 CLI 的 README 里写的 20 是旧值,以文档为准。
     */
    public static final int MAX_UPLOADS_PER_DAY = 50;

    /**
     * 匿名上传带宽配额(字节 / 滚动 24 小时):<b>每个访客令牌 100 GB,每个 IP 500 GB</b>,
     * 两者任一超限都会收到 429。与"文件数"是两个独立上限,所以本地预检除数量外还要看总量。
     * <p>精确余量可查 {@code GET /api/bandwidth/status}(见 {@code StorageToApi.bandwidthStatus})。
     */
    public static final long VISITOR_BANDWIDTH_BYTES_PER_DAY = 100L * 1024 * 1024 * 1024;
    public static final long IP_BANDWIDTH_BYTES_PER_DAY = 500L * 1024 * 1024 * 1024;

    /** {@code /upload/init} 对此大小以上的文件返回分片上传(文档:大于 50 MB) */
    public static final long MULTIPART_THRESHOLD_BYTES = 50L * 1024 * 1024;

    // 键名带 share_ 前缀:与其它模块的设置键隔离,便于"数据备份还原"单独识别/清理
    private static final String KEY_BASE_URL = "share_storageto_base_url";
    private static final String KEY_ENABLED = "share_storageto_enabled";
    private static final String KEY_VISITOR_TOKEN = "share_storageto_visitor_token";
    private static final String KEY_EXPIRY_DAYS = "share_storageto_expiry_days";

    /** 请求 UA:官方按 UA 做统计/限流,给一个能自识别的值便于对方排查(也便于我们看服务端日志) */
    private static final String USER_AGENT = "MBox-Android/1.0 (+storage.to share)";

    private static final SecureRandom RANDOM = new SecureRandom();

    private StorageToConfig() {
    }

    // ── 基址 ──

    /** 平台基址(结尾无斜杠);未配置/配置为空回落官方地址 */
    @NonNull
    public static String baseUrl() {
        String v = PrefsDataStore.getString(KEY_BASE_URL, "");
        String b = v == null ? "" : v.trim();
        if (b.isEmpty()) return DEFAULT_BASE_URL;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        // 只接受 http(s):基址来自用户输入,别让它变成 file:// 或自定义 scheme 去读本地文件
        if (!b.startsWith("http://") && !b.startsWith("https://")) return DEFAULT_BASE_URL;
        return b;
    }

    public static void setBaseUrl(@Nullable String url) {
        String v = url == null ? "" : url.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        if (v.equals(baseUrl())) return;
        if (v.isEmpty() || v.equals(DEFAULT_BASE_URL)) {
            PrefsDataStore.delete(KEY_BASE_URL);
        } else {
            PrefsDataStore.put(KEY_BASE_URL, v);
        }
    }

    public static boolean isDefaultBaseUrl() {
        return DEFAULT_BASE_URL.equals(baseUrl());
    }

    // ── 开关 ──

    /** 在线分享是否启用(默认开);关掉后在线平台会自我报不可用,默认平台自动让给局域网/本地 */
    public static boolean isEnabled() {
        return PrefsDataStore.getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean on) {
        if (isEnabled() == on) return;
        PrefsDataStore.put(KEY_ENABLED, on);
    }

    // ── 有效期 ──

    /** 导出有效期(天);0=用平台默认(见 {@link #DEFAULT_EXPIRY_DAYS}) */
    public static int expiryDays() {
        int v = PrefsDataStore.getInt(KEY_EXPIRY_DAYS, 0);
        if (v <= 0) return 0;
        return Math.min(v, MAX_ANONYMOUS_EXPIRY_DAYS);
    }

    public static void setExpiryDays(int days) {
        int v = Math.max(0, Math.min(days, MAX_ANONYMOUS_EXPIRY_DAYS));
        if (expiryDays() == v) return;
        PrefsDataStore.put(KEY_EXPIRY_DAYS, v);
    }

    // ── 匿名身份令牌 ──

    /**
     * 取本机的匿名 visitor token,没有就生成并持久化。
     * 格式 {@code mbox_<32位十六进制>}:前缀便于服务端/日志区分来源,长度与官方 CLI 的
     * {@code cli_<32hex>} 同构(16 字节随机)。
     */
    @NonNull
    public static String visitorToken() {
        String existing = PrefsDataStore.getString(KEY_VISITOR_TOKEN, "");
        if (existing != null && !existing.trim().isEmpty()) return existing.trim();
        String token = generateToken();
        PrefsDataStore.put(KEY_VISITOR_TOKEN, token);
        return token;
    }

    /** 重置匿名身份(隐私设置里的"清除上传标识"用);下次上传会生成新的 */
    public static void resetVisitorToken() {
        PrefsDataStore.delete(KEY_VISITOR_TOKEN);
        LogStore.log(Category.SYSTEM, "分享: 已重置 storage.to 匿名标识");
    }

    /** 是否已有匿名标识(界面据此说明"上传会被归到本机标识下") */
    public static boolean hasVisitorToken() {
        String v = PrefsDataStore.getString(KEY_VISITOR_TOKEN, "");
        return v != null && !v.trim().isEmpty();
    }

    @NonNull
    private static String generateToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(5 + bytes.length * 2);
        sb.append("mbox_");
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 请求 User-Agent */
    @NonNull
    public static String userAgent() {
        return USER_AGENT;
    }
}

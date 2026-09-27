package com.github.tvbox.osc.share;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 分享平台标识(可插拔)。
 *
 * <p>为什么用枚举而不是散落的字符串:分享/导入导出的"去哪"只有这几种,枚举把
 * 「平台 → 传输实现」的映射固定下来,新增平台(如后续换掉 storage.to)只加一枚枚举值 +
 * 一个 {@link com.github.tvbox.osc.share.transport.ShareTransport} 实现,调用方(UI/门面)不改。
 *
 * <p>注意:{@link #id()} 是<b>持久化键</b>(用于记住"用户上次选的平台",见 online.StorageToConfig 一类
 * 的配置门面),枚举名可变、id 不可变;解析一律走 {@link #fromId(String)},不要用 {@code valueOf}。
 */
public enum SharePlatform {

    /** storage.to 在线文件分享(当前在线实现;不可用时换 {@link #fromId} 之外的其它在线平台)。 */
    STORAGE_TO("storage_to", "storage.to"),

    /** 局域网:本机 HTTP 服务端,同网段设备下载导出包 / 上传导入包(需设置里开启"局域网服务")。 */
    LAN("lan", "局域网"),

    /** 本地文件:导出到应用缓存目录走系统分享(始终可用,不依赖网络)。 */
    LOCAL_FILE("local_file", "本地文件");

    private final String id;
    private final String label;

    SharePlatform(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /** 持久化 id(稳定,勿改) */
    @NonNull
    public String id() {
        return id;
    }

    /** 给用户看的名字(界面文案;需要多语言时由界面按 {@link #id()} 自行映射) */
    @NonNull
    public String label() {
        return label;
    }

    /** 按持久化 id 解析;未知/空返回 {@code null}(调用方决定回落策略) */
    @Nullable
    public static SharePlatform fromId(@Nullable String id) {
        if (id == null) return null;
        for (SharePlatform p : values()) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }
}

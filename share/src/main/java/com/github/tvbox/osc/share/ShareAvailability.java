package com.github.tvbox.osc.share;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 平台可用性:能不用 + 不能用时的原因。
 *
 * <p>为什么不让界面自己判:可用性往往取决于<b>本模块看不到的状态</b> —— 比如局域网分享要求
 * "设置里开了局域网服务"且"当前运行的 HTTP 服务真的绑到了局域网网卡"(后者是重启才生效的,
 * 光看开关会误报,见 :app 侧 ControlManager 的 LAN_ACTIVE/LAN_PENDING_RESTART 区分)。
 * 这类判断只能由持有该状态的实现回答,界面只读结论。
 *
 * <p>{@link #reason()} 是<b>给用户看</b>的一句话(如"请先在设置里开启局域网服务"),
 * 界面可直接展示,也可以按 {@link ShareException#code()} 换自己的文案。
 */
public final class ShareAvailability {

    private static final ShareAvailability AVAILABLE =
            new ShareAvailability(true, "可用");

    private final boolean available;
    private final String reason;

    private ShareAvailability(boolean available, String reason) {
        this.available = available;
        this.reason = reason == null ? "" : reason;
    }

    /** 可用 */
    @NonNull
    public static ShareAvailability available() {
        return AVAILABLE;
    }

    /** 不可用,并说明原因(给用户看) */
    @NonNull
    public static ShareAvailability unavailable(@Nullable String reason) {
        return new ShareAvailability(false, reason == null || reason.trim().isEmpty()
                ? "当前不可用" : reason);
    }

    /** 按条件二选一,省掉调用方的三目 */
    @NonNull
    public static ShareAvailability of(boolean available, @Nullable String unavailableReason) {
        return available ? available() : unavailable(unavailableReason);
    }

    public boolean isAvailable() {
        return available;
    }

    /** 不可用原因;可用时给一句"可用"占位 */
    @NonNull
    public String reason() {
        return reason;
    }

    @NonNull
    @Override
    public String toString() {
        return "ShareAvailability{" + (available ? "available" : "unavailable: " + reason) + "}";
    }
}

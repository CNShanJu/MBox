package com.github.tvbox.osc.share;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 分享链路上的统一异常:必带 {@link ShareErrorCode},文案可空(空则用码的兜底文案)。
 *
 * <p>继承 {@link Exception}(受检)而不是 RuntimeException:分享/导入导出都是"用户按了按钮、
 * 结果必须回到界面"的操作,漏 catch 会在 UI 线程炸掉;受检能逼调用方至少想一下失败路径。
 * 回调式 API({@link ShareCallback})内部会把它转成 {@code onError},不会真的抛穿到界面。
 */
public class ShareException extends Exception {

    private static final long serialVersionUID = 1L;

    private final ShareErrorCode code;

    public ShareException(@NonNull ShareErrorCode code) {
        this(code, code.defaultMessage(), null);
    }

    public ShareException(@NonNull ShareErrorCode code, @Nullable String message) {
        this(code, message, null);
    }

    public ShareException(@NonNull ShareErrorCode code, @Nullable String message, @Nullable Throwable cause) {
        super(message == null || message.trim().isEmpty() ? code.defaultMessage() : message, cause);
        this.code = code;
    }

    @NonNull
    public ShareErrorCode code() {
        return code;
    }

    /** 便捷构造:网络失败 */
    @NonNull
    public static ShareException network(@Nullable String message, @Nullable Throwable cause) {
        return new ShareException(ShareErrorCode.NETWORK, message, cause);
    }

    /** 便捷构造:平台不可用(界面据此引导用户去开开关/换平台) */
    @NonNull
    public static ShareException unavailable(@Nullable String reason) {
        return new ShareException(ShareErrorCode.UNAVAILABLE, reason, null);
    }

    /** 便捷构造:平台不支持该能力 */
    @NonNull
    public static ShareException unsupported(@Nullable String message) {
        return new ShareException(ShareErrorCode.UNSUPPORTED, message, null);
    }

    /** 便捷构造:入参不合法 */
    @NonNull
    public static ShareException invalid(@Nullable String message) {
        return new ShareException(ShareErrorCode.INVALID_INPUT, message, null);
    }
}

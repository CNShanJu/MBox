package com.github.tvbox.osc.share.internal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.share.ShareCallback;
import com.github.tvbox.osc.share.ShareErrorCode;
import com.github.tvbox.osc.share.ShareException;
import com.github.tvbox.osc.share.ShareProgress;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 回调句柄:把"线程 + 恰好一次终态 + 取消后不再回调"这三条约定收在一个地方。
 *
 * <p>为什么必须有它(而不是让每个实现自己 {@code callback.onSuccess(...)}):
 * <ul>
 *   <li><b>恰好一次</b>:上传是"后台线程 PUT + 回调里收尾 + 用户随时点取消"三头并进,
 *       很容易出现"取消已经报了 CANCELLED,紧接着网络失败又报一次 error"或者
 *       "成功回调发了两次"。{@link AtomicBoolean} 一次性闸门从结构上堵掉;</li>
 *   <li><b>取消后丢弃</b>:取消那一刻不可能真的掐断已经发出的 HTTP 请求,
 *       <b>迟到的结果必须有地方被扔掉</b> —— 就是这里;</li>
 *   <li><b>主线程</b>:所有出口都经 {@link ShareMainThread#post},实现方不用各自小心。</li>
 * </ul>
 *
 * <p>实现方典型用法:每次操作开头 {@code ShareCallbackHandle.of(callback)},
 * 中途 {@link #progress},收尾 {@link #success}/{@link #error};需要判"这次操作还算不算数"
 * 时问 {@link #isTerminal()}(已取消即 true,循环应当主动退出)。
 */
public final class ShareCallbackHandle<T> {

    private final ShareCallback<T> target;
    private final AtomicBoolean terminal = new AtomicBoolean(false);

    private ShareCallbackHandle(ShareCallback<T> target) {
        this.target = target;
    }

    @NonNull
    public static <T> ShareCallbackHandle<T> of(@Nullable ShareCallback<T> callback) {
        // 分开写而不是三目:NO_OP_CALLBACK 是 ShareCallback<Object>,三目会把类型推到 Object,
        // 返回值就变成 ShareCallbackHandle<Object> 而无法赋给 ShareCallbackHandle<T>。
        return new ShareCallbackHandle<T>(callback == null ? ShareCallbackHandle.<T>noOp() : callback);
    }

    @SuppressWarnings("unchecked")
    private static <T> ShareCallback<T> noOp() {
        return (ShareCallback<T>) NO_OP_CALLBACK;
    }

    /** 进度:终态后丢弃(丢在调用线程,省一次 post) */
    public void progress(@Nullable final ShareProgress progress) {
        if (progress == null || terminal.get()) return;
        ShareMainThread.post(new Runnable() {
            @Override
            public void run() {
                if (!terminal.get()) target.onProgress(progress);
            }
        });
    }

    /** 成功(终态,恰好一次) */
    public void success(@NonNull final T result) {
        if (!terminal.compareAndSet(false, true)) return;
        ShareMainThread.post(new Runnable() {
            @Override
            public void run() {
                target.onSuccess(result);
            }
        });
    }

    /** 失败(终态,恰好一次) */
    public void error(@NonNull final ShareException error) {
        if (!terminal.compareAndSet(false, true)) return;
        ShareMainThread.post(new Runnable() {
            @Override
            public void run() {
                target.onError(error);
            }
        });
    }

    /** 以"已取消"收尾(终态,恰好一次);已完成/已失败时是空操作 */
    public void cancel() {
        error(new ShareException(ShareErrorCode.CANCELLED));
    }

    /** 是否已走到终态(含已取消):后台循环每轮开头查它,及时停手别白干 */
    public boolean isTerminal() {
        return terminal.get();
    }

    /** 测试/日志用:是否已取消(而非正常完成) */
    public boolean isCancelled() {
        return terminal.get();
    }

    /** 缺省回调:调用方传 null 时兜底,省掉实现里一排 null 判断 */
    private static final ShareCallback<Object> NO_OP_CALLBACK = new ShareCallback<Object>() {
        @Override
        public void onProgress(@NonNull ShareProgress progress) {
        }

        @Override
        public void onSuccess(@NonNull Object result) {
        }

        @Override
        public void onError(@NonNull ShareException error) {
        }
    };
}

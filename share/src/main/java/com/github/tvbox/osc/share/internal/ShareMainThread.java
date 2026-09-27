package com.github.tvbox.osc.share.internal;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

/**
 * 主线程派发(模块内部工具)。
 *
 * <p>所有对外回调都必须落在主线程({@link com.github.tvbox.osc.share.ShareCallback} 的线程约定),
 * 而实现内部一律在后台线程跑 IO。与其让每个实现各写一份 {@code Handler},不如收在这里:
 * 一个静态 Handler(绑主 Looper) + {@link #post} 的"已在主线程就直接跑"短路。
 *
 * <p>短路那一步不是微优化:上传前的<b>入参校验失败</b>会在调用方线程同步返回,
 * 若一律 {@code post},同一次调用里"先 onError 返回、再执行后面的代码"的顺序就被打乱了,
 * 调用方会先看到错误、再走到无意义的后续步骤。
 */
public final class ShareMainThread {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ShareMainThread() {
    }

    /** 当前是否在主线程 */
    public static boolean isMain() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    /** 切到主线程执行(已在主线程则立即执行) */
    public static void post(@NonNull Runnable action) {
        if (isMain()) {
            action.run();
        } else {
            MAIN.post(action);
        }
    }
}

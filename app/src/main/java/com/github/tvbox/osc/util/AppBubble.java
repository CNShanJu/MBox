package com.github.tvbox.osc.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.github.tvbox.osc.base.App;

/**
 * 统一提醒组件（系统 Toast 样式）：
 * 直接使用系统 Toast（Toast.makeText），与 jar/三方库弹出的 toast 视觉统一。
 * <p>
 * 说明：早期版本为自定义圆角气泡（跟随 app 主题配色），但 jar 爬虫内部直接弹系统 Toast，
 * 两种样式并存观感不一；现统一为系统 Toast 样式（Android 13+ 自动为系统圆角胶囊样式）。
 * 用法:AppBubble.toast("xxx") / AppBubble.toastLong("xxx")
 * <p>
 * 连续提示去重:系统 Toast 默认排队显示(前一条播完才播下一条),动作高频/连续触发时
 * 会出现"动作早结束、toast 还在一条条补播"的延迟堆积;这里持有当前 Toast 引用,
 * 每次弹新提示前 cancel 掉上一条(含正在显示/排队中的),后者直接顶掉前者,只保留最新。
 * <p>
 * 调试辅助(仅 debug 构建):toast 触发时经 {@link ToastTracer} 打印调用栈到 logcat(tag=ToastTrace),
 * 定位"不知道哪里冒出来的 toast";release 构建 ToastTracer 为空实现,产物零残留。
 */
public class AppBubble {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 上一条 Toast(正在显示或仍在系统队列中);弹新提示前 cancel 实现"后到顶替" */
    private static Toast sCurrentToast;

    private AppBubble() {
    }

    public static void toast(final CharSequence msg) {
        show(msg, false);
    }

    public static void toast(final int resId) {
        toast(App.getInstance().getString(resId));
    }

    public static void toastLong(final CharSequence msg) {
        show(msg, true);
    }

    public static void toastLong(final int resId) {
        toastLong(App.getInstance().getString(resId));
    }

    private static void show(final CharSequence msg, final boolean longDuration) {
        if (msg == null || msg.length() == 0) return;
        ToastTracer.log(msg); // debug:打印触发调用栈; release:no-op
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // 顶掉上一条仍在显示/排队的 toast,避免连续触发时延迟堆积
                    if (sCurrentToast != null) {
                        sCurrentToast.cancel();
                        sCurrentToast = null;
                    }
                    Context ctx = App.getInstance();
                    Toast toast = Toast.makeText(ctx, msg,
                            longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
                    sCurrentToast = toast;
                    toast.show();
                } catch (Throwable ignored) {
                    sCurrentToast = null;
                }
            }
        });
    }
}

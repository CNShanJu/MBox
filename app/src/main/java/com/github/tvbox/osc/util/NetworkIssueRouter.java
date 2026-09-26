package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.state.SystemState;
import com.github.tvbox.osc.state.SystemStateMonitor;
import com.github.tvbox.osc.ui.activity.NoNetworkActivity;

/**
 * "网络不可用"页的路由：网络层报告"断网 + 真实请求失败"时把页面拉起来。
 * <p>
 * 为什么触发点在网络层而不是这里监听系统状态：用户要的是"<b>断网且发起了网络请求</b>才跳页" ——
 * 只是断网、但用户正在看本地视频/本地文件时不该被弹一屏。系统网络状态单点
 * （{@link SystemStateMonitor}，下载侧已在用）在页面侧负责"有网自动返回"，这里只做"该不该弹"。
 * <p>
 * 抑制与节流：① 网络层已有 3s 节流（整屏内容失败会有几十个请求同时抛）；② 这里再过一道最小间隔；
 * ③ 用户点过"我知道了"后，本次断网期间不再自动弹（网络恢复自动解除）；④ 后台不弹。
 */
public final class NetworkIssueRouter {

    private static final String TAG = "NetworkIssueRouter";

    /** 两次弹页的最小间隔（网络层已节流，这里再兜一层：不同客户端可能各报一次） */
    private static final long MIN_SHOW_INTERVAL_MS = 3000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile long lastShownAt = 0L;
    /** 用户点了"我知道了"：本次断网期间不再自动弹；恢复联网后自动解除 */
    private static volatile boolean dismissedForThisOutage = false;
    /** 安装一次即可（进程级；监听列表用写时复制，不会残留无效引用） */
    private static volatile boolean installed = false;

    private NetworkIssueRouter() {
    }

    /** app 启动时调用一次（组合根）：接上网络层通知 + 订阅"网络恢复"解除抑制 */
    public static void install() {
        if (installed) return;
        installed = true;
        try {
            OkGoHelper.addNetworkIssueListener(NetworkIssueRouter::onNetworkIssue);
        } catch (Throwable th) {
            android.util.Log.w(TAG, "注册网络问题监听失败", th);
        }
        try {
            SystemStateMonitor.get().register(e -> {
                if (e != null && SystemStateMonitor.TYPE_NETWORK.equals(e.type)
                        && !SystemStateMonitor.VAL_NONE.equals(e.value)) {
                    // 恢复联网：解除"我知道了"的抑制，下次断网照常提示
                    dismissedForThisOutage = false;
                }
            }, SystemStateMonitor.TYPE_NETWORK);
        } catch (Throwable th) {
            android.util.Log.w(TAG, "订阅网络状态失败", th);
        }
    }

    /** 用户点"我知道了"：本次断网期间不再自动弹出（手动返回/返回键不受影响） */
    public static void dismissForThisOutage() {
        dismissedForThisOutage = true;
    }

    /** 网络层回调（可能在任意请求线程）：切主线程再决定是否弹 */
    private static void onNetworkIssue(String reason) {
        MAIN.post(() -> show(reason));
    }

    private static void show(String reason) {
        try {
            if (dismissedForThisOutage) return;
            long now = System.currentTimeMillis();
            if (now - lastShownAt < MIN_SHOW_INTERVAL_MS) return;
            SystemState state = SystemStateMonitor.get().getCurrentState();
            if (state != null && !state.appForeground) return;      // 后台不弹（Android 10+ 也禁止后台起 Activity）
            Activity activity = AppManager.getInstance().isActivity()
                    ? AppManager.getInstance().currentActivity() : null;
            if (activity == null || activity.isFinishing()) return;
            if (activity instanceof NoNetworkActivity) return;      // 已经在无网络页
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) return;
            lastShownAt = now;
            Intent intent = new Intent(activity, NoNetworkActivity.class);
            intent.putExtra(NoNetworkActivity.EXTRA_REASON, reason == null ? "" : reason);
            activity.startActivity(intent);
        } catch (Throwable th) {
            android.util.Log.w(TAG, "拉起无网络页失败", th);
        }
    }
}

package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
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
 * ③ 用户点过"我知道了"后，一段时间内（或到下次网络恢复为止）不再自动弹；④ 后台不弹；⑤ <b>当前确实有可用链路时不弹</b>
 * （见 {@link #hasUsableLink()} —— 这是"连着 Wi-Fi 但取不到内容"时每 3 秒闪一屏的根因）；
 * ⑥ <b>正在播放时不弹</b>（播放器/小窗里断网只该在播放器内提示，弹整屏页会把小窗顶掉）。
 */
public final class NetworkIssueRouter {

    private static final String TAG = "NetworkIssueRouter";

    /** 两次弹页的最小间隔（网络层已节流，这里再兜一层：不同客户端可能各报一次） */
    private static final long MIN_SHOW_INTERVAL_MS = 3000L;

    /** "我知道了"的抑制时长：没有网络变化事件时也能自然解除（原来只在"恢复联网事件"里解除 → 有链路取不到内容时等于永久抑制） */
    private static final long DISMISS_WINDOW_MS = 10 * 60 * 1000L;

    /** 弹页后多久内被动返回算"白弹一屏"（用于自我抑制） */
    private static final long QUICK_DISMISS_MS = 2500L;
    /** 连续白弹几次就抑制到下次真实网络变化 */
    private static final int QUICK_DISMISS_LIMIT = 2;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile long lastShownAt = 0L;
    /** 弹页时刻（uptimeMillis，供"白弹"判定） */
    private static volatile long lastShownUptime = 0L;
    /** 用户点了"我知道了"：抑制到 DISMISS_WINDOW_MS 之后，或网络恢复即解除 */
    private static volatile long dismissedUntil = 0L;
    /** 连续"弹了就被动返回"的次数；达到阈值后抑制到下次真实网络变化 */
    private static volatile int quickDismissStreak = 0;
    private static volatile boolean suppressedUntilNetworkChange = false;
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
            SystemStateMonitor.registerSafe(e -> {
                if (e != null && SystemStateMonitor.TYPE_NETWORK.equals(e.type)
                        && !SystemStateMonitor.VAL_NONE.equals(e.value)) {
                    // 恢复联网：解除"我知道了"的抑制与"白弹"抑制，下次断网照常提示
                    dismissedUntil = 0L;
                    suppressedUntilNetworkChange = false;
                    quickDismissStreak = 0;
                }
            }, SystemStateMonitor.TYPE_NETWORK);
        } catch (Throwable th) {
            android.util.Log.w(TAG, "订阅网络状态失败", th);
        }
    }

    /** 用户点"我知道了"：一段时间内不再自动弹出（手动返回/返回键不受影响） */
    public static void dismissForThisOutage() {
        dismissedUntil = System.currentTimeMillis() + DISMISS_WINDOW_MS;
    }

    /**
     * 无网络页在"<b>被动</b>返回"(自动 finish,不是用户点按钮)时回报。
     * <p>
     * 连续两次"弹出来没一会儿就自己走了"说明当时根本不是真断网（典型：连着 Wi-Fi 但 DNS 被拦/DoH 挂了），
     * 这时把自动弹页抑制到下次真实网络变化，避免和页面的重试组成"3 秒一闪"的节拍器。
     */
    public static void reportAutoDismissed() {
        if (SystemClock.uptimeMillis() - lastShownUptime > QUICK_DISMISS_MS) {
            quickDismissStreak = 0;
            return;
        }
        if (++quickDismissStreak < QUICK_DISMISS_LIMIT) return;
        suppressedUntilNetworkChange = true;
        android.util.Log.i(TAG, "无网络页连续 " + quickDismissStreak + " 次弹出即返回,抑制到下次网络变化");
        try {
            LogStore.log(Category.SYSTEM, "网络不可用页连续弹出即返回,已抑制自动弹页到下次网络变化");
        } catch (Throwable ignored) {
        }
    }

    /** 网络层回调（可能在任意请求线程）：切主线程再决定是否弹 */
    private static void onNetworkIssue(String reason) {
        // 一行"网络层报了问题"的日志:排查"为什么没跳页"时先看有没有这一行 ——
        // 没有 = 请求没走收口客户端(或不是网络类失败);有 = 再看下面"不弹页: xxx"的原因
        android.util.Log.i(TAG, "网络层报告: " + reason);
        MAIN.post(() -> show(reason));
    }

    private static void show(String reason) {
        try {
            long now = System.currentTimeMillis();
            if (now < dismissedUntil) {
                logSkip("用户点过\"我知道了\",抑制窗口内不再自动弹");
                return;
            }
            if (suppressedUntilNetworkChange) {
                logSkip("连续弹出即返回已触发抑制(等下次网络变化)");
                return;
            }
            if (now - lastShownAt < MIN_SHOW_INTERVAL_MS) {
                logSkip("距上次弹页不足 " + MIN_SHOW_INTERVAL_MS + "ms");
                return;
            }
            SystemState state = SystemStateMonitor.get() == null ? null : SystemStateMonitor.get().getCurrentState();
            if (state != null && !state.appForeground) {
                logSkip("应用不在前台(后台不弹,Android 10+ 也禁止后台起 Activity)");
                return;
            }
            // 硬条件:当前<b>有可用链路</b>时不弹整屏页。
            // 请求失败(UnknownHost/DNS 被拦/DoH 挂了/源站域名不存在)不等于"没网";这时弹整屏页
            // 既不对(网是通的),又会被页面的"有网自动返回"立刻收掉 → 与页面重试组成 3s 节拍器。
            if (hasUsableLink()) {
                logSkip("当前有可用链路,不弹整屏页(请求失败原因: " + reason + ")");
                return;
            }
            // 正在播放(播放器/小窗)时不弹:弹不透明整屏页会把小窗顶掉、也打断观看,
            // 断流该由播放器自己的错误提示承担(见 VodController.setTip)。
            if (isPlaybackActive()) {
                logSkip("正在播放,不弹整屏页(原因: " + reason + ")");
                return;
            }
            Activity activity = AppManager.getInstance().isActivity()
                    ? AppManager.getInstance().currentActivity() : null;
            if (activity == null || activity.isFinishing()) {
                logSkip("当前没有可用的页面");
                return;
            }
            if (activity instanceof NoNetworkActivity) {
                logSkip("已经在无网络页");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
                logSkip("当前页面已销毁");
                return;
            }
            lastShownAt = now;
            lastShownUptime = SystemClock.uptimeMillis();
            Intent intent = new Intent(activity, NoNetworkActivity.class);
            intent.putExtra(NoNetworkActivity.EXTRA_REASON, reason == null ? "" : reason);
            activity.startActivity(intent);
            android.util.Log.i(TAG, "已拉起无网络页(原因: " + reason + ")");
            try {
                LogStore.log(Category.SYSTEM, "网络不可用页已拉起(原因: " + reason + ")");
            } catch (Throwable ignored) {
            }
        } catch (Throwable th) {
            android.util.Log.w(TAG, "拉起无网络页失败", th);
        }
    }

    /**
     * 当前是否有可用链路（判定与页面侧"有网自动返回"同一口径：{@link SystemStateMonitor}）。
     * 单点未就绪时退回网络层的宽松判定；都读不到时按"有链路"处理（不弹比误弹安全）。
     */
    private static boolean hasUsableLink() {
        try {
            SystemStateMonitor monitor = SystemStateMonitor.get();
            if (monitor == null) return OkGoHelper.hasNetwork();
            SystemState state = monitor.getCurrentState();
            if (state == null) return OkGoHelper.hasNetwork();
            return !SystemStateMonitor.VAL_NONE.equals(state.network);
        } catch (Throwable th) {
            return true;
        }
    }

    /** 是否有播放会话在跑（播放器把会话登记在 :player 的 PlaybackSessions 里；直播/小窗同页） */
    private static boolean isPlaybackActive() {
        try {
            return com.github.tvbox.osc.player.api.PlaybackSessions.activeCount() > 0;
        } catch (Throwable th) {
            return false;
        }
    }

    /** 不弹页的原因写进日志:否则"断网了为什么没跳"只能靠猜 */
    private static void logSkip(String why) {
        android.util.Log.i(TAG, "不弹无网络页: " + why);
    }
}

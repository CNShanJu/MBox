package com.github.tvbox.osc.update;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.ui.dialog.UpdateNoteDialog;
import com.github.tvbox.osc.util.AppBubble;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 更新检查的共用入口:把"检查 → 发现新版本弹更新说明 → 用户点立即更新开始下载"这段固定动作收在一处,
 * 供两处复用——
 * <ul>
 *   <li>「我的-关于-检查更新」按钮({@link com.github.tvbox.osc.ui.dialog.AboutDialog}):{@link #check(Context, Listener)}</li>
 *   <li>启动自动检查(首页"上次看到"气泡消失后):{@link #autoCheckOnce(Context, Runnable)}</li>
 * </ul>
 * 无新版本/检查失败时按 {@code silent} 决定是否提示,避免启动时弹无意义的提示打扰用户。
 */
public final class UpdateCheck {

    /** 检查结果回调(供"关于"页把状态显示在底部弹窗里,可为 null) */
    public interface Listener {
        void onChecking();

        /**
         * 检查完成。
         *
         * @param newVersion null=已是最新
         * @return true=新版本的说明弹窗由调用方负责弹(例如底部弹窗要先等自己退场动画收完再弹,
         *         避免两个弹窗硬切显得僵硬);false=由 {@link UpdateCheck} 立即弹(启动自动检查等无宿主弹窗的场景)
         */
        boolean onResult(UpdateInfo newVersion);

        void onFailed(String message);
    }

    /** 每个进程只自动检查一次(启动检查的语义:一次启动最多检查一次) */
    private static final AtomicBoolean AUTO_CHECKED = new AtomicBoolean(false);

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateCheck() {
    }

    /**
     * 检查更新;有新版本则弹更新说明弹窗,用户点"立即更新"后开始下载(进度交全局悬浮圈)。
     *
     * @param listener 可为 null;仅用于调用方展示自己的状态文案
     */
    public static void check(final Context context, final Listener listener) {
        if (context == null) return;
        if (listener != null) listener.onChecking();
        final Updater updater = UpdaterProvider.get();
        updater.checkUpdate(context, new Updater.Callback() {
            @Override
            public void onCheckStart() {
                if (listener != null) listener.onChecking();
            }

            @Override
            public void onCheckResult(final UpdateInfo newVersion) {
                if (newVersion == null) {
                    if (listener != null) listener.onResult(null);
                    return;
                }
                // 调用方要自己编排弹窗时机(如等底部弹窗退场动画收完)时,这里就不抢着弹
                boolean handledByCaller = listener != null && listener.onResult(newVersion);
                if (!handledByCaller) {
                    showNote(context, newVersion);
                }
            }

            @Override
            public void onDownloadProgress(long current, long total) {
            }

            @Override
            public void onDownloadReady(UpdateInfo info) {
            }

            @Override
            public void onError(String message) {
                if (listener != null) listener.onFailed(message);
            }
        });
    }

    /**
     * 弹更新说明弹窗(宿主弹窗自己编排时机时由调用方调用,见 {@link Listener#onResult})。
     */
    public static void showNote(final Context context, final UpdateInfo info) {
        if (context == null || info == null) return;
        final Updater updater = UpdaterProvider.get();
        UpdateNoteDialog.show(context, info, () -> startDownload(context, updater, info));
    }

    /**
     * 启动自动检查(受"自动检查更新"开关控制,默认开):
     * 每个进程只跑一次;无新版本/失败都不提示,只在真的发现新版本时弹更新说明弹窗。
     *
     * @param onFinished 检查结束(无论结果)后回调,可为 null
     */
    public static void autoCheckOnce(final Context context, final Runnable onFinished) {
        if (context == null) return;
        if (SystemConfig.consumeInternalRestart()) {
            if (onFinished != null) onFinished.run();
            return;
        }
        if (!SystemConfig.isAutoCheckUpdate()) {
            if (onFinished != null) onFinished.run();
            return;
        }
        if (!AUTO_CHECKED.compareAndSet(false, true)) {
            if (onFinished != null) onFinished.run();
            return;
        }
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "更新: 启动自动检查更新");
        check(context, new Listener() {
            @Override
            public void onChecking() {
            }

            @Override
            public boolean onResult(UpdateInfo newVersion) {
                com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, newVersion == null
                        ? "更新: 启动自动检查,已是最新" : "更新: 启动自动检查,发现新版本 v" + newVersion.versionName);
                if (onFinished != null) MAIN.post(onFinished);
                if (newVersion != null) {
                    MAIN.post(() -> {
                        if (context instanceof Activity) {
                            Activity host = (Activity) context;
                            if (host.isFinishing() || host.isDestroyed()
                                    || com.blankj.utilcode.util.ActivityUtils.getTopActivity() != host) return;
                        }
                        if (SystemConfig.claimAutoUpdatePrompt(newVersion.versionName)) showNote(context, newVersion);
                    });
                }
                return true;
            }

            @Override
            public void onFailed(String message) {
                com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.SYSTEM, "更新: 启动自动检查失败 " + message);
                if (onFinished != null) MAIN.post(onFinished);
            }
        });
    }

    /** 下载并安装:进度与控制交全局悬浮圈(UpdateFloatIndicator),与"关于"页手动更新动作一致 */
    private static void startDownload(final Context context, final Updater updater, final UpdateInfo info) {
        if (context == null || updater == null || info == null) return;
        AppBubble.toast("下载已开始，长按气泡管理");
        updater.downloadAndInstall(context, info, new Updater.Callback() {
            @Override
            public void onCheckStart() {
            }

            @Override
            public void onCheckResult(UpdateInfo i) {
            }

            @Override
            public void onDownloadProgress(long current, long total) {
            }

            @Override
            public void onDownloadReady(UpdateInfo i) {
            }

            @Override
            public void onError(String message) {
                AppBubble.toast(message);
            }
        });
    }
}

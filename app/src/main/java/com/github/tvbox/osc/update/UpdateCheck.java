package com.github.tvbox.osc.update;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.ui.startup.UserStartupGate;
import com.github.tvbox.osc.ui.dialog.UpdateNoteDialog;
import com.github.tvbox.osc.util.AppBubble;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.interfaces.SimpleCallback;

/**
 * 更新检查的共用入口:把"检查 → 发现新版本弹更新说明 → 用户点立即更新开始下载"这段固定动作收在一处,
 * 供两处复用——
 * <ul>
 *   <li>「我的-检查更新」入口:{@link #check(Context, Listener)}</li>
 *   <li>启动自动检查(首页"上次看到"气泡消失后):{@link #autoCheckOnce(Context, Runnable)}</li>
 * </ul>
 * 无新版本/检查失败时按 {@code silent} 决定是否提示,避免启动时弹无意义的提示打扰用户。
 */
public final class UpdateCheck {

    /** 检查结果回调(供手动检查入口提示结果,可为 null) */
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

    /** 手动检查优先于本进程排队或在途的自动检查。 */
    private static final UpdateCheckGate CHECK_GATE = new UpdateCheckGate();

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
        CHECK_GATE.onManualCheck();
        performCheck(context, listener);
    }

    private static void performCheck(final Context context, final Listener listener) {
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
        final UpdateManager manager = UpdateManager.get();
        UpdateNoteDialog dialog = new UpdateNoteDialog(context, info,
                action -> performUpdateAction(context, updater, info, action));
        UpdateManager.Listener listener = (state, downloaded, total, current) ->
                bindNoteAction(dialog, info, state, current);
        LifecycleEventObserver cleanup = removeListenerOnDestroy(dialog, manager, listener);
        bindNoteAction(dialog, info, manager.getState(), manager.getInfo());
        dialog.show(new SimpleCallback() {
            @Override
            public void onShow(BasePopupView popupView) {
                manager.addListener(listener);
                bindNoteAction(dialog, info, manager.getState(), manager.getInfo());
            }

            @Override
            public void onDismiss(BasePopupView popupView) {
                manager.removeListener(listener);
                dialog.getLifecycle().removeObserver(cleanup);
            }
        });
    }

    private static LifecycleEventObserver removeListenerOnDestroy(LifecycleOwner owner,
                                                                  UpdateManager manager,
                                                                  UpdateManager.Listener listener) {
        LifecycleEventObserver cleanup = new LifecycleEventObserver() {
            @Override
            public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
                // XPopup 的宿主销毁路径不一定调用 onDismiss。
                if (event == Lifecycle.Event.ON_DESTROY) {
                    manager.removeListener(listener);
                    source.getLifecycle().removeObserver(this);
                }
            }
        };
        owner.getLifecycle().addObserver(cleanup);
        return cleanup;
    }

    private static void bindNoteAction(UpdateNoteDialog dialog, UpdateInfo requested,
                                       UpdateManager.State state, UpdateInfo current) {
        String label;
        String status = null;
        UpdatePromptPolicy.Action action = UpdatePromptPolicy.action(state, current, requested);
        switch (action) {
            case VIEW_PROGRESS:
                label = "查看进度";
                String version = current == null || current.versionName == null
                        ? "" : " v" + current.versionName;
                status = (state == UpdateManager.State.PAUSED ? "更新下载已暂停" : "正在下载更新") + version;
                break;
            case INSTALL:
                label = "立即安装";
                status = "安装包已下载完成";
                break;
            case RETRY:
                label = "重试下载";
                status = "上次下载失败，可重试下载";
                break;
            default:
                label = "立即更新";
                break;
        }
        dialog.setUpdateAction(action, label, status);
    }

    private static void performUpdateAction(Context context, Updater updater, UpdateInfo info,
                                           UpdatePromptPolicy.Action selected) {
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) return;
        }
        UpdateManager manager = UpdateManager.get();
        // 弹窗显示到点击之间状态可能变化,按实际执行时的状态再次判断。
        switch (UpdatePromptPolicy.actionAtExecution(selected, manager.getState(), manager.getInfo(), info)) {
            case VIEW_PROGRESS:
                if (manager.getInfo() != null) {
                    UpdateIndicatorDialog progress = new UpdateIndicatorDialog(context);
                    removeListenerOnDestroy(progress, manager, progress);
                    progress.show();
                } else {
                    AppBubble.toast("当前更新任务已结束");
                }
                break;
            case INSTALL:
                if (manager.installCurrent(context)) AppBubble.toast("正在安装新版...");
                else AppBubble.toast("暂时无法安装，请检查安装权限后重试");
                break;
            case UNAVAILABLE:
                AppBubble.toast("安装包状态已变化，请重新检查更新");
                break;
            default:
                startDownload(context, updater, info);
                break;
        }
    }

    /**
     * 用户主动启动时的自动检查(受"自动检查更新"开关控制,默认开):
     * 每个进程只跑一次;无新版本/失败都不提示,只在真的发现新版本时弹更新说明弹窗。
     *
     * @param onFinished 检查结束(无论结果)后回调,可为 null
     */
    public static void autoCheckOnce(final Context context, final Runnable onFinished) {
        if (context == null) return;
        if (!(context instanceof UserStartupGate)
                || !((UserStartupGate) context).isUserInitiatedLaunch()) {
            if (onFinished != null) onFinished.run();
            return;
        }
        if (!SystemConfig.isAutoCheckUpdate()) {
            if (onFinished != null) onFinished.run();
            return;
        }
        if (!CHECK_GATE.beginAutoCheck()) {
            if (onFinished != null) onFinished.run();
            return;
        }
        performCheck(context, new Listener() {
            @Override
            public void onChecking() {
            }

            @Override
            public boolean onResult(UpdateInfo newVersion) {
                if (newVersion != null) {
                    com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM,
                            "更新: 发现新版本 v" + newVersion.versionName);
                }
                if (onFinished != null) MAIN.post(onFinished);
                if (newVersion != null && CHECK_GATE.mayShowAutoPrompt()
                        && SystemConfig.isAutoCheckUpdate()) {
                    MAIN.post(() -> {
                        // 网络回调与主线程排队期间都可能发生手动检查。
                        if (!CHECK_GATE.mayShowAutoPrompt() || !SystemConfig.isAutoCheckUpdate()) return;
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
        updater.downloadAndInstall(context, info, new Updater.Callback() {
            @Override
            public void onDownloadStart() {
                AppBubble.toast("下载已开始，长按气泡管理");
            }

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

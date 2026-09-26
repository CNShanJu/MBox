package com.github.tvbox.osc.util;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;

import androidx.activity.ComponentActivity;
import androidx.annotation.RequiresApi;
import androidx.lifecycle.Lifecycle;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.constant.IntentKey;

import java.util.ArrayList;
import java.util.List;

/**
 * 画中画(小窗)通用辅助器。
 * <p>
 * 封装:进入小窗、小窗操作按钮(上一集/播放暂停/下一集)、放大/点X关闭的区分与处理、
 * 点X关闭带回前台、onConfigurationChanged 兜底(部分设备点X关闭不触发
 * onPictureInPictureModeChanged)。
 * <p>
 * 用法(宿主 Activity):
 * <ol>
 * <li>创建 PipHelper 并实现 {@link Callback};</li>
 * <li>在 onStart / onResume / onPictureInPictureModeChanged /
 * onConfigurationChanged 里转发给本类;</li>
 * <li>点画中画按钮时调用 {@link #enterPip()}。</li>
 * </ol>
 * 本地视频播放器(LocalPlayActivity)等后续可直接复用。
 */
public class PipHelper {

    /** 宿主 Activity 需提供的钩子 */
    public interface Callback {
        /** 视频是否播放中 */
        boolean isPlaying();

        /** 播放/暂停切换 */
        void togglePlay();

        /** 暂停(幂等:未播放时无操作;播放器 resume 是异步的,暂停必须显式调用) */
        void pause();

        /** 上一集 */
        void playPrevious();

        /** 下一集 */
        void playNext();

        /** 是否全屏 */
        boolean isFullscreen();

        /** 进入全屏(进入小窗前先切全屏,让小窗只显示视频画面;始终全屏的页面可空实现) */
        void enterFullscreen();

        /** 退出全屏(点X关闭时切回详情页;始终全屏的页面可空实现) */
        void exitFullscreen();

        /** 视频宽高(用于计算小窗宽高比),返回 null 用 16:9 */
        int[] getVideoSize();

        /** 通知栏"关闭"按钮(后台播放)回调 */
        void onClose();
    }

    private final ComponentActivity activity;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean pipActive = false;
    private boolean pipPrevPlaying = false;
    /** 点X关闭后的待暂停标记:带回前台触发 onResume 时消费,保证回到详情页一定是暂停 */
    private boolean pausePending = false;
    /** 点X关闭带回前台标记:消费一次,用于触发"小窗放大铺满"进入动画 */
    private boolean closeReturnFlag = false;
    private Rational pipRatio = new Rational(16, 9);
    private BroadcastReceiver remoteActionReceiver;

    public PipHelper(ComponentActivity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    /** 是否处于小窗会话中 */
    public boolean isActive() {
        return pipActive;
    }

    /** 进入小窗(画中画) */
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void enterPip() {
        if (!Utils.supportsPiPMode())
            return;
        pausePending = false; // 新会话开始,清掉上次点X可能残留的待暂停标记
        closeReturnFlag = false;
        pipPrevPlaying = callback.isPlaying();
        pipActive = true;
        int[] size = callback.getVideoSize();
        int vWidth = size != null && size.length >= 2 ? size[0] : 0;
        int vHeight = size != null && size.length >= 2 ? size[1] : 0;
        Rational ratio;
        if (vWidth != 0 && vHeight != 0) {
            if (((double) vWidth) / vHeight > 2.39) {
                vHeight = (int) (vWidth / 2.35);
            }
            ratio = new Rational(vWidth, vHeight);
        } else {
            ratio = new Rational(16, 9);
        }
        pipRatio = ratio;
        PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(ratio)
                .setActions(buildActions()).build();
        // 未全屏先切全屏,让小窗只显示视频画面
        main.postDelayed(() -> {
            if (pipActive && !callback.isFullscreen()) {
                callback.enterFullscreen();
            }
        }, 300);
        registerReceiver();
        activity.enterPictureInPictureMode(params);
        // 进入小窗时 onPause 会暂停视频,若进入前在播放则恢复
        main.postDelayed(() -> {
            if (pipActive && pipPrevPlaying && !callback.isPlaying()) {
                callback.togglePlay();
            }
            refreshActions();
        }, 400);
    }

    /** 转发 Activity.onPictureInPictureModeChanged */
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        if (isInPictureInPictureMode) {
            registerReceiver();
        } else {
            unregisterReceiver();
            scheduleExitCheck();
        }
    }

    /** 转发 Activity.onConfigurationChanged(兜底:部分设备点X关闭不触发上面的回调) */
    public void onConfigurationChanged() {
        if (pipActive && !activity.isInPictureInPictureMode()) {
            scheduleExitCheck();
        }
    }

    /** 转发 Activity.onStart(放大回前台时清掉会话标记) */
    public void onActivityStarted() {
        if (pipActive) {
            pipActive = false;
        }
    }

    /**
     * 转发 Activity.onResume:点X关闭后带回前台会触发播放器自动恢复(异步生效),
     * 这里显式暂停多次(幂等),保证无论小窗内是播放还是暂停,回到详情页都是暂停:
     * - 立即暂停:若异步 start 已生效则当场压住;
     * - 250ms/500ms 兜底:等播放器异步 start 落定后再压,避免"播放一下"或直接播起来。
     */
    public void onActivityResumed() {
        if (pausePending) {
            pausePending = false;
            callback.pause();
            main.postDelayed(() -> callback.pause(), 250);
            main.postDelayed(() -> callback.pause(), 500);
        }
    }

    /** 是否处于"点X关闭带回前台"的首次前台,消费一次(供宿主播放"小窗放大铺满"动画) */
    public boolean consumePipCloseAnimation() {
        boolean v = closeReturnFlag;
        closeReturnFlag = false;
        return v;
    }

    /** 注册/注销操作广播接收器(后台播放 PlayService 也共用) */
    public void setReceiverEnabled(boolean enabled) {
        if (enabled) {
            registerReceiver();
        } else {
            unregisterReceiver();
        }
    }

    private void scheduleExitCheck() {
        if (!pipActive)
            return;
        main.postDelayed(this::checkExit, 600);
    }

    private void checkExit() {
        if (!pipActive)
            return;
        boolean inForeground = activity.getLifecycle().getCurrentState() == Lifecycle.State.STARTED
                || activity.getLifecycle().getCurrentState() == Lifecycle.State.RESUMED;
        if (inForeground) {
            // 放大:系统已把 Activity 带回前台,保持全屏播放
            pipActive = false;
            return;
        }
        // 点X关闭:仍在后台 -> 暂停 + 退出全屏(切回详情页) + 带回前台
        pipActive = false;
        pausePending = true; // 带回前台 onResume 时补暂停
        closeReturnFlag = true; // 带回前台时播放"小窗放大铺满"动画
        if (callback.isPlaying()) {
            callback.pause();
        }
        if (callback.isFullscreen()) {
            callback.exitFullscreen();
        }
        bringToFront();
    }

    /** 把宿主 Activity 所在任务带回前台 */
    private void bringToFront() {
        try {
            ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                for (ActivityManager.AppTask task : am.getAppTasks()) {
                    ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    if (info != null && info.baseActivity != null
                            && activity.getClass().getName().equals(info.baseActivity.getClassName())) {
                        task.moveToFront();
                        return;
                    }
                }
            }
            Intent intent = new Intent(activity, activity.getClass());
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private List<RemoteAction> buildActions() {
        boolean playing = callback.isPlaying();
        List<RemoteAction> actions = new ArrayList<>();
        actions.add(generateAction(R.drawable.ic_play_pre, IntentKey.BROADCAST_ACTION_PREV, "上一集", "上一集"));
        actions.add(generateAction(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                IntentKey.BROADCAST_ACTION_PLAYPAUSE, playing ? "暂停" : "播放", "播放/暂停"));
        actions.add(generateAction(R.drawable.ic_play_next, IntentKey.BROADCAST_ACTION_NEXT, "下一集", "下一集"));
        return actions;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private RemoteAction generateAction(int iconResId, int actionCode, String title, String desc) {
        PendingIntent pi = PendingIntent.getBroadcast(activity, actionCode,
                new Intent(IntentKey.BROADCAST_ACTION)
                        .putExtra("action", actionCode)
                        .setPackage(activity.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE);
        return new RemoteAction(Icon.createWithResource(activity, iconResId), title, desc, pi);
    }

    /** 刷新小窗操作按钮(播放/暂停图标跟随播放状态) */
    private void refreshActions() {
        if (!activity.isInPictureInPictureMode())
            return;
        try {
            activity.setPictureInPictureParams(new PictureInPictureParams.Builder()
                    .setAspectRatio(pipRatio)
                    .setActions(buildActions())
                    .build());
        } catch (Throwable ignored) {
        }
    }

    private void registerReceiver() {
        if (remoteActionReceiver != null)
            return;
        remoteActionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !IntentKey.BROADCAST_ACTION.equals(intent.getAction()))
                    return;
                int code = intent.getIntExtra("action", 1);
                if (code == IntentKey.BROADCAST_ACTION_PREV) {
                    callback.playPrevious();
                    refreshActions();
                } else if (code == IntentKey.BROADCAST_ACTION_PLAYPAUSE) {
                    callback.togglePlay();
                    refreshActions();
                } else if (code == IntentKey.BROADCAST_ACTION_NEXT) {
                    callback.playNext();
                    refreshActions();
                } else if (code == IntentKey.BROADCAST_ACTION_CLOSE) {
                    callback.onClose();
                }
            }
        };
        BroadcastUtils.registerReceiverExported(activity, remoteActionReceiver,
                new IntentFilter(IntentKey.BROADCAST_ACTION));
    }

    private void unregisterReceiver() {
        if (remoteActionReceiver != null) {
            activity.unregisterReceiver(remoteActionReceiver);
            remoteActionReceiver = null;
        }
    }
}

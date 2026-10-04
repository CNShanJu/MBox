package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;

import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.NotificationUtils;
import com.blankj.utilcode.util.SPUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.bean.CastVideo;
import com.github.tvbox.osc.constant.CacheConst;
import com.github.tvbox.osc.databinding.ActivityLocalPlayBinding;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.PlayerSession;
import com.github.tvbox.osc.player.api.PlayConfig;
import com.github.tvbox.osc.player.controller.LocalVideoController;
import com.github.tvbox.osc.ui.dialog.AllLocalSeriesDialog;
import com.github.tvbox.osc.ui.dialog.CastListDialog;
import com.github.tvbox.osc.ui.dialog.DialogCoordinator;
import com.github.tvbox.osc.ui.dialog.PlayingControlRightDialog;
import com.github.tvbox.osc.util.BroadcastUtils;
import com.github.tvbox.osc.util.PipHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.player.SubtitleCoordinator;
import com.github.tvbox.osc.R;
import com.gyf.immersionbar.BarHide;
import com.gyf.immersionbar.ImmersionBar;
import com.google.common.reflect.TypeToken;
import com.lxj.xpopup.core.BasePopupView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import xyz.doikki.videoplayer.player.ProgressManager;
import xyz.doikki.videoplayer.player.VideoView;

public class LocalPlayActivity extends BaseVbActivity<ActivityLocalPlayBinding> {


    private MyVideoView mVideoView;
    LocalVideoController mController;
    private PlayerSession mPlaySession;
    private SubtitleCoordinator mSubtitleCoordinator;
    JSONObject mVodPlayerCfg;
    private List<VideoInfo> mVideoList = new ArrayList<>();
    private int mPosition;
    /** 电池百分比订阅(经 SystemStateMonitor,替代 EventBus 电量广播) */
    private com.github.tvbox.osc.state.SystemStateMonitor.Listener mBatteryListener;
    private BasePopupView mAllSeriesRightDialog;
    private PipHelper pipHelper;
    private int automaticErrorRetries;
    private boolean retryingAfterError;
    @Override
    protected void init() {
        mVideoView = mBinding.player;
        mVideoView.startFullScreen();
        Bundle bundle = getIntent().getExtras();
        String videoListJson =  bundle.getString("videoList");
        mVideoList = GsonUtils.fromJson(videoListJson, new TypeToken<List<VideoInfo>>(){}.getType());
        mPosition = bundle.getInt("position", 0);

        initController();
        initPipHelper();
        initPlayerCfg();
        mVideoView.setVideoController(mController); //设置控制器
        // 电池图标:经 SystemStateMonitor 订阅百分比变化(主线程回调)
        com.github.tvbox.osc.state.SystemStateMonitor monitor = com.github.tvbox.osc.state.SystemStateMonitor.get();
        if (monitor != null) {
            mBatteryListener = e -> {
                if (e != null && com.github.tvbox.osc.state.SystemStateMonitor.TYPE_BATTERY_LEVEL.equals(e.type)
                        && mController != null && mController.mMyBatteryView != null) {
                    try {
                        mController.mMyBatteryView.updateBattery(Integer.parseInt(e.value));
                    } catch (Throwable ignored) {
                    }
                }
            };
            monitor.register(mBatteryListener, com.github.tvbox.osc.state.SystemStateMonitor.TYPE_BATTERY_LEVEL);
            if (mController.mMyBatteryView != null) {
                mController.mMyBatteryView.updateBattery(monitor.getBatteryPercent());
            }
        }
        play(false);

        new Handler()
                .postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mVideoView.getCurrentPlayState() == VideoView.STATE_PREPARED){//不知道为啥部分长视频(不确定是不是因为时长/大小)会卡在准备完成状态,所以延迟重置下状态
                            mVideoView.pause();
                            mVideoView.resume();
                        }
                    }
                },500);
    }



    /**
     * 跳转到上/下一集,需重新播放
     */
    private void play(boolean fromSkip) {
        if (!retryingAfterError) automaticErrorRetries = 0;
        retryingAfterError = false;
        VideoInfo videoInfo = mVideoList.get(mPosition);

        String path = videoInfo.getPath();
        // 先回到全局默认,再装载该文件曾保存的播放设置(播放器/解码/倍速/缩放/片头片尾偏移),
        // 避免上一文件的配置残留在会话对象里串到本文件;setPlayerConfig 同时刷新控制器按钮文案
        fillCfgDefaults();
        loadSavedCfgForPath(path);
        if (mController != null) mController.setPlayerConfig(mVodPlayerCfg);

        String uri = "";
        File file = new File(path);
        if(file.exists()){
            uri = Uri.parse("file://"+file.getAbsolutePath()).toString();
        }
        mController.setTitle(videoInfo.getDisplayName());
        mVideoView.setUrl(uri); //设置视频地址

        mVideoView.setProgressManager(new ProgressManager() {
            @Override
            public void saveProgress(String url, long progress) {// 就本地视频页面用sp,其余用Hawk
                //有点本地文件确实总时长,设置下总时长,为什么用path,因为电影列表要通过媒体文件的path获取缓存的时长/进度,存取报纸缓存的key一直
                SPUtils.getInstance(CacheConst.VIDEO_DURATION_SP).put(path, mVideoView.getDuration());
                SPUtils.getInstance(CacheConst.VIDEO_PROGRESS_SP).put(path, progress);
            }

            @Override
            public long getSavedProgress(String url) {
                long saved = SPUtils.getInstance(CacheConst.VIDEO_PROGRESS_SP).getLong(path);
                // "跳过片头":与在线播放一致,起播位置取 保存进度 与 st(片头跳过秒数) 的较大者
                int st = 0;
                try {
                    st = mVodPlayerCfg.getInt("st");
                } catch (Throwable ignored) {
                }
                return Math.max(saved, st * 1000L);
            }
        });

        PlayerHelper.updateCfg(mVideoView, mVodPlayerCfg);

        if (fromSkip){
            mVideoView.replay(true);
        } else if (mVideoView.getCurrentPlayState() == VideoView.STATE_ERROR) {
            mVideoView.replay(false);
        }else {
            mVideoView.start(); //开始播放，不调用则不自动播放
        }
    }

    /** 本地文件播放设置持久化:SP 文件名固定,key=文件绝对路径 */
    private static final String LOCAL_CFG_SP = "video_local_player_cfg";

    /** 当前正在播放的文件路径 */
    private String currentPath() {
        if (mVideoList == null || mPosition < 0 || mPosition >= mVideoList.size()) return null;
        VideoInfo info = mVideoList.get(mPosition);
        return info == null ? null : info.getPath();
    }

    /** 把曾为该文件保存的播放设置合并进当前配置(保持 mVodPlayerCfg 对象同一性) */
    private void loadSavedCfgForPath(String path) {
        try {
            if (path == null || mVodPlayerCfg == null) return;
            String saved = SPUtils.getInstance(LOCAL_CFG_SP).getString(path, null);
            if (saved == null || saved.isEmpty()) return;
            JSONObject jo = new JSONObject(saved);
            java.util.Iterator<String> it = jo.keys();
            while (it.hasNext()) {
                String key = it.next();
                mVodPlayerCfg.put(key, jo.get(key));
            }
        } catch (Throwable ignored) {
        }
    }

    private void initController() {
        mController = new LocalVideoController(this);
        mController.setListener(new LocalVideoController.VodControlListener() {

            @Override
            public void chooseSeries() {
                showAllSeriesDialog();
            }

            @Override
            public void playNext(boolean rmProgress) {
//                String preProgressKey = progressKey;
//                LocalPlayActivity.this.playNext(rmProgress);
                if (mPosition == mVideoList.size() - 1){
                    AppBubble.toast("当前已经是最后一集了");
                } else {
                    mPosition++;
                    play(true);
                }
            }

            @Override
            public void playPre() {
                //playPrevious();
                if (mPosition == 0){
                    AppBubble.toast("当前已经是第一集了");
                }else {
                    mPosition--;
                    play(true);
                }
            }

            @Override
            public void changeParse(ParseBean pb) {

            }

            @Override
            public void updatePlayerCfg() {
                // 持久化当前播放设置(播放器/解码/倍速/缩放/片头片尾偏移),按本地文件路径保存,
                // 下次播放同一文件自动恢复(片头片尾调节/重置因此生效)
                String path = currentPath();
                if (path != null && mVodPlayerCfg != null) {
                    SPUtils.getInstance(LOCAL_CFG_SP).put(path, mVodPlayerCfg.toString());
                }
            }

            @Override
            public void replay(boolean replay) {
                // 底栏/设置里的 重播 与 刷新:
                // 重播(clearProgress=true,来自 play_retry)= 从头开始;
                // 刷新(replay=false,来自 play_refresh / 切换播放器后重新装载)= 重载当前文件按保存进度续播
                if (replay) {
                    String path = currentPath();
                    if (path != null) {
                        SPUtils.getInstance(CacheConst.VIDEO_PROGRESS_SP).remove(path);
                    }
                }
                play(replay);
            }

            @Override
            public void errReplay() {
                if (automaticErrorRetries >= 2) return;
                VideoInfo current = mVideoList.get(mPosition);
                String detail = "本地播放错误: " + current.getDisplayName() + "，内核="
                        + (mVodPlayerCfg == null ? -1 : mVodPlayerCfg.optInt("pl", -1));
                com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.PLAYER, detail);
                android.util.Log.e("MBoxPlayer", detail);
                if (automaticErrorRetries >= 1) {
                    automaticErrorRetries = 2;
                    AppBubble.toast("播放失败，请检查文件或切换播放器");
                    return;
                }
                automaticErrorRetries++;
                retryingAfterError = true;
                int failedPosition = mPosition;
                // 等当前内核的错误回调退出，再重置播放器，避免在回调栈内重入同一个内核。
                mVideoView.post(() -> {
                    if (isFinishing() || isDestroyed() || mVideoView == null
                            || mPosition != failedPosition
                            || mVideoView.getCurrentPlayState() != VideoView.STATE_ERROR) {
                        retryingAfterError = false;
                        return;
                    }
                    play(false);
                });
            }

            @Override
            public void selectSubtitle() {
                openLocalSubtitleDialog();
            }

            @Override
            public void selectAudioTrack() {
                if (mSubtitleCoordinator != null) mSubtitleCoordinator.openAudioTrackDialog();
            }

            @Override
            public void prepared() {
                initLocalSubtitleView();
            }

            @Override
            public void toggleFullScreen() {
                finish();
            }

            @Override
            public void exit() {
                finish();
            }

            @Override
            public void showSetting() {
                // 本地播放设置:与在线全屏播放共用播放设置抽屉(功能一致)
                mController.hideBottom();
                DialogCoordinator.right(LocalPlayActivity.this,
                        new PlayingControlRightDialog(LocalPlayActivity.this, mController, mVideoView),
                        320, true).show();
            }

            @Override
            public void cast() {
                showCastDialog();
            }

            @Override
            public void pip() {
                enterPip();
            }
        });

        // 本地字幕:与在线全屏共用 SubtitleCoordinator(依赖 SubtitleController 契约,不耦合具体控制器)
        mPlaySession = new PlayerSession(mVideoView);
        mSubtitleCoordinator = new SubtitleCoordinator(this, mController, mPlaySession);
    }

    private void showCastDialog() {
        if (mPosition < 0 || mPosition >= mVideoList.size()) return;
        VideoInfo video = mVideoList.get(mPosition);
        File file = new File(video.getPath());
        if (!file.isFile()) {
            AppBubble.toast("当前本地视频文件不存在");
            return;
        }
        CastListDialog dialog = new CastListDialog(this,
                new CastVideo(video.getDisplayName(), Uri.fromFile(file).toString(),
                        mVideoView == null ? 0 : mVideoView.getCurrentPosition()),
                deviceId -> {
                    if (mVideoView != null) mVideoView.pause();
                });
        DialogCoordinator.centerInHostView(this, dialog).show();
    }

    /** 本地字幕设置弹窗:构造最小 VodInfo(取本地文件名作为搜索词)后交给协调器 */
    private void openLocalSubtitleDialog() {
        if (mSubtitleCoordinator == null) return;
        VodInfo vodInfo = new VodInfo();
        VideoInfo info = mVideoList.get(mPosition);
        vodInfo.name = info.getDisplayName();
        mSubtitleCoordinator.openSubtitleDialog(vodInfo);
    }

    /** 本地视频 prepared 后:更新字幕上下文并装载(缓存外挂字幕优先,其次内置字幕,显隐随开关) */
    private void initLocalSubtitleView() {
        if (mSubtitleCoordinator == null) return;
        VideoInfo info = mVideoList.get(mPosition);
        // 以本地文件路径作为字幕缓存键,便于记住为该文件选过的外挂字幕
        mSubtitleCoordinator.updateSubtitleContext(null, info.getPath());
        mSubtitleCoordinator.initSubtitleView();
    }

    /** 初始化画中画(小窗)辅助器:本地播放器复用详情页同一套逻辑 */
    private void initPipHelper() {
        pipHelper = new PipHelper(this, new PipHelper.Callback() {
            @Override
            public boolean isPlaying() {
                return mVideoView != null && mVideoView.isPlaying();
            }

            @Override
            public void togglePlay() {
                if (mController != null) {
                    mController.togglePlay();
                }
            }

            @Override
            public void pause() {
                if (mVideoView != null) {
                    mVideoView.pause();
                }
            }

            @Override
            public void playPrevious() {
                if (mPosition > 0) {
                    mPosition--;
                    play(true);
                }
            }

            @Override
            public void playNext() {
                if (mPosition < mVideoList.size() - 1) {
                    mPosition++;
                    play(true);
                }
            }

            @Override
            public boolean isFullscreen() {
                return true; // 本地播放始终全屏
            }

            @Override
            public void enterFullscreen() {
            }

            @Override
            public void exitFullscreen() {
            }

            @Override
            public int[] getVideoSize() {
                return mVideoView == null ? null : mVideoView.getVideoSize();
            }

            @Override
            public void onClose() {
                finish();
                NotificationUtils.cancelAll();
            }
        });
    }

    void initPlayerCfg() {
        fillCfgDefaults();
    }

    /** 填入本地播放的全局默认配置(每次 play 前也调用,先复位再叠加该文件存档) */
    private void fillCfgDefaults() {
        mVodPlayerCfg = new JSONObject();
        try {
            mVodPlayerCfg.put("pl", PlayConfig.getPlayType());
            mVodPlayerCfg.put("pr", PlayConfig.getRenderType());
            mVodPlayerCfg.put("ijk", PlayConfig.getIjkCodec());
            mVodPlayerCfg.put("sc", PlayConfig.getScaleType());
            mVodPlayerCfg.put("sp", 1.0f);
            mVodPlayerCfg.put("st", 0);
            mVodPlayerCfg.put("et", 0);
        } catch (Throwable ignored) {
        }
        mController.setPlayerConfig(mVodPlayerCfg);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (pipHelper != null) pipHelper.onActivityStarted();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 进入小窗也会触发 onPause,此时不能暂停视频
        // (PipHelper.isInPip:API 26 方法,低版本按"不在小窗"处理,避免 7.x 上 NoSuchMethodError)
        if (!PipHelper.isInPip(this)) {
            mVideoView.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pipHelper != null) pipHelper.onActivityResumed(); // 点X关闭带回前台:立即补暂停
        mVideoView.resume();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        if (mController != null) mController.setPictureInPicture(isInPictureInPictureMode);
        if (pipHelper != null) pipHelper.onPictureInPictureModeChanged(isInPictureInPictureMode);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && mController != null) {
            mController.setPictureInPicture(isInPictureInPictureMode());
        }
        // 兜底:部分设备点X关闭不触发 onPictureInPictureModeChanged(false),由 PipHelper 延迟判断
        if (pipHelper != null) pipHelper.onConfigurationChanged();
        mBinding.getRoot().post(this::restoreFullscreenBars);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) restoreFullscreenBars();
    }

    private void restoreFullscreenBars() {
        if (mVideoView == null || !mVideoView.isFullScreen()
                || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && isInPictureInPictureMode())) return;
        ImmersionBar.with(this)
                .hideBar(BarHide.FLAG_HIDE_BAR)
                .navigationBarColor(R.color.black)
                .fitsSystemWindows(false)
                .init();
        // 与在线全屏播放一致，转屏后直接补回状态栏和导航栏的隐藏标志。
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    /** 进入画中画(小窗) */
    public void enterPip() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && pipHelper != null) {
            pipHelper.enterPip();
            mController.hideBottom();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBatteryListener != null) {
            com.github.tvbox.osc.state.SystemStateMonitor monitor = com.github.tvbox.osc.state.SystemStateMonitor.get();
            if (monitor != null) monitor.unregister(mBatteryListener);
            mBatteryListener = null;
        }
        if (mVideoView != null) {
            mVideoView.release();
            mVideoView = null;
        }
    }


    @Override
    public void onBackPressed() {
        if (DialogCoordinator.dismissSimpleHostPopupOnBack(this)) return;
        if (!mVideoView.onBackPressed()) {
            super.onBackPressed();
        }
    }

    public void showAllSeriesDialog(){
        // 右侧抽屉:本地选集列表(全高),与在线选集抽屉一致;固定宽度 300,避免长文件名把抽屉撑宽(文字超出跑马灯滚动)
        mAllSeriesRightDialog = DialogCoordinator.right(this,
                new AllLocalSeriesDialog(this, convertLocalVideo(), (position, text) -> {
                    mPosition = position;
                    play(true);
                }), 300, true);
        mAllSeriesRightDialog.show();
    }

    private List<VodInfo.VodSeries> convertLocalVideo(){
        List<VodInfo.VodSeries> seriesList = new ArrayList<>();
        for (VideoInfo local : mVideoList) {
            VodInfo.VodSeries vodSeries = new VodInfo.VodSeries(local.getDisplayName(), local.getPath());
            vodSeries.selected = (Objects.equals(mVideoList.get(mPosition).getPath(), vodSeries.url));
            seriesList.add(vodSeries);
        }
        return seriesList;
    }
}

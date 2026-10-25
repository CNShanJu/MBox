package com.github.tvbox.osc.ui.fragment;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.blankj.utilcode.util.ColorUtils;
import com.blankj.utilcode.util.LogUtils;
import com.blankj.utilcode.util.RegexUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.blankj.utilcode.util.SpanUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.LocalVideoConfig;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.api.PlayConfig;
import com.github.tvbox.osc.player.controller.VodController;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.adapter.ParseAdapter;
import com.github.tvbox.osc.ui.dialog.DialogCoordinator;
import com.github.tvbox.osc.ui.dialog.PlayingControlDialog;
import com.github.tvbox.osc.ui.dialog.PlayingControlRightDialog;
import com.github.tvbox.osc.util.HCallBack;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.LoadingAnim;
import com.github.tvbox.osc.util.PlaybackConnectionProbe;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.player.PlayHistoryRepository;
import com.github.tvbox.osc.util.player.PlayParseCoordinator;
import com.github.tvbox.osc.util.player.ProxyDirectFallback;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.spiderapi.SourceConfigProviders;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.gyf.immersionbar.BarHide;
import com.gyf.immersionbar.ImmersionBar;
import com.lxj.xpopup.core.BasePopupView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import xyz.doikki.videoplayer.player.ProgressManager;
import xyz.doikki.videoplayer.player.PlaybackFailureKind;
import xyz.doikki.videoplayer.player.PlaybackErrorReporter;

public class PlayFragment extends BaseLazyFragment {
    private static final String PLAYBACK_FAILURE_TIP = "播放失败，请更换线路重试";
    private static final String CONNECTION_FAILURE_TIP = "播放失败，请检查网络或更换线路";
    private static final String LOADING_TIMEOUT_TIP = "加载超时，请稍后重试";
    /**
     * 加载看门狗时限:起播链(取播放信息→解析/嗅探→连接预检→清单→起播)任一环节静默断链时,
     * 转圈不允许无限持续。各环节自身均有更短超时(嗅探 20s/预检 8s/清单 6s),
     * 看门狗只兜底"回调被代数/令牌守卫静默丢弃"这类断链;起播/失败/切集/销毁都会解除。
     */
    private static final long LOADING_WATCHDOG_MS = 60_000L;
    /** 宿主同步接口(详情预览页选集高亮/播放配置回写;屏内直调,替代历史 EventBus TYPE_REFRESH 屏内事件) */
    public interface PlaySyncHost {
        void onEpisodeSelected(int index);

        void onPlayerCfgChanged(org.json.JSONObject cfg);

        /** 返回 true 表示地址已交给外部设备，本机播放器无需起播。 */
        default boolean onPlaybackResolved(String title, String url) { return false; }

        /** 手机播放器控件在浏览器投屏期间改为向浏览器发送指令。 */
        default boolean controlBrowserCast(String action, long positionMs) { return false; }

        /** 用户明确切回手机播放时结束当前浏览器投屏。 */
        default boolean stopBrowserCast() { return false; }

        /** 返回 true 表示宿主正在等待同源详情刷新，暂缓默认失败处理。 */
        default boolean onPlaybackFailed(long generation, String episodeUrl, String error,
                                         boolean finish, boolean autoSwitchPlayer) {
            return false;
        }
    }

    private PlaySyncHost mSyncHost;

    public void setPlaySyncHost(PlaySyncHost host) {
        this.mSyncHost = host;
    }

    private MyVideoView mVideoView;
    /** 播放会话门面(指令统一入口;底层暂为共享 MyVideoView,内核隔离见 player/PlayerSession) */
    private com.github.tvbox.osc.player.PlayerSession mPlaySession;
    /** 电池百分比订阅(系统状态经 SystemStateMonitor,替代 EventBus 电量广播) */
    private com.github.tvbox.osc.state.SystemStateMonitor.Listener mBatteryListener;
    private TextView mPlayLoadTip;
    private boolean browserCastStatusVisible;
    private boolean dlnaCastStatusVisible;
    private boolean browserCastActive;
    private boolean browserCastOnline = true;
    private boolean browserCastPaused;
    private boolean browserCastStateKnown;
    private boolean browserCastEpisodesAvailable = true;
    private ImageView mPlayLoadErr;
    private View mPlayLoading;
    private VodController mController;
    private SourceViewModel sourceViewModel;
    /** BaseLazyFragment 会在可见时才初始化播放器；详情快照可能更早到达。 */
    private boolean playbackReady;
    private Runnable pendingReadyAction;
    /**
     * 解析/嗅探引擎(解析编排 + 无头 WebView 嗅探 + json/聚合解析;见 util/player/PlayParseCoordinator)
     */
    private com.github.tvbox.osc.util.player.PlayParseCoordinator mParseEngine;
    private final AtomicLong mParseLifetimeGeneration = new AtomicLong();
    /** 字幕协调器(字幕装载/音轨与内置字幕切换/设置弹窗;见 util/player/SubtitleCoordinator) */
    private com.github.tvbox.osc.util.player.SubtitleCoordinator mSubtitleCoordinator;
    /** 播放进度持久化(key→MD5→CacheRepository,见 util/player/PlayHistoryRepository) */
    private final PlayHistoryRepository mPlayHistory = new PlayHistoryRepository();
    /** playback 会话原型:当前播放对应的会话键(PlaybackSessions 观察/日志用;不驱动内核) */
    private String playbackSessionKey;
    private com.github.tvbox.osc.util.player.NextEpisodePreloader mNextEpisodePreloader;

    private final long videoDuration = -1;
    /** 选集与地址解析共用代数，晚到的旧回调不能覆盖当前播放。 */
    private final AtomicLong mPlaybackGeneration = new AtomicLong();
    private volatile PlaybackSnapshot mResolvedPlayback;
    private final Object mAddressProbeLock = new Object();
    private AddressProbe mAddressProbe;
    private String mRequestedPlayUrl;
    private String mPlayResultToken;

    /** Call 创建与换集取消可能并发；占位状态保证晚到的 Call 仍会被取消。 */
    private static final class AddressProbe {
        final long generation;
        final String url;
        final PlaybackConnectionProbe.Mode mode;
        okhttp3.Call call;
        boolean canceled;
        boolean checked;

        AddressProbe(long generation, String url, PlaybackConnectionProbe.Mode mode) {
            this.generation = generation;
            this.url = url;
            this.mode = mode;
        }
    }

    private static final class PlaybackSnapshot {
        final long generation;
        final String url;
        final String sourceUrl;
        final Map<String, String> headers;

        PlaybackSnapshot(long generation, String url, String sourceUrl, Map<String, String> headers) {
            this.generation = generation;
            this.url = url;
            this.sourceUrl = sourceUrl;
            this.headers = headers == null ? null : new HashMap<>(headers);
        }
    }

    /** 一次已解析播放请求的投屏快照；读取它不会重新解析或重建手机播放器。 */
    public static final class CastRequest {
        public final String url;
        public final String sourceUrl;
        public final Map<String, String> headers;
        public final long positionMs;
        public final boolean playableNow;

        private CastRequest(String url, String sourceUrl, Map<String, String> headers,
                            long positionMs, boolean playableNow) {
            this.url = url;
            this.sourceUrl = sourceUrl;
            this.headers = headers == null ? null
                    : Collections.unmodifiableMap(new HashMap<>(headers));
            this.positionMs = Math.max(0, positionMs);
            this.playableNow = playableNow;
        }
    }

    private boolean isCurrentPlayback(long generation) {
        return generation == mPlaybackGeneration.get();
    }

    public boolean isPlaybackRequestCurrent(long generation, String episodeUrl) {
        return isCurrentPlayback(generation) && TextUtils.equals(mRequestedPlayUrl, episodeUrl);
    }

    private PlaybackSnapshot currentPlaybackSnapshot() {
        PlaybackSnapshot snapshot = mResolvedPlayback;
        return snapshot != null && isCurrentPlayback(snapshot.generation) ? snapshot : null;
    }

    /** 从当前解码会话取 URL、请求头与进度；投屏动作不触发手机重播。 */
    public CastRequest currentCastRequest() {
        PlaybackSnapshot snapshot = currentPlaybackSnapshot();
        if (snapshot == null || TextUtils.isEmpty(snapshot.url) || !RegexUtils.isURL(snapshot.url))
            return null;
        String source = !TextUtils.isEmpty(snapshot.sourceUrl)
                && RegexUtils.isURL(snapshot.sourceUrl) ? snapshot.sourceUrl : null;
        boolean playable = mPlaySession != null && mPlaySession.hasPreparedPlayback();
        return new CastRequest(snapshot.url, source, snapshot.headers,
                mPlaySession == null ? 0 : mPlaySession.currentPosition(), playable);
    }
    private boolean mFullWindows;
    /**
     * 非全屏下的设置弹窗
     */
    private BasePopupView mPlayingControlDialog;
    /**
     * 全屏下的设置弹窗
     */
    private BasePopupView mPlayingControlRightDialog;
    /**
     * 明确的格式/解码兼容失败时，最多自动切换一次播放器。
     */
    boolean retriedSwitchPlayer = false;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_play;
    }

    /**
     * 字幕字号变更(预览/全屏比例切换后由宿主 DetailActivity 直调;替代原 EventBus
     * TYPE_SUBTITLE_SIZE_CHANGE 广播——同屏两端唯一,无需全局事件)。
     */
    public void applySubtitleTextSize(int size) {
        if (mSubtitleCoordinator != null) {
            mSubtitleCoordinator.applySubtitleSize(size);
        }
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
        playbackReady = true;
        Runnable action = pendingReadyAction;
        pendingReadyAction = null;
        if (action != null) action.run();
    }

    /** 只在控制器、解析器和 ViewModel 全部就绪后执行最新的起播请求。 */
    public void runWhenPlaybackReady(Runnable action) {
        if (action == null) return;
        if (playbackReady && isAdded() && getView() != null) action.run();
        else pendingReadyAction = action;
    }

    public long getSavedProgress(String url) {
        int st = 0;
        try {
            st = mVodPlayerCfg.getInt("st");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        // 读取(含"跳过片头"叠加)委托 PlayHistoryRepository
        return mPlayHistory.load(url, st * 1000L);
    }

    private void initView() {
        mVideoView = findViewById(R.id.mVideoView);
        mPlayLoadTip = findViewById(R.id.play_load_tip);
        mPlayLoading = findViewById(R.id.play_loading);
        // 播放器加载动画跟随设置页"加载动画"选项(默认/Glowing Fish)
        LoadingAnim.apply(mPlayLoading);
        mPlayLoadErr = findViewById(R.id.play_load_error);
        mController = new VodController(requireContext());
        mController.showParse(false);
        mController.setCanChangePosition(true);
        mController.setEnableInNormal(true);
        mController.setGestureEnabled(true);
        ProgressManager progressManager = new ProgressManager() {
            @Override
            public void saveProgress(String url, long progress) {
                mPlayHistory.save(url, progress);
            }

            @Override
            public long getSavedProgress(String url) {
                return PlayFragment.this.getSavedProgress(url);
            }
        };
        mVideoView.setProgressManager(progressManager);
        mController.setListener(new VodController.VodControlListener() {
            final DetailActivity activity = (DetailActivity) mActivity;

            @Override
            public void onLocalPlaybackStarted() {
                if (dlnaCastStatusVisible) hideTip();
            }

            @Override
            public void chooseSeries() {
                // activity中已处理
                activity.showAllSeriesDialog();
            }

            @Override
            public void playNext(boolean rmProgress) {
                if (!canChangeBrowserEpisode()) return;
                String preProgressKey = progressKey;
                PlayFragment.this.playNext(rmProgress);
                if (rmProgress && preProgressKey != null)
                    mPlayHistory.delete(preProgressKey);
            }

            @Override
            public void playPre() {
                PlayFragment.this.playPrevious();
            }

            @Override
            public void changeParse(ParseBean pb) {
                autoRetryCount = 0;
                // 视图已销毁时 mParseEngine 会被置空:晚到的切换解析回调直接忽略,避免 NPE
                if (mParseEngine != null)
                    mParseEngine.doParse(pb);
            }

            @Override
            public void updatePlayerCfg() {
                if (mVodInfo == null || mVodPlayerCfg == null) return;
                mVodInfo.playerCfg = mVodPlayerCfg.toString();
                if (mSyncHost != null)
                    mSyncHost.onPlayerCfgChanged(mVodPlayerCfg);
            }

            @Override
            public void replay(boolean replay) {
                autoRetryCount = 0;
                play(replay);
            }

            @Override
            public boolean controlBrowserCast(String action, long positionMs) {
                return browserCastActive && mSyncHost != null
                        && mSyncHost.controlBrowserCast(action, positionMs);
            }

            @Override
            public void errReplay() {
                errorWithRetry("视频播放出错", false);
            }

            @Override
            public void selectSubtitle() {
                try {
                    selectMySubtitle();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void selectAudioTrack() {
                selectMyAudioTrack();
            }

            @Override
            public void prepared() {
                initSubtitleView();
            }

            @Override
            public void toggleFullScreen() {
                activity.toggleFullPreview();
            }

            @Override
            public void exit() {
                activity.onBackPressed();
            }

            @Override
            public void cast() {
                activity.showCastDialog();
            }

            @Override
            public void onHideBottom() {
                if (mFullWindows) {
                    ImmersionBar.with(activity)
                            .hideBar(BarHide.FLAG_HIDE_BAR)
                            .init();
                }
            }

            @Override
            public void showSetting() {
                // 预览控件先于影片详情创建；setData() 尚未送达时没有可修改的播放配置。
                if (mVodInfo == null || mVodPlayerCfg == null) {
                    AppBubble.toast("加载中，请稍后再试");
                    return;
                }
                // 按当前方向决定形态: 横屏右侧抽屉; 竖屏底部弹层(AppBottomPopupView 自带高度上限)
                if (ScreenUtils.isLandscape()) {
                    // view 模式无法自动响应返回键,onBackPress 时手动 dismiss
                    mPlayingControlRightDialog = DialogCoordinator.right(activity,
                            new PlayingControlRightDialog(activity, mController, mVideoView), 320, true);
                    mPlayingControlRightDialog.show();
                } else {
                    mPlayingControlDialog = DialogCoordinator.bottom(activity,
                            new PlayingControlDialog(activity, mController, mVideoView), 0);
                    mPlayingControlDialog.show();
                }
            }

            @Override
            public void pip() {
                activity.enterPip();
            }

            @Override
            public void showDownload() {
                // 全屏控制栏"下载":打开下载选择右侧抽屉,不退出全屏
                activity.showDownloadDialogInFullscreen();
            }

            @Override
            public void showParseRoot(boolean show, ParseAdapter adapter) {
                DetailActivity activity = (DetailActivity) mActivity;
                activity.showParseRoot(show, adapter);
            }
        });
        mVideoView.setVideoController(mController);
        mPlaySession = new com.github.tvbox.osc.player.PlayerSession(mVideoView);
        mSubtitleCoordinator = new com.github.tvbox.osc.util.player.SubtitleCoordinator(mActivity, mController,
                mPlaySession);
        initParseEngine();
        // 电池图标:经 SystemStateMonitor 订阅百分比变化(替代 EventBus 广播;主线程回调)
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
    }

    public boolean hideAllDialogSuccess() {
        if (mPlayingControlRightDialog != null && mPlayingControlRightDialog.isShow()) {
            mPlayingControlRightDialog.dismiss();
            return true;
        }
        if (mPlayingControlDialog != null && mPlayingControlDialog.isShow()) {
            mPlayingControlDialog.dismiss();
            return true;
        }
        return false;
    }

    /**
     * activity返回/点击播放器切换全屏操作等
     */
    public void changedLandscape(boolean fullWindows) {
        mFullWindows = fullWindows;
        if (fullWindows) {
            int[] size = mPlaySession != null ? mPlaySession.videoSize() : mVideoView.getVideoSize();
            int width = size[0];
            int height = size[1];
            if (width > height) {// 根据视频尺寸判断是否横屏,小视频则只在activity改了预览尺寸(全屏预览)
                // 横屏(传感器)
                mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            } else if (width == 0 && height == 0) {
                // 视频尺寸未知(尚未加载出来):用户主动全屏,默认横屏,待视频加载后按真实尺寸校正
                mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }

            ImmersionBar.with(mActivity)
                    .hideBar(BarHide.FLAG_HIDE_BAR)
                    .navigationBarColor(R.color.black)// 即使隐藏部分时候还是会显示
                    .fitsSystemWindows(false)
                    .init();
        } else {// 非全屏统一设置竖屏,activity处理为小的预览尺寸
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

            ImmersionBar.with(mActivity)
                    .hideBar(BarHide.FLAG_SHOW_BAR)
                    .navigationBarColor(R.color.white)
                    // 详情页根布局已通过 WindowInsets 设置系统栏 padding；这里再内缩会让播放器下移两次。
                    .fitsSystemWindows(false)
                    .init();
        }

        mController.changedLandscape(fullWindows);
    }

    // 设置字幕
    void setSubtitle(String path) {
        // 委托 SubtitleCoordinator(显隐跟随字幕开关)
        if (mSubtitleCoordinator != null)
            mSubtitleCoordinator.setSubtitlePath(path);
    }

    void selectMySubtitle() throws Exception {
        if (mSubtitleCoordinator == null || mVodInfo == null)
            return;
        mSubtitleCoordinator.openSubtitleDialog(mVodInfo);
    }

    @SuppressLint("UseCompatLoadingForColorStateLists")
    void setSubtitleViewTextStyle(int style) {
        if (mSubtitleCoordinator != null)
            mSubtitleCoordinator.setSubtitleTextStyle(style);
    }

    void selectMyAudioTrack() {
        if (mSubtitleCoordinator != null)
            mSubtitleCoordinator.openAudioTrackDialog();
    }

    void selectMyInternalSubtitle() {
        if (mSubtitleCoordinator != null)
            mSubtitleCoordinator.openInternalSubtitleDialog();
    }

    void setTip(String msg, boolean loading, boolean err) {
        setTip(msg, loading, err, false);
    }

    private void setTip(String msg, boolean loading, boolean err, boolean autoSwitchPlayer) {
        if (!isAdded())
            return;
        final long generation = mPlaybackGeneration.get();
        requireActivity().runOnUiThread(() -> {
            if (!isCurrentPlayback(generation) || !isAdded() || mPlayLoadTip == null) return;
            // 自动恢复尚未结束，失败文案等最后一次尝试结束后再显示。
            if (autoSwitchPlayer && !retriedSwitchPlayer) {
                logPlaybackProgress("格式或解码不兼容，自动切换播放器");
                retriedSwitchPlayer = true;
                showPlaybackLoading();
                mController.mPlayerBtn.performClick();
                return;
            }
            if (loading) {
                logPlaybackProgress(msg);
                mLoadingStage = msg;
                showPlaybackLoading();
                return;
            }
            // 非加载态:加载链已收尾(成功/失败/外部播放器)，看门狗解除
            disarmLoadingWatchdog();
            String tip = err ? playbackErrorTip(msg) : msg;
            mPlayLoadTip.setText(tip);
            mPlayLoadTip.setVisibility(TextUtils.isEmpty(tip) ? View.GONE : View.VISIBLE);
            mPlayLoading.setVisibility(View.GONE);
            mPlayLoadErr.setVisibility(err ? View.VISIBLE : View.GONE);

            if (autoSwitchPlayer) {
                SpanUtils.with(mPlayLoadTip)
                        .append("播放失败，")
                        .append("切换播放器")
                        .setClickSpan(ColorUtils.getColor(R.color.orange), false, view -> {
                            mController.mPlayerBtn.performClick();
                        }).create();
            }
        });
    }

    /** 加载与自动恢复只显示动画，具体步骤进入日志。 */
    private void showPlaybackLoading() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (!isAdded() || mPlayLoadTip == null) return;
            mPlayLoadTip.setText("");
            mPlayLoadTip.setVisibility(View.GONE);
            mPlayLoading.setVisibility(View.VISIBLE);
            mPlayLoadErr.setVisibility(View.GONE);
            armLoadingWatchdog();
        });
    }

    // ================= 加载看门狗(转圈不允许无限持续) =================

    private final android.os.Handler mMainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mLoadingWatchdog = this::onLoadingWatchdog;
    /** 看门狗武装时的播放代次;晚到的旧代次看门狗不得误伤新请求。 */
    private long mWatchdogGeneration = -1;
    /** 最近一个加载阶段文案(超时提示与日志用)。 */
    private String mLoadingStage = "";

    private void armLoadingWatchdog() {
        mMainHandler.removeCallbacks(mLoadingWatchdog);
        mWatchdogGeneration = mPlaybackGeneration.get();
        mMainHandler.postDelayed(mLoadingWatchdog, LOADING_WATCHDOG_MS);
    }

    private void disarmLoadingWatchdog() {
        mMainHandler.removeCallbacks(mLoadingWatchdog);
        mWatchdogGeneration = -1;
    }

    /**
     * 看门狗到点:仍在转圈且内核未就绪 → 判定起播链断链。
     * 按代次作废所有在途回调(预检/清单/解析/嗅探)，释放会话，给出可重试的超时提示。
     */
    private void onLoadingWatchdog() {
        if (!isAdded() || mPlayLoadTip == null || mPlayLoading == null) return;
        // 已经起播或已出提示:看门狗无需干预(正常情况下 hideTip/非加载 setTip 已解除)
        if (mPlayLoading.getVisibility() != View.VISIBLE) return;
        // 内核已就绪时转圈只是陈旧显示，不放超时错误
        if (mPlaySession != null && mPlaySession.hasPreparedPlayback()) return;
        long armed = mWatchdogGeneration;
        if (armed < 0 || !isCurrentPlayback(armed)) return;
        // 断链:新代次使所有在途回调失效
        mPlaybackGeneration.incrementAndGet();
        disarmLoadingWatchdog();
        cancelAddressProbe();
        HttpClient.cancel("m3u8-1");
        HttpClient.cancel("m3u8-2");
        if (mParseEngine != null) mParseEngine.stopParse();
        if (mPlaySession != null) mPlaySession.release();
        String detail = "播放加载超时(看门狗 " + (LOADING_WATCHDOG_MS / 1000) + "s): 阶段="
                + (TextUtils.isEmpty(mLoadingStage) ? "未知" : mLoadingStage)
                + "，地址=" + PlaybackErrorReporter.source(mRequestedPlayUrl);
        com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.PLAYER, detail);
        android.util.Log.e("MBoxPlayer", detail);
        showFinalPlaybackError(LOADING_TIMEOUT_TIP, false, false);
        if (mPlayLoadTip != null) {
            mPlayLoadTip.setOnClickListener(view -> {
                if (mVodInfo == null) return;
                hideTip();
                play(false);
            });
        }
    }

    private void logPlaybackProgress(String message) {
        if (TextUtils.isEmpty(message)) return;
        String detail = "播放过程: " + PlaybackErrorReporter.safeDiagnosticText(message);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.PLAYER, detail);
        android.util.Log.i("MBoxPlayer", detail);
    }

    /**
     * 诊断:只把 ext 里"凭据路径/URL 类"的值打出来(以 .txt 结尾或 http 开头的),
     * 其余值一律打成 &lt;省略&gt;,避免把真正的 token 写进日志。
     * <p>对相对路径额外做一次<b>磁盘探针</b>:爬虫框架的 {@code Path.root()} 固定在外部存储根,
     * {@code Path.read()} 读不到时返回空串而不是 null,{@code Path.write()} 失败还静默返回。
     * 也就是说"这个文件在不在"直接决定源把自己当已登录还是未登录,而它在界面上很难确认——
     * 所以每行起播日志都带上,一次日志就能定论。
     */
    private static String describeExtCredentialPaths(String ext) {
        if (TextUtils.isEmpty(ext)) return " ext=空";
        try {
            JSONObject o = new JSONObject(ext);
            StringBuilder sb = new StringBuilder(" ext={");
            StringBuilder probes = new StringBuilder();
            boolean first = true;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = o.optString(k, "");
                boolean pathLike = v.endsWith(".txt") || v.startsWith("http");
                if (!first) sb.append(',');
                first = false;
                sb.append(k).append('=').append(pathLike ? v : "<省略>");
                if (pathLike && !v.startsWith("http")) {
                    probes.append(" 文件[").append(v).append("]=").append(probeRelativeFile(v));
                }
            }
            return sb.append('}').append(probes).toString();
        } catch (Throwable t) {
            return " ext=<非 JSON>";
        }
    }

    /**
     * 把相对路径在"爬虫可能读取的四个根"上各探一次:外部存储根、files/、cache/、外部私有目录。
     * 只报存在性/大小/修改时间,<b>绝不读取内容</b>(里面是 cookie)。
     */
    private static String probeRelativeFile(String relative) {
        java.io.File storage = externalStorageRoot();
        StringBuilder hit = new StringBuilder();
        probeOneFile(hit, "sdcard", storage, relative);
        App app = App.getInstance();
        if (app != null) {
            probeOneFile(hit, "files", app.getFilesDir(), relative);
            probeOneFile(hit, "cache", app.getCacheDir(), relative);
            probeOneFile(hit, "extFiles", app.getExternalFilesDir(null), relative);
        }
        if (hit.length() > 0) return hit.toString();
        // 四个根都没有:再报一次父目录是否存在,用来区分"目录没建"和"建了但没写文件"
        boolean parentExists = false;
        if (storage != null) {
            java.io.File parent = new java.io.File(storage, relative).getParentFile();
            parentExists = parent != null && parent.isDirectory();
        }
        return "全无(父目录:" + (parentExists ? "有" : "无")
                + " /sdcard顶层:" + sdcardTopLevel() + ")";
    }

    /** 只算一次并缓存：同一次起播里多行日志重复输出整份目录名没有意义。 */
    private static String sdcardTopLevel;

    private static String sdcardTopLevel() {
        if (sdcardTopLevel != null) return sdcardTopLevel;
        StringBuilder sb = new StringBuilder();
        try {
            java.io.File storage = externalStorageRoot();
            java.io.File[] children = storage == null ? null : storage.listFiles();
            if (children != null) {
                int shown = 0;
                for (java.io.File child : children) {
                    if (child == null || !child.isDirectory() || shown >= 20) continue;
                    if (shown > 0) sb.append(',');
                    sb.append(child.getName());
                    shown++;
                }
            }
        } catch (Throwable ignored) {
            // 探针失败不影响播放链路
        }
        sdcardTopLevel = sb.length() == 0 ? "?" : sb.toString();
        return sdcardTopLevel;
    }

    private static void probeOneFile(StringBuilder out, String label, java.io.File root, String relative) {
        if (root == null) return;
        try {
            java.io.File file = new java.io.File(root, relative);
            if (!file.isFile()) return;
            if (out.length() > 0) out.append(' ');
            out.append('[').append(label).append("]√").append(file.length()).append('B');
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
            out.append('@').append(fmt.format(new java.util.Date(file.lastModified())));
        } catch (Throwable ignored) {
            // 探针失败不影响播放链路
        }
    }

    private static java.io.File externalStorageRoot() {
        try {
            return android.os.Environment.getExternalStorageDirectory();
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * 最近 15 分钟被改写的小文件（≤256KB）：扫码那一下把"已登录"状态写到了哪，这一行直接给答案。
     * <p>只报目录/文件名/时间，<b>不读内容</b>（里面是 cookie）。扫过 {@code files/}、{@code cache/}、
     * {@code databases/}、{@code shared_prefs/}、{@code app_webview/}——磁盘扫描和界面截图都拿不到
     * 这些位置，而它们恰好是爬虫 jar 唯一能写的地方。全空同样是一个结论：状态不在本机。
     */
    private static String describeRecentTouched() {
        // 窗口给 30 分钟：扫码到"再点一次起播"之间用户可能要操作一会儿，窗口太短会把证据漏掉
        long since = System.currentTimeMillis() - 30L * 60L * 1000L;
        java.util.List<java.io.File> roots = new java.util.ArrayList<>();
        java.io.File storage = externalStorageRoot();
        if (storage != null) roots.add(storage);
        App app = App.getInstance();
        if (app != null) {
            addRecentRoot(roots, app.getFilesDir());
            addRecentRoot(roots, app.getCacheDir());
            addRecentRoot(roots, app.getExternalFilesDir(null));
            try {
                java.io.File dataDir = new java.io.File(app.getApplicationInfo().dataDir);
                addRecentRoot(roots, new java.io.File(dataDir, "databases"));
                addRecentRoot(roots, new java.io.File(dataDir, "shared_prefs"));
                addRecentRoot(roots, new java.io.File(dataDir, "app_webview"));
            } catch (Throwable ignored) {
                // dataDir 拿不到时不影响播放链路
            }
        }
        java.util.List<java.io.File> hits = new java.util.ArrayList<>();
        int[] budget = {500};
        for (java.io.File root : roots) {
            collectRecentFiles(root, 0, 2, since, hits, budget);
        }
        boolean fallback = false;
        if (hits.isEmpty()) {
            // 窗口内没动静时退化成"最近改过的几个文件":否则这行日志等于什么都没说,
            // 而"确实一条都没有"本身也是要能看见的结论。
            fallback = true;
            budget[0] = 500;
            for (java.io.File root : roots) {
                collectRecentFiles(root, 0, 2, 0L, hits, budget);
            }
        }
        if (hits.isEmpty()) return " 最近30分钟:无文件改动";
        hits.sort((left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
        StringBuilder sb = new StringBuilder(fallback ? " 最近改动(超30分钟):" : " 最近30分钟:");
        int shown = 0;
        for (java.io.File file : hits) {
            if (shown >= 6) break;
            java.io.File parent = file.getParentFile();
            if (shown > 0) sb.append(',');
            if (parent != null) sb.append(parent.getName()).append('/');
            sb.append(file.getName()).append('@')
                    .append(fmt.format(new java.util.Date(file.lastModified())));
            shown++;
        }
        return sb.toString();
    }

    private static void addRecentRoot(java.util.List<java.io.File> roots, java.io.File dir) {
        if (dir == null) return;
        for (java.io.File root : roots) {
            if (root.equals(dir)) return;
        }
        roots.add(dir);
    }

    private static void collectRecentFiles(java.io.File dir, int level, int maxDepth, long since,
                                           java.util.List<java.io.File> out, int[] budget) {
        if (dir == null || budget[0] <= 0) return;
        java.io.File[] children;
        try {
            if (!dir.isDirectory()) return;
            children = dir.listFiles();
        } catch (Throwable error) {
            return;
        }
        if (children == null) return;
        for (java.io.File child : children) {
            if (child == null || budget[0] <= 0) continue;
            budget[0]--;
            try {
                if (child.isDirectory()) {
                    if (level + 1 >= maxDepth) continue;
                    String name = child.getName();
                    if (name == null || name.startsWith(".")) continue;
                    // 跳过媒体/系统目录：它们的写入与登录状态无关，只会把预算烧光
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    if ("android".equals(lower) || "dcim".equals(lower) || "movies".equals(lower)
                            || "music".equals(lower) || "pictures".equals(lower)
                            || "download".equals(lower)) continue;
                    collectRecentFiles(child, level + 1, maxDepth, since, out, budget);
                } else if (child.length() <= 256L * 1024L && child.lastModified() >= since) {
                    out.add(child);
                }
            } catch (Throwable ignored) {
                // 单个条目不可读不影响其余条目
            }
        }
    }

    /**
     * 诊断:网盘源凭据在磁盘上的状态(目录/文件名/大小,不输出内容)。
     * 只在"解析结果无地址"时拼一行,用于区分两种情况:
     * 目录为空(爬虫该弹扫码)→ 弹了没弹;有文件却仍取不到直链 → 凭据过期或没被读到。
     */
    /**
     * 起播日志里的凭据快照。
     * <p>爬虫 jar 拿的是 App 的 Context，"登录成功"可能落在外部存储，也可能落在
     * {@code files/}、{@code cache/} 或 {@code /sdcard/Android/data/<包名>/files}——
     * 只报外部存储会得出"根本没落盘"的错误结论。
     */
    private static String describeNetDiskCredentials() {
        StringBuilder sb = new StringBuilder();
        String[] dirs = com.github.catvod.utils.Path.CREDENTIAL_DIRS;
        java.util.LinkedHashMap<String, java.io.File> roots = new java.util.LinkedHashMap<>();
        try {
            roots.put("sdcard", android.os.Environment.getExternalStorageDirectory());
        } catch (Throwable ignored) {
        }
        try {
            android.content.Context ctx = com.github.tvbox.osc.base.App.getInstance();
            if (ctx != null) {
                roots.put("files", ctx.getFilesDir());
                roots.put("cache", ctx.getCacheDir());
                roots.put("extFiles", ctx.getExternalFilesDir(null));
                roots.put("extCache", ctx.getExternalCacheDir());
            }
        } catch (Throwable ignored) {
        }
        for (java.util.Map.Entry<String, java.io.File> e : roots.entrySet()) {
            java.io.File root = e.getValue();
            if (root == null) continue;
            for (String dir : dirs) {
                java.io.File d = new java.io.File(root, dir);
                java.io.File[] files;
                try {
                    if (!d.isDirectory()) continue;
                    files = d.listFiles();
                } catch (Throwable ignored) {
                    continue;
                }
                if (files == null || files.length == 0) continue;
                sb.append(' ').append(e.getKey()).append('/').append(dir).append("={");
                int shown = 0;
                for (java.io.File f : files) {
                    if (f == null || !f.isFile() || shown >= 8) continue;
                    if (shown > 0) sb.append(',');
                    sb.append(f.getName()).append(':').append(f.length()).append('B');
                    shown++;
                }
                sb.append('}');
            }
        }
        return sb.length() == 0 ? " 凭据目录=无" : sb.toString();
    }

    /** 起播画像:协议+主机(去查询串),用于日志快速识别磁力/迅雷/荐片/普通 http */
    private static String describePlayUrl(String url) {
        if (TextUtils.isEmpty(url)) return "空地址";
        String s = url.trim();
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        if (s.length() > 80) s = s.substring(0, 80) + "…";
        return s;
    }

    private static String playbackErrorTip(String message) {
        if (TextUtils.isEmpty(message)) return PLAYBACK_FAILURE_TIP;
        switch (message) {
            case "视频播放出错":
            case "获取播放信息错误":
            case "解析错误":
            case "解析异常":
            case "嗅探错误":
            case "下载出错":
                return PLAYBACK_FAILURE_TIP;
            case "解析下载超时":
                return "加载超时，请稍后重试";
            case "拒绝的网络连接":
                return CONNECTION_FAILURE_TIP;
            default:
                return message.startsWith("ErrorCode=") ? PLAYBACK_FAILURE_TIP : message;
        }
    }

    void hideTip() {
        browserCastStatusVisible = false;
        dlnaCastStatusVisible = false;
        disarmLoadingWatchdog();
        mPlayLoadTip.setOnClickListener(null);
        mPlayLoadTip.setVisibility(View.GONE);
        mPlayLoading.setVisibility(View.GONE);
        mPlayLoadErr.setVisibility(View.GONE);
    }

    /** 浏览器接管播放后，手机解码器不再起播；在黑色播放器上说明当前状态。 */
    public void showBrowserCastStatus() {
        browserCastActive = true;
        if (mNextEpisodePreloader != null) mNextEpisodePreloader.cancel();
        browserCastEpisodesAvailable = true;
        browserCastOnline = true;
        browserCastStateKnown = false;
        if (mController != null) mController.setBrowserRemote(true);
        if (mPlaySession != null) mPlaySession.release();
        browserCastStatusVisible = true;
        dlnaCastStatusVisible = false;
        setTip(browserCastStatusText(), false, false);
        if (mPlayLoadTip != null) mPlayLoadTip.setOnClickListener(view -> {
            if (!browserCastActive || mSyncHost == null || !mSyncHost.stopBrowserCast()) return;
            clearBrowserCastStatus();
            play(false);
        });
    }

    public void clearBrowserCastStatus() {
        browserCastActive = false;
        browserCastEpisodesAvailable = true;
        if (mController != null) mController.setBrowserRemote(false);
        if (browserCastStatusVisible && mPlayLoadTip != null) hideTip();
    }

    /** 投屏在别处结束后保留明确入口，用户点按才恢复手机播放。 */
    public void showBrowserCastEndedStatus() {
        if (!browserCastActive) return;
        browserCastActive = false;
        if (mController != null) mController.setBrowserRemote(false);
        browserCastStatusVisible = true;
        dlnaCastStatusVisible = false;
        setTip("电脑投屏已结束\n点此在手机播放", false, false);
        if (mPlayLoadTip != null) mPlayLoadTip.setOnClickListener(view -> {
            if (!browserCastStatusVisible || browserCastActive) return;
            hideTip();
            play(false);
        });
    }

    /** DLNA 独立播放，手机继续播放只恢复本机，不撤销电视的媒体代理。 */
    public void showDlnaCastStatus(String deviceName) {
        String name = TextUtils.isEmpty(deviceName) ? "电视" : deviceName;
        browserCastStatusVisible = false;
        dlnaCastStatusVisible = true;
        setTip("播放请求已发送到" + name
                + "\n手机已暂停，电视投屏会继续\n点此继续手机播放", false, false);
        if (mPlayLoadTip != null) mPlayLoadTip.setOnClickListener(view -> {
            if (!dlnaCastStatusVisible) return;
            hideTip();
            if (mPlaySession != null && mVideoView != null
                    && mVideoView.getCurrentPlayState() == xyz.doikki.videoplayer.player.VideoView.STATE_PAUSED)
                mPlaySession.resume();
            else play(false);
        });
    }

    public void updateBrowserCastState(long positionMs, long durationMs, boolean paused) {
        if (!browserCastActive) return;
        if (mController != null) mController.updateBrowserRemote(positionMs, durationMs, paused);
        if (!browserCastStateKnown || browserCastPaused != paused) {
            browserCastPaused = paused;
            browserCastStateKnown = true;
            setTip(browserCastStatusText(), false, false);
        }
    }

    public void updateBrowserCastConnection(boolean online) {
        if (!browserCastActive || browserCastOnline == online) return;
        browserCastOnline = online;
        setTip(browserCastStatusText(), false, false);
    }

    private String browserCastStatusText() {
        String status = !browserCastOnline ? "电脑暂未连接"
                : !browserCastStateKnown ? "正在投屏至电脑"
                : browserCastPaused ? "电脑已暂停" : "正在电脑播放";
        return status + "\n手机进度条和播放键可控制电脑"
                + (browserCastEpisodesAvailable ? "" : "\n剧集列表已变化，选集暂不可用")
                + "\n点此返回手机播放";
    }

    public void setBrowserCastEpisodeControls(boolean available, String title) {
        browserCastEpisodesAvailable = available;
        if (mController != null) {
            mController.setBrowserEpisodeControls(available);
            mController.setTitle(title);
        }
        if (browserCastActive) setTip(browserCastStatusText(), false, false);
    }

    private boolean canChangeBrowserEpisode() {
        if (!browserCastActive || browserCastEpisodesAvailable) return true;
        AppBubble.toast("剧集列表已变化，当前投屏仅支持播放、暂停和进度控制");
        return false;
    }

    void errorWithRetry(String err, boolean finish) {
        if (mVodInfo == null) return;
        PlaybackSnapshot failedPlayback = currentPlaybackSnapshot();
        String failedUrl = failedPlayback == null ? mRequestedPlayUrl : failedPlayback.sourceUrl;
        PlaybackFailureKind failureKind = failedPlayback != null && "视频播放出错".equals(err)
                && mPlaySession != null ? mPlaySession.playbackFailureKind() : PlaybackFailureKind.UNKNOWN;
        boolean connectionFailure = failureKind == PlaybackFailureKind.SOURCE_CONNECTION;
        boolean autoSwitchPlayer = failureKind == PlaybackFailureKind.ENGINE_COMPATIBILITY;
        String finalError = connectionFailure ? CONNECTION_FAILURE_TIP : err;
        String detail = "点播失败: 源=" + PlaybackErrorReporter.safeDiagnosticText(sourceKey) + "，内核="
                + (mVodPlayerCfg == null ? -1 : mVodPlayerCfg.optInt("pl", -1))
                + "，原因=" + PlaybackErrorReporter.safeDiagnosticText(err)
                + "，失败类型=" + failureKind + "，地址=" + PlaybackErrorReporter.source(failedUrl);
        if (connectionFailure) detail += "，同址重试与自动换内核=跳过";
        if (failedPlayback != null && !TextUtils.equals(failedPlayback.url, failedUrl)) {
            detail += "，播放请求=" + PlaybackErrorReporter.source(failedPlayback.url);
        }
        com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.PLAYER, detail);
        // 解析失败尚未进入内核；净化播放时内核只知道本机地址，需补上原始源地址。
        if (failedPlayback == null || !TextUtils.equals(failedPlayback.url, failedUrl)) {
            android.util.Log.e("MBoxPlayer", detail);
        }
        // 本机代理失败时，原地址可能仍可直接播放。每集只尝试一次，
        // 并保留代理 URL 中的源请求头；直连确认为连接失败时不再重试/换内核。
        if (retryProxyTargetDirectly(failedPlayback)) return;
        if ((connectionFailure || !autoRetry()) && isAdded()) {
            long failedGeneration = mPlaybackGeneration.get();
            String failedEpisodeUrl = mRequestedPlayUrl;
            requireActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!isPlaybackRequestCurrent(failedGeneration, failedEpisodeUrl)) return;
                    boolean waitingForDetail = mSyncHost != null
                            && mSyncHost.onPlaybackFailed(failedGeneration, failedEpisodeUrl,
                            finalError, finish, autoSwitchPlayer);
                    if (!isPlaybackRequestCurrent(failedGeneration, failedEpisodeUrl)) return;
                    if (waitingForDetail) setTip("播放失败，等待详情地址刷新", true, false);
                    else showFinalPlaybackError(finalError, finish, autoSwitchPlayer);
                }
            });
        }
    }

    private boolean retryProxyTargetDirectly(PlaybackSnapshot failedPlayback) {
        if (mProxyDirectFallbackAttempted || failedPlayback == null || !isAdded()) return false;
        ProxyDirectFallback.Candidate candidate = ProxyDirectFallback.from(
                failedPlayback.sourceUrl, failedPlayback.headers);
        if (candidate == null) return false;
        mProxyDirectFallbackAttempted = true;
        mProxyDirectOverrideSource = failedPlayback.sourceUrl;
        mProxyDirectOverride = candidate;
        String detail = "本机代理播放失败，尝试直连: 地址="
                + PlaybackErrorReporter.source(candidate.url);
        com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.PLAYER, detail);
        android.util.Log.e("MBoxPlayer", detail);
        showPlaybackLoading();
        // 直连回退走原始 HLS 清单，不再经净化探针或本机 /purify.m3u8 改写。
        long generation = mPlaybackGeneration.incrementAndGet();
        mResolvedPlayback = null;
        startPlayUrl(candidate.url, candidate.headers, generation, candidate.url);
        return true;
    }

    /** 明确的 DNS、建连或 TLS 失败在内核前拦截；地址与原因进入业务及 Logcat。 */
    private void failBeforePlayback(long generation, String url, String sourceUrl,
                                    String tip, String reason) {
        if (!isCurrentPlayback(generation) || !isAdded()) return;
        String detail = PlaybackErrorReporter.safeDiagnosticText(reason)
                + "，后续=不启动内核、不自动换内核";
        if (sourceUrl != null && !TextUtils.equals(url, sourceUrl)) {
            detail += "，播放入口=" + PlaybackErrorReporter.source(sourceUrl);
        }
        PlaybackErrorReporter.failure("播放地址预检", "拦截", PlaybackErrorReporter.source(url),
                detail);
        String episodeUrl = mRequestedPlayUrl;
        requireActivity().runOnUiThread(() -> {
            if (!isPlaybackRequestCurrent(generation, episodeUrl)) return;
            if (mPlaySession != null) mPlaySession.release();
            boolean waitingForDetail = mSyncHost != null
                    && mSyncHost.onPlaybackFailed(generation, episodeUrl, tip, false, false);
            if (!isPlaybackRequestCurrent(generation, episodeUrl)) return;
            if (waitingForDetail) setTip("播放地址不可用，等待详情地址刷新", true, false);
            else showFinalPlaybackError(tip, false, false);
        });
    }

    public void showFinalPlaybackError(String err, boolean finish, boolean autoSwitchPlayer) {
        if (!isAdded()) return;
        if (finish) {
            AppBubble.toast(playbackErrorTip(err));
            hideTip();
        } else {
            setTip(err, false, true, autoSwitchPlayer);
        }
    }

    void playUrl(String url, HashMap<String, String> headers) {
        if (mVodInfo == null) return;
        if (!TextUtils.isEmpty(url)) {
            mWaitingMissingAddress = false;
            mSawLoginDialog = false;
        }
        final long generation = mPlaybackGeneration.incrementAndGet();
        cancelAddressProbe();
        mResolvedPlayback = null;
        // 同集自动重试、自动切换内核重新解析到相同坏代理时，继续使用已选定的直连地址。
        if (mProxyDirectOverride != null && TextUtils.equals(url, mProxyDirectOverrideSource)) {
            com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.PLAYER,
                    "代理直连复用: 地址=" + PlaybackErrorReporter.source(mProxyDirectOverride.url));
            startPlayUrl(mProxyDirectOverride.url, mProxyDirectOverride.headers,
                    generation, mProxyDirectOverride.url);
            return;
        }
        final HashMap<String, String> requestHeaders = headers == null ? null : new HashMap<>(headers);
        // 爬虫/地址解析已给出真实播放入口；先建连，再让后续清单处理请求此地址。
        checkAddressConnection(url, requestHeaders, generation, url, true,
                () -> playUrlAfterConnectionCheck(url, requestHeaders, generation));
    }

    private void playUrlAfterConnectionCheck(String url, HashMap<String, String> requestHeaders,
                                             long generation) {
        if (!isCurrentPlayback(generation)) return;
        final int purifyMode = PlayConfig.getVideoPurifyMode();
        final boolean purifyEnabled = purifyMode != PlayConfig.VIDEO_PURIFY_OFF;
        if (!purifyEnabled && autoRetryCount == 0) {
            logManifestProbe("入口", "净化关闭，直接播放", null, null);
            startPlayUrl(url, requestHeaders, generation, url);
            return;
        }
        if (!url.contains("://127.0.0.1/") && !url.contains(".m3u8")) {
            logManifestProbe("入口", "非 HLS 候选，直接播放", null, null);
            startPlayUrl(url, requestHeaders, generation, url);
            return;
        }
        HttpClient.cancel("m3u8-1");
        HttpClient.cancel("m3u8-2");
        // remove ads in m3u8
        Map<String, String> hheaders = new HashMap<>();
        if (requestHeaders != null) {
            for (Map.Entry<String, String> s : requestHeaders.entrySet()) {
                hheaders.put(s.getKey(), s.getValue());
            }
        }

        HttpClient.get(url, hheaders, "m3u8-1", new HCallBack() {
            @Override
            public void onSuccess(String content) {
                if (!isCurrentPlayback(generation)) return;
                boolean hadBom = content != null && content.startsWith("\uFEFF");
                // 先剥 BOM:带 BOM 的清单原来会被下面的 startsWith 判否 → 静默放弃广告过滤
                content = com.github.tvbox.osc.util.M3u8Purifier.stripBom(content);
                if (!content.startsWith("#EXTM3U")) {
                    logManifestProbe("首级", "非 HLS 清单，直接播放", null, null);
                    startPlayUrl(url, requestHeaders, generation, url);
                    return;
                }
                PlaylistShape firstShape = PlaylistShape.inspect(content);
                if (com.github.tvbox.osc.util.player.HlsPlaybackPolicy.isRefreshingMedia(content)) {
                    // A static /purify.m3u8 response would freeze the live window after its first segments.
                    logManifestProbe("首级", "动态媒体清单，播放原地址", firstShape, null);
                    startPlayUrl(url, requestHeaders, generation, url);
                    return;
                }
                if (!purifyEnabled) {
                    String localManifest = hadBom ? com.github.tvbox.osc.util.M3u8Purifier
                            .normalizeForLocalPlayback(url, content) : null;
                    if (localManifest != null) {
                        RemoteServer.m3u8Content = localManifest;
                        startPlayUrl(purifyUrl(), requestHeaders, generation, url);
                    } else {
                        startPlayUrl(url, requestHeaders, generation, url);
                    }
                    return;
                }

                String[] lines = null;
                if (content.contains("\r\n"))
                    lines = content.split("\r\n", 10);
                else
                    lines = content.split("\n", 10);
                String forwardurl = "";
                boolean dealedFirst = false;
                for (String line : lines) {
                    if (!"".equals(line) && line.charAt(0) != '#') {
                        if (dealedFirst) {
                            // 跳转行后还有内容，说明不需要跳转
                            forwardurl = "";
                            break;
                        }
                        if (line.endsWith(".m3u8") || line.contains(".m3u8?")) {
                            if (line.startsWith("http://") || line.startsWith("https://")) {
                                forwardurl = line;
                            } else if (line.charAt(0) == '/') {
                                int ifirst = url.indexOf('/', 9);// skip https://, http://
                                if (ifirst >= 0) {
                                    forwardurl = url.substring(0, ifirst) + line;
                                } else {
                                    // 地址本身没有路径(如 https://a.b 或 https://a.b?x=1)时 indexOf 返回 -1,
                                    // substring(0,-1) 会抛 StringIndexOutOfBoundsException;这里退化成"协议+主机(:端口)",
                                    // 与上面正常分支拼出的结果一致。解析不出主机时保持空串,走原有"用原地址播放"的兜底
                                    Uri base = Uri.parse(url);
                                    String scheme = base.getScheme();
                                    String host = base.getHost();
                                    if (scheme != null && host != null) {
                                        int port = base.getPort();
                                        forwardurl = scheme + "://" + host + (port == -1 ? "" : ":" + port) + line;
                                    }
                                }
                            } else {
                                int ilast = url.lastIndexOf('/');
                                forwardurl = url.substring(0, ilast + 1) + line;
                            }
                        }
                        dealedFirst = true;
                    }
                }
                if ("".equals(forwardurl)) {
                    if (com.github.tvbox.osc.util.player.HlsPlaybackPolicy.isMaster(content)) {
                        // Multiple variants must remain with the origin so the player can choose and refresh them.
                        logManifestProbe("首级", "多码率主清单，播放原地址", firstShape, null);
                        String localManifest = hadBom ? com.github.tvbox.osc.util.M3u8Purifier
                                .normalizeForLocalPlayback(url, content) : null;
                        if (localManifest != null) {
                            RemoteServer.m3u8Content = localManifest;
                            startPlayUrl(purifyUrl(), requestHeaders, generation, url);
                        } else {
                            startPlayUrl(url, requestHeaders, generation, url);
                        }
                        return;
                    }
                    int ilast = url.lastIndexOf('/');

                    String purified = purifyMode == PlayConfig.VIDEO_PURIFY_ENHANCED
                            ? com.github.tvbox.osc.util.M3u8Purifier.removeAds(url, content)
                            : com.github.tvbox.osc.util.M3u8Purifier
                                    .removeMinorityUrl(url.substring(0, ilast + 1), content);
                    if (purified == null && hadBom) {
                        purified = com.github.tvbox.osc.util.M3u8Purifier
                                .normalizeForLocalPlayback(url, content);
                    }
                    RemoteServer.m3u8Content = purified;
                    logManifestProbe("首级", "直接处理", firstShape, PlaylistShape.inspect(purified));
                    if (RemoteServer.m3u8Content == null)
                        startPlayUrl(url, requestHeaders, generation, url);
                    else {
                        // 广告过滤静默执行,不弹任何提示
                        startPlayUrl(purifyUrl(), requestHeaders, generation, url);
                    }
                    return;
                }
                logManifestProbe("首级", "转向下级清单", firstShape, null);
                final String finalforwardurl = forwardurl;
                HttpClient.get(forwardurl, hheaders, "m3u8-2", new HCallBack() {
                    @Override
                    public void onSuccess(String content) {
                        if (!isCurrentPlayback(generation)) return;
                        boolean hadBom = content != null && content.startsWith("\uFEFF");
                        content = com.github.tvbox.osc.util.M3u8Purifier.stripBom(content);
                        PlaylistShape lowerShape = PlaylistShape.inspect(content);
                        if (com.github.tvbox.osc.util.player.HlsPlaybackPolicy.isRefreshingMedia(content)
                                || com.github.tvbox.osc.util.player.HlsPlaybackPolicy.isMaster(content)) {
                            logManifestProbe("下级", "动态或主清单，播放原地址", lowerShape, null);
                            startPlayUrl(finalforwardurl, requestHeaders, generation, url);
                            return;
                        }
                        int ilast = finalforwardurl.lastIndexOf('/');
                        String purified = purifyMode == PlayConfig.VIDEO_PURIFY_ENHANCED
                                ? com.github.tvbox.osc.util.M3u8Purifier.removeAds(finalforwardurl, content)
                                : com.github.tvbox.osc.util.M3u8Purifier
                                        .removeMinorityUrl(finalforwardurl.substring(0, ilast + 1), content);
                        if (purified == null && hadBom) {
                            purified = com.github.tvbox.osc.util.M3u8Purifier
                                    .normalizeForLocalPlayback(finalforwardurl, content);
                        }
                        RemoteServer.m3u8Content = purified;
                        logManifestProbe("下级", lowerShape == null ? "非 HLS 清单，直接播放" : "直接处理",
                                lowerShape, PlaylistShape.inspect(purified));

                        if (RemoteServer.m3u8Content == null)
                            startPlayUrl(finalforwardurl, requestHeaders, generation, url);
                        else {
                            // 广告过滤静默执行,不弹任何提示
                            startPlayUrl(purifyUrl(), requestHeaders, generation, url);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        if (!isCurrentPlayback(generation)) return;
                        logManifestProbe("下级", "请求失败，播放原地址", null, null);
                        startPlayUrl(url, requestHeaders, generation, url);
                    }
                });
            }

            @Override
            public void onError(Throwable e) {
                if (!isCurrentPlayback(generation)) return;
                logManifestProbe("首级", "请求失败，播放原地址", null, null);
                startPlayUrl(url, requestHeaders, generation, url);
            }
        });
    }

    /** 仅记录清单结构和净化结果；清单文本、播放地址和请求头可能含鉴权信息，不能入日志。 */
    private static void logManifestProbe(String stage, String decision, PlaylistShape before, PlaylistShape after) {
        if (!com.github.tvbox.osc.log.LogStore.get().isEnabled()) return;
        StringBuilder detail = new StringBuilder("播放清单探针: ").append(stage).append('，').append(decision);
        if (before != null) detail.append("，净化前=").append(before.summary());
        if (after != null) {
            detail.append("，净化后=").append(after.summary());
            if (before != null) detail.append("，移除地址数=").append(Math.max(0, before.uriCount - after.uriCount));
        } else if (before != null && "直接处理".equals(decision)) {
            detail.append("，净化未生效");
        }
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.PLAYER, detail.toString());
    }

    /** 统计 HLS 标签与非注释地址行，不读取变体地址、分片地址或标签内的 URI 属性。 */
    private static final class PlaylistShape {
        final int variantCount;
        final int segmentCount;
        final int uriCount;

        private PlaylistShape(int variantCount, int segmentCount, int uriCount) {
            this.variantCount = variantCount;
            this.segmentCount = segmentCount;
            this.uriCount = uriCount;
        }

        static PlaylistShape inspect(String content) {
            if (!com.github.tvbox.osc.log.LogStore.get().isEnabled()) return null;
            if (content == null || !content.startsWith("#EXTM3U")) return null;
            int variants = 0;
            int segments = 0;
            int uris = 0;
            int start = 0;
            while (start < content.length()) {
                int end = content.indexOf('\n', start);
                if (end < 0) end = content.length();
                int cursor = start;
                while (cursor < end && Character.isWhitespace(content.charAt(cursor))) cursor++;
                if (cursor < end) {
                    if (content.startsWith("#EXT-X-STREAM-INF:", cursor)) variants++;
                    else if (content.startsWith("#EXTINF:", cursor)) segments++;
                    else if (content.charAt(cursor) != '#') uris++;
                }
                start = end + 1;
            }
            return new PlaylistShape(variants, segments, uris);
        }

        String summary() {
            return (variantCount > 0 ? "主清单" : segmentCount > 0 ? "媒体清单" : "未知清单")
                    + "(变体=" + variantCount + "，分片=" + segmentCount + "，地址=" + uriCount + ')';
        }
    }

    /**
     * 净化后清单的本机回环地址。
     *
     * 路径**必须以 .m3u8 结尾**:Exo 侧 {@code ExoMediaSourceHelper.inferContentType} 先按路径扩展名判定,
     * 旧路径 {@code /m3u8} 的"扩展名"取到的是 {@code 127.0.0.1} 里的最后一个点之后的内容
     * (`1:9978/m3u8`),既不等于 m3u8 也不等于 mpd → 退化成 Progressive,首播必然解析失败,
     * 只能靠 ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED 重试兜底(用户看到一次黑屏/重试)。
     */
    private String purifyUrl() {
        return "http://127.0.0.1:" + RemoteServer.serverPort + "/purify.m3u8";
    }

    /**
     * 记录"播放过的剧集":key=sourceKey|vodId,value=已播放集索引集合(后台线程写 SP,避免主线程 IO)。
     * 走应用级共享串行执行器(§六:页面不得自建线程池;串行保证同 key 读改写不交错丢更新)
     */
    private void recordPlayedEpisode() {
        if (mVodInfo == null || mVodInfo.id == null)
            return;
        final String videoId = com.github.tvbox.osc.util.player.PlayedVodKey.of(sourceKey, mVodInfo.id);
        final int index = mVodInfo.playIndex;
        com.github.tvbox.osc.util.HeavyTaskUtil.getSerialExecutorService().execute(() -> {
            try {
                Set<String> set = LocalVideoConfig.getPlayed(videoId);
                if (set == null)
                    set = new HashSet<>();
                if (set.add(String.valueOf(index))) {
                    LocalVideoConfig.savePlayed(videoId, set);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private void cancelAddressProbe() {
        okhttp3.Call call;
        synchronized (mAddressProbeLock) {
            AddressProbe probe = mAddressProbe;
            mAddressProbe = null;
            if (probe == null) return;
            probe.canceled = true;
            call = probe.call;
        }
        if (call != null) call.cancel();
    }

    private boolean cancelAddressProbeIfCurrent(long generation) {
        okhttp3.Call call = null;
        synchronized (mAddressProbeLock) {
            if (!isCurrentPlayback(generation)) return false;
            AddressProbe probe = mAddressProbe;
            mAddressProbe = null;
            if (probe != null) {
                probe.canceled = true;
                call = probe.call;
            }
        }
        if (call != null) call.cancel();
        return true;
    }

    private boolean isCurrentAddressProbe(long generation, AddressProbe probe) {
        synchronized (mAddressProbeLock) {
            return isCurrentPlayback(generation) && mAddressProbe == probe && !probe.canceled;
        }
    }

    void startPlayUrl(String url, HashMap<String, String> headers, long generation, String sourceUrl) {
        checkAddressConnection(url, headers, generation, sourceUrl, false,
                () -> startPlayUrlUnchecked(url, headers, generation, sourceUrl));
    }

    private void checkAddressConnection(String url, HashMap<String, String> headers,
                                        long generation, String sourceUrl, boolean beforeManifest,
                                        Runnable onReady) {
        if (!isCurrentPlayback(generation)) return;
        boolean httpUrl = url != null && (url.regionMatches(true, 0, "http://", 0, 7)
                || url.regionMatches(true, 0, "https://", 0, 8));
        int playerType = mVodPlayerCfg == null ? PlayConfig.getPlayType()
                : mVodPlayerCfg.optInt("pl", PlayConfig.getPlayType());
        // 外部播放器有自己的网络链路；本机内核按各自取流路线判断连接失败。
        if (!httpUrl || playerType >= 10) {
            if (!cancelAddressProbeIfCurrent(generation)) return;
            onReady.run();
            return;
        }
        PlaybackConnectionProbe.Mode probeMode = beforeManifest || playerType == 2
                ? PlaybackConnectionProbe.Mode.MEDIA3 : PlaybackConnectionProbe.Mode.NATIVE;
        AddressProbe probe = new AddressProbe(generation, url, probeMode);
        okhttp3.Call previousCall = null;
        boolean alreadyChecked;
        synchronized (mAddressProbeLock) {
            if (!isCurrentPlayback(generation)) return;
            AddressProbe previous = mAddressProbe;
            alreadyChecked = previous != null && !previous.canceled && previous.checked
                    && previous.generation == generation && previous.mode == probeMode
                    && TextUtils.equals(previous.url, url);
            if (!alreadyChecked) mAddressProbe = probe;
            if (!alreadyChecked && previous != null) {
                previous.canceled = true;
                previousCall = previous.call;
            }
        }
        if (alreadyChecked) {
            onReady.run();
            return;
        }
        if (previousCall != null) previousCall.cancel();
        okhttp3.Call call = PlaybackConnectionProbe.probe(
                beforeManifest ? HttpClient.getClient() : App.playbackHttpClient(), url, probeMode,
                (result, error) -> {
                    android.app.Activity host = getActivity();
                    if (host == null) return;
                    host.runOnUiThread(() -> {
                        if (!isCurrentAddressProbe(generation, probe) || !isAdded()) return;
                        if (result == PlaybackConnectionProbe.Result.CONNECTION_FAILED) {
                            if (beforeManifest && playerType != 2) {
                                // 清单请求走 OkHttp，原生内核可能有不同的 TLS/路由；
                                // 跳过已确认会失败的清单请求，交给原生路线再判断。
                                String detail = "清单连接失败，改由原生内核检查: 地址="
                                        + PlaybackErrorReporter.source(url) + "，原因="
                                        + PlaybackErrorReporter.cause(error);
                                com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.PLAYER, detail);
                                android.util.Log.w("MBoxPlayer", detail);
                                startPlayUrl(url, headers, generation, sourceUrl);
                                return;
                            }
                            PlaybackSnapshot failed = new PlaybackSnapshot(generation, url, sourceUrl, headers);
                            if (retryProxyTargetDirectly(failed)) return;
                            failBeforePlayback(generation, url, sourceUrl,
                                    CONNECTION_FAILURE_TIP, PlaybackErrorReporter.cause(error));
                            return;
                        }
                        if (result == PlaybackConnectionProbe.Result.INCONCLUSIVE && error != null) {
                            String detail = "播放地址预检未判定，交给"
                                    + (beforeManifest ? "清单处理" : PlayerHelper.getPlayerName(playerType))
                                    + ": 地址=" + PlaybackErrorReporter.source(url)
                                    + "，原因=" + PlaybackErrorReporter.cause(error);
                            com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.PLAYER, detail);
                            android.util.Log.w("MBoxPlayer", detail);
                        }
                        synchronized (mAddressProbeLock) {
                            if (mAddressProbe != probe || probe.canceled
                                    || !isCurrentPlayback(generation)) return;
                            probe.checked = true;
                        }
                        onReady.run();
                    });
                });
        boolean cancelCall;
        synchronized (mAddressProbeLock) {
            cancelCall = mAddressProbe != probe || probe.canceled || !isCurrentPlayback(generation);
            if (!cancelCall) probe.call = call;
        }
        if (cancelCall && call != null) call.cancel();
    }

    private void startPlayUrlUnchecked(String url, HashMap<String, String> headers,
                                       long generation, String sourceUrl) {
        if (!isCurrentPlayback(generation)) return;
        String finalUrl = url;
        HashMap<String, String> playbackHeaders = headers == null ? null : new HashMap<>(headers);
        if (mActivity == null || !isAdded())
            return;
        requireActivity().runOnUiThread(() -> {
            if (!isCurrentPlayback(generation) || !isAdded()) return;
            if (mParseEngine != null)
                mParseEngine.stopParse();
            if (mPlaySession != null)
                mPlaySession.release();

            if (finalUrl != null) {
                recordPlayedEpisode();
                try {
                    int playerType = mVodPlayerCfg.getInt("pl");
                    if (playerType >= 10) {
                        VodInfo.VodSeries vs = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
                        String playTitle = mVodInfo.name + " " + vs.name;
                        setTip("调用外部播放器" + PlayerHelper.getPlayerName(playerType) + "进行播放", true, false);
                        boolean callResult = false;
                        long progress = getSavedProgress(progressKey);
                        // 用 getActivity() 并判空:解析回包晚于页面销毁时,requireActivity() 会抛
                        // IllegalStateException 直接把应用带崩
                        android.app.Activity host = getActivity();
                        if (host == null) return;
                        callResult = PlayerHelper.runExternalPlayer(playerType, host, finalUrl, playTitle,
                                playSubtitle, playbackHeaders, progress);
                        String playerName = PlayerHelper.getPlayerName(playerType);
                        logPlaybackProgress("调用外部播放器" + playerName + (callResult ? "成功" : "失败"));
                        setTip((callResult ? "已打开" : "无法打开") + playerName, false, !callResult);
                        return;
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                hideTip();
                String lowerUrl = finalUrl.toLowerCase(Locale.ROOT);
                String contentHint = lowerUrl.contains(".m3u8") ? "HLS候选"
                        : lowerUrl.contains(".mpd") ? "DASH候选" : "其他";
                com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.PLAYER,
                        "播放入口探针: 内核=" + mVodPlayerCfg.optInt("pl", PlayConfig.getPlayType())
                                + "，渲染=" + PlayerHelper.getRenderName(mVodPlayerCfg.optInt("pr", PlayConfig.getRenderType()))
                                + "，类型=" + contentHint);
                PlayerHelper.updateCfg(mVideoView, mVodPlayerCfg);
                // 与真正起播的地址和请求头成对发布，宿主同步回调会立刻读取该快照。
                mResolvedPlayback = new PlaybackSnapshot(generation, finalUrl, sourceUrl, playbackHeaders);
                boolean castOnly = false;
                if (mSyncHost != null && mVodInfo != null && mVodInfo.seriesMap != null
                        && mVodInfo.seriesMap.get(mVodInfo.playFlag) != null
                        && mVodInfo.playIndex < mVodInfo.seriesMap.get(mVodInfo.playFlag).size()) {
                    VodInfo.VodSeries episode = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
                    castOnly = mSyncHost.onPlaybackResolved(mVodInfo.name + " " + episode.name, finalUrl);
                }
                if (castOnly) {
                    showBrowserCastStatus();
                    return;
                }
                // 起播统一经 PlayerSession(设进度键+URL+start;内核隔离入口)
                if (mPlaySession != null) {
                    mPlaySession.play(finalUrl, progressKey, playbackHeaders);
                }
                mController.resetSpeed();
                bindPlaybackSession(finalUrl); // playback 会话原型:观察当前内核状态/进度(仅日志,不驱动)
            }
        });
    }

    /**
     * playback 会话原型(roadmap 2.1):内核对内开始播放后,把共享 mVideoView 包成
     * {@link com.github.tvbox.osc.player.VideoViewPlayerApi}(ownsVideoView=false,不夺权)
     * 注册到 {@link com.github.tvbox.osc.player.api.PlaybackSessions} 并挂只读日志观察者;
     * 用于链路排查(状态/缓冲/错误/进度),不改变现有 mVideoView/Controller 控制流。
     */
    private void bindPlaybackSession(String url) {
        try {
            releasePlaybackSession();
            if (mVideoView == null || mVodInfo == null || mVodInfo.id == null)
                return;
            playbackSessionKey = com.github.tvbox.osc.util.player.PlaySessionKeys.playbackSessionKey(sourceKey,
                    mVodInfo);
            com.github.tvbox.osc.player.VideoViewPlayerApi api = mPlaySession != null ? mPlaySession.playerApi() : null;
            if (api == null) {
                playbackSessionKey = null;
                return;
            }
            com.github.tvbox.osc.player.api.PlaybackSessions.Session session = com.github.tvbox.osc.player.api.PlaybackSessions
                    .bind(playbackSessionKey, api, true);
            if (session == null) {
                playbackSessionKey = null;
                return;
            }
            api.init(requireActivity(), null, null); // 启动状态轮询(不挂 VideoView 额外监听)
            session.observe(new com.github.tvbox.osc.player.api.PlayListener() {
                @Override
                public void onStateChanged(com.github.tvbox.osc.player.api.PlayState state) {
                    android.util.Log.d("PlaybackSession", "[" + playbackSessionKey + "] state=" + state);
                    // 当前集进入健康播放后, opportunistically 预载下一集(配置开启时)
                    if (state == com.github.tvbox.osc.player.api.PlayState.PLAYING) {
                        scheduleNextEpisodePreload();
                    }
                }

                @Override
                public void onBufferingStart() {
                    android.util.Log.d("PlaybackSession", "[" + playbackSessionKey + "] buffering start");
                }

                @Override
                public void onBufferingEnd() {
                    android.util.Log.d("PlaybackSession", "[" + playbackSessionKey + "] buffering end");
                }

                @Override
                public void onError(int code, String message) {
                    android.util.Log.w("PlaybackSession", "[" + playbackSessionKey + "] error code=" + code
                            + " msg=" + message);
                }

                @Override
                public void onCompletion() {
                    android.util.Log.d("PlaybackSession", "[" + playbackSessionKey + "] completed");
                }
            });
        } catch (Throwable th) {
            android.util.Log.w("PlaybackSession", "bind 会话异常(原型,不影响播放)", th);
            playbackSessionKey = null;
        }
    }

    private void scheduleNextEpisodePreload() {
        if (mNextEpisodePreloader == null || mVodInfo == null || mVodInfo.seriesMap == null) return;
        int playerType = mVodPlayerCfg == null ? com.github.tvbox.osc.player.api.PlayConfig.getPlayType()
                : mVodPlayerCfg.optInt("pl", com.github.tvbox.osc.player.api.PlayConfig.getPlayType());
        if (playerType != 2) return;
        java.util.List<VodInfo.VodSeries> episodes = mVodInfo.seriesMap.get(mVodInfo.playFlag);
        int nextIndex = mVodInfo.playIndex + 1;
        if (episodes == null || nextIndex < 0 || nextIndex >= episodes.size()) return;
        VodInfo.VodSeries next = episodes.get(nextIndex);
        if (next == null) return;
        long startMs = mVodPlayerCfg == null ? 0L : Math.max(0L, mVodPlayerCfg.optInt("st", 0) * 1000L);
        mNextEpisodePreloader.schedule(sourceKey, mVodInfo.playFlag, next.url, startMs,
                com.github.tvbox.osc.player.api.PlayConfig.getPreloadDurationS());
    }

    /** playback 会话原型:释放会话观察(不释放共享 mVideoView;视频释放仍由原流程负责) */
    private void releasePlaybackSession() {
        try {
            if (playbackSessionKey != null) {
                com.github.tvbox.osc.player.api.PlaybackSessions.release(playbackSessionKey);
                android.util.Log.d("PlaybackSession", "[" + playbackSessionKey + "] unbind");
                playbackSessionKey = null;
            }
        } catch (Throwable th) {
            android.util.Log.w("PlaybackSession", "release 会话异常(原型)", th);
            playbackSessionKey = null;
        }
    }

    private void initParseEngine() {
        if (mParseEngine != null) return;
        final long lifetime = mParseLifetimeGeneration.get();
        mParseEngine = new com.github.tvbox.osc.util.player.PlayParseCoordinator(mActivity, this,
                new com.github.tvbox.osc.util.player.PlayParseCoordinator.Callback() {
                    private boolean current() { return lifetime == mParseLifetimeGeneration.get(); }
                    @Override public void onShowTip(String msg, boolean loading, boolean err) {
                        if (!current()) return;
                        if (err) PlaybackErrorReporter.failure("地址解析", "失败",
                                PlaybackErrorReporter.source(mRequestedPlayUrl), msg);
                        PlayFragment.this.setTip(msg, loading, err);
                    }
                    @Override public void onPlayUrl(String url, HashMap<String, String> headers) {
                        postOnUiThread(() -> PlayFragment.this.playUrl(url, headers));
                    }
                    @Override public void onErrorRetry(String err, boolean finish) {
                        postOnUiThread(() -> PlayFragment.this.errorWithRetry(err, finish));
                    }
                    @Override public void onShowParseRoot(boolean show) {
                        postOnUiThread(() -> {
                            if (mController != null) mController.showParse(show);
                        });
                    }
                    @Override public boolean postOnUiThread(Runnable action) {
                        if (!current() || !isAdded()) return false;
                        requireActivity().runOnUiThread(() -> {
                            if (current()) action.run();
                        });
                        return true;
                    }
                });
    }

    /** 切换详情影片时停止旧播放及解析；播放器 View 保留供新详情使用。 */
    public void clearData() {
        pendingReadyAction = null;
        if (mNextEpisodePreloader != null) mNextEpisodePreloader.cancel();
        browserCastActive = false;
        if (mController != null) mController.setBrowserRemote(false);
        if (mPlayLoadTip != null) hideTip();
        mParseLifetimeGeneration.incrementAndGet();
        mPlaybackGeneration.incrementAndGet();
        mVodInfo = null;
        mVodPlayerCfg = null;
        mRequestedPlayUrl = null;
        mPlayResultToken = null;
        mResolvedPlayback = null;
        cancelAddressProbe();
        HttpClient.cancel("m3u8-1");
        HttpClient.cancel("m3u8-2");
        if (mParseEngine != null) {
            mParseEngine.destroy();
            mParseEngine = null;
        }
        if (mSubtitleCoordinator != null) {
            mSubtitleCoordinator.invalidateForPlaybackChange();
            mSubtitleCoordinator.updateSubtitleContext(null, null);
        }
        releasePlaybackSession();
        if (mPlaySession != null) mPlaySession.release();
        if (mController != null) {
            mController.setTitle("");
            mController.showParse(false);
        }
        if (mPlayingControlDialog != null) mPlayingControlDialog.dismiss();
        if (mPlayingControlRightDialog != null) mPlayingControlRightDialog.dismiss();
        Thunder.stop(true);
        Jianpian.finish();
    }

    private void initSubtitleView() {
        // 字幕装载/内置字幕自动选中文等已收口 SubtitleCoordinator;同步当前字幕上下文后委托
        if (mSubtitleCoordinator == null)
            return;
        mSubtitleCoordinator.updateSubtitleContext(playSubtitle, subtitleCacheKey);
        mSubtitleCoordinator.initSubtitleView();
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.playResult.observeForever(mObserverPlayResult);
        mNextEpisodePreloader = com.github.tvbox.osc.di.AppCompositionRoot.newNextEpisodePreloader(requireContext());
    }

    private final Observer<JSONObject> mObserverPlayResult = new Observer<JSONObject>() {
        @Override
        public void onChanged(JSONObject info) {
            if (info != null) {
                // ViewModel 的旧集结果可能晚到；它携带请求键，先校验再进入解析链。
                if (!TextUtils.equals(mPlayResultToken, info.optString("proKey"))
                        || !TextUtils.equals(mRequestedPlayUrl, info.optString("key"))) {
                    // 过期回包丢弃(换集/重试的旧响应属正常)。同集地址但令牌不匹配=当前请求的
                    // 回包被丢,加载态会一直转圈,必须留痕排查(看门狗会兜底收尾)。
                    android.util.Log.w("MBoxPlayer", "丢弃播放回包: 当前令牌=" + mPlayResultToken
                            + "，回包令牌=" + info.optString("proKey")
                            + "，同集地址=" + TextUtils.equals(mRequestedPlayUrl, info.optString("key")));
                    return;
                }
                if (info.optBoolean("playError")) {
                    errorWithRetry("获取播放信息错误", true);
                    return;
                }
                try {
                    boolean parse = info.optString("parse", "1").equals("1");
                    boolean jx = info.optString("jx", "0").equals("1");
                    playSubtitle = info.optString("subt", /*
                                                           * "https://dash.akamaized.net/akamai/test/caption_test/ElephantsDream/ElephantsDream_en.vtt"
                                                           */"");
                    // progressKey/subtitleCacheKey 由 play() 经 PlayRequest 落字段,不再从 proKey/subtKey 回写
                    String playUrl = info.optString("playUrl", "");
                    String flag = info.optString("flag");
                    String url = info.getString("url");
                    HashMap<String, String> headers = null;
                    String resultUserAgent = null;
                    // 播放结果 header 上下文收口到解析引擎(嗅探 WebView 加载/下载回退 UA 用)
                    mParseEngine.resetWebRequestContext();
                    if (info.has("header")) {
                        try {
                            JSONObject hds = new JSONObject(info.getString("header"));
                            Iterator<String> keys = hds.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                if (headers == null) {
                                    headers = new HashMap<>();
                                }
                                headers.put(key, hds.getString(key));
                                if (key.equalsIgnoreCase("user-agent")) {
                                    resultUserAgent = hds.getString(key).trim();
                                }
                            }
                            mParseEngine.setWebRequestContext(headers, resultUserAgent);
                        } catch (Throwable th) {

                        }
                    }
                    if (parse || jx) {
                        boolean userJxList = (playUrl.isEmpty()
                                && SourceConfigProviders.get().getVipParseFlags().contains(flag)) || jx;
                        mParseEngine.initParse(flag, userJxList, playUrl, url);
                    } else {
                        mController.showParse(false);
                        String resolvedUrl = (playUrl + url).trim();
                        // 网盘源(playerContent 走 sId/sToken)取不到直链时返回空地址是常态:
                        // 扫码/登录刚完成时爬虫内部才拿得到 cookie,这一轮注定为空。
                        // 以前把空地址直接交给内核 ⇒ 播放器报"地址为空"后停在失败页,
                        // 用户只能退出播放页重进(重进才会再请求一次)。现在留在本页自动重试。
                        if (TextUtils.isEmpty(resolvedUrl)) {
                            logPlaybackProgress("解析结果无地址 parse=" + parse
                                    + " jx=" + jx + " flag=" + flag
                                    + " playUrl长度=" + playUrl.length()
                                    + " url长度=" + (url == null ? -1 : url.length())
                                    + describeNetDiskCredentials()
                                    + describeRecentTouched());
                            onPlayAddressMissing();
                            return;
                        }
                        playUrl(resolvedUrl, headers);
                    }
                } catch (Throwable th) {
                    LogUtils.e(th.toString());
                    errorWithRetry("获取播放信息错误", true);
                }
            }
        }
    };

    public void setData(Bundle bundle) {
        setData(bundle, true);
    }

    /** 已有浏览器投屏重连时只装配当前剧集与控件，不重新取流或起播。 */
    public void setData(Bundle bundle, boolean startPlayback) {
        // mVodInfo = (VodInfo) bundle.getSerializable("VodInfo");
        mVodInfo = App.getInstance().getVodInfo();
        sourceKey = bundle.getString("sourceKey");
        sourceBean = SourceConfigProviders.get().getSource(sourceKey);
        initParseEngine();
        if (mParseEngine != null)
            mParseEngine.setSourceBean(sourceBean);
        initPlayerCfg();
        if (startPlayback) play(false);
        else if (mVodInfo != null && mVodInfo.seriesMap != null) {
            List<VodInfo.VodSeries> episodes = mVodInfo.seriesMap.get(mVodInfo.playFlag);
            if (episodes != null && mVodInfo.playIndex >= 0 && mVodInfo.playIndex < episodes.size())
                mController.setTitle(mVodInfo.name + " " + episodes.get(mVodInfo.playIndex).name);
        }
    }

    private void initData() {
        /*
         * Intent intent = getIntent();
         * if (intent != null && intent.getExtras() != null) {
         * 
         * }
         */
    }

    void initPlayerCfg() {
        try {
            mVodPlayerCfg = new JSONObject(mVodInfo.playerCfg);
        } catch (Throwable th) {
            mVodPlayerCfg = new JSONObject();
        }
        try {
            if (!mVodPlayerCfg.has("pl")) {
                // sourceBean 可为 null(push_agent 直链播放:订阅里没有该虚拟源),不能裸取
                mVodPlayerCfg.put("pl", (sourceBean == null || sourceBean.getPlayerType() == -1)
                        ? (int) PlayConfig.getPlayType() : sourceBean.getPlayerType());
            }
            if (!mVodPlayerCfg.has("pr")) {
                mVodPlayerCfg.put("pr", PlayConfig.getRenderType());
            }
            if (!mVodPlayerCfg.has("sc")) {
                mVodPlayerCfg.put("sc", PlayConfig.getScaleType());
            }
            if (!mVodPlayerCfg.has("sp")) {
                mVodPlayerCfg.put("sp", 1.0f);
            }
            if (!mVodPlayerCfg.has("st")) {
                mVodPlayerCfg.put("st", 0);
            }
            if (!mVodPlayerCfg.has("et")) {
                mVodPlayerCfg.put("et", 0);
            }
        } catch (Throwable th) {

        }
        mController.setPlayerConfig(mVodPlayerCfg);
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null) {
            if (mController.onKeyEvent(event)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mNextEpisodePreloader != null) mNextEpisodePreloader.cancel();
        if (!browserCastActive && mPlaySession != null)
            mPlaySession.pause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!browserCastActive && mPlaySession != null)
            mPlaySession.resume();
        retryMissingAddressOnResume();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) {
            if (mNextEpisodePreloader != null) mNextEpisodePreloader.cancel();
            if (!browserCastActive && mPlaySession != null)
                mPlaySession.pause();
        } else {
            if (!browserCastActive && mPlaySession != null)
                mPlaySession.resume();
        }
        super.onHiddenChanged(hidden);
    }

    @Override
    public void onDestroyView() {
        playbackReady = false;
        browserCastActive = false;
        pendingReadyAction = null;
        disarmLoadingWatchdog();
        mMainHandler.removeCallbacks(mMissingAddressRetryTask);
        mWaitingMissingAddress = false;
        mSawLoginDialog = false;
        mPlaybackGeneration.incrementAndGet();
        mParseLifetimeGeneration.incrementAndGet();
        cancelAddressProbe();
        HttpClient.cancel("m3u8-1");
        HttpClient.cancel("m3u8-2");
        mResolvedPlayback = null;
        mRequestedPlayUrl = null;
        mPlayResultToken = null;
        super.onDestroyView();
        // 手动注销
        sourceViewModel.playResult.removeObserver(mObserverPlayResult);
        if (mNextEpisodePreloader != null) {
            mNextEpisodePreloader.release();
            mNextEpisodePreloader = null;
        }

        releasePlaybackSession(); // playback 会话原型:随视图销毁释放会话观察(共享视图不在此释放)
        // 字幕协调器:取消在途的"切换轨道后恢复进度"延迟任务 —— 否则 800ms 内退出播放页时,
        // 它会打到正在释放的内核上(seekTo/start 落到已释放的原生播放器 = native 崩)
        if (mSubtitleCoordinator != null) {
            mSubtitleCoordinator.release();
            mSubtitleCoordinator = null;
        }
        if (mBatteryListener != null) {
            com.github.tvbox.osc.state.SystemStateMonitor monitor = com.github.tvbox.osc.state.SystemStateMonitor.get();
            if (monitor != null)
                monitor.unregister(mBatteryListener);
            mBatteryListener = null;
        }
        if (mPlaySession != null)
            mPlaySession.release();
        // 置空:释放后若还有晚到的回调(如控制器点击/解析回包)取到它,只会拿到已释放的会话
        mPlaySession = null;
        mVideoView = null;
        if (mParseEngine != null) {
            mParseEngine.destroy(); // 取消解析任务/嗅探超时/HTTP 并销毁无头 WebView(原 stopLoadWebView(true)+stopParse)
            mParseEngine = null;
        }
        Thunder.stop(true);// 停止磁力下载
        Jianpian.finish();// 停止p2p下载
    }

    private VodInfo mVodInfo;
    private JSONObject mVodPlayerCfg;
    private String sourceKey;
    private SourceBean sourceBean;

    public void playNext(boolean isProgress) {
        if (!canChangeBrowserEpisode()) return;
        boolean hasNext;
        if (mVodInfo == null || mVodInfo.seriesMap.get(mVodInfo.playFlag) == null) {
            hasNext = false;
        } else {
            hasNext = mVodInfo.playIndex + 1 < mVodInfo.seriesMap.get(mVodInfo.playFlag).size();
        }
        if (!hasNext) {
            AppBubble.toast("已经是最后一集了!");
            return;
        } else {
            mVodInfo.playIndex++;
        }
        play(false);
    }

    public void playPrevious() {
        if (!canChangeBrowserEpisode()) return;
        boolean hasPre = true;
        if (mVodInfo == null || mVodInfo.seriesMap.get(mVodInfo.playFlag) == null) {
            hasPre = false;
        } else {
            hasPre = mVodInfo.playIndex - 1 >= 0;
        }
        if (!hasPre) {
            AppBubble.toast("已经是第一集了!");
            return;
        }
        mVodInfo.playIndex--;
        play(false);
    }

    private int autoRetryCount = 0;
    private String autoRetryEpisodeKey;
    private volatile boolean mProxyDirectFallbackAttempted;
    private volatile String mProxyDirectOverrideSource;
    private volatile ProxyDirectFallback.Candidate mProxyDirectOverride;

    boolean autoRetry() {
        if (mParseEngine != null && mParseEngine.hasFoundVideo()) {
            logPlaybackProgress("播放失败，尝试下一个候选地址");
            showPlaybackLoading();
            autoRetryFromLoadFoundVideoUrls();
            return true;
        }
        if (autoRetryCount < 1) {
            autoRetryCount++;
            logPlaybackProgress("播放失败，自动重试一次");
            play(false);
            return true;
        } else {
            autoRetryCount = 0;
            return false;
        }
    }

    void autoRetryFromLoadFoundVideoUrls() {
        String videoUrl = mParseEngine.pollFoundVideoUrl();
        HashMap<String, String> header = mParseEngine.getFoundVideoHeaders(videoUrl);
        playUrl(videoUrl, header);
    }

    void initParseLoadFound() {
        if (mParseEngine != null)
            mParseEngine.resetFoundQueue();
    }

    public void play(boolean reset) {
        if (!canChangeBrowserEpisode()) return;
        if (mVodInfo == null) {
            logPlaybackProgress("放弃起播: mVodInfo 为空(详情数据未就绪)");
            return;
        }
        browserCastStatusVisible = false;
        dlnaCastStatusVisible = false;
        if (mPlayLoadTip != null) mPlayLoadTip.setOnClickListener(null);
        if (mSubtitleCoordinator != null) mSubtitleCoordinator.invalidateForPlaybackChange();
        long requestGeneration = mPlaybackGeneration.incrementAndGet();
        if (mNextEpisodePreloader != null) mNextEpisodePreloader.cancel();
        // 新一轮起播接管重试节奏，旧的延迟重试作废(否则会在新请求上再打一发)
        mMainHandler.removeCallbacks(mMissingAddressRetryTask);
        cancelAddressProbe();
        HttpClient.cancel("m3u8-1");
        HttpClient.cancel("m3u8-2");
        mResolvedPlayback = null;
        mRequestedPlayUrl = null;
        mPlayResultToken = null;
        List<VodInfo.VodSeries> playEpisodes = mVodInfo.seriesMap == null ? null
                : mVodInfo.seriesMap.get(mVodInfo.playFlag);
        if (playEpisodes == null || mVodInfo.playIndex < 0 || mVodInfo.playIndex >= playEpisodes.size()) {
            // 原实现裸取 seriesMap.get(flag).get(index):越界即抛,调用方无感知、加载态不再收尾
            logPlaybackProgress("放弃起播: 剧集索引无效 flag=" + mVodInfo.playFlag
                    + " index=" + mVodInfo.playIndex
                    + " 集数=" + (playEpisodes == null ? "flag不存在" : playEpisodes.size()));
            errorWithRetry("获取播放信息错误", true);
            return;
        }
        VodInfo.VodSeries vs = playEpisodes.get(mVodInfo.playIndex);
        if (mSyncHost != null)
            mSyncHost.onEpisodeSelected(mVodInfo.playIndex);
        com.github.tvbox.osc.service.PlayService.onPlaybackNotify(mVodInfo.name + "&&" + vs.name);
        String playTitleInfo = mVodInfo.name + " " + vs.name;
        logPlaybackProgress("起播请求: 源=" + (sourceBean == null ? "?" : sourceBean.getName())
                + " type=" + (sourceBean == null ? "?" : sourceBean.getType())
                + " 集=" + vs.name + " 地址=" + describePlayUrl(vs.url)
                + describeExtCredentialPaths(sourceBean == null ? null : sourceBean.getExt())
                + describeRecentTouched());
        setTip("正在获取播放信息", true, false);
        mController.setTitle(playTitleInfo);

        if (mParseEngine != null)
            mParseEngine.stopParse();
        initParseLoadFound();
        releasePlaybackSession(); // playback 会话原型:切换前释放上一会话(只停观察,不释放共享 mVideoView)
        if (mPlaySession != null)
            mPlaySession.release();
        com.github.tvbox.osc.util.player.PlayRequest playRequest = com.github.tvbox.osc.util.player.PlayRequest
                .of(mVodInfo, vs);
        // 播放请求上下文收敛:键单一来源 PlayRequest;playResult 回调不再经 proKey/subtKey 回写
        progressKey = playRequest.progressKey();
        if (!TextUtils.equals(autoRetryEpisodeKey, progressKey)) {
            autoRetryEpisodeKey = progressKey;
            autoRetryCount = 0;
            mMissingAddressRetryCount = 0;
            mWaitingMissingAddress = false;
            mSawLoginDialog = false;
            mProxyDirectFallbackAttempted = false;
            mProxyDirectOverrideSource = null;
            mProxyDirectOverride = null;
        }
        subtitleCacheKey = playRequest.subtitleCacheKey();
        mRequestedPlayUrl = playRequest.url();
        // SourceViewModel 原样回传 proKey；加入代数后，同一集重试的旧响应也会被过滤。
        mPlayResultToken = progressKey + "|resolve:" + requestGeneration;
        // 重新播放清除现有进度
        if (reset) {
            mPlayHistory.delete(progressKey);
            mPlayHistory.delete(subtitleCacheKey);
        }
        if (com.github.tvbox.osc.config.SystemConfig.isThunderP2pEnabled()) {
            if (Jianpian.isJpUrl(vs.url)) {// 荐片地址特殊判断
                String jp_url = vs.url;
                mController.showParse(false);
                if (vs.url.startsWith("tvbox-xg:")) {
                    playUrl(Jianpian.JPUrlDec(jp_url.substring(9)), null);
                } else {
                    playUrl(Jianpian.JPUrlDec(jp_url), null);
                }
                return;
            }
            if (Thunder.play(vs.url, new Thunder.ThunderCallback() {
                @Override
                public void status(int code, String info) {
                    if (!isCurrentPlayback(requestGeneration)) return;
                    if (code < 0) {
                        PlaybackErrorReporter.failure("磁力播放", "失败",
                                PlaybackErrorReporter.source(vs.url), info);
                        setTip(info, false, true);
                    } else {
                        setTip(info, true, false);
                    }
                }

                @Override
                public void list(Map<Integer, String> urlMap) {
                }

                @Override
                public void play(String url) {
                    if (!isCurrentPlayback(requestGeneration)) return;
                    playUrl(url, null);
                }
            })) {
                mController.showParse(false);
                return;
            }
        } else if (Jianpian.isJpUrl(vs.url) || Thunder.isSupportUrl(vs.url)) {
            // 磁力/迅雷/荐片地址在通道关闭时既无法被 spider 解析、也无法被播放器加载:
            // 明确提示引导开启,避免静默走空解析而永远转圈(2026-10-25 回归修复)。
            showThunderChannelTip(vs.url, requestGeneration);
            return;
        }
        sourceViewModel.getPlay(playRequest.sourceKey(), mVodInfo.playFlag,
                mPlayResultToken, playRequest.url(), playRequest.subtitleCacheKey());
    }

    /**
     * 磁力/迅雷/荐片片源需要「迅雷P2P播放」通道(默认关闭,涉及仅 ARM so 与硬编码凭证)。
     * 通道关闭时起播这类地址只会永远转圈,这里给出可点击跳转设置的明确提示。
     */
    private void showThunderChannelTip(String url, long generation) {
        if (!isAdded()) return;
        PlaybackErrorReporter.failure("迅雷通道", "未开启",
                PlaybackErrorReporter.source(url), "片源为磁力/迅雷/荐片地址,通道已关闭");
        requireActivity().runOnUiThread(() -> {
            if (!isCurrentPlayback(generation) || !isAdded() || mPlayLoadTip == null) return;
            disarmLoadingWatchdog();
            mLoadingStage = "等待开启迅雷P2P播放通道";
            logPlaybackProgress(mLoadingStage);
            mPlayLoading.setVisibility(View.GONE);
            mPlayLoadErr.setVisibility(View.VISIBLE);
            SpanUtils.with(mPlayLoadTip)
                    .append("该片源需要开启「迅雷P2P播放」通道，")
                    .append("去开启")
                    .setClickSpan(ColorUtils.getColor(R.color.orange), false, view -> {
                        try {
                            requireActivity().startActivity(new android.content.Intent(
                                    requireActivity(),
                                    com.github.tvbox.osc.ui.activity.SettingActivity.class));
                        } catch (Throwable ignored) {
                        }
                    }).create();
            mPlayLoadTip.setVisibility(View.VISIBLE);
        });
    }

    // ============ 网盘源取不到直链:留在播放页自动重试 ============

    /**
     * 重试退避(毫秒)。扫码/授权通常要十几秒到一分钟，间隔太短只会把爬虫打满；
     * 超过次数后仍保留"立即重试"，不再自动打。
     */
    private static final long[] MISSING_ADDRESS_RETRY_MS = {20_000L, 15_000L, 30_000L, 60_000L};
    private int mMissingAddressRetryCount = 0;
    /** 当前停在"网盘没给地址"的状态：返回播放页(登录完成)时据此再请求一次。 */
    private volatile boolean mWaitingMissingAddress = false;
    private long mLastMissingAddressRetryAt = 0L;
    /**
     * 本轮等待期内是否出现过登录框(播放页失焦)。
     * jar 弹出的扫码框挂在当前 Activity 上，一出现就说明"它在等用户登录"：
     * 这时候任何自动重试都会把二维码刷掉，必须停；等框关掉再重新拉一次。
     */
    private boolean mSawLoginDialog = false;
    private final Runnable mMissingAddressRetryTask = () -> {
        if (!isAdded() || mVodInfo == null || !mWaitingMissingAddress) return;
        mLastMissingAddressRetryAt = System.currentTimeMillis();
        logPlaybackProgress("网盘地址仍为空，自动重新获取(第" + mMissingAddressRetryCount + "次)");
        play(false);
    };

    /**
     * playerContent 返回空地址 = 爬虫这一轮没有直链(网盘未登录/登录态还没落到它能读到的位置)。
     * 不把空地址交给内核(内核只会报"播放地址为空"并停在失败页)，而是留在本页按退避节奏再请求，
     * 同时给出可点的"立即重试"——这样扫码完成后不必退出播放页重进。
     */
    private void onPlayAddressMissing() {
        if (!isAdded()) return;
        mWaitingMissingAddress = true;
        mLastMissingAddressRetryAt = System.currentTimeMillis();
        long generation = mPlaybackGeneration.get();
        // 登录框是在 playerContent 内部弹的，可能比"空地址"回调更早出现：
        // 那种情况下失焦事件进来时我们还没进入等待状态，会漏掉。这里补查一次当前焦点。
        if (!mSawLoginDialog && !hasHostWindowFocus()) {
            mSawLoginDialog = true;
            mMainHandler.removeCallbacks(mMissingAddressRetryTask);
            logPlaybackProgress("网盘地址为空且登录框已在本页，等登录框关闭后重试");
            showMissingAddressTip(generation);
            return;
        }
        if (mMissingAddressRetryCount < MISSING_ADDRESS_RETRY_MS.length) {
            long delay = MISSING_ADDRESS_RETRY_MS[mMissingAddressRetryCount++];
            mMainHandler.removeCallbacks(mMissingAddressRetryTask);
            mMainHandler.postDelayed(mMissingAddressRetryTask, delay);
            logPlaybackProgress("网盘地址为空，将在 " + (delay / 1000) + "s 后自动重新获取(第"
                    + mMissingAddressRetryCount + "次)");
        }
        showMissingAddressTip(generation);
    }

    private void showMissingAddressTip(long generation) {
        if (!isAdded()) return;
        final boolean autoRetryExhausted = mMissingAddressRetryCount >= MISSING_ADDRESS_RETRY_MS.length;
        requireActivity().runOnUiThread(() -> {
            if (!isCurrentPlayback(generation) || !isAdded() || mPlayLoadTip == null) return;
            disarmLoadingWatchdog();
            mPlayLoading.setVisibility(View.GONE);
            mPlayLoadErr.setVisibility(View.VISIBLE);
            mPlayLoadTip.setOnClickListener(null);
            SpanUtils.with(mPlayLoadTip)
                    .append(autoRetryExhausted ? "网盘未返回播放地址"
                            : "网盘未返回播放地址，完成登录并关闭登录框后自动继续")
                    .append("  ")
                    .append("立即重试")
                    .setClickSpan(ColorUtils.getColor(R.color.orange), false,
                            view -> retryMissingAddressNow())
                    .create();
            mPlayLoadTip.setVisibility(View.VISIBLE);
        });
    }

    private void retryMissingAddressNow() {
        if (!isAdded() || mVodInfo == null) return;
        mMainHandler.removeCallbacks(mMissingAddressRetryTask);
        mMissingAddressRetryCount = 0;
        mSawLoginDialog = false;
        mLastMissingAddressRetryAt = System.currentTimeMillis();
        logPlaybackProgress("手动重新获取播放地址");
        play(false);
    }

    /**
     * 用户离开播放页(去别处完成扫码/授权)再回来时补一次请求：
     * 否则页面会一直停在"没拿到地址"，只有退出重进才会重新请求。
     */
    private void retryMissingAddressOnResume() {
        if (!mWaitingMissingAddress || mVodInfo == null || !isAdded()) return;
        if (System.currentTimeMillis() - mLastMissingAddressRetryAt < 2_000L) return;
        mLastMissingAddressRetryAt = System.currentTimeMillis();
        logPlaybackProgress("返回播放页且地址仍为空，重新获取");
        mMainHandler.removeCallbacks(mMissingAddressRetryTask);
        mMainHandler.postDelayed(mMissingAddressRetryTask, 300L);
    }

    /** 播放页所在窗口当前是否持有焦点：false 通常意味着有弹窗(登录框/系统框)盖在上面。 */
    private boolean hasHostWindowFocus() {
        android.app.Activity activity = getActivity();
        if (activity == null) return true;
        android.view.Window window = activity.getWindow();
        View decor = window == null ? null : window.getDecorView();
        return decor == null || decor.hasWindowFocus();
    }

    /**
     * 宿主 Activity 的窗口焦点变化。扫码登录框是爬虫 jar 自己弹在本页上的 Dialog：
     * <ul>
     *   <li>框弹出 → 本页失焦：说明它在等用户登录，此时任何自动重试都会刷新二维码、把用户手里那个扫废，
     *       所以立刻停掉定时重试，只等框关闭；</li>
     *   <li>框关闭 → 本页重新得焦：登录动作刚结束，这是离"能拿到直链"最近的时刻，立即重新请求一次。</li>
     * </ul>
     * 不依赖固定秒数猜扫码耗时，也避免了"扫码中途被重试打断"。
     */
    public void onHostWindowFocusChanged(boolean hasFocus) {
        if (!hasFocus) {
            if (!mWaitingMissingAddress) return;
            if (mSawLoginDialog) return;
            mSawLoginDialog = true;
            mMainHandler.removeCallbacks(mMissingAddressRetryTask);
            logPlaybackProgress("播放页失焦(登录框打开)，暂停自动重试");
            return;
        }
        if (!mWaitingMissingAddress || !mSawLoginDialog || mVodInfo == null || !isAdded()) return;
        mSawLoginDialog = false;
        if (System.currentTimeMillis() - mLastMissingAddressRetryAt < 1_500L) return;
        mLastMissingAddressRetryAt = System.currentTimeMillis();
        logPlaybackProgress("登录框已关闭，立即重新获取播放地址");
        mMainHandler.removeCallbacks(mMissingAddressRetryTask);
        mMainHandler.postDelayed(mMissingAddressRetryTask, 350L);
    }

    private String playSubtitle;
    private String subtitleCacheKey;
    private String progressKey;

    /** 当前集解析出的原始地址，供下载任务保存；不返回临时净化播放地址。 */
    public String getFinalUrl() {
        PlaybackSnapshot snapshot = currentPlaybackSnapshot();
        return snapshot == null || TextUtils.isEmpty(snapshot.sourceUrl) || !RegexUtils.isURL(snapshot.sourceUrl)
                ? "" : snapshot.sourceUrl;
    }

    /** 当前播放器实际使用的地址，供投屏使用；净化 HLS 时可能是临时本机地址。 */
    public String getCastUrl() {
        PlaybackSnapshot snapshot = currentPlaybackSnapshot();
        return snapshot == null || TextUtils.isEmpty(snapshot.url) || !RegexUtils.isURL(snapshot.url)
                ? "" : snapshot.url;
    }

    /** 与当前集地址同轮解析、同次起播的请求头快照。 */
    public Map<String, String> getPlayHeaders() {
        PlaybackSnapshot snapshot = currentPlaybackSnapshot();
        return snapshot == null || TextUtils.isEmpty(snapshot.sourceUrl) || !RegexUtils.isURL(snapshot.sourceUrl)
                || snapshot.headers == null ? null : new HashMap<>(snapshot.headers);
    }

    public MyVideoView getPlayer() {
        return mVideoView;
    }

    public VodController getController() {
        return mController;
    }

}

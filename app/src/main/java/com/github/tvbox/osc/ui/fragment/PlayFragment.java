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
import com.blankj.utilcode.util.SPUtils;
import com.blankj.utilcode.util.SpanUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.constant.CacheConst;
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
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.player.PlayHistoryRepository;
import com.github.tvbox.osc.util.player.PlayParseCoordinator;
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
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import xyz.doikki.videoplayer.player.ProgressManager;

public class PlayFragment extends BaseLazyFragment {
    /** 宿主同步接口(详情预览页选集高亮/播放配置回写;屏内直调,替代历史 EventBus TYPE_REFRESH 屏内事件) */
    public interface PlaySyncHost {
        void onEpisodeSelected(int index);

        void onPlayerCfgChanged(org.json.JSONObject cfg);

        /** 返回 true 表示地址已交给外部设备，本机播放器无需起播。 */
        default boolean onPlaybackResolved(String title, String url) { return false; }
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
    private ImageView mPlayLoadErr;
    private View mPlayLoading;
    private VodController mController;
    private SourceViewModel sourceViewModel;
    /**
     * 解析/嗅探引擎(解析编排 + 无头 WebView 嗅探 + json/聚合解析;见 util/player/PlayParseCoordinator)
     */
    private com.github.tvbox.osc.util.player.PlayParseCoordinator mParseEngine;
    /** 字幕协调器(字幕装载/音轨与内置字幕切换/设置弹窗;见 util/player/SubtitleCoordinator) */
    private com.github.tvbox.osc.util.player.SubtitleCoordinator mSubtitleCoordinator;
    /** 播放进度持久化(key→MD5→CacheRepository,见 util/player/PlayHistoryRepository) */
    private final PlayHistoryRepository mPlayHistory = new PlayHistoryRepository();
    /** playback 会话原型:当前播放对应的会话键(PlaybackSessions 观察/日志用;不驱动内核) */
    private String playbackSessionKey;

    private final long videoDuration = -1;
    /**
     * 记录当前播放url
     */
    private String mCurrentUrl;
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
     * 视频播放出错时,自动切换另一个播放器,这个开关避免多次切换
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
            public void chooseSeries() {
                // activity中已处理
                activity.showAllSeriesDialog();
            }

            @Override
            public void playNext(boolean rmProgress) {
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
                    AppBubble.toast("播放信息加载中，请稍后再试");
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
        // 解析/嗅探引擎(宿主薄委托;解析编排与无头 WebView 收口 util/player)
        mParseEngine = new com.github.tvbox.osc.util.player.PlayParseCoordinator(mActivity, this,
                new com.github.tvbox.osc.util.player.PlayParseCoordinator.Callback() {
                    @Override
                    public void onShowTip(String msg, boolean loading, boolean err) {
                        PlayFragment.this.setTip(msg, loading, err);
                    }

                    @Override
                    public void onPlayUrl(String url, HashMap<String, String> headers) {
                        PlayFragment.this.playUrl(url, headers);
                    }

                    @Override
                    public void onErrorRetry(String err, boolean finish) {
                        PlayFragment.this.errorWithRetry(err, finish);
                    }

                    @Override
                    public void onShowParseRoot(boolean show) {
                        if (mController != null)
                            mController.showParse(show);
                    }

                    @Override
                    public boolean postOnUiThread(Runnable r) {
                        if (!isAdded())
                            return false;
                        requireActivity().runOnUiThread(r);
                        return true;
                    }
                });
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
        if (!isAdded())
            return;
        // 影魔
        requireActivity().runOnUiThread(() -> {
            mPlayLoadTip.setText(msg);
            mPlayLoadTip.setVisibility(View.VISIBLE);
            mPlayLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
            mPlayLoadErr.setVisibility(err ? View.VISIBLE : View.GONE);

            if ("视频播放出错".equals(msg)) {
                if (!retriedSwitchPlayer) {
                    AppBubble.toast("播放出错,正在尝试切换播放器");
                    retriedSwitchPlayer = true;
                    mController.mPlayerBtn.performClick();
                } else {
                    SpanUtils.with(mPlayLoadTip)
                            .append("视频播放出错，")
                            .append("切换播放器")
                            .setClickSpan(ColorUtils.getColor(R.color.orange), false, view -> {
                                mController.mPlayerBtn.performClick();
                            }).create();
                }
            }
        });
    }

    void hideTip() {
        mPlayLoadTip.setVisibility(View.GONE);
        mPlayLoading.setVisibility(View.GONE);
        mPlayLoadErr.setVisibility(View.GONE);
    }

    void errorWithRetry(String err, boolean finish) {
        String detail = "点播失败: 源=" + sourceKey + "，内核="
                + (mVodPlayerCfg == null ? -1 : mVodPlayerCfg.optInt("pl", -1)) + "，原因=" + err;
        com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.PLAYER, detail);
        if (!autoRetry() && isAdded()) {
            requireActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (finish) {
                        AppBubble.toast(err);
                        hideTip();
                    } else {
                        setTip(err, false, true);
                    }
                }
            });
        }
    }

    void playUrl(String url, HashMap<String, String> headers) {
        mCurrentUrl = url;
        if (!PlayConfig.isVideoPurify()) {
            startPlayUrl(url, headers);
            return;
        }
        if (!url.contains("://127.0.0.1/") && !url.contains(".m3u8")) {
            startPlayUrl(url, headers);
            return;
        }
        HttpClient.cancel("m3u8-1");
        HttpClient.cancel("m3u8-2");
        // remove ads in m3u8
        Map<String, String> hheaders = new HashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> s : headers.entrySet()) {
                hheaders.put(s.getKey(), s.getValue());
            }
        }

        HttpClient.get(url, hheaders, "m3u8-1", new HCallBack() {
            @Override
            public void onSuccess(String content) {
                // 先剥 BOM:带 BOM 的清单原来会被下面的 startsWith 判否 → 静默放弃广告过滤
                content = com.github.tvbox.osc.util.M3u8Purifier.stripBom(content);
                if (!content.startsWith("#EXTM3U")) {
                    startPlayUrl(url, headers);
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
                    int ilast = url.lastIndexOf('/');

                    RemoteServer.m3u8Content = com.github.tvbox.osc.util.M3u8Purifier
                            .removeMinorityUrl(url.substring(0, ilast + 1), content);
                    if (RemoteServer.m3u8Content == null)
                        startPlayUrl(url, headers);
                    else {
                        // 广告过滤静默执行,不弹任何提示
                        startPlayUrl(purifyUrl(), headers);
                    }
                    return;
                }
                final String finalforwardurl = forwardurl;
                HttpClient.get(forwardurl, hheaders, "m3u8-2", new HCallBack() {
                    @Override
                    public void onSuccess(String content) {
                        content = com.github.tvbox.osc.util.M3u8Purifier.stripBom(content);
                        int ilast = finalforwardurl.lastIndexOf('/');
                        RemoteServer.m3u8Content = com.github.tvbox.osc.util.M3u8Purifier
                                .removeMinorityUrl(finalforwardurl.substring(0, ilast + 1), content);

                        if (RemoteServer.m3u8Content == null)
                            startPlayUrl(finalforwardurl, headers);
                        else {
                            // 广告过滤静默执行,不弹任何提示
                            startPlayUrl(purifyUrl(), headers);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        startPlayUrl(url, headers);
                    }
                });
            }

            @Override
            public void onError(Throwable e) {
                startPlayUrl(url, headers);
            }
        });
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
                SPUtils sp = SPUtils.getInstance(CacheConst.VIDEO_PLAYED_SP);
                Set<String> set = sp.getStringSet(videoId, null);
                if (set == null)
                    set = new HashSet<>();
                if (set.add(String.valueOf(index))) {
                    sp.put(videoId, set);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    void startPlayUrl(String url, HashMap<String, String> headers) {
        if (autoRetryCount > 0 && url.contains(".m3u8")) {
            url = "http://home.jundie.top:666/unBom.php?m3u8=" + url;// 尝试去bom头再次播放
        }
        String finalUrl = url;
        if (mActivity == null || !isAdded())
            return;
        requireActivity().runOnUiThread(() -> {
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
                                playSubtitle, headers, progress);
                        setTip("调用外部播放器" + PlayerHelper.getPlayerName(playerType) + (callResult ? "成功" : "失败"),
                                callResult, !callResult);
                        return;
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                hideTip();
                PlayerHelper.updateCfg(mVideoView, mVodPlayerCfg);
                boolean castOnly = false;
                if (mSyncHost != null && mVodInfo != null && mVodInfo.seriesMap != null
                        && mVodInfo.seriesMap.get(mVodInfo.playFlag) != null
                        && mVodInfo.playIndex < mVodInfo.seriesMap.get(mVodInfo.playFlag).size()) {
                    VodInfo.VodSeries episode = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
                    castOnly = mSyncHost.onPlaybackResolved(mVodInfo.name + " " + episode.name, finalUrl);
                }
                if (castOnly) return;
                // 起播统一经 PlayerSession(设进度键+URL+start;内核隔离入口)
                if (mPlaySession != null) {
                    mPlaySession.play(finalUrl, progressKey, headers);
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
    }

    private final Observer<JSONObject> mObserverPlayResult = new Observer<JSONObject>() {
        @Override
        public void onChanged(JSONObject info) {
            if (info != null) {
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
                        playUrl(playUrl + url, headers);
                    }
                } catch (Throwable th) {
                    LogUtils.e(th.toString());
                    errorWithRetry("获取播放信息错误", true);
                }
            } else {
                errorWithRetry("获取播放信息错误", true);
                // AppBubble.toast("获取播放信息错误");
            }
        }
    };

    public void setData(Bundle bundle) {
        // mVodInfo = (VodInfo) bundle.getSerializable("VodInfo");
        mVodInfo = App.getInstance().getVodInfo();
        sourceKey = bundle.getString("sourceKey");
        sourceBean = SourceConfigProviders.get().getSource(sourceKey);
        if (mParseEngine != null)
            mParseEngine.setSourceBean(sourceBean);
        initPlayerCfg();
        play(false);
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
                mVodPlayerCfg.put("pl", (sourceBean.getPlayerType() == -1) ? (int) PlayConfig.getPlayType()
                        : sourceBean.getPlayerType());
            }
            if (!mVodPlayerCfg.has("pr")) {
                mVodPlayerCfg.put("pr", PlayConfig.getRenderType());
            }
            if (!mVodPlayerCfg.has("ijk")) {
                mVodPlayerCfg.put("ijk", PlayConfig.getIjkCodec());
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
        if (mPlaySession != null)
            mPlaySession.pause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mPlaySession != null)
            mPlaySession.resume();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) {
            if (mPlaySession != null)
                mPlaySession.pause();
        } else {
            if (mPlaySession != null)
                mPlaySession.resume();
        }
        super.onHiddenChanged(hidden);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 手动注销
        sourceViewModel.playResult.removeObserver(mObserverPlayResult);

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

    boolean autoRetry() {
        if (mParseEngine != null && mParseEngine.hasFoundVideo()) {
            autoRetryFromLoadFoundVideoUrls();
            return true;
        }
        if (autoRetryCount < 1) {
            autoRetryCount++;
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
        if (mVodInfo == null)
            return;
        VodInfo.VodSeries vs = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
        if (mSyncHost != null)
            mSyncHost.onEpisodeSelected(mVodInfo.playIndex);
        com.github.tvbox.osc.service.PlayService.onPlaybackNotify(mVodInfo.name + "&&" + vs.name);
        String playTitleInfo = mVodInfo.name + " " + vs.name;
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
        subtitleCacheKey = playRequest.subtitleCacheKey();
        // 重新播放清除现有进度
        if (reset) {
            mPlayHistory.delete(progressKey);
            mPlayHistory.delete(subtitleCacheKey);
        }
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
                if (code < 0) {
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
                playUrl(url, null);
            }
        })) {
            mController.showParse(false);
            return;
        }
        sourceViewModel.getPlay(playRequest.sourceKey(), mVodInfo.playFlag,
                playRequest.progressKey(), playRequest.url(), playRequest.subtitleCacheKey());
    }

    private String playSubtitle;
    private String subtitleCacheKey;
    private String progressKey;

    /** 记录当前播放url(供 DetailActivity/下载回退取址) */
    public String getFinalUrl() {
        return TextUtils.isEmpty(mCurrentUrl) || !RegexUtils.isURL(mCurrentUrl) ? "" : mCurrentUrl;
    }

    /** 当前播放所用请求头:经解析引擎(嗅探 WebView 收集 UA/Referer 等)取,下载回退播放地址必须携带 */
    public Map<String, String> getPlayHeaders() {
        return mParseEngine != null ? mParseEngine.getPlayHeaders() : null;
    }

    public MyVideoView getPlayer() {
        return mVideoView;
    }

    public VodController getController() {
        return mController;
    }

}

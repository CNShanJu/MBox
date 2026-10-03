package com.github.tvbox.osc.ui.activity;

import android.content.Context;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Handler;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.CastVideo;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.player.controller.LiveNewController;
import com.github.tvbox.osc.util.LiveChannelAuth;
import com.github.tvbox.osc.util.LiveChannelNav;
import com.github.tvbox.osc.util.LivePlayerManager;
import com.github.tvbox.osc.ui.adapter.LiveChannelGroupNewAdapter;
import com.github.tvbox.osc.ui.adapter.LiveChannelItemNewAdapter;
import com.github.tvbox.osc.ui.dialog.AllChannelsRightDialog;
import com.github.tvbox.osc.ui.dialog.CastListDialog;
import com.github.tvbox.osc.ui.dialog.DialogCoordinator;
import com.github.tvbox.osc.ui.dialog.LiveLineSelectDialog;
import com.github.tvbox.osc.ui.dialog.LiveLineSelectHost;
import com.github.tvbox.osc.ui.dialog.LiveLineSelectRightDialog;
import com.github.tvbox.osc.ui.dialog.LivePasswordDialog;
import com.github.tvbox.osc.ui.dialog.LiveSettingDialog;
import com.github.tvbox.osc.ui.dialog.LiveSettingHost;
import com.github.tvbox.osc.ui.dialog.LiveSettingRightDialog;
import com.github.tvbox.osc.ui.kit.LinearSpacingItemDecoration;
import com.github.tvbox.osc.ui.widget.LiveNormalControlView;
import com.github.tvbox.osc.ui.widget.LiveSideControlView;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.BackgroundPlaySettings;
import com.github.tvbox.osc.util.HCallBack;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.LiveConfig;
import com.github.tvbox.osc.util.live.TxtSubscribe;
import com.google.gson.JsonArray;
import com.gyf.immersionbar.BarHide;
import com.gyf.immersionbar.ImmersionBar;
import com.lxj.xpopup.core.BasePopupView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;


import xyz.doikki.videocontroller.component.TitleView;
import xyz.doikki.videoplayer.player.VideoView;

/**
 * @author pj567
 * @date :2021/1/12
 * @description:
 */
public class LiveActivity extends BaseActivity implements LiveLineSelectHost, LiveSettingHost {
    public static Context context;
    private VideoView mVideoView;
    private TextView tvChannelInfo;
    private LinearLayout tvLeftChannelListLayout;
    private RecyclerView mChannelGroupView;
    private RecyclerView mLiveChannelView;
    public LiveChannelGroupNewAdapter liveChannelGroupAdapter;
    public LiveChannelItemNewAdapter liveChannelItemAdapter;

    public static  int currentChannelGroupIndex = 0;
    private Handler mHandler = new Handler();

    private List<LiveChannelGroup> liveChannelGroupList = new ArrayList<>();
    private int currentLiveChannelIndex = -1;
    private int currentLiveChangeSourceTimes = 0;
    private LiveChannelItem currentLiveChannelItem = null;
    private LivePlayerManager livePlayerManager = new LivePlayerManager();
    private ArrayList<Integer> channelGroupPasswordConfirmed = new ArrayList<>();

//EPG   by 龍
    private static LiveChannelItem  channel_Name = null;
    TextView tv_channelnum;
    TextView tip_chname;

    TextView tv_srcinfo;
    public String epgStringAddress ="";

    private boolean isBack = false;
    private LiveSideControlView mSideControlView;
    private LiveNormalControlView mNormalControlView;
    private BasePopupView mSettingRightDialog;
    private BasePopupView mSettingBottomDialog;
    private BasePopupView mAllChannelRightDialog;
    private LivePasswordDialog mPasswordDialog;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_live;
    }

    /**
     * 按系统栏 insets 给页面根布局让位(替代 fitsSystemWindows 的整帧内缩,原因见 {@link #init()} 里的注释)。
     * <p>
     * 与详情页同一套做法:用 insets 监听而不是一次性算状态栏高度 —— 全屏/横屏隐藏系统栏时 insets 归 0,
     * padding 自动归零;刘海屏的 displayCutout 也一并算进去。页面背景层挂在 content 里、不是本布局的子节点,
     * 不受这里的 padding 影响,所以仍铺满整屏(状态栏那条露出的就是它)。
     */
    private void applySystemBarsPadding() {
        View root = findViewById(R.id.live_root);
        if (root == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            if (v.getPaddingLeft() != bars.left || v.getPaddingTop() != bars.top
                    || v.getPaddingRight() != bars.right || v.getPaddingBottom() != bars.bottom) {
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    @Override
    protected void init() {
        // 状态栏不设底色(默认透明),与其它页/详情页一致:原来这里写死 statusBarColor(R.color.black)
        // + statusBarDarkFont(false),亮色主题下就是一条既不跟主题、也不跟页面背景的黑带
        // (详情页当年同一问题已改掉)。图标色改为跟随明暗主题。
        // 导航栏保持黑底:它是深色视频页,且平时被 FLAG_HIDE_NAVIGATION_BAR 隐藏,只在划出时露一下。
        //
        // fitsSystemWindows 走 false(内容顶到状态栏下面)+ 自己按 insets 让位(applySystemBarsPadding):
        // 用 true 时系统会给 android.R.id.content 整帧内缩,而页面背景层(PageBackgroundView)是它的
        // 子节点,会跟着一起缩 —— 状态栏那条没有任何人绘制,露出的是 DecorView 的 windowBackground
        // (@color/bg_body 纯色),看着仍是一条色带;改成自己让位后,那条就是页面背景(背景图),
        // 与详情页改完后的观感一致,而内容位置与内缩时完全相同。
        ImmersionBar.with(this)
                .statusBarDarkFont(!com.github.tvbox.osc.util.Utils.isDarkTheme())
                .navigationBarColor(R.color.black)
                .fitsSystemWindows(false)
                .hideBar(BarHide.FLAG_HIDE_NAVIGATION_BAR)
                .init();
        applySystemBarsPadding();
        context = this;
        epgStringAddress = LiveConfig.epgUrl();
        if(epgStringAddress == null || epgStringAddress.length()<5)
            epgStringAddress = "http://epg.51zmt.top:8000/api/diyp/";

        setLoadSir(findViewById(R.id.live_root));
        mVideoView = findViewById(R.id.mVideoView);

        tvLeftChannelListLayout = findViewById(R.id.tvLeftChannnelListLayout);

        mChannelGroupView = findViewById(R.id.mGroupGridView);
        mLiveChannelView = findViewById(R.id.mChannelGridView);
        mChannelGroupView.addItemDecoration(new LinearSpacingItemDecoration(20,true));
        mLiveChannelView.addItemDecoration(new LinearSpacingItemDecoration(20,true));

        tvChannelInfo = findViewById(R.id.tvChannel);

        //EPG  findViewById  by 龍
        tip_chname = findViewById(R.id.tv_channel_bar_name);//底部名称
        tip_chname.setOnClickListener(view -> {
            mChannelGroupView.scrollToPosition(currentChannelGroupIndex);
            mLiveChannelView.scrollToPosition(currentLiveChannelIndex);
        });
        tv_channelnum = findViewById(R.id.tv_channel_bottom_number); //底部数字

        tv_srcinfo = findViewById(R.id.tv_source);//线路状态

        //源切换
        findViewById(R.id.ic_pre_source).setOnClickListener(view -> playPreSource());
        findViewById(R.id.ic_next_source).setOnClickListener(view -> playNextSource());
        tv_srcinfo.setOnClickListener(view -> showLineSelectDialog(false));
        //投屏/设置
        findViewById(R.id.ic_setting).setOnClickListener(view -> showSettingDialog(false));
        findViewById(R.id.ic_cast).setOnClickListener(view -> showCastDialog());
        View refreshBtn = findViewById(R.id.ic_refresh);
        if (refreshBtn != null) {
            refreshBtn.setOnClickListener(view -> {
                if (!isCurrentLiveChannelValid()) return;
                playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
            });
        }

        initVideoView();
        initChannelGroupView();
        initLiveChannelView();
        initLiveChannelList();
    }

    //显示底部EPG
    private void showBottomEpg() {
        if (channel_Name.getChannelName() != null) {
            mSideControlView.setTitle(channel_Name.getChannelName());
            if (mNormalControlView != null) mNormalControlView.setTitle(channel_Name.getChannelName());
            tip_chname.setText(channel_Name.getChannelName());
            tv_channelnum.setText("" + channel_Name.getChannelNum());
            //todo 上一/下一/当前节目信息
        }
        updateLineText();
    }

    /** 同步线路文字(页面 tv_srcinfo 与全屏线路标签),只显示当前线路序号 */
    private void updateLineText() {
        String info = "线路1";
        if (channel_Name != null && channel_Name.getSourceNum() > 0) {
            info = "线路" + (channel_Name.getSourceIndex() + 1);
        }
        tv_srcinfo.setText(info);
        mSideControlView.setLineInfo(info);
    }


    @Override
    public void onBackPressed() {
        if (mPasswordDialog != null && mPasswordDialog.isShow()) {
            mPasswordDialog.cancelFromBack();
        } else if (DialogCoordinator.dismissSimpleHostPopupOnBack(this)) {
            return;
        } else if(isBack){
            isBack= false;
            playPreSource();
        } else if(mSettingBottomDialog!=null && mSettingBottomDialog.isShow()){//适配底部导航栏(手势条闪屏)变成view模式后在back时手动隐藏
            mSettingBottomDialog.dismiss();
        } else if(mSettingRightDialog!=null && mSettingRightDialog.isShow()){
            mSettingRightDialog.dismiss();
        }  else if(mAllChannelRightDialog!=null && mAllChannelRightDialog.isShow()){
            mAllChannelRightDialog.dismiss();
        } else if (!mVideoView.onBackPressed()) {
            super.onBackPressed();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                // 菜单键预留:直播设置已走底部/抽屉弹窗(showSettingDialog)
            } else if (!isListOrSettingLayoutVisible()) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (LiveConfig.channelReverse())
                            playNext();
                        else
                            playPrevious();
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (LiveConfig.channelReverse())
                            playPrevious();
                        else
                            playNext();
                        break;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if(isBack){

                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if(isBack){

                        }else{
                            playNextSource();
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        showChannelList();
                        break;
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mVideoView != null) {
            mVideoView.resume();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 横竖屏变化可能重置系统栏标志；等新布局完成后恢复直播全屏状态。
        View decor = getWindow().getDecorView();
        decor.post(this::restoreLiveFullscreenBars);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) restoreLiveFullscreenBars();
    }

    private void restoreLiveFullscreenBars() {
        if (mVideoView == null || !mVideoView.isFullScreen()) return;
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        decor.setSystemUiVisibility(flags);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    /**
     * 「跟随系统」翻明暗时的重建:正在放直播就先不重建 —— 翻明暗不该把正在看的直播重建掉;
     * 那次重建会记下来,等本页空闲回到前台时由 {@code BaseActivity.onResume} 补做。
     */
    @Override
    protected boolean allowRecreateOnNightChange() {
        try {
            return mVideoView == null || !mVideoView.isPlaying();
        } catch (Throwable th) {
            return true;
        }
    }


    @Override
    protected void onPause() {
        super.onPause();
        if (mVideoView != null) {
            mVideoView.pause();
        }
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
        if (mVideoView != null) {
            mVideoView.release();
            mVideoView = null;
        }
    }

    private void showChannelList() {
        //重新载入上一次状态
        liveChannelItemAdapter.setNewData(getLiveChannels(currentChannelGroupIndex));
        if (currentLiveChannelIndex > -1){
            mLiveChannelView.smoothScrollToPosition(currentLiveChannelIndex);
            if (currentChannelGroupIndex==0){
                mChannelGroupView.scrollToPosition(currentChannelGroupIndex);
            }else {
                mChannelGroupView.smoothScrollToPosition(currentChannelGroupIndex);
            }
        }
    }

    private void showChannelInfo() {
        tvChannelInfo.setText(String.format(Locale.getDefault(), "%d %s %s(%d/%d)", currentLiveChannelItem.getChannelNum(),
                currentLiveChannelItem.getChannelName(), currentLiveChannelItem.getSourceName(),
                currentLiveChannelItem.getSourceIndex() + 1, currentLiveChannelItem.getSourceNum()));

        FrameLayout.LayoutParams lParams = new FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lParams.gravity = Gravity.RIGHT;
        lParams.rightMargin = 60;
        lParams.topMargin = 30;
        tvChannelInfo.setLayoutParams(lParams);

        tvChannelInfo.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mHideChannelInfoRun);
        mHandler.postDelayed(mHideChannelInfoRun, 3000);
    }

    private Runnable mHideChannelInfoRun = new Runnable() {
        @Override
        public void run() {
            tvChannelInfo.setVisibility(View.INVISIBLE);
        }
    };

    private boolean playChannel(int channelGroupIndex, int liveChannelIndex, boolean changeSource) {
        if ((channelGroupIndex == currentChannelGroupIndex && liveChannelIndex == currentLiveChannelIndex && !changeSource)
                || (changeSource && currentLiveChannelItem.getSourceNum() == 1)) {
           // showChannelInfo();
            return true;
        }
        mVideoView.release();
        if (!changeSource) {
            currentChannelGroupIndex = channelGroupIndex;
            currentLiveChannelIndex = liveChannelIndex;
            currentLiveChannelItem = getLiveChannels(currentChannelGroupIndex).get(currentLiveChannelIndex);
            LiveConfig.setLastChannel(currentLiveChannelItem.getChannelName());
            livePlayerManager.getLiveChannelPlayer(mVideoView, currentLiveChannelItem.getChannelName());
        }

        channel_Name = currentLiveChannelItem;
        isBack = false;
        if(currentLiveChannelItem.getUrl().indexOf("PLTV/8888") !=-1){
            currentLiveChannelItem.setinclude_back(true);
        }else {
            currentLiveChannelItem.setinclude_back(false);
        }
        showBottomEpg();

        mVideoView.setUrl(currentLiveChannelItem.getUrl());
       // showChannelInfo();
        mVideoView.start();
        return true;
    }

    private void playNext() {
        if (!isCurrentLiveChannelValid()) return;
        Integer[] groupChannelIndex = getNextChannel(1);
        playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
    }

    private void playPrevious() {
        if (!isCurrentLiveChannelValid()) return;
        Integer[] groupChannelIndex = getNextChannel(-1);
        playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
    }

    public void playPreSource() {
        if (!isCurrentLiveChannelValid()) return;
        currentLiveChannelItem.preSource();
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
        updateLineText();
    }

    public void playNextSource() {
        if (!isCurrentLiveChannelValid()) return;
        currentLiveChannelItem.nextSource();
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
        updateLineText();
    }

//    private void initVideoView() {
//        StandardVideoController controller = new StandardVideoController(this);
//        controller.addControlComponent(new LiveControlView(this)); //直播控制条
//        controller.setEnableInNormal(true);
//        controller.setGestureEnabled(true);
//        mVideoView.setVideoController(controller);
//        mVideoView.setProgressManager(null);
//    }

    private void initVideoView() {
        LiveNewController controller = new LiveNewController(this);
        // 单条右缘垂直居中控制栏(返回/台名/时间/播放暂停/刷新/投屏/换台/设置)
        mSideControlView = new LiveSideControlView(this);
        mSideControlView.setOnLiveSideListener(new LiveSideControlView.OnLiveSideListener() {
            @Override
            public void onExpand() {
                showAllChannelDialog();
            }

            @Override
            public void onSetting() {
                showSettingDialog(true);
            }

            @Override
            public void onCast() {
                showCastDialog();
            }

            @Override
            public void onBack() {
                if (mVideoView != null && mVideoView.isFullScreen()) {
                    mVideoView.stopFullScreen();
                } else {
                    finish();
                }
            }

            @Override
            public void onLineClick() {
                showLineSelectDialog(true);
            }

            @Override
            public void onRefresh() {
                // 重播当前线路
                playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
            }
        });
        controller.addControlComponent(mSideControlView);
        // 非全屏小窗横向控制条(频道名/时间/暂停/刷新/全屏)
        LiveNormalControlView normalControlView = new LiveNormalControlView(this);
        normalControlView.setOnNormalListener(new LiveNormalControlView.OnNormalListener() {
            @Override
            public void onRefresh() {
                playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
            }
        });
        controller.addControlComponent(normalControlView);
        mNormalControlView = normalControlView;
        controller.setListener(new LiveNewController.LiveControlListener() {

            @Override
            public void setting() {
                // 直播设置已走 showSettingDialog(底部/抽屉弹窗),这里不再内嵌面板
            }

            @Override
            public void playStateChanged(int playState) {
                switch (playState) {
                    case VideoView.STATE_IDLE:
                    case VideoView.STATE_PAUSED:
                        break;
                    case VideoView.STATE_PREPARED:
                    case VideoView.STATE_BUFFERED:
                    case VideoView.STATE_PLAYING:
                        currentLiveChangeSourceTimes = 0;
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        break;
                    case VideoView.STATE_ERROR:
                        if (currentLiveChannelItem != null) {
                            recordLivePlaybackError("直播播放错误: 频道=" + currentLiveChannelItem.getChannelName()
                                    + "，线路=" + (currentLiveChannelItem.getSourceIndex() + 1)
                                    + "/" + currentLiveChannelItem.getSourceNum() + "，准备切换线路");
                        }
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        mHandler.postDelayed(mConnectTimeoutChangeSourceRun, 2000);
                        break;
                    case VideoView.STATE_PLAYBACK_COMPLETED:
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        mHandler.postDelayed(mConnectTimeoutChangeSourceRun, 2000);
                        break;
                    case VideoView.STATE_PREPARING:
                    case VideoView.STATE_BUFFERING:
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        mHandler.postDelayed(mConnectTimeoutChangeSourceRun, (LiveConfig.connectTimeout() + 1) * 5000);
                        break;
                }
            }

            @Override
            public void changeSource(int direction) {
                if (direction > 0){
                    playNextSource();
                } else {
                    playPreSource();
                }
            }
        });
        controller.setCanChangePosition(false);
        controller.setEnableInNormal(true);
        controller.setGestureEnabled(true);
        controller.setDoubleTapTogglePlayEnabled(false);
        mVideoView.setVideoController(controller);
        mVideoView.setProgressManager(null);
    }

    @NonNull
    private Runnable mConnectTimeoutChangeSourceRun = new Runnable() {
        @Override
        public void run() {
            if (mVideoView == null || currentLiveChannelItem == null || isFinishing() || isDestroyed()) return;
            int state = mVideoView.getCurrentPlayState();
            if (state == VideoView.STATE_PREPARING || state == VideoView.STATE_BUFFERING) {
                recordLivePlaybackError("直播" + (state == VideoView.STATE_BUFFERING ? "缓冲" : "连接")
                        + "超时: 频道=" + currentLiveChannelItem.getChannelName() + "，线路="
                        + (currentLiveChannelItem.getSourceIndex() + 1) + "/"
                        + currentLiveChannelItem.getSourceNum());
            }
            currentLiveChangeSourceTimes++;
            if (currentLiveChannelItem.getSourceNum() == currentLiveChangeSourceTimes) {
                // 当前频道所有线路都失败:停止播放,不自动切下一个频道
                currentLiveChangeSourceTimes = 0;
                try {
                    mVideoView.release();
                } catch (Throwable ignored) {
                }
                AppBubble.toast("无可用线路，请切换频道");
            } else {
                playNextSource();
            }
        }
    };

    private static void recordLivePlaybackError(String detail) {
        com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.PLAYER, detail);
        android.util.Log.e("MBoxPlayer", detail);
    }

    private void initChannelGroupView() {
        mChannelGroupView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveChannelGroupAdapter = new LiveChannelGroupNewAdapter();
        mChannelGroupView.setAdapter(liveChannelGroupAdapter);

        //手机/模拟器
        liveChannelGroupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectChannelGroup(position, false, -1);
            }
        });
    }

    private void selectChannelGroup(int groupIndex, boolean focus, int liveChannelIndex) {
        if (focus) {
            liveChannelGroupAdapter.setFocusedGroupIndex(groupIndex);
            liveChannelItemAdapter.setFocusedChannelIndex(-1);
        }
        if ((groupIndex > -1 && groupIndex != liveChannelGroupAdapter.getSelectedGroupIndex()) || isNeedInputPassword(groupIndex)) {
            liveChannelGroupAdapter.setSelectedGroupIndex(groupIndex);
            if (isNeedInputPassword(groupIndex)) {
                showPasswordDialog(groupIndex, liveChannelIndex);
                return;
            }
            loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex);
        }
    }

    private void initLiveChannelView() {
        mLiveChannelView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveChannelItemAdapter = new LiveChannelItemNewAdapter();
        mLiveChannelView.setAdapter(liveChannelItemAdapter);

        liveChannelItemAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                clickLiveChannel(position);
            }
        });
    }

    private void clickLiveChannel(int position) {
        liveChannelItemAdapter.setSelectedChannelIndex(position);
        playChannel(liveChannelGroupAdapter.getSelectedGroupIndex(), position, false);
    }


    private void initLiveChannelList() {
        List<LiveChannelGroup> list = com.github.tvbox.osc.spiderapi.LiveChannelConfigProviders.get().getChannelGroupList();
        if (list.isEmpty()) {
            AppBubble.toast("频道列表为空");
            finish();
            return;
        }

        if (isProxyOnly(list)) {
            // 主列表是"待拉取的直播源"(设置里配置的直播源):拉它;
            // 没内容/失败再退到订阅源自带的直播(见 useFallbackOrFail)
            loadProxyLives(list.get(0).getGroupName(),
                    com.github.tvbox.osc.spiderapi.LiveChannelConfigProviders.get().getFallbackChannelGroupList());
        }
        else {
            // 没配直播源(用的是订阅源自带的直播):直接用
            liveChannelGroupList.clear();
            liveChannelGroupList.addAll(list);
            showSuccess();
            initLiveState();
        }
    }

    /** 单个"待拉取"的代理分组 = 一个直播源地址(设置里的直播源 / 订阅源的直播地址都是这个形态) */
    private static boolean isProxyOnly(List<LiveChannelGroup> list) {
        return list != null && list.size() == 1
                && list.get(0) != null && list.get(0).getGroupName() != null
                && list.get(0).getGroupName().startsWith("http://127.0.0.1");
    }

    /**
     * 拉取一个直播源地址并解析成频道。
     *
     * @param fallback 该直播源没内容/加载失败时的兜底(订阅源自带的直播);null/空 = 没有兜底,直接进空态。
     *                 <b>主直播源优先</b>:用户配的直播源能用就绝不碰订阅源的直播。
     */
    public void loadProxyLives(String url, List<LiveChannelGroup> fallback) {
        try {
            Uri parsedUrl = Uri.parse(url);
            url = new String(Base64.decode(parsedUrl.getQueryParameter("ext"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
        } catch (Throwable th) {
            useFallbackOrFail(fallback, "频道列表为空");
            return;
        }
        showLoading();
        HttpClient.get(url, null, new HCallBack() {

            @Override
            public void onSuccess(String content) {
                try {
                    JsonArray livesArray;
                    LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> linkedHashMap = new LinkedHashMap<>();
                    TxtSubscribe.parse(linkedHashMap, content);
                    livesArray = TxtSubscribe.live2JsonArray(linkedHashMap);

                    com.github.tvbox.osc.spiderapi.LiveChannelConfigProviders.get().loadLives(livesArray);
                    List<LiveChannelGroup> list = com.github.tvbox.osc.spiderapi.LiveChannelConfigProviders.get().getChannelGroupList();
                    if (list.isEmpty()) {
                        useFallbackOrFail(fallback, "频道列表为空");
                        return;
                    }
                    liveChannelGroupList.clear();
                    liveChannelGroupList.addAll(list);

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            LiveActivity.this.showSuccess();
                            initLiveState();
                        }
                    });
                } catch (Throwable th) {
                    th.printStackTrace();
                    com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.OTHER,
                            "直播: 直播源解析失败: " + th.getMessage());
                    useFallbackOrFail(fallback, "直播源解析失败,请检查订阅中的直播源");
                }
            }

            @Override
            public void onError(Throwable e) {
                com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.OTHER,
                        "直播: 直播源加载失败: " + (e == null ? "null" : e.getMessage()));
                useFallbackOrFail(fallback, "直播源加载失败,请检查网络或直播源");
            }
        });
    }

    /**
     * 主直播源(设置里配置的直播源)没内容 / 加载失败时:退到<b>订阅源自带的直播</b>(只兜底一层,不再递归);
     * 连兜底都没有才进空态。会 toast 说明"改用订阅源的直播",避免用户以为配的直播源在生效。
     */
    private void useFallbackOrFail(List<LiveChannelGroup> fallback, String msg) {
        if (fallback != null && !fallback.isEmpty()) {
            AppBubble.toast("直播源不可用,改用订阅源的直播");
            com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.OTHER,
                    "直播: 主直播源不可用, 回退订阅源直播");
            if (isProxyOnly(fallback)) {
                loadProxyLives(fallback.get(0).getGroupName(), null);
            } else {
                liveChannelGroupList.clear();
                liveChannelGroupList.addAll(fallback);
                showSuccess();
                initLiveState();
            }
            return;
        }
        onLiveLoadFail(msg);
    }

    /** 直播源加载失败:提示并进入空态,避免一直停留在加载中 */
    private void onLiveLoadFail(String msg) {
        try {
            AppBubble.toast(msg);
            if (!isFinishing() && !isDestroyed()) {
                showEmpty();
            }
        } catch (Throwable ignored) {
        }
    }

    private void initLiveState() {
        String lastChannelName = LiveConfig.lastChannel();

        int lastChannelGroupIndex = -1;
        int lastLiveChannelIndex = -1;
        for (LiveChannelGroup liveChannelGroup : liveChannelGroupList) {
            for (LiveChannelItem liveChannelItem : liveChannelGroup.getLiveChannels()) {
                if (liveChannelItem.getChannelName().equals(lastChannelName)) {
                    lastChannelGroupIndex = liveChannelGroup.getGroupIndex();
                    lastLiveChannelIndex = liveChannelItem.getChannelIndex();
                    break;
                }
            }
            if (lastChannelGroupIndex != -1) break;
        }
        if (lastChannelGroupIndex == -1) {
            lastChannelGroupIndex = getFirstNoPasswordChannelGroup();
            if (lastChannelGroupIndex == -1)
                lastChannelGroupIndex = 0;
            lastLiveChannelIndex = 0;
        }

        if (mVideoView == null || isFinishing() || isDestroyed()) return; // 防御:页面已销毁/播放器未初始化

        livePlayerManager.init(mVideoView);

        liveChannelGroupAdapter.setNewData(liveChannelGroupList);
        selectChannelGroup(lastChannelGroupIndex, false, lastLiveChannelIndex);
    }

    private boolean isListOrSettingLayoutVisible() {
        return tvLeftChannelListLayout.getVisibility() == View.VISIBLE;
    }

    private void showPasswordDialog(int groupIndex, int liveChannelIndex) {

        LivePasswordDialog dialog = new LivePasswordDialog(this);
        mPasswordDialog = dialog;
        dialog.setOnListener(new LivePasswordDialog.OnListener() {
            @Override
            public void onChange(String password) {
                if (password.equals(liveChannelGroupList.get(groupIndex).getGroupPassword())) {
                    channelGroupPasswordConfirmed.add(groupIndex);
                    loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex);
                } else {
                    AppBubble.toast("密码错误");
                }
            }

            @Override
            public void onCancel() {
                if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
                    int groupIndex = liveChannelGroupAdapter.getSelectedGroupIndex();
                    liveChannelItemAdapter.setNewData(getLiveChannels(groupIndex));
                }
            }
        });
        dialog.show();
    }

    private void loadChannelGroupDataAndPlay(int groupIndex, int liveChannelIndex) {
        liveChannelItemAdapter.setNewData(getLiveChannels(groupIndex));
        if (groupIndex == currentChannelGroupIndex) {
            if (currentLiveChannelIndex > -1)
                mLiveChannelView.smoothScrollToPosition(currentLiveChannelIndex);
            liveChannelItemAdapter.setSelectedChannelIndex(currentLiveChannelIndex);
        }
        else {
            mLiveChannelView.smoothScrollToPosition(0);
            liveChannelItemAdapter.setSelectedChannelIndex(-1);
        }

        if (liveChannelIndex > -1) {
            clickLiveChannel(liveChannelIndex);
            if (groupIndex==0){//部分手机smoothScrollToPosition向上划出屏幕
                mChannelGroupView.scrollToPosition(groupIndex);
            }else {
                mChannelGroupView.smoothScrollToPosition(groupIndex);
            }

            mLiveChannelView.smoothScrollToPosition(liveChannelIndex);
            playChannel(groupIndex, liveChannelIndex, false);
        }
    }

    private boolean isNeedInputPassword(int groupIndex) {
        return LiveChannelAuth.needInputPassword(liveChannelGroupList, channelGroupPasswordConfirmed, groupIndex);
    }

    private boolean isPasswordConfirmed(int groupIndex) {
        return LiveChannelAuth.isPasswordConfirmed(channelGroupPasswordConfirmed, groupIndex);
    }

    private ArrayList<LiveChannelItem> getLiveChannels(int groupIndex) {
        if (LiveChannelAuth.needInputPassword(liveChannelGroupList, channelGroupPasswordConfirmed, groupIndex)) {
            return new ArrayList<>();
        }
        return liveChannelGroupList.get(groupIndex).getLiveChannels();
    }

    private Integer[] getNextChannel(int direction) {
        // 跨组/加密跳过/回卷决策已抽到 LiveChannelNav(纯逻辑,防死循环兜底)
        int[] next = LiveChannelNav.next(direction,
                liveChannelGroupList.size(),
                group -> getLiveChannels(group).size(),
                group -> !liveChannelGroupList.get(group).getGroupPassword().isEmpty(),
                currentChannelGroupIndex,
                currentLiveChannelIndex,
                LiveConfig.crossGroup());
        return new Integer[]{next[0], next[1]};
    }

    private int getFirstNoPasswordChannelGroup() {
        return LiveChannelAuth.firstOpenGroup(liveChannelGroupList);
    }

    private boolean isCurrentLiveChannelValid() {
        if (currentLiveChannelItem == null) {
            AppBubble.toast("请先选择频道");
            return false;
        }
        return true;
    }

    public void showAllChannelDialog() {
        mAllChannelRightDialog = DialogCoordinator.right(this,
                new AllChannelsRightDialog(this, liveChannelGroupAdapter, liveChannelItemAdapter),
                0, true, false, null);
        mAllChannelRightDialog.show();
    }

    public void showCastDialog() {
        if (currentLiveChannelItem != null) {
            CastListDialog castDialog = new CastListDialog(this,
                    new CastVideo(currentLiveChannelItem.getChannelName(), currentLiveChannelItem.getUrl()));
            DialogCoordinator.centerInHostView(this, castDialog).show();
        }
    }

    public LivePlayerManager getLivePlayerManager(){
        return livePlayerManager;
    }

    @Override
    public int getLivePlayerScale() {
        return livePlayerManager.getLivePlayerScale();
    }

    @Override
    public int getLivePlayerType() {
        return livePlayerManager.getLivePlayerType();
    }

    @Override
    public int getBackgroundPlayType() {
        return BackgroundPlaySettings.currentMode();
    }

    @Override
    public void setBackgroundPlayType(int position) {
        BackgroundPlaySettings.select(this, position);
    }

    @Override
    public LiveChannelItem getCurrentLiveChannelItem(){
        return currentLiveChannelItem;
    }

    /**
     * 切换某个线路播放
     * @param position
     */
    @Override
    public void switchingLine2Replay(int position){
        currentLiveChannelItem.setSourceIndex(position);
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex,true);
        updateLineText();
    }

    /**
     * 切换缩放比例
     * @param position
     */
    @Override
    public void changeScale(int position){
        livePlayerManager.changeLivePlayerScale(mVideoView, position, currentLiveChannelItem.getChannelName());
    }

    /**
     * 更换播放解码
     * @param position
     */
    @Override
    public void changePlayer(int position){
        mVideoView.release();
        livePlayerManager.changeLivePlayerType(mVideoView, position, currentLiveChannelItem.getChannelName());
        mVideoView.setUrl(currentLiveChannelItem.getUrl());
        mVideoView.start();
    }

    /**
     * 设置弹窗
     * @param fullScreenStyle 全屏显示侧边弹窗
     */
    /** 偏好设置即时应用到两套控制条(时间/网速显示开关) */
    @Override
    public void refreshPreferenceUi() {
        if (mSideControlView != null) mSideControlView.refreshPreferenceUi();
        if (mNormalControlView != null) mNormalControlView.refreshPreferenceUi();
    }

    /** 线路入口:全屏用右侧抽屉(与设置抽屉同尺寸),非全屏用底部抽屉 */
    private void showLineSelectDialog(boolean fullScreenStyle) {
        if (!isCurrentLiveChannelValid()) {
            AppBubble.toast("当前频道未加载");
            return;
        }
        if (fullScreenStyle) {
            DialogCoordinator.right(this, new LiveLineSelectRightDialog(this, this), 300, true).show();
        } else {
            DialogCoordinator.bottom(this, new LiveLineSelectDialog(this, this), ScreenUtils.getScreenHeight() / 3).show();
        }
    }

    private void showSettingDialog(boolean fullScreenStyle) {
        if (!isCurrentLiveChannelValid()){
            AppBubble.toast("当前频道未加载");
            return;
        }
        if (fullScreenStyle){
            mSettingRightDialog = DialogCoordinator.right(this,
                    new LiveSettingRightDialog(this, this), 300, true);
            mSettingRightDialog.show();
        }else {
            mSettingBottomDialog = DialogCoordinator.bottom(this,
                    new LiveSettingDialog(this, this), ScreenUtils.getScreenHeight() / 2);
            mSettingBottomDialog.show();
        }

    }

}

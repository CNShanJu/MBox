package com.github.tvbox.osc.player.controller;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.player.api.PlayConfig;
import com.github.tvbox.osc.subtitle.widget.SimpleSubtitleView;
import com.github.tvbox.osc.ui.adapter.ParseAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.DialogCoordinator;
import com.github.tvbox.osc.ui.widget.MyBatteryView;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.Utils;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import java.util.Date;

import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

import static xyz.doikki.videoplayer.util.PlayerUtils.stringForTime;

public class VodController extends BaseController implements PlaybackSettingsController, SubtitleController {

    /** 是否成功播放过(详情页下载时用于复用当前集的 finalUrl;未播放过也能下载,走后台解析) */
    public volatile boolean hasPlayedOnce = false;
    /** 是否全屏(由 changedLandscape 记录,用于视频加载后校正横竖屏) */
    private boolean mFullWindows = false;
    /** 用户是否锁定竖屏: 锁定后刷新/重播不得按片源宽高自动转回横屏 */
    private boolean mPortraitLock = false;

    public VodController(@NonNull @NotNull Context context) {
        super(context);
        mHandlerCallback = new HandlerCallback() {
            @Override
            public void callback(Message msg) {
                switch (msg.what) {
                    case 1000: { // seek 刷新(浮层显示中:loading 让位,见 BaseController)
                        mProgressRoot.setVisibility(VISIBLE);
                        setSeekPanelVisible(true);
                        break;
                    }
                    case 1001: { // seek 关闭(还原 loading:仍缓冲则重新转圈)
                        mProgressRoot.setVisibility(GONE);
                        setSeekPanelVisible(false);
                        break;
                    }
                    case 1002: { // 显示底部菜单
                        toggleViewShowWithAlpha(mBottomRoot, true);
                        toggleViewShowWithAlpha(mTopRoot1, true);
                        toggleViewShowWithAlpha(mTopRoot2, true);
                        if (!isLock){// 未上锁,随底部显示
                            toggleViewShowWithAlpha(mLockView, true);
                            toggleViewShowWithAlpha(mScreenRotateView, true);
                            if (mFullWindows) {
                                toggleViewShowWithAlpha(mPlayPauseTime, true);
                            }
                        }
                        mNextBtn.requestFocus();
                        break;
                    }
                    case 1003: { // 隐藏底部菜单
                        toggleViewShowWithAlpha(mBottomRoot, false);
                        toggleViewShowWithAlpha(mTopRoot1, false);
                        toggleViewShowWithAlpha(mTopRoot2, false);
                        toggleViewShowWithAlpha(mScreenRotateView, false);
                        toggleViewShowWithAlpha(mPlayPauseTime, false);
                        if (!isLock){// 未上锁,随底部显示
                            toggleViewShowWithAlpha(mLockView, false);
                        }
                        if (listener != null) {
                            listener.onHideBottom();
                        }
                        break;
                    }
                    case 1004: { // 设置速度
                        if (isInPlaybackState()) {
                            try {
                                float speed = (float) mPlayerConfig.getDouble("sp");
                                mControlWrapper.setSpeed(speed);
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        } else
                            mHandler.sendEmptyMessageDelayed(1004, 100);
                        break;
                    }
                }
            }
        };
    }

    private android.widget.FrameLayout mLlSpeed;
    TextView mTvSpeedTip;
    SeekBar mSeekBar;
    TextView mCurrentTime;
    TextView mTotalTime;
    boolean mIsDragging;
    View mProgressRoot;
    TextView mProgressText;
    ImageView mProgressIcon;
    LinearLayout mBottomRoot;
    LinearLayout mTopRoot1;
    View mTopRoot2;
    LinearLayout mParseRoot;
    TvRecyclerView mGridView;
    TextView mPlayTitle1;
    TextView mPlayLoadNetSpeedRightTop;
    ImageView mNextBtn;
    ImageView mPreBtn;
    public TextView mPlayerScaleBtn;
    public TextView mPlayerSpeedBtn;
    public TextView mPlayerBtn;
    public TextView mPlayerIJKBtn;
    public TextView mPlayerTimeStartEndText;
    public TextView mPlayerTimeStartBtn;
    public TextView mPlayerTimeSkipBtn;
    public TextView mPlayerTimeResetBtn;
    TextView mPlayPauseTime;
    TextView mPlayLoadNetSpeed;
    TextView mVideoSize;
    public SimpleSubtitleView mSubtitleView;
    public TextView mZimuBtn;
    public TextView mAudioTrackBtn;
    private ImageView mIvPlayStatus;
    private ImageView mIvFullscreen;
    private View mChooseSeries;
    private View mChooseDownload;
    public MyBatteryView mMyBatteryView;
    private View mTopRightDeviceInfo;
    public TextView mPlayRetry;
    public TextView mPlayRefresh;
    ImageView mLockView;
    private ImageView mScreenRotateView;
    Handler myHandle;
    Runnable myRunnable;
    int dismissTimeOperationBar = 5000;//闲置多少毫秒隐藏操作栏(上中下)  默认6秒
    int dismissTimeLock = 2000;//闲置多少毫秒隐藏已上锁按钮

    int videoPlayState = 0;
    LockRunnable lockRunnable = new LockRunnable();
    private boolean isLock = false;
    private ParseAdapter mParseAdapter;

    private Runnable myRunnable2 = new Runnable() {
        @Override
        public void run() {
            Date date = new Date();
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
            mPlayPauseTime.setText(timeFormat.format(date));
            String speed = PlayerHelper.getDisplaySpeed(mControlWrapper.getTcpSpeed());
            mPlayLoadNetSpeedRightTop.setText(speed);
            mPlayLoadNetSpeed.setText(speed);

            if (mControlWrapper.getVideoSize()[0] > 0 && mControlWrapper.getVideoSize()[1] > 0) {
                String width = Integer.toString(mControlWrapper.getVideoSize()[0]);
                String height = Integer.toString(mControlWrapper.getVideoSize()[1]);
                mVideoSize.setText(width + " x " + height);
            }

            mHandler.postDelayed(this, 1000);
        }
    };
    private class LockRunnable implements Runnable {
        @Override
        public void run() {
            if (isLock){//上锁的才隐藏,非上锁状态随操作栏显示隐藏
                mLockView.setVisibility(GONE);
            }
        }
    }

    @Override
    protected void initView() {
        super.initView();
        View pip = findViewById(R.id.pip);
        // 画中画按钮:设备支持小窗就始终显示(手动进入小窗的入口),与"后台播放"设置无关。
        // "后台播放"设置控制的是按Home切后台时的行为:0关闭/1后台续播/2自动进小窗
        pip.setVisibility(Utils.supportsPiPMode() ? VISIBLE : GONE);
        mTopRightDeviceInfo = findViewById(R.id.container_top_right_device_info);
        mLlSpeed = findViewById(R.id.ll_speed);
        mTvSpeedTip = findViewById(R.id.tv_speed);
        mCurrentTime = findViewById(R.id.curr_time);
        mTotalTime = findViewById(R.id.total_time);
        mPlayTitle1 = findViewById(R.id.tv_info_name1);
        mPlayLoadNetSpeedRightTop = findViewById(R.id.tv_play_load_net_speed_right_top);
        mSeekBar = findViewById(R.id.seekBar);
        mProgressRoot = findViewById(R.id.tv_progress_container);
        mProgressIcon = findViewById(R.id.tv_progress_icon);
        mProgressText = findViewById(R.id.tv_progress_text);
        mBottomRoot = findViewById(R.id.bottom_container);
        mTopRoot1 = findViewById(R.id.tv_top_l_container);
        mTopRoot2 = findViewById(R.id.tv_top_r_container);
        mParseRoot = findViewById(R.id.parse_root);
        mGridView = findViewById(R.id.mGridView);
        mNextBtn = findViewById(R.id.play_next);
        mPreBtn = findViewById(R.id.play_pre);
        mPlayerScaleBtn = findViewById(R.id.play_scale);
        mPlayerSpeedBtn = findViewById(R.id.play_speed);
        mPlayerBtn = findViewById(R.id.play_player);
        mPlayerIJKBtn = findViewById(R.id.play_ijk);
        mPlayerTimeStartEndText = findViewById(R.id.play_time_start_end_text);
        mPlayerTimeStartBtn = findViewById(R.id.play_time_start);
        mPlayerTimeSkipBtn = findViewById(R.id.play_time_end);
        mPlayerTimeResetBtn = findViewById(R.id.play_time_reset);
        mPlayPauseTime = findViewById(R.id.tv_sys_time);
        mPlayLoadNetSpeed = findViewById(R.id.tv_play_load_net_speed);
        mVideoSize = findViewById(R.id.tv_videosize);
        mSubtitleView = findViewById(R.id.subtitle_view);
        mZimuBtn = findViewById(R.id.zimu_select);
        mAudioTrackBtn = findViewById(R.id.audio_track_select);
        mIvPlayStatus = findViewById(R.id.play_status);
        mIvFullscreen = findViewById(R.id.iv_fullscreen);
        mChooseSeries = findViewById(R.id.choose_series);
        mChooseDownload = findViewById(R.id.choose_download);
        mLockView = findViewById(R.id.iv_lock);
        mScreenRotateView = findViewById(R.id.iv_screen_rotate);
        positionSystemTime(mPlayPauseTime, mLockView, mBottomRoot);

        initSubtitleInfo();

        myHandle = new Handler();

        mLockView.setOnClickListener(v -> {
            isLock = !isLock;
            setLocked(isLock);
            if (isLock){// 上了锁
                clearDoubleTapSeekFeedback();
                mLockView.setImageResource(R.drawable.ic_lock);
                hideBottom();
                mHandler.removeCallbacks(lockRunnable);
                mHandler.postDelayed(lockRunnable,dismissTimeLock);
            }else {// 解了锁
                mLockView.setImageResource(R.drawable.ic_unlock);
                showBottom();
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            }
        });
        View rootView = findViewById(R.id.rootView);
        rootView.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isLock) {
                    if (event.getAction() == MotionEvent.ACTION_UP) {//短暂显示上锁view,lockRunnable统一隐藏上锁view
                        mLockView.setVisibility(VISIBLE);
                        mHandler.removeCallbacks(lockRunnable);
                        mHandler.postDelayed(lockRunnable, dismissTimeLock);
                    }
                }
                return isLock;
            }
        });

        myRunnable = this::hideBottom;

        mPlayPauseTime.post(new Runnable() {
            @Override
            public void run() {
                mHandler.post(myRunnable2);
            }
        });

        mGridView.setLayoutManager(new V7LinearLayoutManager(getContext(), 0, false));
        mParseAdapter = new ParseAdapter();
        mParseAdapter.setOnItemClickListener((adapter, view, position) -> {
            ParseBean parseBean = mParseAdapter.getItem(position);
            // 当前默认解析需要刷新
            int currentDefault = mParseAdapter.getData().indexOf(com.github.tvbox.osc.spiderapi.ParseConfigProviders.get().getDefaultParse());
            mParseAdapter.notifyItemChanged(currentDefault);
            com.github.tvbox.osc.spiderapi.ParseConfigProviders.get().setDefaultParse(parseBean);
            mParseAdapter.notifyItemChanged(position);
            listener.changeParse(parseBean);
            hideBottom();
        });
        mGridView.setAdapter(mParseAdapter);
        mParseAdapter.setNewData(com.github.tvbox.osc.spiderapi.ParseConfigProviders.get().getParseBeanList());

        //mParseRoot.setVisibility(VISIBLE);

        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }

                long duration = mControlWrapper.getDuration();
                long newPosition = (duration * progress) / seekBar.getMax();
                if (mCurrentTime != null)
                    mCurrentTime.setText(stringForTime((int) newPosition));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mIsDragging = true;
                beginSeeking();
                mControlWrapper.stopProgress();
                mControlWrapper.stopFadeOut();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
                long duration = mControlWrapper.getDuration();
                long newPosition = (duration * seekBar.getProgress()) / seekBar.getMax();
                mControlWrapper.seekTo((int) newPosition);
                mIsDragging = false;
                finishSeeking(true);
                mControlWrapper.startProgress();
                mControlWrapper.startFadeOut();
            }
        });

        // 返回icon退出;标题区域不再整块响应退出(与本地播放一致)
        mTopRoot1.setOnClickListener(null);
        findViewById(R.id.iv_title_back).setOnClickListener(view -> listener.exit());

        mPlayRetry = findViewById(R.id.play_retry);
        mPlayRetry.setOnClickListener(v -> {
            listener.replay(true);
            hideBottom();
        });
        mPlayRefresh = findViewById(R.id.play_refresh);
        mPlayRefresh.setOnClickListener(v -> {
            listener.replay(false);
            hideBottom();
        });
        mIvPlayStatus.setOnClickListener(view -> {
            togglePlay();
            if (videoPlayState == VideoView.STATE_PLAYING) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, 300);
            }
        });
        mNextBtn.setOnClickListener(view -> {
            listener.playNext(false);
            hideBottom();
        });
        mPreBtn.setOnClickListener(view -> {
            listener.playPre();
            hideBottom();
        });
        findViewById(R.id.setting).setOnClickListener(view -> {
            hideBottom();
            listener.showSetting();
        });
        findViewById(R.id.iv_fullscreen).setOnClickListener(view -> {
            listener.toggleFullScreen();
            hideBottom();
        });
        findViewById(R.id.cast).setOnClickListener(view -> {
            listener.cast();
            hideBottom();
        });
        pip.setOnClickListener(view -> {//画中画
            if (isInPlaybackState()){
                listener.pip();
                hideBottom();
            }
        });
        mPlayerScaleBtn.setOnClickListener(view -> {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            try {
                int scaleType = mPlayerConfig.getInt("sc");
                scaleType++;
                if (scaleType > 5)
                    scaleType = 0;
                mPlayerConfig.put("sc", scaleType);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
                mControlWrapper.setScreenScaleType(scaleType);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
        mPlayerSpeedBtn.setOnClickListener(view -> setSpeed(""));

        mPlayerSpeedBtn.setOnLongClickListener(view -> {
            try {
                mPlayerConfig.put("sp", 1.0f);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
                speed_old = 1.0f;
                mControlWrapper.setSpeed(1.0f);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return true;
        });
        mPlayerBtn.setOnClickListener(view -> {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            try {
                int playerType = mPlayerConfig.getInt("pl");
                ArrayList<Integer> exsitPlayerTypes = PlayerHelper.getExistPlayerTypes();
                int playerTypeIdx = 0;
                int playerTypeSize = exsitPlayerTypes.size();
                for (int i = 0; i < playerTypeSize; i++) {
                    if (playerType == exsitPlayerTypes.get(i)) {
                        if (i == playerTypeSize - 1) {
                            playerTypeIdx = 0;
                        } else {
                            playerTypeIdx = i + 1;
                        }
                    }
                }
                playerType = exsitPlayerTypes.get(playerTypeIdx);
                mPlayerConfig.put("pl", playerType);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
                listener.replay(false);
                hideBottom();
            } catch (JSONException e) {
                e.printStackTrace();
            }
            mPlayerBtn.requestFocus();
            mPlayerBtn.requestFocusFromTouch();
        });

        mPlayerBtn.setOnLongClickListener(view -> {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            FastClickCheckUtil.check(view);
            try {
                int playerType = mPlayerConfig.getInt("pl");
                int defaultPos = 0;
                ArrayList<Integer> players = PlayerHelper.getExistPlayerTypes();
                ArrayList<Integer> renders = new ArrayList<>();
                for (int p = 0; p < players.size(); p++) {
                    renders.add(p);
                    if (players.get(p) == playerType) {
                        defaultPos = p;
                    }
                }
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择播放器");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        try {
                            dialog.cancel();
                            int thisPlayType = players.get(pos);
                            if (thisPlayType != playerType) {
                                mPlayerConfig.put("pl", thisPlayType);
                                updatePlayerCfgView();
                                listener.updatePlayerCfg();
                                listener.replay(false);
                                hideBottom();
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        mPlayerBtn.requestFocus();
                        mPlayerBtn.requestFocusFromTouch();
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        Integer playerType = players.get(val);
                        return PlayerHelper.getPlayerName(playerType);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, renders, defaultPos);
                DialogCoordinator.centerInHostView(mActivity, dialog).show();
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return true;
        });
        mPlayerIJKBtn.setOnClickListener(view -> {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            try {
                String ijk = mPlayerConfig.getString("ijk");
                List<IJKCode> codecs = com.github.tvbox.osc.player.api.IjkCodecConfigProviders.get().getIjkCodes();
                for (int i = 0; i < codecs.size(); i++) {
                    if (ijk.equals(codecs.get(i).getName())) {
                        if (i >= codecs.size() - 1)
                            ijk = codecs.get(0).getName();
                        else {
                            ijk = codecs.get(i + 1).getName();
                        }
                        break;
                    }
                }
                mPlayerConfig.put("ijk", ijk);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
                listener.replay(false);
                hideBottom();
            } catch (JSONException e) {
                e.printStackTrace();
            }
            mPlayerIJKBtn.requestFocus();
            mPlayerIJKBtn.requestFocusFromTouch();
        });
//        增加播放页面片头片尾时间重置
        mPlayerTimeResetBtn.setOnClickListener(v -> {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            try {
                mPlayerConfig.put("et", 0);
                mPlayerConfig.put("st", 0);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
        mPlayerTimeStartBtn.setOnClickListener(view -> {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            try {
                int current = (int) mControlWrapper.getCurrentPosition();
                int duration = (int) mControlWrapper.getDuration();
                if (current > duration / 2) {
                    // 片头只能在进度前半段取:否则会把"跳过片头"设到片子后半段 —— 给个提示,别让用户以为点了没反应
                    AppBubble.toast("视频前半段才允许设置跳过片头");
                    return;
                }
                mPlayerConfig.put("st", current / 1000);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
        mPlayerTimeStartBtn.setOnLongClickListener(view -> {
            try {
                mPlayerConfig.put("st", 0);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return true;
        });
        mPlayerTimeSkipBtn.setOnClickListener(view -> {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            try {
                int current = (int) mControlWrapper.getCurrentPosition();
                int duration = (int) mControlWrapper.getDuration();
                if (current < duration / 2) {
                    // 片尾只能在进度后半段取:提示同上,避免"点了没反应"
                    AppBubble.toast("视频后半段才允许设置跳过片尾");
                    return;
                }
                mPlayerConfig.put("et", (duration - current) / 1000);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
        mPlayerTimeSkipBtn.setOnLongClickListener(view -> {
            try {
                mPlayerConfig.put("et", 0);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return true;
        });
        mZimuBtn.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            listener.selectSubtitle();
            hideBottom();
        });
        mAudioTrackBtn.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            listener.selectAudioTrack();
            hideBottom();
        });
        OnClickListener rotateScreenClick = view -> {
            FastClickCheckUtil.check(view);
            if (mFullWindows) {
                setLandscapePortrait();
            } else {
                listener.toggleFullScreen();
            }
            hideBottom();
        };
        mScreenRotateView.setOnClickListener(rotateScreenClick);
        mNextBtn.setNextFocusLeftId(R.id.play_time_start);
        mChooseSeries.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            hideBottom();
            listener.chooseSeries();
        });
        mChooseDownload.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            hideBottom();
            listener.showDownload();
        });

        findViewById(R.id.container_playing_setting).setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    // User is scrolling, remove callbacks
                    myHandle.removeCallbacks(myRunnable);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // User stopped scrolling, post callbacks
                    myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
                    break;
            }
            return false;
        });
    }

    public void setSpeed(String speedStr) {
        myHandle.removeCallbacks(myRunnable);
        myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
        try {
            float speed = (float) mPlayerConfig.getDouble("sp");
            if (TextUtils.isEmpty(speedStr)) {// 未设置.点击切换
                speed += 0.25f;
                if (speed > 3)
                    speed = 0.5f;
            } else {
                speed = Float.parseFloat(speedStr);
            }
            mPlayerConfig.put("sp", speed);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
            speed_old = speed;
            mControlWrapper.setSpeed(speed);
        } catch (Exception e) {
            AppBubble.toast("倍速参数异常");
            e.printStackTrace();
        }
    }

    private void hideLiveAboutBtn() {
        if (mControlWrapper != null && mControlWrapper.getDuration() == 0) {
            mPlayerSpeedBtn.setVisibility(GONE);
            mPlayerTimeStartEndText.setVisibility(GONE);
            mPlayerTimeStartBtn.setVisibility(GONE);
            mPlayerTimeSkipBtn.setVisibility(GONE);
            mPlayerTimeResetBtn.setVisibility(GONE);
            mNextBtn.setNextFocusLeftId(R.id.zimu_select);
        } else {
            mPlayerSpeedBtn.setVisibility(View.VISIBLE);
            mPlayerTimeStartEndText.setVisibility(View.VISIBLE);
            mPlayerTimeStartBtn.setVisibility(View.VISIBLE);
            mPlayerTimeSkipBtn.setVisibility(View.VISIBLE);
            mPlayerTimeResetBtn.setVisibility(View.VISIBLE);
            mNextBtn.setNextFocusLeftId(R.id.play_time_start);
        }
    }

    /**
     * 横竖屏切换: 记录是否"用户锁竖屏"(防止刷新/重播按片源宽高自动转回横屏)
     */
    void setLandscapePortrait() {
        if (com.blankj.utilcode.util.ScreenUtils.isPortrait()){
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            mPortraitLock = false;
        }else {
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            mPortraitLock = true;
        }
    }

    void initSubtitleInfo() {
        int subtitleTextSize = SubtitleHelper.getTextSize(mActivity);
        mSubtitleView.setTextSize(subtitleTextSize);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.player_vod_control_view;
    }

    public void showParse(boolean userJxList) {
        //mParseRoot.setVisibility(userJxList ? VISIBLE : GONE);
        if (listener!=null && mParseAdapter!=null){
            listener.showParseRoot(userJxList,mParseAdapter);
        }
    }

    private JSONObject mPlayerConfig = null;

    public void setPlayerConfig(JSONObject playerCfg) {
        this.mPlayerConfig = playerCfg;
        updatePlayerCfgView();
    }

    void updatePlayerCfgView() {
        try {
            int playerType = mPlayerConfig.getInt("pl");
            mPlayerBtn.setText(PlayerHelper.getPlayerName(playerType));
            mPlayerScaleBtn.setText(PlayerHelper.getScaleName(mPlayerConfig.getInt("sc")));
            mPlayerIJKBtn.setText(mPlayerConfig.getString("ijk"));
            mPlayerIJKBtn.setVisibility(playerType == 1 ? VISIBLE : GONE);
            mPlayerScaleBtn.setText(PlayerHelper.getScaleName(mPlayerConfig.getInt("sc")));
            mPlayerSpeedBtn.setText("x" + mPlayerConfig.getDouble("sp"));
            mPlayerTimeStartBtn.setText(PlayerUtils.stringForTime(mPlayerConfig.getInt("st") * 1000));
            mPlayerTimeSkipBtn.setText(PlayerUtils.stringForTime(mPlayerConfig.getInt("et") * 1000));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /** 直接设置画面缩放(0默认 1 16:9 2 4:3 3 填充 4 原始 5 裁剪) */
    public void setScaleType(int scaleType) {
        try {
            mPlayerConfig.put("sc", scaleType);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
            mControlWrapper.setScreenScaleType(scaleType);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /** 直接设置播放器类型 */
    public void setPlayerType(int playerType) {
        try {
            mPlayerConfig.put("pl", playerType);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
            listener.replay(false);
            hideBottom();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public int getScaleType() {
        return mPlayerConfig == null ? PlayConfig.getScaleType()
                : mPlayerConfig.optInt("sc", PlayConfig.getScaleType());
    }

    public int getPlayerType() {
        return mPlayerConfig == null ? PlayConfig.getPlayType()
                : mPlayerConfig.optInt("pl", PlayConfig.getPlayType());
    }

    @Override
    public int getRenderType() {
        return mPlayerConfig == null ? PlayConfig.getRenderType()
                : mPlayerConfig.optInt("pr", PlayConfig.getRenderType());
    }

    @Override
    public void setRenderType(int renderType) {
        if (renderType != 0 && renderType != 1) return;
        try {
            mPlayerConfig.put("pr", renderType);
            listener.updatePlayerCfg();
            listener.replay(false);
            hideBottom();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // ------------------------------------------------------------------
    // PlaybackSettingsController:设置抽屉(在线全屏与本地共用)取控制器按钮/状态
    @Override public boolean supportsLanPush() { return true; }

    @Override public void requestLanPush() { if (listener != null) listener.cast(); }

    @Override public boolean supportsVideoPurify() { return true; }

    @Override public boolean isVideoPurifyEnabled() { return PlayConfig.isVideoPurify(); }

    @Override
    public void setVideoPurifyEnabled(boolean enabled) {
        if (PlayConfig.isVideoPurify() == enabled) return;
        PlayConfig.setVideoPurify(enabled);
        if (listener != null) listener.replay(false);
    }
    // ------------------------------------------------------------------

    @Override
    public TextView settingsPlayerBtn() {
        return mPlayerBtn;
    }

    @Override
    public TextView settingsScaleBtn() {
        return mPlayerScaleBtn;
    }

    @Override
    public TextView settingsIjkBtn() {
        return mPlayerIJKBtn;
    }

    @Override
    public TextView settingsTimeStartBtn() {
        return mPlayerTimeStartBtn;
    }

    @Override
    public TextView settingsTimeSkipBtn() {
        return mPlayerTimeSkipBtn;
    }

    @Override
    public TextView settingsTimeResetBtn() {
        return mPlayerTimeResetBtn;
    }

    @Override
    public TextView settingsRetryBtn() {
        return mPlayRetry;
    }

    @Override
    public TextView settingsRefreshBtn() {
        return mPlayRefresh;
    }

    @Override
    public TextView settingsZimuBtn() {
        return mZimuBtn;
    }

    @Override
    public TextView settingsAudioBtn() {
        return mAudioTrackBtn;
    }

    public void setTitle(String playTitleInfo) {
        mPlayTitle1.setText(playTitleInfo);
    }

    public void resetSpeed() {
        skipEnd = true;
        mHandler.removeMessages(1004);
        mHandler.sendEmptyMessageDelayed(1004, 100);
    }

    /**
     * 变成全屏
     *
     * @param b
     */
    public void changedLandscape(boolean b) {
        mFullWindows = b;
        if (!b) clearDoubleTapSeekFeedback();
        mPlayTitle1.setSelected(true);
        if (!isLock && isBottomVisible()) {
            toggleViewShowWithAlpha(mScreenRotateView, true);
        } else {
            mScreenRotateView.animate().cancel();
            mScreenRotateView.setVisibility(GONE);
        }
        mScreenRotateView.setContentDescription(getContext().getString(b
                ? R.string.player_rotate_screen : R.string.player_enter_fullscreen));
        if (b && !isLock && isBottomVisible()) {
            toggleViewShowWithAlpha(mPlayPauseTime, true);
        } else {
            mPlayPauseTime.animate().cancel();
            mPlayPauseTime.setVisibility(GONE);
        }
        if (b) {
            // 全屏:放大按钮显示"缩小"图标
            mIvFullscreen.setImageResource(R.drawable.ic_zoom_out);
            mPreBtn.setVisibility(VISIBLE);
            mNextBtn.setVisibility(VISIBLE);
            mChooseSeries.setVisibility(VISIBLE);
            mChooseDownload.setVisibility(VISIBLE);
        } else {
            // 非全屏(播放详情页):放大按钮显示"聚焦"图标
            mIvFullscreen.setImageResource(R.drawable.ic_zoom_in);
            mPreBtn.setVisibility(GONE);
            mNextBtn.setVisibility(GONE);
            mChooseSeries.setVisibility(GONE);
            mChooseDownload.setVisibility(GONE);
        }
    }

    public interface VodControlListener {
        void chooseSeries();

        void playNext(boolean rmProgress);

        void playPre();

        void prepared();

        void changeParse(ParseBean pb);

        void updatePlayerCfg();

        void replay(boolean replay);

        void errReplay();

        void selectSubtitle();

        void selectAudioTrack();

        void toggleFullScreen();

        void exit();

        void cast();

        /**
         * Imm..bar沉浸式在系统弹窗/部分弹窗消失后会重新显示标题栏状态栏(未解决),暂时将隐藏底部栏的时机回调给外部页面处理
         */
        void onHideBottom();

        void showSetting();

        void pip();

        /** 全屏控制栏"下载"按钮:打开下载选择右侧抽屉(不退出全屏) */
        void showDownload();

        void showParseRoot(boolean show,ParseAdapter adapter);
    }

    public void setListener(VodControlListener listener) {
        this.listener = listener;
    }

    private VodControlListener listener;

    private boolean skipEnd = true;

    @Override
    protected void setProgress(int duration, int position) {

        if (mIsDragging) {
            return;
        }
        super.setProgress(duration, position);
        if (skipEnd && position != 0 && duration != 0) {
            int et = 0;
            try {
                et = mPlayerConfig.getInt("et");
            } catch (JSONException e) {
                e.printStackTrace();
            }
            if (et > 0 && position + (et * 1000) >= duration) {
                skipEnd = false;
                listener.playNext(true);
            }
        }
        updateProgressTimeLabels(mCurrentTime, mTotalTime, duration, position);
        if (duration > 0) {
            mSeekBar.setEnabled(true);
            int pos = (int) (position * 1.0 / duration * mSeekBar.getMax());
            mSeekBar.setProgress(pos);
        } else {
            mSeekBar.setEnabled(false);
        }
        int percent = mControlWrapper.getBufferedPercentage();
        if (percent >= 95) {
            mSeekBar.setSecondaryProgress(mSeekBar.getMax());
        } else {
            mSeekBar.setSecondaryProgress(percent * 10);
        }
    }

    private boolean simSlideStart = false;
    private int simSeekPosition = 0;
    private long simSlideOffset = 0;

    public void tvSlideStop() {
        if (!simSlideStart)
            return;
        mControlWrapper.seekTo(simSeekPosition);
        finishSeeking(true);
        if (!mControlWrapper.isPlaying())
            mControlWrapper.start();
        simSlideStart = false;
        simSeekPosition = 0;
        simSlideOffset = 0;
        dismissSeekPanel(); // 遥控 seek 结束:浮层立即消失,状态机马上接管 loading
    }

    public void tvSlideStart(int dir) {
        int duration = (int) mControlWrapper.getDuration();
        if (duration <= 0)
            return;
        if (!simSlideStart) {
            simSlideStart = true;
            beginSeeking();
        }
        // 每次10秒
        simSlideOffset += (10000.0f * dir);
        int currentPosition = (int) mControlWrapper.getCurrentPosition();
        int position = (int) (simSlideOffset + currentPosition);
        if (position > duration) position = duration;
        if (position < 0) position = 0;
        updateSeekUI(currentPosition, position, duration);
        simSeekPosition = position;
    }

    @Override
    protected void updateSeekUI(int curr, int seekTo, int duration) {
        super.updateSeekUI(curr, seekTo, duration);
        // 快进/快退用播放器那对 chevron(ic_seek_right / ic_seek_left,矢量、fillColor 为白),
        // 再叠一层白色滤镜:保证快退/快进在灰色进度浮层上一律白显
        mProgressIcon.setColorFilter(0xFFFFFFFF);
        if (seekTo > curr) {
            mProgressIcon.setImageResource(R.drawable.ic_seek_right);
        } else {
            mProgressIcon.setImageResource(R.drawable.ic_seek_left);
        }
        mProgressText.setText(PlayerUtils.stringForTime(seekTo) + " / " + PlayerUtils.stringForTime(duration));
        mHandler.sendEmptyMessage(1000);
        mHandler.removeMessages(1001);
        mHandler.sendEmptyMessageDelayed(1001, 1000);
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        if (isLock && !isLocked()) setLocked(true);
        com.github.tvbox.osc.service.PlayService.onPlaybackNotify(null);
        videoPlayState = playState;
        switch (playState) {
            case VideoView.STATE_IDLE:
                break;
            case VideoView.STATE_PLAYING:
                hasPlayedOnce = true;
                startProgress();
                mIvPlayStatus.setImageResource(R.drawable.ic_pause);
                // 全屏下视频加载出真实尺寸后校正横竖屏(进全屏时尺寸可能未知,未能切横屏)
                if (mFullWindows) {
                    int[] size = mControlWrapper.getVideoSize();
                    if (size != null && size.length >= 2 && size[0] > 0 && size[1] > 0 && !mPortraitLock) {
                        if (size[0] > size[1]) {
                            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                        } else {
                            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                        }
                    }
                }
                break;
            case VideoView.STATE_PAUSED:
                mIvPlayStatus.setImageResource(R.drawable.ic_play);
                break;
            case VideoView.STATE_ERROR:
                listener.errReplay();
                break;
            case VideoView.STATE_PREPARED:
                hideLiveAboutBtn();
                listener.prepared();
                break;
            case VideoView.STATE_BUFFERED:
                break;
            case VideoView.STATE_PREPARING:
            case VideoView.STATE_BUFFERING:
                break;
            case VideoView.STATE_PLAYBACK_COMPLETED:
                listener.playNext(true);
                break;
        }
    }

    boolean isBottomVisible() {
        return mBottomRoot.getVisibility() == VISIBLE;
    }

    void showBottom() {
        mHandler.removeMessages(1003);
        mHandler.sendEmptyMessage(1002);
    }

    public void hideBottom() {
        mHandler.removeMessages(1002);
        mHandler.sendEmptyMessage(1003);
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        myHandle.removeCallbacks(myRunnable);
        if (super.onKeyEvent(event)) {
            return true;
        }
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (isBottomVisible()) {
            mHandler.removeMessages(1002);
            mHandler.removeMessages(1003);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            return super.dispatchKeyEvent(event);
        }
        boolean isInPlayback = isInPlaybackState();
        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (isInPlayback) {
                    tvSlideStart(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (isInPlayback) {
                    togglePlay();
                    return true;
                }
//            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {  return true;// 闲置开启计时关闭透明底栏
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_MENU) {
                if (!isBottomVisible()) {
                    showBottom();
                    myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
                    return true;
                }
            }
        } else if (action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (isInPlayback) {
                    tvSlideStop();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }


    private boolean fromLongPress;
    private float speed_old = 1.0f;

    @Override
    public void onLongPress(MotionEvent e) {
        if (videoPlayState != VideoView.STATE_PAUSED) {
            fromLongPress = true;
            try {
                speed_old = (float) mPlayerConfig.getDouble("sp");
                float speed = PlayConfig.getVideoSpeed();
                mPlayerConfig.put("sp", speed);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
                mControlWrapper.setSpeed(speed);
                mLlSpeed.setVisibility(VISIBLE);
                mTvSpeedTip.setText(speed + "x");
            } catch (JSONException f) {
                f.printStackTrace();
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            if (fromLongPress) {
                fromLongPress = false;
                mLlSpeed.setVisibility(GONE);
                try {
                    float speed = speed_old;
                    mPlayerConfig.put("sp", speed);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                    mControlWrapper.setSpeed(speed);
                } catch (JSONException f) {
                    f.printStackTrace();
                }
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        myHandle.removeCallbacks(myRunnable);
        if (!isBottomVisible()) {
            showBottom();
            // 闲置计时关闭
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
        } else {
            hideBottom();
        }
        return true;
    }

    /** 全屏与详情页预览使用同一曲边双击区域，中央保持暂停/播放。 */
    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (isLock || isLocked() || !isInPlaybackState()) return true;
        // 已展开控件的触摸区域不响应跳转，画面左右侧才交给双击手势。
        if (mProgressRoot.getVisibility() == VISIBLE) return true;
        return handleDoubleTapSeek(e, mBottomRoot, mTopRoot1, mTopRoot2,
                mLockView, mScreenRotateView, mPlayPauseTime)
                || super.onDoubleTap(e);
    }

    @Override
    public boolean onBackPressed() {
        if (super.onBackPressed()) {
            return true;
        }
        if (isBottomVisible()) {
            hideBottom();
            return true;
        }
        return false;
    }

    @Override
    protected void onDetachedFromWindow() {
        clearDoubleTapSeekFeedback();
        super.onDetachedFromWindow();
        mHandler.removeCallbacks(myRunnable2);
        // 两个 handler 的剩余消息必须一起清干净:1004(设速度)在"非播放态"分支会每 100ms 自我重投,
        // 而 MessageQueue 里的消息持有 Handler → 持有 View → 持有 Activity;起播失败时还会一直 100ms 空转
        // (myHandle 在 initView 里才创建,视图被提前移除时为 null,故判空)
        if (myHandle != null) myHandle.removeCallbacksAndMessages(null);
        if (mHandler != null) mHandler.removeCallbacksAndMessages(null);
    }

    /** {@link SubtitleController}: 字幕视图(在线全屏与本地播放共用字幕设置弹窗) */
    @Override
    public SimpleSubtitleView getSubtitleView() {
        return mSubtitleView;
    }

    public void openSubtitle(boolean open) {
        // 持久化字幕开关,供字幕设置弹窗显示当前状态
        PlayConfig.setSubtitleOpen(open);
        if (open) {
            mSubtitleView.setVisibility(VISIBLE);
            AppBubble.toast("字幕已开启");
        } else {
            mSubtitleView.setVisibility(View.GONE);
            AppBubble.toast("字幕已关闭");
        }
        hideBottom();
    }

    public void increaseTime(String type) {
        try {
            int step = PlayConfig.getTimeStep();
            int time = mPlayerConfig.getInt(type);
            time += step;
            if (time > 30 * 10)
                time = 0;
            mPlayerConfig.put(type, time);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void decreaseTime(String type) {
        try {
            int step = PlayConfig.getTimeStep();
            int time = mPlayerConfig.getInt(type);
            time -= step;
            if (time < 0)
                time = (30 * 10);
            mPlayerConfig.put(type, time);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void toggleViewShowWithAlpha(View view, boolean show) {
        if (show) {
            view.setVisibility(View.VISIBLE);
            view.animate()
                    .alpha(1.0f)
                    .setDuration(100)
                    .setInterpolator(new AccelerateInterpolator())
                    .start();
        } else {
            view.animate()
                    .alpha(0.0f)
                    .setDuration(100)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> view.setVisibility(View.GONE))
                    .start();
        }
    }
}

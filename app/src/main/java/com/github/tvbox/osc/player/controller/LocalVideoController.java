package com.github.tvbox.osc.player.controller;
import com.github.tvbox.osc.util.AppBubble;

import static xyz.doikki.videoplayer.util.PlayerUtils.stringForTime;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.blankj.utilcode.util.LogUtils;
import com.blankj.utilcode.util.SPUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.constant.CacheConst;
import com.github.tvbox.osc.player.api.PlayConfig;
import com.github.tvbox.osc.subtitle.widget.SimpleSubtitleView;
import com.github.tvbox.osc.ui.adapter.ParseAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.widget.MyBatteryView;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.ScreenUtils;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.Utils;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

public class LocalVideoController extends BaseController implements PlaybackSettingsController, SubtitleController {

    private TextView mTvSpeedTip;
    private android.widget.FrameLayout mLlSpeed;

    public LocalVideoController(@NonNull @NotNull Context context) {
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
                        mBottomRoot.setVisibility(VISIBLE);
                        mTopRoot1.setVisibility(VISIBLE);
                        mTopRoot2.setVisibility(VISIBLE);
                        if (!isLock) {// 未上锁,锁按钮随操作栏显示
                            mLockView.setVisibility(VISIBLE);
                        }
                        mNextBtn.requestFocus();
                        break;
                    }
                    case 1003: { // 隐藏底部菜单
                        mBottomRoot.setVisibility(GONE);
                        mTopRoot1.setVisibility(GONE);
                        mTopRoot2.setVisibility(GONE);
                        if (!isLock) {// 未上锁,锁按钮随操作栏隐藏
                            mLockView.setVisibility(GONE);
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
    public TextView mPlayRetry;
    public TextView mPlayRefresh;
    TextView mPlayPauseTime;
    TextView mPlayLoadNetSpeed;
    TextView mVideoSize;
    public SimpleSubtitleView mSubtitleView;
    TextView mZimuBtn;
    TextView mAudioTrackBtn;
    public TextView mLandscapePortraitBtn;
    private ImageView mIvPlayStatus;
    public MyBatteryView mMyBatteryView;
    private ImageView mLockView;
    private boolean isLock = false;
    int dismissTimeLock = 2000;//闲置多少毫秒隐藏已上锁按钮
    private final Runnable lockRunnable = new Runnable() {
        @Override
        public void run() {
            if (isLock) {//上锁的才隐藏,非上锁状态随操作栏显示隐藏
                mLockView.setVisibility(GONE);
            }
        }
    };
    Handler myHandle;
    Runnable myRunnable;
    int myHandleSeconds = 4000;//闲置多少毫秒秒关闭底栏  默认6秒

    int videoPlayState = 0;

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


    @Override
    protected void initView() {
        super.initView();
        View pip = findViewById(R.id.pip);
        // 画中画按钮:设备支持小窗就显示(与在线播放一致)
        pip.setVisibility(Utils.supportsPiPMode() ? VISIBLE : GONE);
        mCurrentTime = findViewById(R.id.curr_time);
        mTvSpeedTip = findViewById(R.id.tv_speed);
        mLlSpeed = findViewById(R.id.ll_speed);
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
        mLandscapePortraitBtn = findViewById(R.id.landscape_portrait);
        mIvPlayStatus = findViewById(R.id.play_status);
        initSubtitleInfo();

        // 锁定按钮:上锁后隐藏操作栏并拦截触摸,点按屏幕短暂显示锁按钮
        mLockView = findViewById(R.id.iv_lock);
        mLockView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                isLock = !isLock;
                if (isLock) {// 上了锁
                    mLockView.setImageResource(R.drawable.ic_lock);
                    hideBottom();
                    mHandler.removeCallbacks(lockRunnable);
                    mHandler.postDelayed(lockRunnable, dismissTimeLock);
                } else {// 解了锁
                    mLockView.setImageResource(R.drawable.ic_unlock);
                    showBottom();
                    myHandle.removeCallbacks(myRunnable);
                    myHandle.postDelayed(myRunnable, myHandleSeconds);
                }
            }
        });

        //本地播放没小屏播放,直接显示上下集(后续将本地播放的xml独立)
        mPreBtn.setVisibility(VISIBLE);
        mNextBtn.setVisibility(VISIBLE);

        myHandle = new Handler();
        myRunnable = new Runnable() {
            @Override
            public void run() {
                hideBottom();
            }
        };

        mPlayPauseTime.post(new Runnable() {
            @Override
            public void run() {
                mHandler.post(myRunnable2);
            }
        });
        View chooseSeries = findViewById(R.id.choose_series);
        chooseSeries.setVisibility(VISIBLE);
        chooseSeries.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                hideBottom();
                listener.chooseSeries();
            }
        });
        // 右上角:设置(显示本地播放设置底栏) / 投屏 / 小窗
        findViewById(R.id.setting).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                listener.showSetting();
            }
        });
        findViewById(R.id.cast).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                listener.cast();
                hideBottom();
            }
        });
        pip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isInPlaybackState()) {
                    listener.pip();
                    hideBottom();
                }
            }
        });
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
                mControlWrapper.stopProgress();
                mControlWrapper.stopFadeOut();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                long duration = mControlWrapper.getDuration();
                long newPosition = (duration * seekBar.getProgress()) / seekBar.getMax();
                mControlWrapper.seekTo((int) newPosition);
                mIsDragging = false;
                mControlWrapper.startProgress();
                mControlWrapper.startFadeOut();
            }
        });

        // 返回icon退出;标题不响应退出(与在线全屏播放一致)
        mPlayTitle1.setOnClickListener(null);
        findViewById(R.id.iv_title_back).setOnClickListener(view -> listener.exit());

        mPlayRetry = findViewById(R.id.play_retry);
        mPlayRefresh = findViewById(R.id.play_refresh);
        mPlayRetry.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.replay(true);
                hideBottom();
            }
        });
        mPlayRefresh.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.replay(false);
                hideBottom();
            }
        });
        mIvPlayStatus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                togglePlay();
                if (videoPlayState == VideoView.STATE_PLAYING){
                    myHandle.removeCallbacks(myRunnable);
                    myHandle.postDelayed(myRunnable, 300);
                }
            }
        });
        mNextBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.playNext(false);
                hideBottom();
            }
        });
        mPreBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.playPre();
                hideBottom();
            }
        });
        findViewById(R.id.iv_fullscreen).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                listener.toggleFullScreen();
                hideBottom();
            }
        });
        mPlayerScaleBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
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
            }
        });
//        mPlayerSpeedBtn.setOnClickListener(new OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                myHandle.removeCallbacks(myRunnable);
//                myHandle.postDelayed(myRunnable, myHandleSeconds);
//                try {
//                    float speed = (float) mPlayerConfig.getDouble("sp");
//                    speed += 0.25f;
//                    if (speed > 3)
//                        speed = 0.5f;
//                    mPlayerConfig.put("sp", speed);
//                    updatePlayerCfgView();
//                    listener.updatePlayerCfg();
//                    speed_old = speed;
//                    mControlWrapper.setSpeed(speed);
//                } catch (JSONException e) {
//                    e.printStackTrace();
//                }
//            }
//        });

//        mPlayerSpeedBtn.setOnLongClickListener(new OnLongClickListener() {
//            @Override
//            public boolean onLongClick(View view) {
//                try {
//                    mPlayerConfig.put("sp", 1.0f);
//                    updatePlayerCfgView();
//                    listener.updatePlayerCfg();
//                    speed_old = 1.0f;
//                    mControlWrapper.setSpeed(1.0f);
//                } catch (JSONException e) {
//                    e.printStackTrace();
//                }
//                return true;
//            }
//        });
        mPlayerBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
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
            }
        });

        mPlayerBtn.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
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
                    dialog.show();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return true;
            }
        });
        mPlayerIJKBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
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
            }
        });
//        增加播放页面片头片尾时间重置
        mPlayerTimeResetBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                try {
                    mPlayerConfig.put("et", 0);
                    mPlayerConfig.put("st", 0);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        mPlayerTimeStartBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                try {
                    int current = (int) mControlWrapper.getCurrentPosition();
                    int duration = (int) mControlWrapper.getDuration();
                    if (current > duration / 2) return;
                    mPlayerConfig.put("st", current / 1000);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        mPlayerTimeStartBtn.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                try {
                    mPlayerConfig.put("st", 0);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return true;
            }
        });
        mPlayerTimeSkipBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, myHandleSeconds);
                try {
                    int current = (int) mControlWrapper.getCurrentPosition();
                    int duration = (int) mControlWrapper.getDuration();
                    if (current < duration / 2) return;
                    mPlayerConfig.put("et", (duration - current) / 1000);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        mPlayerTimeSkipBtn.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                try {
                    mPlayerConfig.put("et", 0);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return true;
            }
        });
        mZimuBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                listener.selectSubtitle();
                hideBottom();
            }
        });
        mZimuBtn.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                mSubtitleView.setVisibility(View.GONE);
                mSubtitleView.destroy();
                mSubtitleView.clearSubtitleCache();
                mSubtitleView.isInternal = false;
                hideBottom();
                AppBubble.toast("字幕已关闭");
                return true;
            }
        });
        mAudioTrackBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                listener.selectAudioTrack();
                hideBottom();
            }
        });
        mLandscapePortraitBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                setLandscapePortrait();
                hideBottom();
            }
        });
        mNextBtn.setNextFocusLeftId(R.id.play_time_start);
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

    public void initLandscapePortraitBtnInfo() {
        if (mControlWrapper != null && mActivity != null) {
            int width = mControlWrapper.getVideoSize()[0];
            int height = mControlWrapper.getVideoSize()[1];
            double screenSqrt = ScreenUtils.getSqrt(mActivity);
            if (screenSqrt < 10.0 && width < height) {
                mLandscapePortraitBtn.setVisibility(View.VISIBLE);
                mLandscapePortraitBtn.setText("竖屏");
            }
        }
    }

    void setLandscapePortrait() {
        int requestedOrientation = mActivity.getRequestedOrientation();
        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
            mLandscapePortraitBtn.setText("横屏");
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        } else if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT) {
            mLandscapePortraitBtn.setText("竖屏");
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    void initSubtitleInfo() {
        int subtitleTextSize = SubtitleHelper.getTextSize(mActivity);
        mSubtitleView.setTextSize(subtitleTextSize);
    }

    /** {@link SubtitleController}: 字幕视图(本地播放与在线全屏共用字幕设置弹窗) */
    @Override
    public SimpleSubtitleView getSubtitleView() {
        return mSubtitleView;
    }

    /** {@link SubtitleController}: 字幕开关(持久化配置并显隐字幕视图;与在线 VodController 行为一致) */
    @Override
    public void openSubtitle(boolean open) {
        PlayConfig.setSubtitleOpen(open);
        if (open) {
            mSubtitleView.setVisibility(VISIBLE);
            AppBubble.toast("字幕已开启");
        } else {
            mSubtitleView.setVisibility(GONE);
            AppBubble.toast("字幕已关闭");
        }
        hideBottom();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.player_vod_control_view;
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
            mAudioTrackBtn.setVisibility((playerType == 1) ? VISIBLE : GONE);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void setTitle(String playTitleInfo) {
        mPlayTitle1.setText(playTitleInfo);
    }

    // ------------------------------------------------------------------
    // PlaybackSettingsController:设置抽屉(在线全屏与本地共用)取控制器按钮/状态
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

    @Override
    public TextView settingsLandscapeBtn() {
        return mLandscapePortraitBtn;
    }

    /** 设置倍速;speed 为空表示循环切换(与在线播放一致) */
    @Override
    public void setSpeed(String speedStr) {
        myHandle.removeCallbacks(myRunnable);
        myHandle.postDelayed(myRunnable, myHandleSeconds);
        try {
            float speed = (float) mPlayerConfig.getDouble("sp");
            if (TextUtils.isEmpty(speedStr)) {
                speed += 0.25f;
                if (speed > 3) speed = 0.5f;
            } else {
                speed = Float.parseFloat(speedStr);
            }
            mPlayerConfig.put("sp", speed);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
            mControlWrapper.setSpeed(speed);
        } catch (Exception e) {
            AppBubble.toast("倍速参数异常");
            e.printStackTrace();
        }
    }

    @Override
    public int getScaleType() {
        try {
            return mPlayerConfig.getInt("sc");
        } catch (JSONException e) {
            return 0;
        }
    }

    @Override
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

    @Override
    public int getPlayerType() {
        try {
            return mPlayerConfig.getInt("pl");
        } catch (JSONException e) {
            return 0;
        }
    }

    @Override
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

    @Override
    public void increaseTime(String type) {
        try {
            int step = PlayConfig.getTimeStep();
            int time = mPlayerConfig.getInt(type);
            time += step;
            if (time > 30 * 10) time = 0;
            mPlayerConfig.put(type, time);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void decreaseTime(String type) {
        try {
            int step = PlayConfig.getTimeStep();
            int time = mPlayerConfig.getInt(type);
            time -= step;
            if (time < 0) time = (30 * 10);
            mPlayerConfig.put(type, time);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void resetSpeed() {
        skipEnd = true;
        mHandler.removeMessages(1004);
        mHandler.sendEmptyMessageDelayed(1004, 100);
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

        /** 右上角设置按钮:本地播放显示设置底栏 */
        void showSetting();

        /** 右上角投屏按钮 */
        void cast();

        /** 右上角画中画按钮 */
        void pip();
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
        mCurrentTime.setText(PlayerUtils.stringForTime(position));
        mTotalTime.setText(PlayerUtils.stringForTime(duration));
        if (fromLongPress){
            LogUtils.d("当前获取时间:"+PlayerUtils.stringForTime(duration));
            LogUtils.d("当前播放状态:"+videoPlayState);
        }

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
        // 快进/快退图标统一置白:ic_back 是黑色矢量,ic_pre 为浅色 png,
        // 叠加白色滤镜保证快退/快进在灰色进度浮层上一律白显
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
        videoPlayState = playState;
        switch (playState) {
            case VideoView.STATE_IDLE:
                break;
            case VideoView.STATE_PLAYING:
                initLandscapePortraitBtnInfo();
                startProgress();
                mIvPlayStatus.setImageResource(R.drawable.ic_pause);
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

    public void showBottom() {
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
            myHandle.postDelayed(myRunnable, myHandleSeconds);
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
                    myHandle.postDelayed(myRunnable, myHandleSeconds);
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

    @Override
    public void onLongPress(MotionEvent e) {
        if (videoPlayState != VideoView.STATE_PAUSED) {
            fromLongPress = true;
            float speed = PlayConfig.getVideoSpeed();
            mControlWrapper.setSpeed(speed);
            mLlSpeed.setVisibility(VISIBLE);
            mTvSpeedTip.setText(speed + "x");
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (isLock) {
            // 上锁:消费所有触摸(手势/点按全部屏蔽),点按屏幕短暂显示锁按钮
            if (event.getAction() == MotionEvent.ACTION_UP) {
                mLockView.setVisibility(VISIBLE);
                mHandler.removeCallbacks(lockRunnable);
                mHandler.postDelayed(lockRunnable, dismissTimeLock);
            }
            return true;
        }
        return super.onTouch(v, event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            if (fromLongPress) {
                fromLongPress = false;
                mLlSpeed.setVisibility(GONE);
                try {
                    mControlWrapper.setSpeed(1.0f);
                } catch (Exception f) {
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
            myHandle.postDelayed(myRunnable, myHandleSeconds);
        } else {
            hideBottom();
        }
        return true;
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
        super.onDetachedFromWindow();
        mHandler.removeCallbacks(myRunnable2);
    }
}

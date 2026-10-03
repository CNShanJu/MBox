package com.github.tvbox.osc.player.controller;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.LoadingAnim;
import java.util.Map;

import xyz.doikki.videoplayer.controller.BaseVideoController;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.controller.IGestureComponent;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

public abstract class BaseController extends BaseVideoController implements GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, View.OnTouchListener {
    private GestureDetector mGestureDetector;
    private AudioManager mAudioManager;
    private boolean mIsGestureEnabled = true;
    private int mStreamVolume;
    private float mBrightness;
    private int mSeekPosition = -1;
    private boolean mFirstTouch;
    private boolean mChangePosition;
    private boolean mChangeBrightness;
    private boolean mChangeVolume;
    private boolean mCanChangePosition = true;
    private boolean mEnableInNormal;
    private boolean mCanSlide;
    private int mCurPlayState;

    protected Handler mHandler;

    protected HandlerCallback mHandlerCallback;

    protected interface HandlerCallback {
        void callback(Message msg);
    }

    private boolean mIsDoubleTapTogglePlayEnabled = true;


    public BaseController(@NonNull Context context) {
        super(context);
        mHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(@NonNull Message msg) {
                int what = msg.what;
                switch (what) {
                    case 100: { // 亮度+音量调整
                        mSlideInfo.setVisibility(VISIBLE);
                        mSlideInfo.setText(msg.obj.toString());
                        break;
                    }

                    case 101: { // 亮度+音量调整 关闭
                        mSlideInfo.setVisibility(GONE);
                        break;
                    }
                    default: {
                        if (mHandlerCallback != null)
                            mHandlerCallback.callback(msg);
                        break;
                    }
                }
                return false;
            }
        });
    }

    public BaseController(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public BaseController(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    private TextView mSlideInfo;
    private View mLoading;
    /**
     * 加载中网速文字(tag=play_load_net_speed,仅点播/本地布局有)。
     * 注意:播放器 loading 分三类——资源解析/起播准备(PREPARING)、首次起播缓冲(未出画面的 BUFFERING)、
     * 播中缓存(BUFFERING)。网速只跟"出过画面后的播中缓存"一致:起播阶段(解析/准备/首缓冲)不显示,
     * 避免与"正在获取播放信息/起播转圈"同屏;卡顿重缓冲时才显示速度。
     */
    private View mNetSpeed;
    /** 是否已出过画面(PLAYING/PREPARED 过);新会话(IDLE)复位,用于区分"首缓冲"与"播中卡顿" */
    private boolean mEverPrepared = false;
    /** 快进/快退进度浮层是否显示中;显示期间 loading/网速让位(二者不同时出现),浮层消失后按播放状态还原 */
    private boolean mSeekPanelVisible = false;
    /** 点播/本地共用的中心播放状态；直播控制布局没有这两个视图。 */
    private View mCenterPlaybackStatus;
    private ImageView mCenterPlaybackIcon;
    private boolean mWasPaused;
    private boolean mSeeking;
    private boolean mPausedBeforeSeeking;
    private boolean mSuppressPlayFeedback;
    private int mReservedProgressTimeChars = -1;
    private static final long PLAY_FEEDBACK_DURATION_MS = 2500L;
    private final Runnable mHidePlayFeedback = () -> {
        if (mCenterPlaybackStatus != null && mCurPlayState != VideoView.STATE_PAUSED) {
            mCenterPlaybackStatus.setVisibility(GONE);
        }
    };
    private final DoubleTapSeekPolicy mDoubleTapSeek = new DoubleTapSeekPolicy();
    private DoubleTapSeekFeedbackView mDoubleTapSeekFeedback;
    private final Runnable mHideDoubleTapSeekFeedback = () -> {
        if (mDoubleTapSeekFeedback != null) mDoubleTapSeekFeedback.hide();
        mDoubleTapSeek.reset();
    };

    @Override
    protected void initView() {
        super.initView();
        mAudioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        mGestureDetector = new GestureDetector(getContext(), this);
        setOnTouchListener(this);
        mSlideInfo = findViewWithTag("vod_control_slide_info");
        mLoading = findViewWithTag("vod_control_loading");
        mNetSpeed = findViewWithTag("play_load_net_speed"); // 直播布局无此 tag → null,仅控 loading
        mCenterPlaybackStatus = findViewById(R.id.center_playback_status);
        mCenterPlaybackIcon = findViewById(R.id.center_playback_icon);
        mDoubleTapSeekFeedback = findViewById(R.id.double_tap_seek_feedback);
        if (mCenterPlaybackStatus != null) {
            mCenterPlaybackStatus.setOnClickListener(v -> {
                if (mControlWrapper != null && isInPlaybackState() && !isLocked() && !mSeeking) {
                    togglePlay();
                }
            });
        }
        // 播放器加载动画跟随设置页"加载动画"选项(默认/Glowing Fish)
        LoadingAnim.apply(mLoading);
        // 直播全屏控制器根统一固定 30dp 边距:标题栏/右侧菜单/底部控制条等 doikki 组件
        // 全部内缩相同距离、位置恒定,不读取挖孔安全区(固定值,无视摄像头)。
        if (getLayoutId() == R.layout.player_live_control_view) {
            int side = Math.round(30f * getResources().getDisplayMetrics().density);
            setPadding(side, 0, side, 0);
        }
        // 初始也走状态机:控制器刚 inflate、尚未收到播放状态回调时,把初始态视作 STATE_IDLE
        // 收敛一次——loading/网速的显隐唯一由 refreshLoadingUi 决定,不依赖布局默认值,
        // 也不存在"手动隐藏"的第二条路径。
        refreshLoadingUi(VideoView.STATE_IDLE);
    }

    /** loading 显隐(资源解析/起播准备/播中缓存都转圈) */
    private void setLoadingVisible(boolean visible) {
        if (mLoading != null) mLoading.setVisibility(visible ? VISIBLE : GONE);
    }

    /** 网速显隐:仅"出过画面后的播中缓存"显示(首缓冲/起播不显示,避免与加载提示同屏) */
    private void setNetSpeedVisible(boolean visible) {
        if (mNetSpeed != null) mNetSpeed.setVisibility(visible ? VISIBLE : GONE);
    }

    /**
     * 按播放状态刷新 loading/网速显隐。
     * 快进/快退浮层显示期间一律隐藏,保证 loading 与拖动指示不同时出现;浮层隐藏后按状态还原。
     */
    private void refreshLoadingUi(int playState) {
        boolean showLoading = false;
        boolean showNetSpeed = false;
        switch (playState) {
            case VideoView.STATE_PREPARING: // 起播准备:loading 转,但网速不显示(尚未出画面)
                showLoading = true;
                break;
            case VideoView.STATE_BUFFERING: // 缓存:出过画面(播中卡顿)才显示网速;首缓冲不显示
                showLoading = true;
                showNetSpeed = mEverPrepared;
                break;
            default:
                break;
        }
        if (mSeekPanelVisible) { // 拖动/遥控快进快退指示显示中:loading 让位,不同屏
            showLoading = false;
            showNetSpeed = false;
        }
        setLoadingVisible(showLoading);
        setNetSpeedVisible(showNetSpeed);
    }

    /**
     * seek 指示浮层显隐回调(由点播/本地控制器的 1000/1001 消息驱动)。
     * 显示时立即隐藏 loading/网速,消失时按当前播放状态还原。
     * 属播放器 UI 内部实现细节,不外扩为公开契约(见 改进.txt §六)。
     */
    protected void setSeekPanelVisible(boolean visible) {
        if (mSeekPanelVisible == visible) return;
        mSeekPanelVisible = visible;
        refreshLoadingUi(mCurPlayState);
    }

    /**
     * seek 动作结束(手势松手/遥控器松键):立即关闭进度浮层,不等 1s 超时。
     * 浮层一消失状态机立刻按当前播放状态接管 loading/网速——若 seek 后确实在缓冲,
     * loading 马上如实显示;不会出现"浮层还挂着、loading 被压住 1 秒后才冒出来"的拖沓感。
     * 属播放器 UI 内部实现细节,不外扩为公开契约(见 改进.txt §六)。
     */
    protected void dismissSeekPanel() {
        mHandler.removeMessages(1000);
        mHandler.removeMessages(1001);
        mHandler.sendEmptyMessage(1001); // 子类 1001:浮层 GONE + setSeekPanelVisible(false)
    }

    /** seek 开始时让中心状态按钮退场；本次 seek 恢复播放不展示“点击播放”的反馈。 */
    protected void beginSeeking() {
        mPausedBeforeSeeking = mCurPlayState == VideoView.STATE_PAUSED;
        mSeeking = true;
        mSuppressPlayFeedback = true;
        hideCenterPlaybackStatus();
    }

    /** 拖动结束后从暂停态恢复播放，取消拖动则还原暂停提示。 */
    protected void finishSeeking(boolean committed) {
        mSeeking = false;
        boolean resume = committed && mPausedBeforeSeeking;
        mPausedBeforeSeeking = false;
        if (resume && mControlWrapper != null) {
            mControlWrapper.start();
        } else {
            mSuppressPlayFeedback = false;
            if (mCurPlayState == VideoView.STATE_PAUSED) showCenterPlaybackStatus(false);
        }
    }

    private void hideCenterPlaybackStatus() {
        mHandler.removeCallbacks(mHidePlayFeedback);
        if (mCenterPlaybackStatus != null) mCenterPlaybackStatus.setVisibility(GONE);
    }

    private void showCenterPlaybackStatus(boolean playingFeedback) {
        if (mCenterPlaybackStatus == null || mCenterPlaybackIcon == null) return;
        mHandler.removeCallbacks(mHidePlayFeedback);
        mCenterPlaybackIcon.setImageResource(playingFeedback ? R.drawable.ic_pause : R.drawable.ic_play);
        mCenterPlaybackStatus.setContentDescription(playingFeedback ? "暂停播放" : "继续播放");
        mCenterPlaybackStatus.setVisibility(VISIBLE);
        if (playingFeedback) mHandler.postDelayed(mHidePlayFeedback, PLAY_FEEDBACK_DURATION_MS);
    }

    @Override
    protected void setProgress(int duration, int position) {
        super.setProgress(duration, position);
    }

    /** Reserve the longest displayed timestamp so changing digits cannot resize the seek bar. */
    protected void updateProgressTimeLabels(TextView currentTime, TextView totalTime, int duration, int position) {
        String currentText = PlayerUtils.stringForTime(position);
        String totalText = PlayerUtils.stringForTime(duration);
        String widthSample = duration > 0 ? totalText : "0:00:00";
        int reservedChars = Math.max(mReservedProgressTimeChars,
                Math.max(widthSample.length(), currentText.length()));
        if (mReservedProgressTimeChars != reservedChars) {
            float characterWidth = currentTime.getPaint().measureText("0");
            int width = (int) Math.ceil(characterWidth * reservedChars)
                    + currentTime.getCompoundPaddingLeft() + currentTime.getCompoundPaddingRight();
            currentTime.setMinWidth(width);
            mReservedProgressTimeChars = reservedChars;
        }
        currentTime.setText(currentText);
        totalTime.setText(totalText);
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        if (playState == VideoView.STATE_IDLE || playState == VideoView.STATE_PREPARING
                || playState == VideoView.STATE_ERROR || playState == VideoView.STATE_PLAYBACK_COMPLETED) {
            clearDoubleTapSeekFeedback();
        }
        switch (playState) {
            case VideoView.STATE_PAUSED:
                mWasPaused = true;
                if (mSeeking) hideCenterPlaybackStatus();
                else showCenterPlaybackStatus(false);
                break;
            case VideoView.STATE_PLAYING:
                boolean showPlayFeedback = mWasPaused && !mSeeking && !mSuppressPlayFeedback;
                mWasPaused = false;
                mSuppressPlayFeedback = false;
                if (showPlayFeedback) showCenterPlaybackStatus(true);
                else hideCenterPlaybackStatus();
                break;
            case VideoView.STATE_BUFFERING:
                hideCenterPlaybackStatus();
                break;
            case VideoView.STATE_BUFFERED:
                if (mWasPaused && !mSeeking) showCenterPlaybackStatus(false);
                break;
            case VideoView.STATE_IDLE:
            case VideoView.STATE_PREPARING:
            case VideoView.STATE_PREPARED:
            case VideoView.STATE_ERROR:
            case VideoView.STATE_PLAYBACK_COMPLETED:
            case VideoView.STATE_START_ABORT:
                mWasPaused = false;
                mSeeking = false;
                mPausedBeforeSeeking = false;
                mSuppressPlayFeedback = false;
                hideCenterPlaybackStatus();
                break;
            default:
                break;
        }
        switch (playState) {
            case VideoView.STATE_IDLE: // 新会话起点(切集/重播前 release)
                mEverPrepared = false;
                break;
            case VideoView.STATE_PLAYING:
            case VideoView.STATE_PREPARED: // 已出画面
                mEverPrepared = true;
                break;
            default:
                break;
        }
        refreshLoadingUi(playState);
    }

    /**
     * 设置是否可以滑动调节进度，默认可以
     */
    public void setCanChangePosition(boolean canChangePosition) {
        mCanChangePosition = canChangePosition;
    }

    /**
     * 是否在竖屏模式下开始手势控制，默认关闭
     */
    public void setEnableInNormal(boolean enableInNormal) {
        mEnableInNormal = enableInNormal;
    }

    /**
     * 是否开启手势控制，默认开启，关闭之后，手势调节进度，音量，亮度功能将关闭
     */
    public void setGestureEnabled(boolean gestureEnabled) {
        mIsGestureEnabled = gestureEnabled;
    }

    /**
     * 是否开启双击播放/暂停，默认开启
     */
    public void setDoubleTapTogglePlayEnabled(boolean enabled) {
        mIsDoubleTapTogglePlayEnabled = enabled;
    }

    @Override
    public void setPlayerState(int playerState) {
        super.setPlayerState(playerState);
        if (playerState == VideoView.PLAYER_NORMAL) {
            mCanSlide = mEnableInNormal;
        } else if (playerState == VideoView.PLAYER_FULL_SCREEN) {
            mCanSlide = true;
        }
    }

    @Override
    public void setPlayState(int playState) {
        super.setPlayState(playState);
        mCurPlayState = playState;
    }

    protected boolean isInPlaybackState() {
        return mControlWrapper != null
                && mCurPlayState != VideoView.STATE_ERROR
                && mCurPlayState != VideoView.STATE_IDLE
                && mCurPlayState != VideoView.STATE_PREPARING
                && mCurPlayState != VideoView.STATE_PREPARED
                && mCurPlayState != VideoView.STATE_START_ABORT
                && mCurPlayState != VideoView.STATE_PLAYBACK_COMPLETED;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        boolean handled = mGestureDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        // GestureDetector 消费 ACTION_UP 时 View 不再进入 onTouchEvent，seek 仍须提交。
        if (handled && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                && (mSeeking || mSeekPosition >= 0)) {
            stopSlide();
            finishGestureSeek(action);
        }
        return handled;
    }

    /**
     * 手指按下的瞬间
     */
    @Override
    public boolean onDown(MotionEvent e) {
        if (!isInPlaybackState() //不处于播放状态
                || !mIsGestureEnabled //关闭了手势
                || PlayerUtils.isEdge(this, e)) //处于控制器边沿
            return true;
        mStreamVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        Activity activity = PlayerUtils.scanForActivity(getContext());
        if (activity == null) {
            mBrightness = 0;
        } else {
            mBrightness = activity.getWindow().getAttributes().screenBrightness;
        }
        mFirstTouch = true;
        mChangePosition = false;
        mChangeBrightness = false;
        mChangeVolume = false;
        return true;
    }

    /**
     * 单击
     */
    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        if (isInPlaybackState()) {
            mControlWrapper.toggleShowState();
        }
        return true;
    }

    /**
     * 双击
     */
    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (mIsDoubleTapTogglePlayEnabled && !isLocked() && isInPlaybackState()) togglePlay();
        return true;
    }

    /** 按播放器自身尺寸命中左右曲边区域；中央返回 false，由原有双击暂停逻辑接管。 */
    protected final boolean handleDoubleTapSeek(MotionEvent event, View... controls) {
        for (View control : controls) {
            if (isOverVisibleControl(event, control)) return true;
        }
        int direction = DoubleTapSeekPolicy.direction(event.getX(), event.getY(), getWidth(), getHeight());
        if (direction == 0) {
            clearDoubleTapSeekFeedback();
            return false;
        }
        DoubleTapSeekPolicy.Result seek = mDoubleTapSeek.seek(direction,
                mControlWrapper.getCurrentPosition(), mControlWrapper.getDuration(), event.getEventTime());
        if (seek == null) return true;
        if (seek.moved) mControlWrapper.seekTo(seek.targetMs);
        showDoubleTapSeekFeedback(seek, event);
        return true;
    }

    private boolean isOverVisibleControl(MotionEvent event, View view) {
        if (view == null || !view.isShown()) return false;
        Rect bounds = new Rect();
        return view.getGlobalVisibleRect(bounds)
                && bounds.contains((int) event.getRawX(), (int) event.getRawY());
    }

    private void showDoubleTapSeekFeedback(DoubleTapSeekPolicy.Result seek, MotionEvent event) {
        if (mDoubleTapSeekFeedback == null) return;
        mDoubleTapSeekFeedback.show(seek, event.getX(), event.getY());
        mHandler.removeCallbacks(mHideDoubleTapSeekFeedback);
        mHandler.postDelayed(mHideDoubleTapSeekFeedback, 1000);
    }

    protected final void clearDoubleTapSeekFeedback() {
        mHandler.removeCallbacks(mHideDoubleTapSeekFeedback);
        mHideDoubleTapSeekFeedback.run();
    }

    /**
     * 在屏幕上滑动
     */
    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        if (!isInPlaybackState() //不处于播放状态
                || !mIsGestureEnabled //关闭了手势
                || !mCanSlide //关闭了滑动手势
                || isLocked() //锁住了屏幕
                || PlayerUtils.isEdge(this, e1)) //处于控制器边沿
            return true;
        float deltaX = e1.getX() - e2.getX();
        float deltaY = e1.getY() - e2.getY();
        if (mFirstTouch) {
            mChangePosition = Math.abs(distanceX) >= Math.abs(distanceY);
            if (!mChangePosition) {
                //半屏宽度
                float halfScreen = getWidth() / 2f;
                if (e1.getX() > halfScreen) {
                    mChangeVolume = true;
                } else {
                    mChangeBrightness = true;
                }
            }

            if (mChangePosition) {
                //根据用户设置是否可以滑动调节进度来决定最终是否可以滑动调节进度
                mChangePosition = mCanChangePosition;
                if (mChangePosition) beginSeeking();
            }

            if (mChangePosition || mChangeBrightness || mChangeVolume) {
                for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
                    IControlComponent component = next.getKey();
                    if (component instanceof IGestureComponent) {
                        ((IGestureComponent) component).onStartSlide();
                    }
                }
            }
            mFirstTouch = false;
        }
        if (mChangePosition) {
            slideToChangePosition(deltaX);
        } else if (mChangeBrightness) {
            slideToChangeBrightness(deltaY);
        } else if (mChangeVolume) {
            slideToChangeVolume(deltaY);
        }
        return true;
    }

    protected void slideToChangePosition(float deltaX) {
        deltaX = -deltaX;
        int width = getMeasuredWidth();
        int duration = (int) mControlWrapper.getDuration();
        int currentPosition = (int) mControlWrapper.getCurrentPosition();
        int position = (int) (deltaX / width * 120000 + currentPosition);
        if (position > duration) position = duration;
        if (position < 0) position = 0;
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onPositionChange(position, currentPosition, duration);
            }
        }
        updateSeekUI(currentPosition, position, duration);
        mSeekPosition = position;
    }

    protected void updateSeekUI(int curr, int seekTo, int duration) {

    }

    protected void slideToChangeBrightness(float deltaY) {
        Activity activity = PlayerUtils.scanForActivity(getContext());
        if (activity == null) return;
        Window window = activity.getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        int height = getMeasuredHeight();
        if (mBrightness == -1.0f) mBrightness = 0.5f;
        float brightness = deltaY * 2 / height * 1.0f + mBrightness;
        if (brightness < 0) {
            brightness = 0f;
        }
        if (brightness > 1.0f) brightness = 1.0f;
        int percent = (int) (brightness * 100);
        attributes.screenBrightness = brightness;
        window.setAttributes(attributes);
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onBrightnessChange(percent);
            }
        }
        Message msg = Message.obtain();
        msg.what = 100;
        msg.obj = "亮度" + percent + "%";
        mHandler.sendMessage(msg);
        mHandler.removeMessages(101);
        mHandler.sendEmptyMessageDelayed(101, 1000);
    }

    protected void slideToChangeVolume(float deltaY) {
        int streamMaxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int height = getMeasuredHeight();
        float deltaV = deltaY * 2 / height * streamMaxVolume;
        float index = mStreamVolume + deltaV;
        if (index > streamMaxVolume) index = streamMaxVolume;
        if (index < 0) index = 0;
        int percent = (int) (index / streamMaxVolume * 100);
        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (int) index, 0);
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onVolumeChange(percent);
            }
        }
        Message msg = Message.obtain();
        msg.what = 100;
        msg.obj = "音量" + percent + "%";
        mHandler.sendMessage(msg);
        mHandler.removeMessages(101);
        mHandler.sendEmptyMessageDelayed(101, 1000);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        //滑动结束时事件处理
        boolean handled = mGestureDetector.onTouchEvent(event);
        if (!handled || mSeeking || mSeekPosition >= 0) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                stopSlide();
                finishGestureSeek(action);
            }
        }
        return super.onTouchEvent(event);
    }

    private void finishGestureSeek(int action) {
        if (action == MotionEvent.ACTION_UP && mSeekPosition >= 0) {
            mControlWrapper.seekTo(mSeekPosition);
            mSeekPosition = -1;
            finishSeeking(true);
            dismissSeekPanel(); // seek 结束:浮层立即消失,状态机马上接管 loading
        } else if (action == MotionEvent.ACTION_CANCEL || mSeeking) {
            mSeekPosition = -1;
            if (mSeeking) finishSeeking(false);
            dismissSeekPanel();
        }
    }

    private void stopSlide() {
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onStopSlide();
            }
        }
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {

    }

    @Override
    public void onShowPress(MotionEvent e) {

    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && mDoubleTapSeekFeedback != null) {
            mDoubleTapSeekFeedback.updateTouch(e.getX(), e.getY());
        }
        return false;
    }


    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    public boolean onKeyEvent(KeyEvent event) {
        return false;
    }
}

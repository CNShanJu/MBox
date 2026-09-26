package com.github.tvbox.osc.ui.widget;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.LiveConfig;
import com.github.tvbox.osc.util.PlayerHelper;

import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

/**
 * 直播全屏控制:四角横排(返回+台名 左上 / 时间 右上 / 暂停+刷新 左下 / 全屏 右下)
 * + 右缘垂直居中窄菜单(投屏/换台/设置)。仅全屏显示,控件统一 30dp 固定边距、不随翻转漂移。
 * 非全屏由 {@link LiveNormalControlView} 的小窗横向控制条接管。
 */
public class LiveSideControlView extends FrameLayout implements IControlComponent {

    public interface OnLiveSideListener {
        void onExpand();
        void onSetting();
        void onCast();
        void onBack();
        void onRefresh();
        void onLineClick();
    }

    private OnLiveSideListener mOnLiveSideListener;
    private ControlWrapper mControlWrapper;

    private TextView mTitle;
    private TextView mTime;
    private TextView mLine;
    private TextView mSpeed;
    private ImageView mPlayPause;
    private ImageView mFullscreen;

    public LiveSideControlView(@NonNull Context context) {
        super(context);
    }

    public LiveSideControlView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public LiveSideControlView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    {
        setVisibility(GONE);
        LayoutInflater.from(getContext()).inflate(R.layout.dkplayer_layout_live_side, this, true);
        mTitle = findViewById(R.id.tv_title);
        mTime = findViewById(R.id.tv_time);
        mLine = findViewById(R.id.tv_line);
        mSpeed = findViewById(R.id.tv_speed);
        mPlayPause = findViewById(R.id.iv_play_pause);
        mFullscreen = findViewById(R.id.iv_fullscreen);

        findViewById(R.id.iv_back).setOnClickListener(v -> {
            // 全屏内返回 = 先切回竖屏再退全屏
            Activity activity = PlayerUtils.scanForActivity(getContext());
            if (activity != null) {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
            if (mControlWrapper != null && mControlWrapper.isFullScreen()) {
                mControlWrapper.stopFullScreen();
            } else if (mOnLiveSideListener != null) {
                mOnLiveSideListener.onBack();
            }
        });
        findViewById(R.id.iv_play_pause).setOnClickListener(v -> {
            if (mControlWrapper != null) mControlWrapper.togglePlay();
        });
        findViewById(R.id.iv_refresh).setOnClickListener(v -> {
            if (mOnLiveSideListener != null) mOnLiveSideListener.onRefresh();
        });
        findViewById(R.id.iv_cast).setOnClickListener(v -> {
            if (mOnLiveSideListener != null) mOnLiveSideListener.onCast();
        });
        findViewById(R.id.iv_expand).setOnClickListener(v -> {
            if (mOnLiveSideListener != null) mOnLiveSideListener.onExpand();
        });
        findViewById(R.id.iv_setting).setOnClickListener(v -> {
            if (mOnLiveSideListener != null) mOnLiveSideListener.onSetting();
        });
        findViewById(R.id.tv_line).setOnClickListener(v -> {
            if (mOnLiveSideListener != null) mOnLiveSideListener.onLineClick();
        });
        findViewById(R.id.iv_fullscreen).setOnClickListener(v -> {
            if (mControlWrapper == null) return;
            if (mControlWrapper.isFullScreen()) {
                Activity activity = PlayerUtils.scanForActivity(getContext());
                if (activity != null) {
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                }
                mControlWrapper.stopFullScreen();
            }
        });
    }

    public void setTitle(String title) {
        if (mTitle != null) mTitle.setText(title);
    }

    /** 顶部左侧线路信息(如 "线路 1/3") */
    public void setLineInfo(String info) {
        if (mLine != null) mLine.setText(info);
    }

    /** 偏好开关即时生效:显示时间/显示网速 */
    public void refreshPreferenceUi() {
        if (mTime != null) mTime.setVisibility(LiveConfig.showTime() ? VISIBLE : GONE);
        if (mSpeed != null) mSpeed.setVisibility(LiveConfig.showNetSpeed() ? VISIBLE : GONE);
        if (mSpeed != null && LiveConfig.showNetSpeed()) {
            refreshSpeed();
        }
    }

    private void refreshSpeed() {
        if (mSpeed == null) return;
        // 全局下载网速采样(内核无关:系统/ijk/exo 通用),由 PlayerHelper 平滑
        mSpeed.setText(PlayerHelper.getDisplaySpeed(PlayerHelper.sampleNetworkSpeed()));
    }

    public void setOnLiveSideListener(OnLiveSideListener listener) {
        mOnLiveSideListener = listener;
    }

    @Override
    public void attach(@NonNull ControlWrapper controlWrapper) {
        mControlWrapper = controlWrapper;
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, Animation anim) {
        if (mControlWrapper == null || !mControlWrapper.isFullScreen()) return;
        if (isVisible) {
            if (getVisibility() == GONE) {
                setVisibility(VISIBLE);
                if (mTime != null) mTime.setText(PlayerUtils.getCurrentSystemTime());
                if (anim != null) startAnimation(anim);
            }
        } else {
            if (getVisibility() == VISIBLE) {
                setVisibility(GONE);
                if (anim != null) startAnimation(anim);
            }
        }
    }

    @Override
    public void onPlayStateChanged(int playState) {
        if (mPlayPause != null) {
            mPlayPause.setImageResource(playState == VideoView.STATE_PLAYING
                    ? R.drawable.ic_pause : R.drawable.ic_play);
        }
        if (playState == VideoView.STATE_IDLE
                || playState == VideoView.STATE_START_ABORT
                || playState == VideoView.STATE_PREPARING
                || playState == VideoView.STATE_PREPARED
                || playState == VideoView.STATE_ERROR
                || playState == VideoView.STATE_PLAYBACK_COMPLETED) {
            setVisibility(GONE);
        }
        if (mFullscreen != null) mFullscreen.setImageResource(R.drawable.ic_zoom_out);
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
        if (playerState == VideoView.PLAYER_FULL_SCREEN) {
            refreshPreferenceUi();
            if (mControlWrapper != null && mControlWrapper.isShowing() && !mControlWrapper.isLocked()) {
                setVisibility(VISIBLE);
            }
        } else {
            setVisibility(GONE);
        }
    }

    @Override
    public void setProgress(int duration, int position) {
        refreshPreferenceUi();
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
        if (mControlWrapper == null || !mControlWrapper.isFullScreen()) return;
        setVisibility(isLocked ? GONE : VISIBLE);
    }
}

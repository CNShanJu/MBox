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
 * 直播小窗(非全屏)横向控制条:频道名左上/时间右上,暂停刷新左下,全屏右下。
 * 仅在非全屏状态显示;进入全屏后隐藏(由 {@link LiveSideControlView} 接管)。
 */
public class LiveNormalControlView extends FrameLayout implements IControlComponent {

    public interface OnNormalListener {
        void onRefresh();
    }

    private OnNormalListener mOnNormalListener;
    private ControlWrapper mControlWrapper;

    private TextView mTitle;
    private TextView mTime;
    private TextView mSpeed;
    private ImageView mPlayPause;

    public LiveNormalControlView(@NonNull Context context) {
        super(context);
    }

    public LiveNormalControlView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public LiveNormalControlView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    {
        setVisibility(GONE);
        LayoutInflater.from(getContext()).inflate(R.layout.dkplayer_layout_live_normal, this, true);
        mTitle = findViewById(R.id.tv_title);
        mTime = findViewById(R.id.tv_time);
        mSpeed = findViewById(R.id.tv_speed);
        mPlayPause = findViewById(R.id.iv_play_pause);

        findViewById(R.id.iv_play_pause).setOnClickListener(v -> {
            if (mControlWrapper != null) mControlWrapper.togglePlay();
        });
        findViewById(R.id.iv_fullscreen).setOnClickListener(v -> {
            // doikki 全屏不切方向:进入全屏前先切横屏
            Activity activity = PlayerUtils.scanForActivity(getContext());
            if (activity != null) {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
            if (mControlWrapper != null) mControlWrapper.startFullScreen();
        });
    }

    public void setTitle(String title) {
        if (mTitle != null) mTitle.setText(title);
    }

    public void setOnNormalListener(OnNormalListener listener) {
        mOnNormalListener = listener;
    }

    /** 偏好开关即时生效:显示时间/显示网速 */
    public void refreshPreferenceUi() {
        if (mTime != null) mTime.setVisibility(LiveConfig.showTime() ? VISIBLE : GONE);
        if (mSpeed != null) mSpeed.setVisibility(LiveConfig.showNetSpeed() ? VISIBLE : GONE);
        if (mSpeed != null && LiveConfig.showNetSpeed()) refreshSpeed();
    }

    private void refreshSpeed() {
        if (mSpeed == null) return;
        // 全局下载网速采样(内核无关:系统/ijk/exo 通用),由 PlayerHelper 平滑
        mSpeed.setText(PlayerHelper.getDisplaySpeed(PlayerHelper.sampleNetworkSpeed()));
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
        // 非全屏:小窗控制条仅随控制器显隐(全屏时由 SideControl 负责)
        if (mControlWrapper == null || mControlWrapper.isFullScreen()) return;
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
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
        // 非全屏才显示小窗控制条
        if (playerState == VideoView.PLAYER_NORMAL) {
            applyTimeIfEmpty();
            refreshPreferenceUi();
            setVisibility(VISIBLE);
        } else {
            setVisibility(GONE);
        }
    }

    private void applyTimeIfEmpty() {
        if (mTime != null && (mTime.getText() == null || mTime.getText().length() == 0)) {
            mTime.setText(PlayerUtils.getCurrentSystemTime());
        }
    }

    @Override
    public void setProgress(int duration, int position) {
        refreshPreferenceUi();
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
    }
}

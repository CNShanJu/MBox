package com.github.tvbox.osc.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.player.api.PlayListener;
import com.github.tvbox.osc.player.api.PlayOptions;
import com.github.tvbox.osc.player.api.PlayState;
import com.github.tvbox.osc.player.api.PlayerApi;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import xyz.doikki.videoplayer.player.VideoView;

/**
 * 基于 {@link VideoView}(doikki) 的 {@link PlayerApi} 适配器(⑥ 适配层原型)。
 * <p>
 * 把 PlayFragment 现有的 MyVideoView 播放能力以引擎无关契约暴露：状态/进度/缓冲/错误
 * 通过**轮询 VideoView.getCurrentPlayState() 差分**映射为 {@link PlayState} 转发给订阅者，
 * 不向 VideoView 挂额外 OnStateChangeListener——避免与会话共享同一视图(PlayFragment 直接持有)
 * 时对既有 Controller 监听造成干扰；控制方法直接委托 VideoView(内核经 PlayerHelper 配置)。
 * <p>
 * 纯新增会话原型：PlayFragment 仍由 mVideoView/Controller 直接驱动，本适配器仅用于
 * {@link com.github.tvbox.osc.player.api.PlaybackSessions} 会话观察与链路日志，
 * 收敛到 session.play/pause/observe 属后续真机回归项。Media3 升级 = 新增 Media3Adapter，
 * 调用方零改动。
 */
public final class VideoViewPlayerApi implements PlayerApi {

    private static final String TAG = "TVBox-VideoViewPlayer";

    private final VideoView videoView;
    private final boolean ownsVideoView;
    private final List<PlayListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile PlayState state = PlayState.IDLE;
    private int lastRawState = VideoView.STATE_IDLE;
    private boolean buffering = false;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (videoView != null) {
                    pollState();
                    boolean active = state == PlayState.PLAYING || state == PlayState.PAUSED
                            || state == PlayState.READY || state == PlayState.PREPARING;
                    if (active) {
                        long pos = videoView.getCurrentPosition();
                        long dur = videoView.getDuration();
                        int buf = videoView.getBufferedPercentage();
                        for (PlayListener l : listeners) {
                            try {
                                l.onProgress(pos, dur, buf);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, 500);
        }
    };

    /**
     * @param videoView      已配置好的播放视图(内核/渲染由 PlayerHelper 设置)
     * @param ownsVideoView  true=release 时顺带释放 VideoView;共享会话(PlayFragment 持有)传 false
     */
    public VideoViewPlayerApi(VideoView videoView, boolean ownsVideoView) {
        this.videoView = videoView;
        this.ownsVideoView = ownsVideoView;
    }

    public VideoView getVideoView() {
        return videoView;
    }

    @Override
    public void init(Context context, PlayOptions options, PlayListener listener) {
        subscribe(listener);
        setState(PlayState.INITIALIZING);
        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, 250);
        android.util.Log.d(TAG, "init " + (options == null ? "" : options.url));
    }

    /** 轮询一次 VideoView 状态并做差分上报(不修改视图状态) */
    private void pollState() {
        int raw = videoView.getCurrentPlayState();
        if (raw == lastRawState) return;
        lastRawState = raw;
        // 缓冲起止不改变主状态,单独以 BufferingStart/End 上报(与 PlayListener 语义对齐)
        if (raw == VideoView.STATE_BUFFERING) {
            if (!buffering) {
                buffering = true;
                for (PlayListener l : listeners) {
                    try {
                        l.onBufferingStart();
                    } catch (Throwable ignored) {
                    }
                }
            }
            return;
        }
        if (raw == VideoView.STATE_BUFFERED) {
            if (buffering) {
                buffering = false;
                for (PlayListener l : listeners) {
                    try {
                        l.onBufferingEnd();
                    } catch (Throwable ignored) {
                    }
                }
            }
            return;
        }
        PlayState mapped;
        switch (raw) {
            case VideoView.STATE_ERROR:
                mapped = PlayState.ERROR;
                break;
            case VideoView.STATE_IDLE:
                mapped = PlayState.IDLE;
                break;
            case VideoView.STATE_PREPARING:
                mapped = PlayState.PREPARING;
                break;
            case VideoView.STATE_PREPARED:
                mapped = PlayState.READY;
                break;
            case VideoView.STATE_PLAYING:
                mapped = PlayState.PLAYING;
                break;
            case VideoView.STATE_PAUSED:
                mapped = PlayState.PAUSED;
                break;
            case VideoView.STATE_PLAYBACK_COMPLETED:
                mapped = PlayState.COMPLETED;
                break;
            default:
                return;
        }
        setState(mapped);
    }

    @Override
    public void play() {
        if (videoView != null) {
            try {
                videoView.start();
            } catch (Throwable th) {
                android.util.Log.w(TAG, "start 异常", th);
            }
        }
    }

    @Override
    public void pause() {
        if (videoView != null) {
            try {
                videoView.pause();
            } catch (Throwable th) {
                android.util.Log.w(TAG, "pause 异常", th);
            }
        }
    }

    @Override
    public void seekTo(long positionMs) {
        if (videoView != null) {
            try {
                videoView.seekTo(positionMs);
            } catch (Throwable th) {
                android.util.Log.w(TAG, "seekTo 异常", th);
            }
        }
    }

    @Override
    public void setSpeed(float speed) {
        if (videoView != null) {
            try {
                videoView.setSpeed(speed);
            } catch (Throwable th) {
                android.util.Log.w(TAG, "setSpeed 异常", th);
            }
        }
    }

    /** 画面缩放(0-5,与 VideoView.SCREEN_SCALE_* 对应) */
    @Override
    public void setScale(int scaleType) {
        if (videoView != null) {
            try {
                videoView.setScreenScaleType(scaleType);
            } catch (Throwable th) {
                android.util.Log.w(TAG, "setScale 异常", th);
            }
        }
    }

    @Override
    public PlayState getState() {
        return state;
    }

    @Override
    public long getPosition() {
        try {
            return videoView == null ? 0 : videoView.getCurrentPosition();
        } catch (Throwable th) {
            return 0;
        }
    }

    @Override
    public long getDuration() {
        try {
            return videoView == null ? 0 : videoView.getDuration();
        } catch (Throwable th) {
            return 0;
        }
    }

    @Override
    public int[] getVideoSize() {
        try {
            return videoView == null ? new int[]{0, 0} : videoView.getVideoSize();
        } catch (Throwable th) {
            return new int[]{0, 0};
        }
    }

    @Override
    public void subscribe(PlayListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    @Override
    public void unsubscribe(PlayListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void release() {
        handler.removeCallbacks(tickRunnable);
        if (videoView != null && ownsVideoView) {
            try {
                videoView.release();
            } catch (Throwable ignored) {
            }
        }
        listeners.clear();
        setState(PlayState.RELEASED);
    }

    /** 小窗/后台由外层 UI 容器承载，适配器不接管 */
    @Override
    public void enterWindow() {
    }

    @Override
    public void backgroundPlay(boolean on) {
    }

    private void setState(PlayState s) {
        state = s;
        for (PlayListener l : listeners) {
            try {
                l.onStateChanged(s);
            } catch (Throwable ignored) {
            }
        }
    }
}

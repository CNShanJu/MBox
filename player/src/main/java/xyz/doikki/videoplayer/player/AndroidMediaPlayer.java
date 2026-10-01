package xyz.doikki.videoplayer.player;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.Map;

/**
 * 封装系统的MediaPlayer，不推荐，系统的MediaPlayer兼容性较差，建议使用IjkPlayer或者ExoPlayer
 */
public class AndroidMediaPlayer extends AbstractPlayer implements MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnInfoListener,
        MediaPlayer.OnBufferingUpdateListener, MediaPlayer.OnPreparedListener,
        MediaPlayer.OnVideoSizeChangedListener {

    protected MediaPlayer mMediaPlayer;
    private int mBufferedPercent;
    private Context mAppContext;
    private boolean mIsPreparing;
    private String sourceSummary = "未知来源";

    public AndroidMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
    }

    @Override
    public void initPlayer() {
        mMediaPlayer = new MediaPlayer();
        setOptions();
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setOnErrorListener(this);
        mMediaPlayer.setOnCompletionListener(this);
        mMediaPlayer.setOnInfoListener(this);
        mMediaPlayer.setOnBufferingUpdateListener(this);
        mMediaPlayer.setOnPreparedListener(this);
        mMediaPlayer.setOnVideoSizeChangedListener(this);
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        sourceSummary = PlaybackErrorReporter.source(path);
        try {
            mMediaPlayer.setDataSource(mAppContext, Uri.parse(path), headers);
        } catch (Exception e) {
            reportError("设置播放地址", e);
        }
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        sourceSummary = "本地文件描述符";
        try {
            mMediaPlayer.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
        } catch (Exception e) {
            reportError("设置本地文件", e);
        }
    }

    @Override
    public void start() {
        try {
            mMediaPlayer.start();
        } catch (RuntimeException e) {
            reportError("开始播放", e);
        }
    }

    @Override
    public void pause() {
        try {
            mMediaPlayer.pause();
        } catch (RuntimeException e) {
            reportError("暂停", e);
        }
    }

    @Override
    public void stop() {
        try {
            mMediaPlayer.stop();
        } catch (RuntimeException e) {
            reportError("停止", e);
        }
    }

    @Override
    public void prepareAsync() {
        try {
            mIsPreparing = true;
            mMediaPlayer.prepareAsync();
        } catch (RuntimeException e) {
            reportError("准备播放", e);
        }
    }

    @Override
    public void reset() {
        stop();
        mMediaPlayer.reset();
        mMediaPlayer.setSurface(null);
        mMediaPlayer.setDisplay(null);
        mMediaPlayer.setVolume(1, 1);
    }

    @Override
    public boolean isPlaying() {
        return mMediaPlayer.isPlaying();
    }

    @Override
    public void seekTo(long time) {
        try {
            mMediaPlayer.seekTo((int) time);
        } catch (RuntimeException e) {
            reportError("跳转进度", e);
        }
    }

    @Override
    public void release() {
        final String releasedSource = sourceSummary;
        mMediaPlayer.setOnErrorListener(null);
        mMediaPlayer.setOnCompletionListener(null);
        mMediaPlayer.setOnInfoListener(null);
        mMediaPlayer.setOnBufferingUpdateListener(null);
        mMediaPlayer.setOnPreparedListener(null);
        mMediaPlayer.setOnVideoSizeChangedListener(null);
        stop();
        final MediaPlayer mediaPlayer = mMediaPlayer;
        mMediaPlayer = null;
        // 与 Surface 解绑后再异步释放:宿主随后会释放渲染视图的 Surface,原生输出线程不能还挂在上面
        try {
            mediaPlayer.setSurface(null);
        } catch (Throwable ignored) {
        }
        releaseAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    mediaPlayer.release();
                } catch (Throwable e) {
                    // 必须兜 Throwable:native 释放失败抛的是 Error,只 catch Exception 会逃到释放线程上
                    PlaybackErrorReporter.failure("系统播放器", "释放", releasedSource, e);
                }
            }
        });
    }

    @Override
    public long getCurrentPosition() {
        return mMediaPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        return mMediaPlayer.getDuration();
    }

    @Override
    public int getBufferedPercentage() {
        return mBufferedPercent;
    }

    @Override
    public void setSurface(Surface surface) {
        try {
            mMediaPlayer.setSurface(surface);
        } catch (Exception e) {
            reportError("设置画面", e);
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        try {
            mMediaPlayer.setDisplay(holder);
        } catch (Exception e) {
            reportError("设置显示输出", e);
        }
    }

    @Override
    public void setVolume(float v1, float v2) {
        mMediaPlayer.setVolume(v1, v2);
    }

    @Override
    public void setLooping(boolean isLooping) {
        mMediaPlayer.setLooping(isLooping);
    }

    @Override
    public void setOptions() {
    }

    @Override
    public void setSpeed(float speed) {
        // only support above Android M
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mMediaPlayer.setPlaybackParams(mMediaPlayer.getPlaybackParams().setSpeed(speed));
            } catch (Exception e) {
                reportError("设置倍速", e);
            }
        }
    }

    @Override
    public float getSpeed() {
        // only support above Android M
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                return mMediaPlayer.getPlaybackParams().getSpeed();
            } catch (Exception e) {
                reportError("读取倍速", e);
            }
        }
        return 1f;
    }

    @Override
    public long getTcpSpeed() {
        // no support
        return 0;
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        PlaybackErrorReporter.failure("系统播放器", "播放回调", sourceSummary,
                "what=" + what + "(" + errorName(what) + "), extra=" + extra
                        + "(" + errorName(extra) + ")");
        mPlayerEventListener.onError();
        return true;
    }

    private void reportError(String operation, Exception error) {
        PlaybackErrorReporter.failure("系统播放器", operation, sourceSummary, error);
        mPlayerEventListener.onError();
    }

    private static String errorName(int code) {
        switch (code) {
            case MediaPlayer.MEDIA_ERROR_UNKNOWN: return "未知错误";
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED: return "播放器服务异常";
            case MediaPlayer.MEDIA_ERROR_IO: return "读取失败";
            case MediaPlayer.MEDIA_ERROR_MALFORMED: return "媒体格式损坏";
            case MediaPlayer.MEDIA_ERROR_UNSUPPORTED: return "格式不支持";
            case MediaPlayer.MEDIA_ERROR_TIMED_OUT: return "超时";
            default: return "其他";
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        mPlayerEventListener.onCompletion();
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        //解决MEDIA_INFO_VIDEO_RENDERING_START多次回调问题
        if (what == AbstractPlayer.MEDIA_INFO_RENDERING_START) {
            if (mIsPreparing) {
                mPlayerEventListener.onInfo(what, extra);
                mIsPreparing = false;
            }
        } else {
            mPlayerEventListener.onInfo(what, extra);
        }
        return true;
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        mBufferedPercent = percent;
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        mPlayerEventListener.onPrepared();
        start();
        // 修复播放纯音频时状态出错问题
        if (!isVideo()) {
            mPlayerEventListener.onInfo(AbstractPlayer.MEDIA_INFO_RENDERING_START, 0);
        }
    }

    private boolean isVideo() {
        try {
            MediaPlayer.TrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
            for (MediaPlayer.TrackInfo info :
                    trackInfo) {
                if (info.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    @Override
    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
        int videoWidth = mp.getVideoWidth();
        int videoHeight = mp.getVideoHeight();
        if (videoWidth != 0 && videoHeight != 0) {
            mPlayerEventListener.onVideoSizeChanged(videoWidth, videoHeight);
        }
    }
}

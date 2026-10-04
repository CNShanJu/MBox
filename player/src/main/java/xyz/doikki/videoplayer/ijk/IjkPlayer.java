package xyz.doikki.videoplayer.ijk;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.PlaybackErrorReporter;
import xyz.doikki.videoplayer.player.PlaybackFailureKind;
import xyz.doikki.videoplayer.player.VideoViewManager;

public class IjkPlayer extends AbstractPlayer implements IMediaPlayer.OnErrorListener,
        IMediaPlayer.OnCompletionListener, IMediaPlayer.OnInfoListener,
        IMediaPlayer.OnBufferingUpdateListener, IMediaPlayer.OnPreparedListener,
        IMediaPlayer.OnVideoSizeChangedListener, IjkMediaPlayer.OnNativeInvokeListener {

    protected IjkMediaPlayer mMediaPlayer;
    private volatile PlaybackFailureKind failureKind = PlaybackFailureKind.UNKNOWN;
    private int mBufferedPercent;
    private final Context mAppContext;
    private String sourceSummary = "未知来源";

    public IjkPlayer(Context context) {
        mAppContext = context;
    }

    @Override
    public void initPlayer() {
        mMediaPlayer = new IjkMediaPlayer();
        //native日志
        IjkMediaPlayer.native_setLogLevel(VideoViewManager.getConfig().mIsEnableLog ? IjkMediaPlayer.IJK_LOG_INFO : IjkMediaPlayer.IJK_LOG_SILENT);
        setOptions();
        mMediaPlayer.setOnErrorListener(this);
        mMediaPlayer.setOnCompletionListener(this);
        mMediaPlayer.setOnInfoListener(this);
        mMediaPlayer.setOnBufferingUpdateListener(this);
        mMediaPlayer.setOnPreparedListener(this);
        mMediaPlayer.setOnVideoSizeChangedListener(this);
        mMediaPlayer.setOnNativeInvokeListener(this);
    }


    @Override
    public void setOptions() {
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        failureKind = PlaybackFailureKind.UNKNOWN;
        sourceSummary = PlaybackErrorReporter.source(path);
        try {
            Uri uri = Uri.parse(path);
            if (ContentResolver.SCHEME_ANDROID_RESOURCE.equals(uri.getScheme())) {
                RawDataSourceProvider rawDataSourceProvider = RawDataSourceProvider.create(mAppContext, uri);
                mMediaPlayer.setDataSource(rawDataSourceProvider);
            } else {
                // IJK 的 UA 需要单独设置；只改本次请求头副本，不能删掉会话保存的头。
                Map<String, String> requestHeaders = headers == null ? null : new HashMap<>(headers);
                if (requestHeaders != null) {
                    String userAgent = null;
                    for (Iterator<Map.Entry<String, String>> it = requestHeaders.entrySet().iterator(); it.hasNext(); ) {
                        Map.Entry<String, String> entry = it.next();
                        if ("User-Agent".equalsIgnoreCase(entry.getKey())) {
                            if (!TextUtils.isEmpty(entry.getValue())) userAgent = entry.getValue();
                            it.remove();
                        }
                    }
                    if (!TextUtils.isEmpty(userAgent)) {
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", userAgent);
                    }
                }
                mMediaPlayer.setDataSource(mAppContext, uri, requestHeaders);
            }
        } catch (Exception e) {
            PlaybackErrorReporter.failure("IJK", "设置播放地址", sourceSummary, e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        failureKind = PlaybackFailureKind.UNKNOWN;
        sourceSummary = "本地文件描述符";
        try {
            mMediaPlayer.setDataSource(new RawDataSourceProvider(fd));
        } catch (Exception e) {
            PlaybackErrorReporter.failure("IJK", "设置本地文件", sourceSummary, e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void pause() {
        try {
            mMediaPlayer.pause();
        } catch (RuntimeException e) {
            PlaybackErrorReporter.failure("IJK", "暂停", sourceSummary, e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void start() {
        try {
            mMediaPlayer.start();
        } catch (RuntimeException e) {
            PlaybackErrorReporter.failure("IJK", "开始播放", sourceSummary, e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void stop() {
        try {
            mMediaPlayer.stop();
        } catch (RuntimeException e) {
            PlaybackErrorReporter.failure("IJK", "停止", sourceSummary, e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void prepareAsync() {
        try {
            mMediaPlayer.prepareAsync();
        } catch (RuntimeException e) {
            PlaybackErrorReporter.failure("IJK", "准备播放", sourceSummary, e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void reset() {
        failureKind = PlaybackFailureKind.UNKNOWN;
        mMediaPlayer.reset();
        mMediaPlayer.setOnVideoSizeChangedListener(this);
        setOptions();
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
            PlaybackErrorReporter.failure("IJK", "跳转进度", sourceSummary, e);
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void release() {
        failureKind = PlaybackFailureKind.UNKNOWN;
        // 先抓本地引用:释放是异步的(见 releaseAsync),匿名类里直接读字段会读到"之后新建的那一个"
        // (VideoView 每次起播都会 new 一个新内核实例),把新内核释放掉 —— 表现就是换源/切集后起不来。
        final IjkMediaPlayer player = mMediaPlayer;
        if (player == null) return;
        final String releasedSource = sourceSummary;
        // 与 Surface 解绑必须在这里、且在异步释放之前做完:宿主紧接着就会释放渲染视图的
        // Surface/SurfaceTexture(TextureRenderView.release),此刻原生输出线程若还挂在它上面就是 SIGSEGV。
        // 这一步执行时播放器还活着,是安全的。
        try {
            player.setSurface(null);
        } catch (Throwable ignored) {
        }
        player.setOnErrorListener(null);
        player.setOnCompletionListener(null);
        player.setOnInfoListener(null);
        player.setOnBufferingUpdateListener(null);
        player.setOnPreparedListener(null);
        player.setOnVideoSizeChangedListener(null);
        // mMediaPlayer 字段不置空:①子类(app 的 IjkMediaPlayer)的 setOptions() 直接读这个字段,
        // 置空会让每次 reset/起播都 NPE;②置空还会让未做兜底的 isPlaying/getDuration 从
        // "原生侧空指针检查后返回默认值"变成 Java 层 NPE。这里真正要保证的是"异步线程只释放它当时那一个"。
        releaseAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    player.release();
                } catch (Throwable e) {
                    // 必须兜 Throwable:native 释放失败会抛 Error,只 catch Exception 会让它逃到释放线程上
                    PlaybackErrorReporter.failure("IJK", "释放", releasedSource, e);
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
        mMediaPlayer.setSurface(surface);
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        mMediaPlayer.setDisplay(holder);
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
    public void setSpeed(float speed) {
        mMediaPlayer.setSpeed(speed);
    }

    @Override
    public float getSpeed() {
        // 兜底值传 1f(不是 0):内核未就绪时该属性可能为 0,控制器拿它当除数会得到 Infinity(进度停摆)
        return mMediaPlayer.getSpeed(1f);
    }

    @Override
    public long getTcpSpeed() {
        return mMediaPlayer.getTcpSpeed();
    }

    @Override
    public PlaybackFailureKind playbackFailureKind() {
        return failureKind;
    }

    @Override
    public boolean onError(IMediaPlayer mp, int what, int extra) {
        failureKind = classifyFailure(what);
        PlaybackErrorReporter.failure("IJK", "播放回调", sourceSummary,
                "what=" + what + "(" + errorName(what) + "), extra=" + extra);
        mPlayerEventListener.onError();
        return true;
    }

    static PlaybackFailureKind classifyFailure(int what) {
        return what == IMediaPlayer.MEDIA_ERROR_UNSUPPORTED
                ? PlaybackFailureKind.ENGINE_COMPATIBILITY : PlaybackFailureKind.UNKNOWN;
    }

    private static String errorName(int what) {
        switch (what) {
            case IMediaPlayer.MEDIA_ERROR_UNKNOWN: return "未知错误";
            case IMediaPlayer.MEDIA_ERROR_SERVER_DIED: return "播放器服务异常";
            case IMediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK: return "不支持渐进播放";
            case IMediaPlayer.MEDIA_ERROR_IO: return "读取失败";
            case IMediaPlayer.MEDIA_ERROR_MALFORMED: return "媒体格式损坏";
            case IMediaPlayer.MEDIA_ERROR_UNSUPPORTED: return "格式不支持";
            case IMediaPlayer.MEDIA_ERROR_TIMED_OUT: return "超时";
            default: return "其他";
        }
    }

    @Override
    public void onCompletion(IMediaPlayer mp) {
        mPlayerEventListener.onCompletion();
    }

    @Override
    public boolean onInfo(IMediaPlayer mp, int what, int extra) {
        mPlayerEventListener.onInfo(what, extra);
        return true;
    }

    @Override
    public void onBufferingUpdate(IMediaPlayer mp, int percent) {
        mBufferedPercent = percent;
    }

    @Override
    public void onPrepared(IMediaPlayer mp) {
        failureKind = PlaybackFailureKind.UNKNOWN;
        mPlayerEventListener.onPrepared();
        // 修复播放纯音频时状态出错问题
        if (!isVideo()) {
            mPlayerEventListener.onInfo(AbstractPlayer.MEDIA_INFO_RENDERING_START, 0);
        }
    }

    private boolean isVideo() {
        IjkTrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
        if (trackInfo == null) return false;
        for (IjkTrackInfo info : trackInfo) {
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onVideoSizeChanged(IMediaPlayer mp, int width, int height, int sar_num, int sar_den) {
        int videoWidth = mp.getVideoWidth();
        int videoHeight = mp.getVideoHeight();
        if (videoWidth != 0 && videoHeight != 0) {
            mPlayerEventListener.onVideoSizeChanged(videoWidth, videoHeight);
        }
    }

    @Override
    public boolean onNativeInvoke(int what, Bundle args) {
        return true;
    }
}

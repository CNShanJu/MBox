package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.TrafficStats;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;

import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.common.VideoSize;

import java.util.Map;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.PlaybackErrorReporter;

/** DKVideoPlayer 的 Media3 ExoPlayer 内核，沿用类名以兼容现有工厂。 */
@UnstableApi
public class ExoMediaPlayer extends AbstractPlayer implements Player.Listener {

    protected Context mAppContext;
    protected ExoPlayer mMediaPlayer;
    protected MediaSource mMediaSource;
    protected ExoMediaSourceHelper mMediaSourceHelper;
    protected ExoTrackNameProvider trackNameProvider;
    private PlaybackParameters mSpeedPlaybackParameters;
    private boolean mIsPreparing;

    private LoadControl mLoadControl;
    private DefaultRenderersFactory mRenderersFactory;
    private DefaultTrackSelector mTrackSelector;

    private int errorCode = -100;
    private String path;
    private String sourceSummary = "未知来源";
    private Map<String, String> headers;

    public ExoMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
        mMediaSourceHelper = ExoMediaSourceHelper.getInstance(context);
    }

    @Override
    public void initPlayer() {
        if (mRenderersFactory == null) {
            mRenderersFactory = new DefaultRenderersFactory(mAppContext);
            // 规避部分设备/驱动(如魅族/Android16)异步 MediaCodec 在 surface 切换时的
            // "releaseOutputBuffer() is valid only at Executing states" 竞态崩溃, 退回同步队列
            mRenderersFactory.forceDisableMediaCodecAsynchronousQueueing();
            // 编解码异常时允许回退到其它解码器, 提升健壮性
            mRenderersFactory.setEnableDecoderFallback(true);
        }
        mRenderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        if (mTrackSelector == null) {
            mTrackSelector = new DefaultTrackSelector(mAppContext);
        }
        if (mLoadControl == null) {
            mLoadControl = new DefaultLoadControl();
        }
        mTrackSelector.setParameters(mTrackSelector.getParameters().buildUpon().setTunnelingEnabled(true));
        mMediaPlayer = new ExoPlayer.Builder(mAppContext)
                .setLoadControl(mLoadControl)
                .setRenderersFactory(mRenderersFactory)
                .setTrackSelector(mTrackSelector).build();

        // seek 一律落到"目标之前的关键帧"(PREVIOUS_SYNC),不做精确 seek:
        // Exo 对点播的默认 SeekParameters 是 EXACT —— seek 后视频渲染器必须从关键帧一路解码到目标位置
        // 才允许出画,这中间的帧全被丢弃,而音频帧很小、几步就能对齐到目标继续响,
        // 于是频繁拖动进度条时看到的就是"画面卡着不动、声音还在走"(上一次 seek 还没解码到目标,
        // 就被下一次 seek flush 掉,画面永远追不上)。PREVIOUS_SYNC 让采样队列直接从关键帧开始、
        // 解码出第一帧即出画,与 IJK 内核的口径一致(默认解码档带 fflags=fastseek +
        // enable-accurate-seek=0,同样是关键帧 seek),seek 精度也不会越过用户落点(只向前对齐)。
        mMediaPlayer.setSeekParameters(SeekParameters.PREVIOUS_SYNC);

        setOptions();

        mMediaPlayer.addListener(this);
    }

    public DefaultTrackSelector getTrackSelector() {
        return mTrackSelector;
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        this.path = path;
        this.sourceSummary = PlaybackErrorReporter.source(path);
        this.headers = headers;
        mMediaSource = null;
        mMediaSource = mMediaSourceHelper.getMediaSource(path, headers, false, errorCode);
        errorCode = -1;
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        path = null;
        headers = null;
        sourceSummary = "本地文件描述符";
        mMediaSource = null;
        PlaybackErrorReporter.failure("Media3", "设置本地文件", sourceSummary, "不支持文件描述符播放");
        if (mPlayerEventListener != null) mPlayerEventListener.onError();
    }

    @Override
    public void start() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.setPlayWhenReady(true);
    }

    @Override
    public void pause() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.setPlayWhenReady(false);
    }

    @Override
    public void stop() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.stop();
    }

    @Override
    public void prepareAsync() {
        if (mMediaPlayer == null) {
            PlaybackErrorReporter.failure("Media3", "准备播放", sourceSummary, "播放器未初始化");
            if (mPlayerEventListener != null) mPlayerEventListener.onError();
            return;
        }
        if (mMediaSource == null) {
            PlaybackErrorReporter.failure("Media3", "准备播放", sourceSummary, "媒体源为空或格式不支持");
            if (mPlayerEventListener != null) mPlayerEventListener.onError();
            return;
        }
        if (mSpeedPlaybackParameters != null) {
            mMediaPlayer.setPlaybackParameters(mSpeedPlaybackParameters);
        }
        mIsPreparing = true;
        mMediaPlayer.setMediaSource(mMediaSource);
        mMediaPlayer.prepare();
    }

    @Override
    public void reset() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.clearMediaItems();
            mMediaPlayer.setVideoSurface(null);
            mIsPreparing = false;
        }
    }

    @Override
    public boolean isPlaying() {
        if (mMediaPlayer == null)
            return false;
        int state = mMediaPlayer.getPlaybackState();
        switch (state) {
            case Player.STATE_BUFFERING:
            case Player.STATE_READY:
                return mMediaPlayer.getPlayWhenReady();
            case Player.STATE_IDLE:
            case Player.STATE_ENDED:
            default:
                return false;
        }
    }

    @Override
    public void seekTo(long time) {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.seekTo(time);
    }

    @Override
    public void release() {
        if (mMediaPlayer != null) {
            mMediaPlayer.removeListener(this);
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        lastRxBytes = -1;
        lastSampleTime = 0;
        smoothSpeed = -1;
        mIsPreparing = false;
        mSpeedPlaybackParameters = null;
    }

    @Override
    public long getCurrentPosition() {
        if (mMediaPlayer == null)
            return 0;
        return mMediaPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        if (mMediaPlayer == null)
            return 0;
        return mMediaPlayer.getDuration();
    }

    @Override
    public int getBufferedPercentage() {
        return mMediaPlayer == null ? 0 : mMediaPlayer.getBufferedPercentage();
    }

    @Override
    public void setSurface(Surface surface) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setVideoSurface(surface);
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        if (holder == null)
            setSurface(null);
        else
            setSurface(holder.getSurface());
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        if (mMediaPlayer != null)
            mMediaPlayer.setVolume((leftVolume + rightVolume) / 2);
    }

    @Override
    public void setLooping(boolean isLooping) {
        if (mMediaPlayer != null)
            mMediaPlayer.setRepeatMode(isLooping ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
    }

    @Override
    public void setOptions() {
        //准备好就开始播放
        mMediaPlayer.setPlayWhenReady(true);
    }

    @Override
    public void setSpeed(float speed) {
        PlaybackParameters playbackParameters = new PlaybackParameters(speed);
        mSpeedPlaybackParameters = playbackParameters;
        if (mMediaPlayer != null) {
            mMediaPlayer.setPlaybackParameters(playbackParameters);
        }
    }

    @Override
    public float getSpeed() {
        if (mSpeedPlaybackParameters != null) {
            return mSpeedPlaybackParameters.speed;
        }
        return 1f;
    }

    private long lastRxBytes = -1;
    private long lastSampleTime = 0;
    /** 平滑后的下载速度(bytes/s):流量是突发式的,直接采样会在 0 与大数值间跳动,做一阶平滑 */
    private long smoothSpeed = -1;

    @Override
    public long getTcpSpeed() {
        if (mAppContext == null) {
            return 0;
        }
        // 统计本应用(Uid)接收字节数:避免整个设备的流量(其他应用)掺入导致网速跳动;
        // 个别 ROM 不支持 uid 统计时回退到设备总流量
        long total;
        try {
            long uidRx = TrafficStats.getUidRxBytes(mAppContext.getApplicationInfo().uid);
            total = uidRx == TrafficStats.UNSUPPORTED ? TrafficStats.getTotalRxBytes() : uidRx;
        } catch (Throwable th) {
            total = TrafficStats.getTotalRxBytes();
        }
        long time = System.currentTimeMillis();
        if (lastRxBytes < 0 || lastSampleTime == 0) {
            // 首次采样:只记录基线,返回 0,避免把"开机至今的平均流量"当网速闪一下
            lastRxBytes = total;
            lastSampleTime = time;
            return 0;
        }
        long dt = time - lastSampleTime;
        long diff = total - lastRxBytes;
        if (diff < 0) {
            diff = 0; // 流量计数被系统重置(重启/飞行模式等)
        }
        lastRxBytes = total;
        lastSampleTime = time;
        // 与 IJK 的 tcp_speed 语义一致:返回 bytes/s
        long sample = dt <= 0 ? 0 : diff * 1000 / dt;
        if (sample < 0) {
            sample = 0;
        }
        // 一阶平滑(约 1s 采样一次):缓冲间歇(如 Exo 边下边播的读空窗)速度不会瞬间掉零,显示更连续
        smoothSpeed = smoothSpeed < 0 ? sample : (smoothSpeed + sample) / 2;
        return smoothSpeed;
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
        if (trackNameProvider == null)
            trackNameProvider = new ExoTrackNameProvider(mAppContext.getResources());
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (mPlayerEventListener == null) return;
        if (mIsPreparing) {
            if (playbackState == Player.STATE_READY) {
                mPlayerEventListener.onPrepared();
                mPlayerEventListener.onInfo(MEDIA_INFO_RENDERING_START, 0);
                mIsPreparing = false;
            }
            return;
        }
        switch (playbackState) {
            case Player.STATE_BUFFERING:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_START, getBufferedPercentage());
                break;
            case Player.STATE_READY:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_END, getBufferedPercentage());
                break;
            case Player.STATE_ENDED:
                mPlayerEventListener.onCompletion();
                break;
            case Player.STATE_IDLE:
                break;
        }
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        errorCode = error.errorCode;
        // 只有瞬时/可自愈的错误才值得再请求一次;403/404(状态码错误)、解析、解码、不支持类错误
        // 重试必然同样失败,且会把真实原因盖掉,必须直接透传。
        // path 在重试后被置空,天然保证"同一地址最多重试一次"。
        if (path != null && isRetryableError(error.errorCode)) {
            // 重试前留下原因:否则日志里只能看到最终那次失败,看不出"中途重试过"
            PlaybackErrorReporter.retrying("Media3", sourceSummary, describeError(error));
            String retryPath = path;
            try {
                setDataSource(retryPath, headers);
                path = null;
                prepareAsync();
                if (mMediaSource != null && mMediaPlayer != null) start();
            } catch (RuntimeException retryError) {
                path = null;
                PlaybackErrorReporter.failure("Media3", "重新请求播放源", sourceSummary, retryError);
                if (mPlayerEventListener != null) mPlayerEventListener.onError();
            }
            return;
        }
        PlaybackErrorReporter.failure("Media3", "播放回调", sourceSummary, describeError(error));
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onError();
        }
    }

    /**
     * 是否属于可重试的瞬时错误。
     *
     * <p>白名单而非黑名单:未归类的错误码一律直接透传,宁可让上层/日志看到真实原因,
     * 也不要再发一次注定失败的请求。
     *
     * <p>{@link PlaybackException#ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED} 必须留在可重试里:
     * ExoMediaSourceHelper 正是靠"重试时 setDataSource 带过去的 errorCode"改按 m3u8 容器再解一次
     * (见 ExoMediaSourceHelper#getMediaSource 的同名校验分支),去掉这次重试该兜底就失效了。
     */
    private static boolean isRetryableError(int errorCode) {
        switch (errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED:
            case PlaybackException.ERROR_CODE_TIMEOUT:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
                return true;
            default:
                return false;
        }
    }

    /** 错误码 + 直接原因的可读描述(日志用):把 403/404、解码失败等具体原因带出去 */
    private static String describeError(@NonNull PlaybackException error) {
        StringBuilder sb = new StringBuilder(error.getErrorCodeName())
                .append("(code=").append(error.errorCode).append(")");
        Throwable cause = error.getCause();
        if (cause != null) sb.append(' ').append(PlaybackErrorReporter.cause(cause));
        return sb.toString();
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onVideoSizeChanged(videoSize.width, videoSize.height);
            if (videoSize.unappliedRotationDegrees > 0) {
                mPlayerEventListener.onInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED, videoSize.unappliedRotationDegrees);
            }
        }
    }

}

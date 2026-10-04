package xyz.doikki.videoplayer.player;

import android.content.res.AssetFileDescriptor;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.Map;

/**
 * 抽象的播放器，继承此接口扩展自己的播放器
 * Created by Doikki on 2017/12/21.
 */
public abstract class AbstractPlayer {

    /**
     * 视频/音频开始渲染
     */
    public static final int MEDIA_INFO_RENDERING_START = 3;

    /**
     * 缓冲开始
     */
    public static final int MEDIA_INFO_BUFFERING_START = 701;

    /**
     * 缓冲结束
     */
    public static final int MEDIA_INFO_BUFFERING_END = 702;

    /**
     * 视频旋转信息
     */
    public static final int MEDIA_INFO_VIDEO_ROTATION_CHANGED = 10001;

    /**
     * 播放器事件回调
     */
    protected PlayerEventListener mPlayerEventListener;

    /**
     * 初始化播放器实例
     */
    public abstract void initPlayer();

    /**
     * 设置播放地址
     *
     * @param path    播放地址
     * @param headers 播放地址请求头
     */
    public abstract void setDataSource(String path, Map<String, String> headers);

    /**
     * 用于播放raw和asset里面的视频文件
     */
    public abstract void setDataSource(AssetFileDescriptor fd);

    /**
     * 播放
     */
    public abstract void start();

    /**
     * 暂停
     */
    public abstract void pause();

    /**
     * 停止
     */
    public abstract void stop();

    /**
     * 准备开始播放（异步）
     */
    public abstract void prepareAsync();

    /**
     * 重置播放器
     */
    public abstract void reset();

    /**
     * 是否正在播放
     */
    public abstract boolean isPlaying();

    /**
     * 调整进度
     */
    public abstract void seekTo(long time);

    /**
     * 释放播放器
     */
    public abstract void release();

    /**
     * 把播放器与当前 Surface 解绑(不动其它状态)。
     * <p>
     * <b>释放渲染视图的 Surface/SurfaceTexture 之前必须调它</b>:IJK 这类内核的原生输出线程
     * 是按 Surface 持有的 ANativeWindow 直接写的,而 {@code release()} 是异步的(见 {@link #releaseAsync}),
     * "先释放 Surface、后释放播放器"就是让原生线程往已经释放的窗口上写 —— 真机表现是 SIGSEGV(闪退),
     * 触发点正是"播放中返回/切集/换源"。在播放器还活着的时候解绑这一步本身是安全的。
     */
    public void detachSurface() {
        try {
            setSurface(null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 内核释放执行器(所有内核共用一条串行线程)。
     * <p>
     * 内核 {@code release()} 不能留在调用线程上:IJK/系统内核的释放要等原生输出线程收尾,
     * 放在 UI 线程上会卡住界面(切集时的"顿一下");而"每次 release 各起一条裸线程"会随
     * 换源/切集把线程数无界堆上去(见改进.txt §六:后台任务须走模块级共享执行器)。
     */
    private static final java.util.concurrent.ExecutorService RELEASE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "player-release");
                t.setDaemon(true);
                return t;
            });

    /** 异步执行内核释放(单线程串行:同一时刻只释放一个内核,不会出现两次释放并发) */
    protected static void releaseAsync(Runnable task) {
        if (task == null) return;
        try {
            RELEASE_EXECUTOR.execute(task);
        } catch (Throwable th) {
            // 执行器本身异常(极端情况):退回就地执行,至少别把这次释放丢掉
            try {
                task.run();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 获取当前播放的位置
     */
    public abstract long getCurrentPosition();

    /**
     * 获取视频总时长
     */
    public abstract long getDuration();

    /**
     * 获取缓冲百分比
     */
    public abstract int getBufferedPercentage();

    /**
     * 设置渲染视频的View,主要用于TextureView
     */
    public abstract void setSurface(Surface surface);

    /**
     * 设置渲染视频的View,主要用于SurfaceView
     */
    public abstract void setDisplay(SurfaceHolder holder);

    /**
     * 设置音量
     */
    public abstract void setVolume(float v1, float v2);

    /**
     * 设置是否循环播放
     */
    public abstract void setLooping(boolean isLooping);

    /**
     * 设置其他播放配置
     */
    public abstract void setOptions();

    /**
     * 设置播放速度
     */
    public abstract void setSpeed(float speed);

    /**
     * 获取播放速度
     */
    public abstract float getSpeed();

    /**
     * 获取当前缓冲的网速
     */
    public abstract long getTcpSpeed();

    /** 最近一次失败的类别；新来源、重置或成功准备后应清除旧类别。 */
    public PlaybackFailureKind playbackFailureKind() {
        return PlaybackFailureKind.UNKNOWN;
    }

    public final boolean isSourceConnectionFailure() {
        return playbackFailureKind() == PlaybackFailureKind.SOURCE_CONNECTION;
    }

    /**
     * 绑定VideoView
     */
    public void setPlayerEventListener(PlayerEventListener playerEventListener) {
        this.mPlayerEventListener = playerEventListener;
    }

    public interface PlayerEventListener {

        void onError();

        void onCompletion();

        void onInfo(int what, int extra);

        void onPrepared();

        void onVideoSizeChanged(int width, int height);

    }

}

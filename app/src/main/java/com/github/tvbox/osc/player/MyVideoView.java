package com.github.tvbox.osc.player;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoView;

public class MyVideoView extends VideoView {

    /**
     * 视频真实尺寸到达时回调(起播、切集各一次;部分解码器还会再报一次校正值)。
     * <p>
     * 详情页预览要靠它在拿到尺寸后重排播放器高度/左栏宽度,减少上下黑边
     * (见 {@code ui.kit.PreviewPlayerSizing});doikki 的 VideoView 只把尺寸存在内部数组里,
     * 外部没有变更通知,所以这里补一个最小监听口。
     */
    public interface OnVideoSizeChangedListener {
        void onVideoSizeChanged(int videoWidth, int videoHeight);
    }

    private OnVideoSizeChangedListener mSizeListener;

    public MyVideoView(@NonNull Context context) {
        super(context, null);
    }

    public MyVideoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs, 0);
    }

    public MyVideoView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener listener) {
        mSizeListener = listener;
    }

    @Override
    public void onVideoSizeChanged(int videoWidth, int videoHeight) {
        super.onVideoSizeChanged(videoWidth, videoHeight);
        OnVideoSizeChangedListener listener = mSizeListener;
        if (listener != null) listener.onVideoSizeChanged(videoWidth, videoHeight);
    }

    public AbstractPlayer getMediaPlayer() {
        return mMediaPlayer;
    }

}

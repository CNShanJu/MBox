package com.github.tvbox.osc.player.api;

/**
 * 播放事件监听（引擎无关；回调线程由实现约定，默认主线程）。
 */
public interface PlayListener {

    /** 状态跳变（INITIALIZING/PREPARING/READY/PLAYING/PAUSED/COMPLETED/ERROR/RELEASED） */
    default void onStateChanged(PlayState state) {
    }

    /** 进度回调（位置/时长/缓冲百分比） */
    default void onProgress(long positionMs, long durationMs, int bufferedPercent) {
    }

    default void onBufferingStart() {
    }

    default void onBufferingEnd() {
    }

    /** 播放错误（code 见实现层映射；message 必带原因，调用方须记日志） */
    default void onError(int code, String message) {
    }

    default void onCompletion() {
    }

    /** 视频尺寸变化（宽/高） */
    default void onVideoSizeChanged(int width, int height) {
    }
}

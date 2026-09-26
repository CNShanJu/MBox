package com.github.tvbox.osc.player.api;

/**
 * 播放状态（引擎无关，自有类型——不暴露任何内核枚举）。
 */
public enum PlayState {
    IDLE,
    INITIALIZING,
    PREPARING,
    READY,
    PLAYING,
    PAUSED,
    COMPLETED,
    ERROR,
    RELEASED
}

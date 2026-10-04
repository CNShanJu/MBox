package xyz.doikki.videoplayer.player;

/** 播放失败类别；仅标记有明确证据的故障，避免把普通取流错误误判为地址不可达。 */
public enum PlaybackFailureKind {
    UNKNOWN,
    SOURCE_CONNECTION,
    ENGINE_COMPATIBILITY
}

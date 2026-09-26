package com.github.tvbox.osc.player;

import com.github.tvbox.osc.player.MyVideoView;

import java.util.Map;

/**
 * 播放会话门面（改进.txt §三 PlayerSession / 播放器全驱动第一步）。
 * <p>
 * 现状:底层仍是共享 {@link MyVideoView}(doikki VideoView),本类只把"驱动指令"
 * (起播/暂停/恢复/释放/进度键)从 PlayFragment 散点收口为单一入口,行为与直接调用完全等价;
 * 后续把 UI 控制切到播放契约(:player)的 PlayerApi 语义(或替换 Media3 adapter)时,
 * 只改本类内部实现,播放页与控制器零改动。
 */
public final class PlayerSession {

    private final MyVideoView video;

    public PlayerSession(MyVideoView video) {
        this.video = video;
    }

    /** 起播:绑定进度键 → 设 URL → start(headers 可为空) */
    public void play(String url, String progressKey, Map<String, String> headers) {
        if (video == null || url == null) return;
        video.setProgressKey(progressKey);
        if (headers != null && !headers.isEmpty()) {
            video.setUrl(url, headers);
        } else {
            video.setUrl(url);
        }
        video.start();
    }

    /** 起播(无进度键/无头) */
    public void play(String url, Map<String, String> headers) {
        if (video == null || url == null) return;
        if (headers != null && !headers.isEmpty()) {
            video.setUrl(url, headers);
        } else {
            video.setUrl(url);
        }
        video.start();
    }

    public void pause() {
        if (video != null) video.pause();
    }

    public void resume() {
        if (video != null) video.resume();
    }

    /** 释放当前内核/会话(切换、离开、调外部播放器前) */
    public void release() {
        if (video != null) video.release();
    }

    // ── 查询/交互(供 UI 层;底层仍是共享 VideoView,行为与原直用一致)──

    /** 视频尺寸(width,height);未出画面为 (0,0) */
    public int[] videoSize() {
        return video == null ? new int[]{0, 0} : video.getVideoSize();
    }

    public long currentPosition() {
        return video == null ? 0 : video.getCurrentPosition();
    }

    public long duration() {
        return video == null ? 0 : video.getDuration();
    }

    public boolean isPlaying() {
        return video != null && video.isPlaying();
    }

    public void seekTo(long positionMs) {
        if (video != null) video.seekTo(positionMs);
    }

    /** 倍速(如 2.0f) */
    public void setSpeed(float speed) {
        if (video != null) video.setSpeed(speed);
    }

    /** 画面缩放(doikki scale 常量) */
    public void setScreenScaleType(int scaleType) {
        if (video != null) video.setScreenScaleType(scaleType);
    }

    /** 把当前共享视图包成 PlayerApi(ownsVideoView=false)供会话观察/审计 */
    public com.github.tvbox.osc.player.VideoViewPlayerApi playerApi() {
        return video == null ? null : new com.github.tvbox.osc.player.VideoViewPlayerApi(video, false);
    }

    /** 底层内核(doikki AbstractPlayer;字幕/轨道装载用)。换内核时此处返回适配层内核 */
    public xyz.doikki.videoplayer.player.AbstractPlayer kernel() {
        return video == null ? null : video.getMediaPlayer();
    }
}

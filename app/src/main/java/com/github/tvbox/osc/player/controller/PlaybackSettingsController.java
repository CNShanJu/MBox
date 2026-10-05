package com.github.tvbox.osc.player.controller;

import android.widget.TextView;

import com.github.tvbox.osc.player.api.PlayConfig;

import java.util.List;

/**
 * 播放设置抽屉(PlayingControlRightDialog)需要的控制器能力:
 * 在线全屏播放(VodController)与本地播放(LocalVideoController)共用同一个设置抽屉,
 * 两者实现本接口即可,保证功能一致。
 */
public interface PlaybackSettingsController {

    default boolean supportsLanPush() { return false; }

    default void requestLanPush() { }

    TextView settingsPlayerBtn();

    TextView settingsScaleBtn();

    TextView settingsIjkBtn();

    TextView settingsTimeStartBtn();

    TextView settingsTimeSkipBtn();

    TextView settingsTimeResetBtn();

    TextView settingsRetryBtn();

    TextView settingsRefreshBtn();

    TextView settingsZimuBtn();

    TextView settingsAudioBtn();

    /** 设置倍速;speed 为空表示循环切换 */
    void setSpeed(String speed);

    /** 长按临时加速使用全局播放配置；在线和本地播放共用同一组选项。 */
    default float getLongPressSpeed() { return PlayConfig.getVideoSpeed(); }

    default List<Float> getLongPressSpeedOptions() { return PlayConfig.getVideoSpeedOptions(); }

    default void setLongPressSpeed(float speed) { PlayConfig.setVideoSpeed(speed); }

    int getScaleType();

    void setScaleType(int scaleType);

    int getPlayerType();

    void setPlayerType(int playerType);

    int getRenderType();

    void setRenderType(int renderType);

    /** 仅在线播放可对 HLS 清单执行广告过滤；本地播放隐藏此项。 */
    default boolean supportsVideoPurify() { return false; }

    /** 当前广告过滤模式，由控制器提供给设置面板。 */
    default int getVideoPurifyMode() { return 0; }

    /** 持久化配置并重播当前视频，使新配置立即用于本次取流。 */
    default void setVideoPurifyMode(int mode) { }

    /** 片头/片尾时间调整,type = "st" / "et" */
    void increaseTime(String type);

    void decreaseTime(String type);
}

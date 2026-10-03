package com.github.tvbox.osc.player.controller;

import android.widget.TextView;

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

    TextView settingsLandscapeBtn();

    /** 播放详情页预览态的同一入口先进入视频全屏。 */
    default String settingsLandscapeActionLabel() { return "横竖屏"; }

    /** 设置倍速;speed 为空表示循环切换 */
    void setSpeed(String speed);

    int getScaleType();

    void setScaleType(int scaleType);

    int getPlayerType();

    void setPlayerType(int playerType);

    int getRenderType();

    void setRenderType(int renderType);

    /** 仅在线播放可对 HLS 清单执行广告过滤；本地播放隐藏此项。 */
    default boolean supportsVideoPurify() { return false; }

    /** 当前广告过滤配置，由控制器提供给设置面板。 */
    default boolean isVideoPurifyEnabled() { return false; }

    /** 持久化配置并重播当前视频，使新配置立即用于本次取流。 */
    default void setVideoPurifyEnabled(boolean enabled) { }

    /** 片头/片尾时间调整,type = "st" / "et" */
    void increaseTime(String type);

    void decreaseTime(String type);
}

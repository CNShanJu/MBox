package com.github.tvbox.osc.player.controller;

import android.widget.TextView;

/**
 * 播放设置抽屉(PlayingControlRightDialog)需要的控制器能力:
 * 在线全屏播放(VodController)与本地播放(LocalVideoController)共用同一个设置抽屉,
 * 两者实现本接口即可,保证功能一致。
 */
public interface PlaybackSettingsController {

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

    /** 设置倍速;speed 为空表示循环切换 */
    void setSpeed(String speed);

    int getScaleType();

    void setScaleType(int scaleType);

    int getPlayerType();

    void setPlayerType(int playerType);

    /** 片头/片尾时间调整,type = "st" / "et" */
    void increaseTime(String type);

    void decreaseTime(String type);
}

package com.github.tvbox.osc.ui.dialog;

/**
 * 直播设置面板宿主能力:由 {@code LiveActivity} 实现后以 {@code this} 注入,
 * 弹窗与面板不再依赖具体 Activity 类型。
 */
public interface LiveSettingHost {

    /** 当前播放器画面缩放档位 */
    int getLivePlayerScale();

    /** 当前播放解码类型 */
    int getLivePlayerType();

    /** 切换画面缩放 */
    void changeScale(int position);

    /** 更换播放解码 */
    void changePlayer(int position);

    /** 偏好设置变化后即时刷新两套控制条的显示项 */
    void refreshPreferenceUi();
}

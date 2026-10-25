package com.github.tvbox.osc.ui.dialog;

import java.util.List;

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

    /** 与在线播放、本地播放和设置页共用的后台播放模式。 */
    int getBackgroundPlayType();

    void setBackgroundPlayType(int position);

    /** 偏好设置变化后即时刷新两套控制条的显示项 */
    void refreshPreferenceUi();

    /**
     * 直播源切换候选项(设置抽屉「直播源」组:「跟随订阅」之后的条目):
     * 订阅自带的直播源 + 用户历史,按此顺序、各自去重。
     */
    List<LiveSourceChoice> getLiveSourceChoices();

    /** 切换直播源(url 空串 = 跟随订阅自带的直播) */
    void changeLiveSource(String url);

    /** 直播源切换候选项(名 + 地址) */
    final class LiveSourceChoice {
        public final String name;
        public final String url;

        public LiveSourceChoice(String name, String url) {
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
        }
    }
}

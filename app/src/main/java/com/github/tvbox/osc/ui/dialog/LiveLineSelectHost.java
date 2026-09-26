package com.github.tvbox.osc.ui.dialog;

import com.github.tvbox.osc.bean.LiveChannelItem;

/**
 * 线路选择抽屉宿主能力:由 {@code LiveActivity} 实现后以 {@code this} 注入,
 * 弹窗不再依赖具体 Activity 类型。
 */
public interface LiveLineSelectHost {

    /** 当前频道(可能为 null,抽屉据此收起);切换时按源序号重播 */
    LiveChannelItem getCurrentLiveChannelItem();

    /** 切换到指定线路序号并重播 */
    void switchingLine2Replay(int sourceIndex);
}

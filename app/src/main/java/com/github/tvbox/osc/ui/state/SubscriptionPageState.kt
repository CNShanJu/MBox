package com.github.tvbox.osc.ui.state

import com.github.tvbox.osc.bean.Subscription

/**
 * 订阅页跨协调器共享的页面状态(普通状态容器,生命周期与 Activity 绑定):
 * 订阅列表与"当前订阅"是导入/直播/导出三个协调器共同读写的核心;
 * 直播源是否改动过、当前停在哪个标签页也是页面级状态。
 */
class SubscriptionPageState(
    val beforeUrl: String,
    subscriptions: List<Subscription>
) {
    val subscriptions: MutableList<Subscription> = subscriptions.toMutableList()
    var selectedUrl: String = subscriptions.firstOrNull { it.isChecked }?.url ?: ""
    var liveTabActive: Boolean = false
    var liveSourceChanged: Boolean = false

    /** 当前启用订阅的名字(未启用返回空串) */
    fun currentName(): String = subscriptions.firstOrNull { it.isChecked }?.name ?: ""
}

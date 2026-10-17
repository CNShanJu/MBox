package com.github.tvbox.osc.util

import android.content.Context
import com.blankj.utilcode.util.AppUtils
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.spiderapi.LiveChannelConfigProviders

/**
 * 从收藏/历史的"来源信息"弹窗一键切回某订阅:落盘当前订阅 → 清理旧订阅残留直播地址 → 内部重启。
 *
 * 与订阅管理页切换走同一套重启语义:不同订阅的 csp.jar 可能加载同名 native 库，只清 Activity 任务栈
 * 仍在同一进程会被 ART 拒绝，必须整进程重启。返回 true 表示已发起重启(进程即将结束)；
 * false 表示未切换(已是当前/目标不存在/重启未发起，均已给出提示)。
 */
object SubscriptionSwitcher {

    @JvmStatic
    fun switchTo(context: Context?, targetUrl: String?): Boolean {
        if (context == null || targetUrl.isNullOrEmpty()) return false
        val subs = SubscriptionConfig.getSubscriptions()
        val target = subs.firstOrNull { it.url == targetUrl } ?: return false
        if (SubscriptionConfig.getApiUrl() == targetUrl && target.isChecked) {
            AppBubble.toast("当前已在使用该订阅源")
            return false
        }
        clearStaleLiveUrl(targetUrl)
        for (s in subs) s.setChecked(s.url == targetUrl)
        SubscriptionConfig.setSubscriptions(subs)
        SubscriptionConfig.setApiUrl(targetUrl)
        LogStore.log(Category.SUBSCRIPTION, "订阅: 从收藏/历史切换到来源订阅")
        if (context.packageManager.getLaunchIntentForPackage(context.packageName) == null) {
            AppBubble.toast("订阅已保存，自动重启失败，请手动重开应用")
            return false
        }
        return try {
            SystemConfig.markInternalRestart()
            AppUtils.relaunchApp(true)
            true
        } catch (failure: Throwable) {
            SystemConfig.clearInternalRestart()
            AppBubble.toast("订阅已保存，自动重启失败，请手动重开应用")
            false
        }
    }

    /** 换订阅重启前:旧订阅独有、且非用户自建/历史的直播地址不能悬空带到新订阅(与订阅页同款保护)。 */
    private fun clearStaleLiveUrl(targetUrl: String) {
        try {
            val provider = LiveChannelConfigProviders.get()
            val selectedLiveUrl = SystemConfig.getLiveUrl()
            if (selectedLiveUrl.isNullOrEmpty()) return
            if (provider.loadedSubscriptionUrl == targetUrl) return
            if (provider.subscribeLiveSources.none { it.url == selectedLiveUrl }) return
            if (LiveConfig.liveHistory().contains(selectedLiveUrl)) return
            SystemConfig.setLiveUrl("")
        } catch (ignored: Throwable) {
        }
    }
}

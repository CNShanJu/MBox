package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.Subscription
import com.github.tvbox.osc.spiderapi.SourceConfigProviders

/** 一条收藏/历史记录相对"当前订阅"的来源归属状态。 */
enum class OriginStatus {
    /** 来源就是当前订阅(源在当前订阅可用，或指纹匹配当前订阅)。 */
    CURRENT,

    /** 来源是另一个仍然存在的订阅，可一键切回。 */
    SWITCHABLE,

    /** 来源订阅已被删除(或地址已变)，无法切回。 */
    DELETED,

    /** 无指纹且源不可用:升级前的老记录，无法追溯来源订阅。 */
    UNKNOWN
}

/**
 * 收藏/历史卡片"来源徽标"的解析结果。
 * [badgeLabel] 直接作徽标文字(源在当前订阅可用时显示源名，否则"源未启用")；
 * [status] 决定弹窗里的说法与是否给出"使用该订阅源"；[targetUrl] 仅 [OriginStatus.SWITCHABLE] 时非空。
 */
class SourceOrigin(
    val status: OriginStatus,
    val sourceName: String?,
    val subscriptionName: String?,
    val targetUrl: String?
) {
    val badgeLabel: String
        get() = if (status == OriginStatus.CURRENT && !sourceName.isNullOrEmpty()) sourceName!! else "源未启用"
}

/**
 * 一次订阅快照下的来源解析器:把"某条记录的 sourceKey + 订阅指纹"解析成 [SourceOrigin]。
 *
 * 订阅列表与当前订阅只在 [build] 时读一次(每次列表加载重建)，逐条 [resolve] 只做内存比对与
 * getSource(内存查表)，避免每个卡片都去解析 DataStore、算 SHA-256。指纹算法与
 * [HistorySourceBinding] 完全一致(收藏/历史共用同一把"这条来自哪个订阅"的尺子)。
 */
class SubscriptionIndex internal constructor(
    private val currentFingerprint: String,
    private val currentName: String?,
    private val byFingerprint: Map<String, Subscription>
) {

    fun resolve(sourceKey: String?, fingerprint: String?): SourceOrigin {
        val source = if (!sourceKey.isNullOrEmpty()) SourceConfigProviders.get().getSource(sourceKey) else null

        // 老记录没有指纹:只能按 sourceKey 是否在当前订阅里判断，认不出具体来自哪个订阅。
        if (fingerprint.isNullOrEmpty()) {
            return if (source != null) {
                SourceOrigin(OriginStatus.CURRENT, source.name, currentName, null)
            } else {
                SourceOrigin(OriginStatus.UNKNOWN, null, null, null)
            }
        }
        // 指纹就是当前订阅:来源即当前(源可能因订阅内容变化不再加载，仍算当前，不给切换)。
        if (fingerprint == currentFingerprint) {
            return SourceOrigin(OriginStatus.CURRENT, source?.name, currentName, null)
        }
        // 指纹命中列表里另一个订阅:可切回(源名以来源订阅为准，当前订阅即使同 key 也不代表同一站点，故不显示)。
        val match = byFingerprint[fingerprint]
        if (match != null) {
            return SourceOrigin(OriginStatus.SWITCHABLE, null, match.name, match.url)
        }
        // 指纹谁都不像:来源订阅已删除/改址。
        return SourceOrigin(OriginStatus.DELETED, null, null, null)
    }

    companion object {
        @JvmStatic
        fun build(): SubscriptionIndex {
            val subs = SubscriptionConfig.getSubscriptions()
            val currentUrl = SubscriptionConfig.getApiUrl()
            val currentFingerprint = HistorySourceBinding.fingerprintOf(currentUrl)
            val byFingerprint = HashMap<String, Subscription>()
            for (s in subs) {
                val fp = HistorySourceBinding.fingerprintOf(s.url)
                if (fp.isNotEmpty() && !byFingerprint.containsKey(fp)) byFingerprint[fp] = s
            }
            val currentName = subs.firstOrNull { it.url == currentUrl }?.name
                ?: subs.firstOrNull { it.isChecked }?.name
            return SubscriptionIndex(currentFingerprint, currentName, byFingerprint)
        }
    }
}

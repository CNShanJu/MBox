package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.bean.Subscription
import com.github.tvbox.osc.spiderapi.SourceConfigApi
import com.github.tvbox.osc.spiderapi.SourceConfigProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 收藏/历史"来源徽标"解析:[SubscriptionIndex.resolve] 的各分支。
 * 只测纯逻辑——注入假的源注册表 + 直接用 internal 构造器给定订阅快照，不碰 DataStore。
 */
class SubscriptionIndexTest {

    private val subA = Subscription("订阅A", "https://a.example/1.json")
    private val subB = Subscription("订阅B", "https://b.example/2.json")

    /** 让 [SourceConfigProviders] 只认 [available] 里的 sourceKey(名字为 "源-<key>")。 */
    private fun givenSources(vararg available: String) {
        val keys = available.toSet()
        SourceConfigProviders.set(object : SourceConfigApi {
            override fun getSource(sourceKey: String?): SourceBean? =
                if (sourceKey != null && keys.contains(sourceKey))
                    SourceBean().apply { key = sourceKey; name = "源-$sourceKey" } else null

            override fun setSourceBean(sourceBean: SourceBean?) {}
            override fun getHomeSourceBean(): SourceBean? = null
            override fun getSourceBeanList(): List<SourceBean> = emptyList()
            override fun getVipParseFlags(): List<String> = emptyList()
        })
    }

    /** 当前订阅 = currentUrl，订阅列表 = subs；直接用 internal 构造器建索引(不读配置)。 */
    private fun indexOf(currentUrl: String, subs: List<Subscription>): SubscriptionIndex {
        val byFp = HashMap<String, Subscription>()
        for (s in subs) {
            val fp = HistorySourceBinding.fingerprintOf(s.url)
            if (fp.isNotEmpty()) byFp[fp] = s
        }
        val currentName = subs.firstOrNull { it.url == currentUrl }?.name
        return SubscriptionIndex(HistorySourceBinding.fingerprintOf(currentUrl), currentName, byFp)
    }

    @Test
    fun sourceInCurrentSubscriptionShowsNameAndNoSwitch() {
        givenSources("site_a")
        val index = indexOf(subB.url, listOf(subA, subB))
        val origin = index.resolve("site_a", HistorySourceBinding.fingerprintOf(subB.url))
        assertEquals(OriginStatus.CURRENT, origin.status)
        assertEquals("源-site_a", origin.badgeLabel)
        assertNull(origin.targetUrl)
    }

    @Test
    fun sourceFromAnotherExistingSubscriptionIsSwitchable() {
        givenSources() // 当前订阅 B 里没有 site_a
        val index = indexOf(subB.url, listOf(subA, subB))
        val origin = index.resolve("site_a", HistorySourceBinding.fingerprintOf(subA.url))
        assertEquals(OriginStatus.SWITCHABLE, origin.status)
        assertEquals("源未启用", origin.badgeLabel)
        assertEquals("订阅A", origin.subscriptionName)
        assertEquals(subA.url, origin.targetUrl)
    }

    @Test
    fun sourceFromDeletedSubscriptionIsReportedDeleted() {
        givenSources()
        val index = indexOf(subB.url, listOf(subB)) // 订阅A 已删除
        val origin = index.resolve("site_a", HistorySourceBinding.fingerprintOf(subA.url))
        assertEquals(OriginStatus.DELETED, origin.status)
        assertEquals("源未启用", origin.badgeLabel)
        assertNull(origin.targetUrl)
    }

    @Test
    fun legacyRecordWithoutFingerprintFallsBackToSourceKeyAvailability() {
        givenSources("site_a") // site_a 可用, site_z 不可用
        val index = indexOf(subB.url, listOf(subA, subB))
        val available = index.resolve("site_a", null)
        assertEquals(OriginStatus.CURRENT, available.status)
        assertEquals("源-site_a", available.badgeLabel)

        val missing = index.resolve("site_z", null)
        assertEquals(OriginStatus.UNKNOWN, missing.status)
        assertEquals("源未启用", missing.badgeLabel)
        assertNull(missing.targetUrl)
    }

    @Test
    fun currentFingerprintButSourceMissingStaysCurrentWithoutSwitch() {
        givenSources() // 当前订阅里 site_x 已不再加载
        val index = indexOf(subB.url, listOf(subA, subB))
        val origin = index.resolve("site_x", HistorySourceBinding.fingerprintOf(subB.url))
        assertEquals(OriginStatus.CURRENT, origin.status)
        assertEquals("源未启用", origin.badgeLabel)
        assertNull(origin.targetUrl)
    }
}

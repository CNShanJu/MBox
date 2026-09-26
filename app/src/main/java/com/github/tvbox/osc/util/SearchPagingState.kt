package com.github.tvbox.osc.util

import java.util.LinkedHashMap

/**
 * 聚合搜索「加载更多」记账(纯逻辑,可 JVM 单测)。
 *
 * 每个来源各自记「已加载到第几页 / 总页数」,并负责一轮翻页的 发起 → 回收 → 到底 判定:
 * 页面只按它的结论发请求与追加列表,不再自己散落一堆 map/counter。
 *
 * 关键语义(都是被真实站点踩出来的):
 * - 某源某页**没有数据**即视为该源到底,记 `总页数 = 已加载页`,不再重复请求同一页;
 * - 一轮翻页里**没回有效批次**的来源同样按到底处理(请求失败/超时不能让页面永远转"加载中");
 * - 页码只前进不后退(乱序回包也不会把页码打回去)。
 */
class SearchPagingState {

    /** 来源 key → 已加载到第几页(从 1 起) */
    private val loadedPage = LinkedHashMap<String, Int>()

    /** 来源 key → 总页数(来自各源搜索结果的 pagecount;非正数按 1) */
    private val totalPage = LinkedHashMap<String, Int>()

    /** 本轮翻页已发起的来源 */
    private val roundKeys = ArrayList<String>()

    /** 本轮已回有效数据的来源 */
    private val roundArrived = HashSet<String>()

    /** 本轮尚未回包的来源数 */
    private var remaining = 0

    /** 新一轮搜索 / 下拉重刷:清空全部记账 */
    fun reset() {
        loadedPage.clear()
        totalPage.clear()
        roundKeys.clear()
        roundArrived.clear()
        remaining = 0
    }

    /** 首屏某来源返回:记「已加载第 1 页 + 该源总页数」 */
    fun recordFirstPage(key: String?, totalPages: Int) {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return
        loadedPage[k] = 1
        totalPage[k] = if (totalPages > 0) totalPages else 1
    }

    /** 已加载到第几页(未见过的来源按 1) */
    fun pageOf(key: String?): Int = loadedPage[key?.trim().orEmpty()] ?: 1

    /** 总页数(未见过的来源按 1) */
    fun totalOf(key: String?): Int = totalPage[key?.trim().orEmpty()] ?: 1

    /** 是否还有来源能往下翻 */
    fun hasMore(): Boolean = loadedPage.keys.any { pageOf(it) < totalOf(it) }

    /** 还有下一页的来源(按首屏登记顺序) */
    fun keysWithMore(): List<String> = loadedPage.keys.filter { pageOf(it) < totalOf(it) }

    /** 是否正在一轮翻页中 */
    fun inRound(): Boolean = remaining > 0

    /**
     * 发起一轮翻页。
     * @return true=本轮已发起(调用方据此发请求);false=没有可用来源,未发起
     */
    fun beginRound(keys: List<String>?): Boolean {
        roundKeys.clear()
        roundArrived.clear()
        remaining = 0
        keys?.forEach { key ->
            val k = key?.trim().orEmpty()
            if (k.isNotEmpty()) roundKeys.add(k)
        }
        remaining = roundKeys.size
        return remaining > 0
    }

    /**
     * 本轮某来源回包(空页/失败也要回,否则页面会一直等)。
     *
     * @param pageNo     本次请求的页码
     * @param hasItems   该页是否带回数据(false=到底)
     * @param totalPages 该页响应里的总页数(<=0 表示没有该信息)
     * @return true=本轮所有来源都已回包(调用方据此收尾)
     */
    fun reply(key: String?, pageNo: Int, hasItems: Boolean, totalPages: Int): Boolean {
        val k = key?.trim().orEmpty()
        if (k.isEmpty() || !roundKeys.contains(k)) return false   // 过期/未发起的批次:不记账也不减计数
        if (pageNo > pageOf(k)) loadedPage[k] = pageNo
        if (!hasItems) {
            totalPage[k] = pageOf(k)                              // 没有下一页:该源到底
        } else {
            if (totalPages > 0) totalPage[k] = totalPages
            roundArrived.add(k)
        }
        if (remaining > 0) remaining--
        return remaining <= 0
    }

    /**
     * 收尾本轮:本轮没回有效批次的来源按到底处理;
     * @return true=本轮确实追加过数据(调用方据此决定是否继续自动补页)
     */
    fun finishRound(): Boolean {
        val arrivedAny = roundArrived.isNotEmpty()
        for (k in roundKeys) {
            if (!roundArrived.contains(k)) totalPage[k] = pageOf(k)
        }
        roundKeys.clear()
        roundArrived.clear()
        remaining = 0
        return arrivedAny
    }
}

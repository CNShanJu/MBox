package com.github.tvbox.osc.util

import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * 聚合搜索「来源快慢画像 + 本轮分波投递」(纯逻辑,可 JVM 单测)。
 *
 * 背景(真机日志实测):所有来源共用 5 线程的应用级共享大池,一个来源从发起到回包**全程占一个槽位**;
 * 慢源(6~15s 才超时的那些)把槽位占满后,**排在后面的来源根本发不出去** —— 现象就是
 * 「先出来三四个,然后卡住十几秒,再陆陆续续冒出来」。开始的加载观感因此被少数慢源决定。
 *
 * 所以一轮搜索按画像分两波投递:
 * - **第一波(大部队)**:上一轮没被判慢的来源,其中**快的排前面**(有画像的按上次实测耗时升序,
 *   快源先占槽位,首屏结果最快出现;没有画像的新源保持配置顺序排在后面,不能无依据地推迟);
 * - **第二波(单独跑)**:上一轮被判慢的来源 + 本轮第一波里「慢且没出结果」的来源,
 *   等第一波收尾之后再投 —— 它们不再堵住大部队,失败也只在自己的小批次里重来一次。
 *
 * 判定只看**实测耗时**:UI 层拿不到「超时还是空结果」(两者都表现为空批次),但耗时能区分,
 * 耗时 ≥ [slowCostMs] 即记为慢。这套机制的用途是"别让慢源占住槽位",不是给来源打分。
 *
 * 线程安全:画像进程内共享,`onSourceDone` 会从共享池线程 / HttpClient 回调线程写入,
 * 而 `split`/`beginRound` 在 UI 线程读,故所有公开方法都加锁(每次搜索只调用几次,开销可忽略)。
 */
class SearchSourceHealth(private val slowCostMs: Long = DEFAULT_SLOW_COST_MS) {

    companion object {
        /** 慢源阈值:与 jar 侧常见的 6s 连接超时同量级 */
        const val DEFAULT_SLOW_COST_MS = 6000L

        /** 进程内共享画像:来源快慢跨轮次、跨页面都有效(页面销毁重建后仍记得谁慢) */
        private val shared = SearchSourceHealth()

        fun shared(): SearchSourceHealth = shared
    }

    /** 来源 key → 上一轮实测耗时(ms) */
    private val lastCost = LinkedHashMap<String, Long>()

    /** 被判慢的来源(上一轮耗时 ≥ 阈值) */
    private val slowKeys = HashSet<String>()

    /** 本轮「慢且没出结果」的来源(第二波要单独重跑的) */
    private val roundRetry = LinkedHashSet<String>()

    /** 一轮开始:只清本轮记账,画像保留 */
    @Synchronized
    fun beginRound() {
        roundRetry.clear()
    }

    /**
     * 按画像把本轮来源切成两波。
     *
     * @return primary=第一波(大部队,快的在前) deferred=第二波(上一轮判慢的,第一波收尾后再投)
     */
    @Synchronized
    fun split(keys: List<String>?): Waves {
        val primary = ArrayList<String>()
        val deferred = ArrayList<String>()
        keys?.forEach { raw ->
            val k = raw?.trim().orEmpty()
            if (k.isEmpty()) return@forEach
            if (slowKeys.contains(k)) deferred.add(k) else primary.add(k)
        }
        // 稳定排序:有画像的按耗时升序在前(快源先占槽位),没画像的保持原序在后
        primary.sortWith(compareBy({ if (lastCost.containsKey(it)) 0 else 1 }, { costOf(it) }))
        deferred.sortWith(compareBy { costOf(it) })
        return Waves(primary, deferred)
    }

    /**
     * 某来源跑完一波后记账。
     *
     * @param costMs    实测耗时(含超时;负数视为无效不记账)
     * @param hasResult 这一波有没有带回有效结果(慢且没出结果的,本轮第二波单独重跑)
     */
    @Synchronized
    fun onSourceDone(key: String?, costMs: Long, hasResult: Boolean) {
        val k = key?.trim().orEmpty()
        if (k.isEmpty() || costMs < 0) return
        lastCost[k] = costMs
        if (costMs >= slowCostMs) {
            slowKeys.add(k)
            if (!hasResult) roundRetry.add(k) // 慢且空手而归:值得单独重来一次
        } else {
            slowKeys.remove(k) // 又变快了:回到大部队
        }
    }

    /** 本轮第二波要单独重跑的来源(第一波里「慢且没出结果」的那些) */
    @Synchronized
    fun retryKeys(): List<String> = ArrayList(roundRetry)

    /** 是否已被判慢(下一轮进第二波) */
    @Synchronized
    fun isSlow(key: String?): Boolean = slowKeys.contains(key?.trim().orEmpty())

    /** 上一轮实测耗时(没有画像返回 -1) */
    @Synchronized
    fun costOf(key: String?): Long = lastCost[key?.trim().orEmpty()] ?: -1L

    /** 清空全部画像(换订阅等场景) */
    @Synchronized
    fun clear() {
        lastCost.clear()
        slowKeys.clear()
        roundRetry.clear()
    }

    /** 一轮投递的两波来源 */
    data class Waves(val primary: List<String>, val deferred: List<String>) {
        val all: List<String> get() = primary + deferred
    }
}

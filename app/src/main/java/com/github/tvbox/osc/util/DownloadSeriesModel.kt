package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.VodInfo
import java.util.function.IntFunction

/**
 * 下载选择弹窗的选集数据模型纯逻辑(自 DetailActivity 抽取,等价搬移;Java→Kotlin 化,改进.txt §八):
 * - 从弹窗当前列表收集勾选集名(排序/刷新后按集名保留勾选)
 * - 按详情页"全集正表"重建选集副本(写统一剧集标识 episodeId)
 * - 批量查询所需的 episodeIds/episodeNames 数组拆分
 * 无 View/Context 依赖,episodeId 生成经 [rebuildCopy] 注入的工厂,可 JVM 单测。
 */
object DownloadSeriesModel {

    /** 收集弹窗当前勾选的集名(selected 且 name 非空) */
    @JvmStatic
    fun collectSelectedNames(shown: List<VodInfo.VodSeries>?): MutableSet<String> {
        val selectedNames = HashSet<String>()
        if (shown == null) return selectedNames
        for (s in shown) {
            if (s.selected && s.name != null) {
                selectedNames.add(s.name)
            }
        }
        return selectedNames
    }

    /**
     * 按全集正表重建弹窗选集副本:每集写统一剧集标识并保留勾选(按集名匹配)。
     *
     * @param master        详情页全集(seriesMap.get(playFlag)),决定顺序与子集
     * @param selectedNames 需保留勾选的集名集合
     * @param episodeIdOf   第 i 集(0 起)的剧集标识生成器(episodeId = 来源|剧id|线路|索引)
     */
    @JvmStatic
    fun rebuildCopy(
        master: List<VodInfo.VodSeries>?,
        selectedNames: Set<String>,
        episodeIdOf: IntFunction<String>
    ): List<VodInfo.VodSeries> {
        val copy = ArrayList<VodInfo.VodSeries>()
        if (master == null) return copy
        var copyIdx = 0
        for (s in master) {
            val c = VodInfo.VodSeries()
            c.name = s.name
            c.url = s.url
            c.selected = s.name != null && selectedNames.contains(s.name)
            c.episodeId = episodeIdOf.apply(copyIdx)
            copyIdx++
            copy.add(c)
        }
        return copy
    }

    /** 批量查询用:episodeId 数组(与 copy 顺序一致) */
    @JvmStatic
    fun episodeIdsOf(copy: List<VodInfo.VodSeries>): Array<String> {
        val ids = arrayOfNulls<String>(copy.size)
        for (i in copy.indices) {
            ids[i] = copy[i].episodeId
        }
        @Suppress("UNCHECKED_CAST")
        return ids as Array<String>
    }

    /** 批量查询用:集名数组(与 copy 顺序一致) */
    @JvmStatic
    fun episodeNamesOf(copy: List<VodInfo.VodSeries>): Array<String> {
        val names = arrayOfNulls<String>(copy.size)
        for (i in copy.indices) {
            names[i] = copy[i].name
        }
        @Suppress("UNCHECKED_CAST")
        return names as Array<String>
    }
}

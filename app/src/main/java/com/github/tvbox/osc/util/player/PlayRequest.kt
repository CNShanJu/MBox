package com.github.tvbox.osc.util.player

import com.github.tvbox.osc.bean.VodInfo

/**
 * 一次播放请求的上下文值对象(自 PlayFragment.play 抽取,等价搬移;Java→Kotlin 化,改进.txt §八):
 * 把"来源/线路/集索引/集名/播放地址 + 进度/字幕缓存键"聚成不可变对象,
 * 键由 [PlaySessionKeys] 统一生成,与历史逐字一致。后续 PlayViewModel 化的请求载体。
 * 访问器保留 Java 风格方法名(vodInfo()/seriesName()/...)以兼容既有 Java 调用。
 */
class PlayRequest private constructor(
    private val vodInfoVal: VodInfo,
    private val seriesNameVal: String,
    private val urlVal: String
) {
    private val sourceKeyVal: String = vodInfoVal.sourceKey
    private val progressKeyVal: String = PlaySessionKeys.progressKey(vodInfoVal, seriesNameVal)
    private val subtitleCacheKeyVal: String = PlaySessionKeys.subtitleCacheKey(vodInfoVal, seriesNameVal)

    fun vodInfo(): VodInfo = vodInfoVal

    fun seriesName(): String = seriesNameVal

    fun url(): String = urlVal

    fun sourceKey(): String = sourceKeyVal

    fun progressKey(): String = progressKeyVal

    fun subtitleCacheKey(): String = subtitleCacheKeyVal

    companion object {
        /** 由当前剧集与选集构造请求上下文 */
        @JvmStatic
        fun of(vodInfo: VodInfo, series: VodInfo.VodSeries): PlayRequest =
            PlayRequest(vodInfo, series.name, series.url)
    }
}

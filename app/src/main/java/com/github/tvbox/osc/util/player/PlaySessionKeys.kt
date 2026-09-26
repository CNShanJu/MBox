package com.github.tvbox.osc.util.player

import com.github.tvbox.osc.bean.VodInfo

/**
 * 播放会话键纯构造(自 PlayFragment 抽取,等价搬移;Java→Kotlin 化,改进.txt §八):
 * - [progressKey]:进度持久化用(与 PlayHistoryRepository 内部再 MD5)
 * - [subtitleCacheKey]:字幕缓存/清理用
 * - [playbackSessionKey]:PlaybackSessions 会话登记用
 * 无 View/Android 依赖,可 JVM 单测;作为后续 PlayViewModel 化的数据键来源。
 */
object PlaySessionKeys {

    /** 播放进度 key = 来源 + 剧id + 线路 + 索引 + 集名(与历史实现拼接一致) */
    @JvmStatic
    fun progressKey(vodInfo: VodInfo, seriesName: String): String =
        vodInfo.sourceKey + vodInfo.id + vodInfo.playFlag + vodInfo.playIndex + seriesName

    /** 字幕缓存 key = 来源-剧id-线路-索引-集名-subt(与历史实现拼接一致) */
    @JvmStatic
    fun subtitleCacheKey(vodInfo: VodInfo, seriesName: String): String =
        vodInfo.sourceKey + "-" + vodInfo.id + "-" + vodInfo.playFlag + "-" + vodInfo.playIndex + "-" + seriesName + "-subt"

    /** playback 会话 key = vod|来源|剧id|线路|索引(与 PlaybackSessions 登记语义一致;sourceKey null 时与 Java 拼接一致输出 "null") */
    @JvmStatic
    fun playbackSessionKey(sourceKey: String?, vodInfo: VodInfo): String =
        "vod|" + (sourceKey ?: "null") + "|" + vodInfo.id + "|" + vodInfo.playFlag + "|" + vodInfo.playIndex
}

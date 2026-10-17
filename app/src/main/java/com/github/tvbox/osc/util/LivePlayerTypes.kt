package com.github.tvbox.osc.util

/**
 * 直播播放器内核类型纯映射(自 LivePlayerManager 抽取,等价搬移;Java→Kotlin 化,改进.txt §八):
 * 详情/设置弹窗的"内核索引"(0..1) ↔ 内核 pl 转换。
 * - pl 0=系统 2=Media3(与 PlayConfig/PlayerHelper 语义一致;原 1=IJK 已移除)
 * 无 Android/JSON 依赖,可 JVM 单测。
 */
object LivePlayerTypes {

    const val PLAYER_SYSTEM = 0
    const val PLAYER_MEDIA3 = 2

    /** 内核索引(设置弹窗用):0=系统,1=Media3 */
    @JvmStatic
    fun typeIndex(playerType: Int): Int = when (playerType) {
        PLAYER_SYSTEM -> 0
        PLAYER_MEDIA3 -> 1
        else -> 0
    }

    /** 由内核索引回填 pl */
    @JvmStatic
    fun playerTypeOf(index: Int): Int = if (index == 1) PLAYER_MEDIA3 else PLAYER_SYSTEM
}

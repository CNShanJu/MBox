package com.github.tvbox.osc.util

/**
 * 直播播放器内核类型纯映射(自 LivePlayerManager 抽取,等价搬移;Java→Kotlin 化,改进.txt §八):
 * 详情/设置弹窗的"内核索引"(0..3) ↔ (内核 pl, 软/硬解码) 双向转换。
 * - pl 0=系统 1=ijk 2=Exo(与 PlayConfig/PlayerHelper 语义一致)
 * - ijk 下解码:硬解码/软解码
 * 无 Android/JSON 依赖,可 JVM 单测。
 */
object LivePlayerTypes {

    const val PLAYER_SYSTEM = 0
    const val PLAYER_IJK = 1
    const val PLAYER_EXO = 2

    /** 内核索引(设置弹窗用):0=系统,1=ijk硬,2=ijk软,3=Exo */
    @JvmStatic
    fun typeIndex(playerType: Int, ijkCodec: String?): Int = when (playerType) {
        PLAYER_SYSTEM -> 0
        PLAYER_IJK -> if ("硬解码" == ijkCodec) 1 else 2
        PLAYER_EXO -> 3
        else -> 0
    }

    /** 由内核索引回填 pl 与解码方式 */
    @JvmStatic
    fun playerTypeOf(index: Int): Int = when (index) {
        1, 2 -> PLAYER_IJK
        3 -> PLAYER_EXO
        else -> PLAYER_SYSTEM
    }

    /** 解码方式:索引 1=硬解码,其余(0 系统/2 ijk 软/3 Exo)统一软 */
    @JvmStatic
    fun ijkCodecOf(index: Int): String = if (index == 1) "硬解码" else "软解码"
}

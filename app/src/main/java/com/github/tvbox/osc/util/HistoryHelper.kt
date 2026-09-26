package com.github.tvbox.osc.util

/**
 * 历史条数档位(Java→Kotlin 试点,改进.txt §八 第五阶段)。
 * 语义与旧 Java 实现逐字等价:越界(含负数)回退首档 30。
 */
object HistoryHelper {

    private val hisNumArray = intArrayOf(30, 50, 70)

    @JvmStatic
    fun getHistoryNumName(index: Int): String = "${getHisNum(index)}条"

    @JvmStatic
    fun getHisNum(index: Int): Int =
        if (index in hisNumArray.indices) hisNumArray[index] else hisNumArray[0]
}

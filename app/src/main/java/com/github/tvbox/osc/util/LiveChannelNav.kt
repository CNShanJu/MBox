package com.github.tvbox.osc.util

import java.util.function.IntPredicate
import java.util.function.IntUnaryOperator

/**
 * 直播频道组/频道导航纯逻辑(自 LiveActivity.getNextChannel 抽取,等价搬移;Java→Kotlin 化,改进.txt §八):
 * 上/下一条频道;越界时按分组回卷;跨组模式(direction 越组)跳过"锁定组"(加密)
 * 且不回当前组。输入为组数、各组可见频道数、各组是否锁定,输出 {组索引, 频道索引}。
 * 无 View/Context 依赖,可 JVM 单测。
 */
object LiveChannelNav {

    /**
     * 计算上一条(direction&lt;0)或下一条(direction&gt;0)频道。
     *
     * @param direction      -1 上一频道 / 1 下一频道
     * @param groupCount     分组总数(回卷上界)
     * @param channelCount   第 i 组的可见频道数(授权后取实际数,未授权通常 0)
     * @param groupLocked    第 i 组是否锁定(加密);跨组跳转时跳过
     * @param currentGroup   当前组索引
     * @param currentChannel 当前组内频道索引
     * @param crossGroup     是否允许跨组(与 currentGroup 同组不可回跳)
     * @return {组索引, 频道索引}
     */
    @JvmStatic
    fun next(
        direction: Int,
        groupCount: Int,
        channelCount: IntUnaryOperator,
        groupLocked: IntPredicate,
        currentGroup: Int,
        currentChannel: Int,
        crossGroup: Boolean
    ): IntArray {
        var group = currentGroup
        var channel = currentChannel
        if (direction > 0) {
            channel++
            if (channel >= channelCount.applyAsInt(group)) {
                channel = 0
                if (crossGroup) {
                    // 跨选分组模式下跳过加密分组,且不回当前组;找不到可用目标(全锁定/单组)停留在当前组
                    var guard = 0
                    var target = -1
                    do {
                        group++
                        if (group >= groupCount) group = 0
                        guard++
                        if (!groupLocked.test(group) && group != currentGroup) {
                            target = group
                            break
                        }
                    } while (guard < groupCount)
                    group = if (target < 0) currentGroup else target
                }
            }
        } else {
            channel--
            if (channel < 0) {
                if (crossGroup) {
                    var guard = 0
                    var target = -1
                    do {
                        group--
                        if (group < 0) group = groupCount - 1
                        guard++
                        if (!groupLocked.test(group) && group != currentGroup) {
                            target = group
                            break
                        }
                    } while (guard < groupCount)
                    group = if (target < 0) currentGroup else target
                }
                channel = Math.max(channelCount.applyAsInt(group) - 1, 0)
            }
        }
        return intArrayOf(group, channel)
    }
}

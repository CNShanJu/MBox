package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.LiveChannelGroup
import com.github.tvbox.osc.bean.LiveChannelItem

/**
 * 直播频道分组密码门禁纯逻辑(自 LiveActivity 抽出,等价搬移;Java→Kotlin 化,改进.txt §八):
 * "该分组是否已输密码确认 / 是否需要弹密码框 / 未授权时可见频道列表"。
 * 无 View/Context 依赖,组数据与确认集合由调用方传入,可 JVM 单测。
 */
object LiveChannelAuth {

    /** 该分组是否已在本次会话输入过正确密码 */
    @JvmStatic
    fun isPasswordConfirmed(confirmedGroupIndexes: List<Int>?, groupIndex: Int): Boolean {
        if (confirmedGroupIndexes == null) return false
        for (confirmedNum in confirmedGroupIndexes) {
            if (confirmedNum == groupIndex) return true
        }
        return false
    }

    /** 进入该分组是否需要先输密码:分组设了密码且本次会话尚未确认 */
    @JvmStatic
    fun needInputPassword(
        groups: List<LiveChannelGroup>,
        confirmedGroupIndexes: List<Int>?,
        groupIndex: Int
    ): Boolean =
        groups[groupIndex].groupPassword.isNotEmpty()
            && !isPasswordConfirmed(confirmedGroupIndexes, groupIndex)

    /** 可见频道列表:未授权(需密码未确认)返回空表;已授权返回调用方给的分组频道(保持引用/可空语义) */
    @JvmStatic
    fun visibleChannels(
        groups: List<LiveChannelGroup>,
        confirmedGroupIndexes: List<Int>?,
        groupIndex: Int,
        authorizedChannels: List<LiveChannelItem>?
    ): List<LiveChannelItem>? =
        if (needInputPassword(groups, confirmedGroupIndexes, groupIndex)) {
            ArrayList()
        } else {
            authorizedChannels
        }

    /** 第一个无密码分组(启动/回退目标);全加密返回 -1 */
    @JvmStatic
    fun firstOpenGroup(groups: List<LiveChannelGroup>?): Int {
        if (groups == null) return -1
        for (g in groups) {
            if (g.groupPassword.isEmpty()) return g.groupIndex
        }
        return -1
    }
}

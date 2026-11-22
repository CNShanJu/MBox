package com.github.tvbox.osc.ui.splash

import com.github.tvbox.osc.calendar.HolidayCatalog

/**
 * 节日日期说明文案（节气 / 农历 / 公历 / 月末），开屏预览页与海报页共用，避免两处口径不一致。
 * 传 null 表示素材没有对应的节日目录条目。
 */
internal fun holidayDateLabel(entry: HolidayCatalog.Entry?): String {
    if (entry == null) return "未匹配节日目录"
    return when {
        entry.dateRule == "solar_term" -> "二十四节气"
        entry.dateRule == "last_day" -> "农历${entry.month}月最后一天"
        entry.type == "lunar" -> "农历${entry.month}月${entry.day}日"
        else -> "${entry.month}月${entry.day}日"
    }
}

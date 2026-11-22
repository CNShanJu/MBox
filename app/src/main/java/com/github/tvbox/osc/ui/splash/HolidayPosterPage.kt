package com.github.tvbox.osc.ui.splash

/**
 * 节日海报页的一页：一个节日对应一张海报。
 *
 * [cachedPath] 为 null 表示还没预取到本地，展示与保存都直接走 [url]。
 */
internal data class HolidayPosterPage(
    val id: String,
    val name: String,
    val dateLabel: String,
    val url: String,
    val cachedPath: String?,
    val anchorX: Float,
    val anchorY: Float
)

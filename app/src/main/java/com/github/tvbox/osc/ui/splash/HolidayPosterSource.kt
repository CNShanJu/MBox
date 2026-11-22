package com.github.tvbox.osc.ui.splash

/**
 * 一张可直接上屏的开屏海报：本地文件 + 这张图自己的裁切锚点。
 *
 * 海报是竖版构图，标题固定在顶部左侧或右侧（每张不同），铺满屏幕时必然要裁掉一条边：
 * 竖屏只裁左右、横屏只裁上下，所以锚点必须跟着图走，不能全局统一。
 */
internal data class HolidayPosterSource(
    val imagePath: String,
    val anchorX: Float,
    val anchorY: Float
)

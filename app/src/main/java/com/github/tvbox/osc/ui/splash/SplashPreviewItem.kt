package com.github.tvbox.osc.ui.splash

/**
 * 开屏预览页的展示模型：素材本身 + 节日日期说明 + 本地缓存状态。
 *
 * [cachedPath] 为 null 表示还没预取到本地，预览页会直接走网络加载，不写入正式缓存目录。
 */
internal data class SplashPreviewItem(
    val id: String,
    val name: String,
    val url: String,
    val dateLabel: String,
    val cachedPath: String?,
    /** 该图铺满屏幕时的裁切锚点（标题在左 0 / 在右 1）。 */
    val anchorX: Float,
    val anchorY: Float
)

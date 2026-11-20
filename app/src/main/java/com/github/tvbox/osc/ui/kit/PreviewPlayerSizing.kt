package com.github.tvbox.osc.ui.kit

import kotlin.math.roundToInt

/**
 * 详情页预览播放器的尺寸策略(纯计算,JVM 可直接测)。
 *
 * 现状:预览播放器是写死的高度(布局 250dp/260dp),手机上勉强、平板上明显偏小;
 * 容器比例与视频比例不一致时还会留出大块黑边(用户口径 2026-11-20)。
 *
 * 策略:
 * - 竖屏:高度 = `max(可用高度 30%, 可用宽度 ÷ 宽高比)`,封顶可用高度 50%。
 *   上下留黑边没关系,**左右不能有黑边** —— 后者意味着容器比"视频按宽铺满所需的高度"还矮;
 * - 横屏:详情页改左右布局,左栏宽度基准 = 可用宽度 50%,按视频比例**只向下**微调(不越过一半)。
 *
 * 竖屏侧的约束只看一个方向:宁可加高(多出上下黑边)也不许压矮(挤出左右黑边)。
 * 横屏侧反过来 —— 右侧才是详情主体,播放器再宽也不能过半屏。
 */
object PreviewPlayerSizing {

    /**
     * 竖屏基准:占可用高度的 30%。
     * <p>
     * 这是**下限**而不是目标值:竖屏播放允许上下留黑边,但**左右绝不能有黑边**
     * (用户口径 2026-11-20)。视频按宽铺满所需的高度 = 可用宽度 ÷ 宽高比,
     * 容器低于这个值就会被逼成"按高铺满",左右立刻出现黑边 —— 所以高度取
     * `max(基准, 该高度)`。
     */
    const val PORTRAIT_BASE_RATIO = 0.30f
    /**
     * 高度上限(占可用高度):竖屏片源(9:16 或接近方形)按宽铺满需要很高的容器,
     * 放那么高会把下方的标题/线路/选集全挤出屏幕,这里封顶一半(用户口径 2026-11-20)。
     * 触发封顶时左右才可能出现黑边,属于极端比例的无奈取舍。
     */
    const val PORTRAIT_MAX_RATIO = 0.50f

    /** 横屏基准:左栏占可用宽度的 50% —— 这也是**上限**,只允许往下微调 */
    const val LANDSCAPE_BASE_RATIO = 0.50f
    /**
     * 横屏微调区间(占可用宽度)。上限钉死在一半:右侧才是详情主体,
     * 宽片源不能把详情挤到半屏以下(用户口径 2026-11-20);下限防止竖屏片源把左栏压太窄。
     */
    const val LANDSCAPE_MIN_RATIO = 0.45f
    const val LANDSCAPE_MAX_RATIO = 0.50f

    /**
     * 竖屏预览播放器高度(px)。
     * <p>
     * 取 `max(30% 可用高度, 可用宽度 ÷ 宽高比)`,并封顶可用高度一半:
     * 基准保证播放器不会太矮,中间一项保证视频按宽铺满、左右没有黑边,
     * 多出来的部分自然变成上下黑边(允许)。
     *
     * @param availableWidth  播放器可用宽度(整屏减系统栏与页面内缩)
     * @param availableHeight 可用高度
     * @param aspect          视频宽高比(宽/高);`<= 0` 表示尺寸未知,直接给基准值
     */
    @JvmStatic
    fun portraitHeight(availableWidth: Int, availableHeight: Int, aspect: Float): Int {
        if (availableWidth <= 0 || availableHeight <= 0) return 0
        val base = availableHeight * PORTRAIT_BASE_RATIO
        if (aspect <= 0f) return base.roundToInt()
        // 视频按宽铺满(= 左右不留黑边)所需的最小容器高度
        val noSideBarHeight = availableWidth / aspect
        // 上限也不许压破 30% 下限(极端窄高可用区时 ceiling 理论上可能反超,这里兜住)
        val ceiling = (availableHeight * PORTRAIT_MAX_RATIO).coerceAtLeast(base)
        return noSideBarHeight.coerceAtLeast(base).coerceAtMost(ceiling).roundToInt()
    }

    /**
     * 横屏左栏(播放器)宽度(px)。左栏高度取整屏可用高度,所以理想宽度由视频比例决定。
     *
     * @param aspect 视频宽高比(宽/高);`<= 0` 表示尺寸未知,直接给基准值
     */
    @JvmStatic
    fun landscapeWidth(availableWidth: Int, availableHeight: Int, aspect: Float): Int {
        if (availableWidth <= 0 || availableHeight <= 0) return 0
        val base = availableWidth * LANDSCAPE_BASE_RATIO
        if (aspect <= 0f) return base.roundToInt()
        // 视频按高铺满左栏时"左右不留黑边"的理想宽度
        val ideal = availableHeight * aspect
        return ideal.coerceIn(
            availableWidth * LANDSCAPE_MIN_RATIO,
            availableWidth * LANDSCAPE_MAX_RATIO
        ).roundToInt()
    }
}

package com.github.tvbox.osc.ui.splash

import androidx.core.graphics.ColorUtils

/** 开屏素材的选择集中在这里；后续节日规则可返回新的图片或视频素材类型。 */
internal sealed interface SplashContent {
    data class Lottie(val assetPath: String) : SplashContent
}

internal object SplashContentSelector {
    fun select(backgroundColor: Int): SplashContent {
        // 暗背景用黑猫白描边，亮背景保留原始黑猫。节日素材规则从这里接入。
        val darkBackground = ColorUtils.calculateLuminance(backgroundColor) < 0.5
        val asset = if (darkBackground) DARK_CAT else LIGHT_CAT
        return SplashContent.Lottie(asset)
    }

    private const val LIGHT_CAT = "splash/mbox_cat_light.json"
    private const val DARK_CAT = "splash/mbox_cat_dark.json"
}

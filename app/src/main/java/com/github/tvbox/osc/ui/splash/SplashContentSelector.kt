package com.github.tvbox.osc.ui.splash

import android.graphics.Color
import androidx.core.graphics.ColorUtils

/** 开屏素材的选择集中在这里；后续节日规则可返回新的图片或视频素材类型。 */
internal sealed interface SplashContent {
    data class Lottie(val assetPath: String, val backgroundColor: Int) : SplashContent
}

internal object SplashContentSelector {
    fun select(themeBackground: Int, hasBackgroundImage: Boolean): SplashContent {
        val darkBackground = ColorUtils.calculateLuminance(themeBackground) < 0.5
        val asset = if (darkBackground) DARK_CAT else LIGHT_CAT
        // 背景图下保留黑色画布，以免图片和开屏素材抢画面；纯色主题直接使用其 bg_body。
        val background = if (hasBackgroundImage && darkBackground) Color.BLACK else themeBackground
        return SplashContent.Lottie(asset, background)
    }

    private const val LIGHT_CAT = "splash/mbox_cat_light.json"
    private const val DARK_CAT = "splash/mbox_cat_dark.json"
}

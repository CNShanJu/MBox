package com.github.tvbox.osc.ui.splash

import androidx.core.graphics.ColorUtils
import com.github.tvbox.osc.calendar.HolidayCatalog
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import java.util.Calendar

/** 开屏素材选择集中在这里；节日目录预留 image/video，待相应播放器接入。 */
internal sealed interface SplashContent {
    data class Lottie(
        val assetPath: String,
        val backgroundColor: Int,
        val backgroundImagePath: String? = null
    ) : SplashContent

    data class Image(
        val imagePath: String,
        val backgroundColor: Int,
        val fallbackLottieAssetPath: String
    ) : SplashContent

    /**
     * 节日/节气海报：整张图铺满屏幕，按该图自己的锚点裁切，保证顶部标题留在画面里。
     * 界面上不叠加任何文字，名称由海报自身承载。
     */
    data class Holiday(
        val imagePath: String,
        val backgroundColor: Int,
        val fallbackLottieAssetPath: String,
        val anchorX: Float,
        val anchorY: Float
    ) : SplashContent
}

internal object SplashContentSelector {
    fun select(
        themeBackground: Int,
        customColor: Int?,
        backgroundImagePath: String?,
        showLottieOnImage: Boolean,
        catalog: HolidayCatalog?,
        date: Calendar,
        holidayPoster: ((HolidayCatalog.Entry) -> HolidayPosterSource?)? = null,
        todayPoster: HolidayPosterSource? = null
    ): SplashContent {
        val backgroundColor = customColor ?: themeBackground
        val darkBackground = ColorUtils.calculateLuminance(backgroundColor) < 0.5
        val defaultAsset = if (darkBackground) DARK_CAT else LIGHT_CAT
        // 图片模式可只显示图片；纯色与跟随主题始终播放 Lottie。
        if (backgroundImagePath != null && !showLottieOnImage) {
            return SplashContent.Image(backgroundImagePath, themeBackground, defaultAsset)
        }
        // 上次预取记下的"今日海报"：不依赖节日目录，首页秒开时也能第一帧出图。
        if (todayPoster != null) {
            LogStore.log(Category.SYSTEM, "节日开屏: 命中今日已缓存海报（快速路径）")
            return SplashContent.Holiday(
                todayPoster.imagePath, themeBackground, defaultAsset,
                todayPoster.anchorX, todayPoster.anchorY
            )
        }
        val matches = catalog?.getMatchingHolidays(
            date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH)
        ).orEmpty()
        for (holiday in matches) {
            // 节日海报优先：只有本地已缓存才会返回，未缓存时按默认开屏处理，绝不为图片等待网络。
            val poster = holidayPoster?.invoke(holiday)
            if (poster != null) {
                LogStore.log(Category.SYSTEM, "节日开屏: 选用 ${holiday.getName()} 的本地海报")
                return SplashContent.Holiday(
                    poster.imagePath, themeBackground, defaultAsset, poster.anchorX, poster.anchorY
                )
            }
            val splash = holiday.getSplash()
            if (!splash.isEnabled()) continue
            if (splash.getMediaType() == "lottie" && splash.getAsset().isNotBlank()) {
                LogStore.log(Category.SYSTEM, "节日开屏: 选用 ${holiday.getName()} 的 Lottie 素材")
                return SplashContent.Lottie(splash.getAsset(), backgroundColor, backgroundImagePath)
            }
            // JSON 已可表达 image/video；当前渲染器只支持 Lottie，配置错配时仍显示默认素材。
            LogStore.log(Category.SYSTEM, "节日开屏: ${holiday.getName()} 的素材类型 " +
                "${splash.getMediaType()} 暂不可播放，使用默认素材")
        }
        return SplashContent.Lottie(defaultAsset, backgroundColor, backgroundImagePath)
    }

    private const val LIGHT_CAT = "splash/mbox_cat_light.json"
    private const val DARK_CAT = "splash/mbox_cat_dark.json"
}

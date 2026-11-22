package com.github.tvbox.osc.ui.splash

import android.graphics.Bitmap
import android.graphics.Matrix
import android.widget.ImageView
import com.github.tvbox.osc.util.BgImageTransform

/**
 * 让竖版海报铺满整个控件，并按该图自己的锚点决定裁掉哪条边。
 *
 * 海报是竖构图、标题固定在顶部某一侧（每张不固定），铺满就必然要裁：
 * 竖屏只裁左右、横屏只裁上下，所以锚点必须按图配置，否则标题会被裁掉。
 * 裁切复用全局背景图那套 [BgImageTransform]（cover 缩放 + 锚点），保证与主题背景图观感一致。
 */
internal object SplashPosterFit {

    fun applyCover(image: ImageView, bitmap: Bitmap, anchorX: Float, anchorY: Float) {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) {
            // 刚设为 VISIBLE 时还没 measure，等下一帧拿到尺寸再算。
            image.post { applyCover(image, bitmap, anchorX, anchorY) }
            return
        }
        val transform = BgImageTransform.resolve(
            bitmap.width, bitmap.height, width, height, 1f, anchorX, anchorY
        )
        image.scaleType = ImageView.ScaleType.MATRIX
        image.imageMatrix = Matrix().apply {
            setScale(transform[0], transform[0])
            postTranslate(transform[1], transform[2])
        }
    }
}

package com.github.tvbox.osc.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import com.github.tvbox.osc.databinding.DialogPosterPreviewBinding
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.DefaultConfig
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import kotlin.math.max
import kotlin.math.min

/**
 * 详情抽屉的海报大图查看（真全屏）。
 *
 * <p>为什么不用 XPopup 的弹窗：XPopup 的窗口高度按整屏算但不做 edge-to-edge，
 * 系统栏隐藏后顶部仍留一条状态栏高度的空白；底部提示文字又按“整屏高度”摆放，
 * 窗口可见区域比它小时提示会被切掉；监听返回又挂在弹窗视图上，
 * 手势返回会先被 Activity 接走，大图关不掉。
 *
 * <p>这里换成一个普通全屏 [Dialog]：窗口自己铺满屏幕（含状态栏/导航栏区域）、
 * 沉浸式隐藏系统栏、返回键与手势返回由 Dialog 自己消费；
 * 提示文字按系统手势/导航栏 insets 抬起来，不再贴边被裁。
 */
class PosterPreviewDialog(context: Context, private val imageUrl: String) :
    Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private lateinit var binding: DialogPosterPreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogPosterPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes = attributes.apply {
                    // 刘海/挖孔区域也要铺满,否则大图顶部还会缺一块
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        applyImmersive()
        applyTipInsets()
        applyContainFit()

        // 轻点图片/空白处关闭;双指缩放交给 PhotoView 自己的手势
        binding.pvPoster.setOnViewTapListener { _, _, _ -> dismiss() }
        binding.pvPoster.setOnOutsidePhotoTapListener { dismiss() }
        binding.posterViewerRoot.setOnClickListener { dismiss() }

        // 等控件完成一次布局再设图片:PhotoView 的基准矩阵就是在设图那一刻按控件尺寸算的,
        // 缩略图内存缓存命中时会同步回调,不等布局就去设图会按 0×0/旧尺寸算出错误矩阵。
        binding.pvPoster.doOnLayout { loadImage() }
    }

    override fun onStart() {
        super.onStart()
        // 窗口尺寸兜底:个别机型/多窗口下 dialog 窗口不会随旋转重排,
        // 每次显示都重新铺满一次,再按当前控件尺寸重新适配。
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        binding.pvPoster.post { fitContain() }
    }

    /** 隐藏系统栏并让内容铺到屏幕四边；具体尺寸由 decorFitsSystemWindows=false 决定。 */
    private fun applyImmersive() {
        val window = window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * 提示文字按导航栏/手势区 insets 抬高。
     * 沉浸态下导航栏 insets 常为 0，手势返回条仍在，故两者取大值。
     */
    private fun applyTipInsets() {
        val tip = binding.tvPosterTip
        val density = context.resources.displayMetrics.density
        val baseBottom = tip.paddingBottom
        val edgeGap = (20 * density).toInt()
        tip.updatePadding(bottom = baseBottom + edgeGap)
        ViewCompat.setOnApplyWindowInsetsListener(binding.posterViewerRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val gesture = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
            tip.updatePadding(bottom = baseBottom + edgeGap + max(bars, gesture))
            insets
        }
    }

    /**
     * 图片默认"完整显示 + 居中"，按 [min] 取宽比/高比里的较小值，且控件尺寸变化后重算。
     *
     * <p>不依赖 PhotoView 自己算的基准矩阵：它只在设置图片那一刻按当时的控件尺寸算一次，
     * 尺寸变了不会重算（横屏后仍按竖屏尺寸缩放，看着像"横向铺满、上下被裁"）。
     * 这里自己算目标矩阵并直接套用，PhotoView 内部算得对不对都不影响结果。
     */
    private fun applyContainFit() {
        val photo = binding.pvPoster
        // 先钉住 FIT_CENTER，避免图片到位到 fitContain 之间闪一帧 1:1 原图
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER)
        var lastWidth = 0
        var lastHeight = 0
        photo.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return@addOnLayoutChangeListener
            if (width == lastWidth && height == lastHeight) return@addOnLayoutChangeListener
            lastWidth = width
            lastHeight = height
            // 等这一帧布局落定后再算，否则拿到的还是旧尺寸
            v.post { fitContain() }
        }
    }

    /**
     * 按当前控件尺寸把图片缩放到"完整显示并居中"。
     *
     * <p>缩放比 = min(控件宽/图宽, 控件高/图高)：图片比控件宽就按宽度算，比控件高就按高度算。
     *
     * <p>PhotoView 的显示矩阵是 supp × base（base 由它内部按自己的尺寸算），
     * 这里先把它内部矩阵清零读出 base，再用 base 的逆左乘我们的目标矩阵：
     * 最终 display = (target × base⁻¹) × base = target，与 PhotoView 的 base 是否正确无关。
     */
    private fun fitContain() {
        val photo = binding.pvPoster
        val drawable = photo.drawable ?: return
        val viewWidth = photo.width.toFloat()
        val viewHeight = photo.height.toFloat()
        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) return

        val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
        val target = Matrix()
        target.postScale(scale, scale)
        target.postTranslate(
            (viewWidth - imageWidth * scale) / 2f,
            (viewHeight - imageHeight * scale) / 2f
        )

        // 清零内部矩阵后读回 base（此时 display 就等于 base）
        photo.setDisplayMatrix(Matrix())
        val base = Matrix()
        photo.getDisplayMatrix(base)
        val inverse = Matrix()
        if (!base.invert(inverse)) return

        val relative = Matrix(target)
        relative.preConcat(inverse) // relative = target × base⁻¹
        photo.setDisplayMatrix(relative)
    }

    private fun loadImage() {
        val real = DefaultConfig.checkReplaceProxy(imageUrl)
        if (TextUtils.isEmpty(real)) {
            binding.pbPoster.visibility = View.GONE
            AppBubble.toast("图片地址为空")
            dismiss()
            return
        }
        binding.pbPoster.visibility = View.VISIBLE
        binding.tvPosterTip.visibility = View.GONE
        try {
            // 不套 fit()/centerInside():看大图要的是原图(Picasso 的 fit() 会按 view 尺寸重采样,
            // 缩放后发糊;centerInside() 没配 resize 还会直接抛 IllegalStateException)。
            // 等比适配由 fitContain() 按 min(宽比, 高比) 自己算。
            Picasso.get()
                .load(real)
                .into(binding.pvPoster, object : Callback {
                    override fun onSuccess() {
                        binding.pbPoster.visibility = View.GONE
                        binding.tvPosterTip.visibility = View.VISIBLE
                        // 图片到位后按当前尺寸适配一次(缓存命中时 drawable 可能早于首次布局到位)
                        binding.pvPoster.post { fitContain() }
                    }

                    override fun onError(e: Exception) {
                        // 加载失败不弹崩溃页:收起进度圈、提示一句,查看器保持可关闭
                        binding.pbPoster.visibility = View.GONE
                        LogStore.log(Category.OTHER, "海报大图加载失败: " + e.javaClass.simpleName)
                        AppBubble.toast("图片加载失败")
                    }
                })
        } catch (th: Throwable) {
            // 兜底:图片库/解码层异常不允许把用户带进崩溃页
            binding.pbPoster.visibility = View.GONE
            LogStore.log(Category.OTHER, "海报大图打开失败: " + th.javaClass.simpleName)
            AppBubble.toast("图片打开失败")
            dismiss()
        }
    }
}

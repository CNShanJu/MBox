package com.github.tvbox.osc.ui.dialog

import android.content.Context
import android.text.TextUtils
import android.view.View
import com.blankj.utilcode.util.ScreenUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.databinding.DialogPosterPreviewBinding
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.DefaultConfig
import com.lxj.xpopup.core.CenterPopupView
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso

/**
 * 详情抽屉的海报大图查看。
 *
 * <p>为什么不用 XPopup 的 `asImageViewer`:那个查看器要自己接管
 * “源图 → 首帧快照 → PhotoView”这条链(自定义 `XPopupImageLoader`,从缩略图的 drawable 复制首帧),
 * 而详情抽屉本身是 view 模式弹窗(挂在 Activity 的 decorView 上),大图再往同一层叠一个
 * view 模式弹窗,层级/系统栏/快照位图都不受控 —— 用户点海报直接崩到崩溃页
 * (2026-11 口径:“详情抽屉的图片,点击会报错”)。
 *
 * <p>这里换成最朴素的一条链:XPopup 的 `asCustom`(全 App 都在用) + 独立窗口 + 自带 PhotoView,
 * 图片只经 Picasso(全 App 唯一图片库)加载,不复制别人的 drawable、不依赖查看器内部实现,
 * 加载失败也只提示、不把异常抛成崩溃。
 */
class PosterPreviewView(context: Context, private val imageUrl: String) : CenterPopupView(context) {

    override fun getImplLayoutId(): Int {
        return R.layout.dialog_poster_preview
    }

    /** 大图必须铺满窗口(居中弹窗默认会按内容宽度收缩) */
    override fun getMaxWidth(): Int = ScreenUtils.getScreenWidth()

    override fun getMaxHeight(): Int = ScreenUtils.getScreenHeight()

    private lateinit var binding: DialogPosterPreviewBinding

    override fun onCreate() {
        super.onCreate()
        binding = DialogPosterPreviewBinding.bind(popupImplView)

        // 轻点图片/空白处关闭;双指缩放交给 PhotoView 自己的手势
        binding.pvPoster.setOnViewTapListener { _, _, _ -> dismiss() }
        binding.pvPoster.setOnOutsidePhotoTapListener { dismiss() }
        binding.posterViewerRoot.setOnClickListener { dismiss() }

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
            // 等比适配交给 PhotoView 自己的 base matrix。
            Picasso.get()
                .load(real)
                .into(binding.pvPoster, object : Callback {
                    override fun onSuccess() {
                        binding.pbPoster.visibility = View.GONE
                        binding.tvPosterTip.visibility = View.VISIBLE
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

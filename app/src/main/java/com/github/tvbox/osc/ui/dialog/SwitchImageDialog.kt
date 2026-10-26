package com.github.tvbox.osc.ui.dialog

import android.content.Context
import androidx.annotation.NonNull
import com.github.tvbox.osc.R
import com.github.tvbox.osc.databinding.DialogSwitchImageBinding

/**
 * 「切换图片」居中弹窗:标题 + 两个纯文字动作项(本地图片 / 缓存图片)。
 *
 * 与 [SelectDialog] 的选择列表不同,这里两项是**动作**而不是选项,所以
 * 不带勾选框、点击即执行对应动作并关闭。居中弹窗壳沿用 [AppCenterPopupView]
 * (主题面板底 `bg_dialog` + 统一圆角档 + 最大高度分档)。
 */
class SwitchImageDialog(@NonNull context: Context, private val listener: OnActionListener?) :
    AppCenterPopupView(context) {

    /** 动作项回调(点击后触发,[index] 0=本地图片、1=缓存图片) */
    interface OnActionListener {
        fun onAction(index: Int)
    }

    override fun getImplLayoutId(): Int = R.layout.dialog_switch_image

    override fun onCreate() {
        super.onCreate()
        val binding = DialogSwitchImageBinding.bind(popupImplView)
        binding.ivClose.setOnClickListener { dismiss() }
        binding.btnLocal.setOnClickListener { onPick(0) }
        binding.btnCache.setOnClickListener { onPick(1) }
    }

    private fun onPick(index: Int) {
        dismissWith {
            listener?.onAction(index)
        }
    }
}

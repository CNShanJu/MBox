package com.github.tvbox.osc.ui.dialog

import android.content.Context
import android.text.TextUtils
import com.blankj.utilcode.util.ClipboardUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.databinding.DialogTextCopyBinding
import com.github.tvbox.osc.util.AppBubble

/**
 * 文本查看/复制弹窗(标题 + 可选正文 + 「关闭」「复制」)。
 *
 * 用途:详情页与详情抽屉的剧名是 maxLines=2 + ellipsize=end,长剧名会被截断,
 * 长按标题弹出本弹窗看全文,并能一键复制(正文本身也可选中,便于只取其中一段)。
 *
 * 弹出方式统一走 [showInHostView]:详情页/播放页是 view 模式弹窗族,
 * 独立窗口会重设状态栏颜色与系统栏,spec 见 [DialogCoordinator.centerInHostView]。
 */
class TextCopyDialog(
    context: Context,
    private val titleText: String,
    private val contentText: String
) : AppCenterPopupView(context) {

    companion object {
        /** 长按截断文本调出全文;空白内容不弹(避免弹出一个空框) */
        @JvmStatic
        fun showInHostView(context: Context?, title: String, content: String) {
            if (context == null || TextUtils.isEmpty(content)) return
            DialogCoordinator.centerInHostView(
                context,
                TextCopyDialog(context, title, content)
            ).show()
        }
    }

    override fun getImplLayoutId(): Int = R.layout.dialog_text_copy

    override fun onCreate() {
        super.onCreate()
        val binding = DialogTextCopyBinding.bind(popupImplView)
        binding.tvCopyTitle.text = if (TextUtils.isEmpty(titleText)) "" else titleText
        binding.tvCopyContent.text = contentText
        binding.btnCopy.setOnClickListener {
            ClipboardUtils.copyText(contentText)
            // Android 13+ 系统自己还会弹一次复制确认;这里保留应用内反馈,与详情抽屉「链接」的复制口径一致
            AppBubble.toast("已复制")
        }
        binding.btnClose.setOnClickListener { dismiss() }
    }
}

package com.github.tvbox.osc.ui.dialog

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import com.github.tvbox.osc.R
import com.github.tvbox.osc.databinding.DialogSourceInfoBinding
import com.github.tvbox.osc.util.OriginStatus
import com.github.tvbox.osc.util.SourceOrigin
import com.github.tvbox.osc.util.SubscriptionSwitcher
import com.lxj.xpopup.XPopup

/**
 * 收藏/历史卡片"来源徽标"点开的来源信息弹窗:展示影片、来源订阅、站点与归属状态；
 * 当来源是另一个仍存在的订阅([OriginStatus.SWITCHABLE])时给出"使用该订阅源"按钮，
 * 点击二次确认后切回并重启。当前订阅([OriginStatus.CURRENT])、来源已删除([OriginStatus.DELETED])
 * 与无法追溯的老记录([OriginStatus.UNKNOWN])都不显示切换按钮。
 */
class SourceInfoDialog(
    context: Context,
    private val vodName: String?,
    private val origin: SourceOrigin
) : AppCenterPopupView(context) {

    private lateinit var binding: DialogSourceInfoBinding

    override fun getImplLayoutId(): Int = R.layout.dialog_source_info

    override fun onCreate() {
        super.onCreate()
        binding = DialogSourceInfoBinding.bind(popupImplView)
        binding.tvVod.text = "剧名：" + if (vodName.isNullOrEmpty()) "（未知影片）" else vodName

        when (origin.status) {
            OriginStatus.CURRENT -> {
                binding.tvSubscription.text = "订阅源：" + (origin.subscriptionName ?: "当前订阅")
                binding.tvSource.text = "站点：" + (origin.sourceName ?: "源未启用")
                setStatus("当前正在使用该订阅", false)
                binding.btnUse.visibility = View.GONE
            }

            OriginStatus.SWITCHABLE -> {
                binding.tvSubscription.text = "订阅源：" + (origin.subscriptionName ?: "其他订阅")
                binding.tvSource.text = "站点：" + (origin.sourceName ?: "源未启用")
                setStatus("当前订阅未包含此源，需切回原来的订阅源方可播放", false)
                binding.btnUse.visibility = View.VISIBLE
            }

            OriginStatus.DELETED -> {
                binding.tvSubscription.text = "订阅源：已删除"
                binding.tvSource.text = "站点：源未启用"
                setStatus("对应的订阅源已删除，无法切回", true)
                binding.btnUse.visibility = View.GONE
            }

            OriginStatus.UNKNOWN -> {
                binding.tvSubscription.text = "订阅源：未知"
                binding.tvSource.text = "站点：源未启用"
                setStatus("无法确定订阅源（升级前的旧记录）", false)
                binding.btnUse.visibility = View.GONE
            }
        }

        binding.btnUse.setOnClickListener {
            val target = origin.targetUrl
            dismiss()
            if (!target.isNullOrEmpty()) {
                ConfirmDialog.show(
                    context, "切换订阅源",
                    "确定切换到「" + (origin.subscriptionName ?: "该订阅") + "」吗？切换后应用将重启。",
                    "切换"
                ) { SubscriptionSwitcher.switchTo(context, target) }
            }
        }
        binding.ivClose.setOnClickListener { dismiss() }
    }

    private fun setStatus(text: String, danger: Boolean) {
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(
                context,
                if (danger) R.color.text_danger else R.color.text_sub_foreground
            )
        )
    }

    companion object {
        @JvmStatic
        fun show(context: Context, vodName: String?, origin: SourceOrigin) {
            XPopup.Builder(context).asCustom(SourceInfoDialog(context, vodName, origin)).show()
        }
    }
}

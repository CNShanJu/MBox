package com.github.tvbox.osc.ui.dialog

import android.content.Context
import com.github.tvbox.osc.R
import com.github.tvbox.osc.databinding.DialogLiveApiBinding
import com.github.tvbox.osc.util.AppBubble

/**
 * 直播源输入弹窗:名称(可选)+ 地址。
 *
 * <p>纯 UI:不读任何全局配置——预填内容、历史地址都由调用方传入,点"确定"只把 (名称, 地址) 交回
 * [onConfirm];落库、要不要把它设成当前直播源,由调用方(订阅页 → LiveSourceCoordinator)决定。
 *
 * @param requireUrl true = 编辑既有的自建直播源,地址不允许清空
 *   ("清空则跟随订阅"只属于新增/设置那个入口,编辑一条已有记录时清空没有意义)
 */
class LiveApiDialog(
    context: Context,
    private val title: String = "直播源",
    private val confirmText: String = "设 置",
    private val initialName: String = "",
    private val initialUrl: String = "",
    history: List<String> = emptyList(),
    private val requireUrl: Boolean = false,
    private val onDeleteHistory: (url: String) -> Boolean,
    private val onConfirm: (name: String, url: String) -> Unit
) : AppCenterPopupView(context) {

    private lateinit var binding: DialogLiveApiBinding
    private val historySnapshot = LiveHistorySnapshot(history)

    override fun getImplLayoutId(): Int = R.layout.dialog_live_api

    override fun onCreate() {
        super.onCreate()
        binding = DialogLiveApiBinding.bind(popupImplView)
        binding.tvTitle.text = title
        binding.btnConfirm.text = confirmText
        binding.etName.setText(initialName)
        updateUrl(initialUrl)

        binding.ivHistory.setOnClickListener {
            val visibleHistory = historySnapshot.entries(binding.etUrl.text.toString())
            if (visibleHistory.isEmpty()) {
                AppBubble.toast("暂无历史记录")
                return@setOnClickListener
            }
            // 历史抽屉只接收可见快照，删除只回传目标地址，不回写筛选后的列表。
            DialogCoordinator.bottom(
                context,
                ApiHistoryDialog(context, visibleHistory, { url ->
                    historySnapshot.deleteConfirmed(url, onDeleteHistory)
                }) { text -> updateUrl(text) },
                0
            ).show()
        }

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnConfirm.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isEmpty() && requireUrl) {
                AppBubble.toast("请输入直播源地址")
                return@setOnClickListener
            }
            onConfirm(binding.etName.text.toString().trim(), url)
            dismiss()
        }
    }

    private fun updateUrl(text: String) {
        binding.etUrl.setText(text)
        binding.etUrl.setSelection(text.length)
    }
}

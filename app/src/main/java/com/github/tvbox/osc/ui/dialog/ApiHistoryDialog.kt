package com.github.tvbox.osc.ui.dialog

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.tvbox.osc.R
import com.github.tvbox.osc.databinding.DialogTitleListBinding
import com.github.tvbox.osc.ui.adapter.TitleWithDelAdapter
import com.github.tvbox.osc.ui.kit.LinearSpacingItemDecoration

class ApiHistoryDialog(
    context: Context,
    history: List<String>,
    private val onDelete: (url: String) -> Boolean,
    private val onSelect: (url: String) -> Unit
) : AppBottomPopupView(context) {

    private val historySnapshot = LiveHistorySnapshot(history)

    override fun getImplLayoutId(): Int = R.layout.dialog_title_list

    override fun onCreate() {
        super.onCreate()
        val binding = DialogTitleListBinding.bind(popupImplView)
        binding.title.text = "历史直播源"
        binding.ivUseTip.setOnClickListener {
            ConfirmDialog.show(context, "使用帮助",
                "这里显示手动添加过的直播源,最多20条。订阅自带直播源请到「订阅管理 → 直播源」查看。", "知道了", null)
        }
        binding.rv.layoutManager = LinearLayoutManager(context)
        binding.rv.addItemDecoration(LinearSpacingItemDecoration(20, true))
        val adapter = TitleWithDelAdapter()
        binding.rv.adapter = adapter
        adapter.setNewData(historySnapshot.entries().toMutableList())
        adapter.setOnItemChildClickListener { _, view, position ->
            val url = adapter.data.getOrNull(position) ?: return@setOnItemChildClickListener
            if (view.id == R.id.tvDel) {
                ConfirmDialog.showDanger(context, "删除直播源", "确定从历史直播源中删除这条地址吗？", "删除") {
                    if (historySnapshot.deleteConfirmed(url, onDelete)) {
                        val index = adapter.data.indexOf(url)
                        if (index >= 0) adapter.remove(index)
                    }
                }
            } else {
                onSelect(url)
                dismiss()
            }
        }
    }
}

internal class LiveHistorySnapshot(history: List<String>) {
    private val history = history.toMutableList()

    fun entries(excludedUrl: String? = null): List<String> = history.filterNot { it == excludedUrl }

    fun deleteConfirmed(url: String, onDelete: (String) -> Boolean): Boolean {
        if (!history.contains(url) || !onDelete(url)) return false
        history.remove(url)
        return true
    }
}

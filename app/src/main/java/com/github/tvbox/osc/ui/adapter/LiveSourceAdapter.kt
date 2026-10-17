package com.github.tvbox.osc.ui.adapter

import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.github.tvbox.osc.R
import com.github.tvbox.osc.util.LiveSourceEntries

/**
 * 订阅管理「直播源」页的条目列,与视频源的 [SubscriptionAdapter] **共用同一布局**
 * (`R.layout.item_subscription`,两页样式统一、改样式只改那一处)。
 *
 * 两类条目(见 [LiveSourceEntries]):
 *  - 订阅导入(fromSubscription 非空):显示「来自:<订阅名>」徽标 —— 跟着订阅走,不可删;
 *  - 用户自建:无徽标,可经长按多选删除。
 * 订阅里的"内嵌频道分组"(没有单独地址)只作说明:隐藏勾选圈、地址行写明不能单独指定。
 *
 * 删除统一走长按「多选」([setSelectMode]) + 底部操作栏(与本地视频页同款
 * [com.github.tvbox.osc.ui.kit.SelectActionBar]):**只有用户自建的能勾**(订阅导入的删不了,勾选圈一并收起)。
 */
class LiveSourceAdapter : BaseQuickAdapter<LiveSourceEntries.Entry, BaseViewHolder>(R.layout.item_subscription) {

    /** 长按多选(删除)模式 */
    var selectMode = false
        private set

    private val selected = LinkedHashSet<String>()

    /** 勾选数变化(操作栏"删除"键的可用态看它) */
    var onSelectCountListener: ((Int) -> Unit)? = null

    fun setSelectMode(on: Boolean) {
        selectMode = on
        selected.clear()
        notifyDataSetChanged()
        onSelectCountListener?.invoke(selected.size)
    }

    /** 该条能不能被勾(多选删除只针对用户自建的非内嵌条目) */
    fun selectable(item: LiveSourceEntries.Entry?): Boolean =
        item != null && item.removable() && !item.embedded()

    fun toggleSelection(item: LiveSourceEntries.Entry?): Boolean {
        if (!selectable(item)) return false
        val url = item!!.url
        val nowSelected = if (!selected.remove(url)) {
            selected.add(url)
            true
        } else {
            false
        }
        notifyDataSetChanged()
        onSelectCountListener?.invoke(selected.size)
        return nowSelected
    }

    fun setSelectAll(all: Boolean) {
        selected.clear()
        if (all) {
            for (entry in data) {
                if (selectable(entry)) selected.add(entry.url)
            }
        }
        notifyDataSetChanged()
        onSelectCountListener?.invoke(selected.size)
    }

    fun isAllSelected(): Boolean {
        val candidates = data.filter { selectable(it) }
        if (candidates.isEmpty()) return false
        return candidates.all { selected.contains(it.url) }
    }

    fun selectedCount(): Int = selected.size

    /** 按列表顺序返回勾选的用户自建条目 */
    fun selection(): List<LiveSourceEntries.Entry> = data.filter { it.removable() && selected.contains(it.url) }

    override fun convert(holder: BaseViewHolder, item: LiveSourceEntries.Entry) {
        val embedded = item.embedded()
        val canSelect = selectable(item)
        holder.setText(R.id.tv_name, item.name)
        holder.setText(
            R.id.tv_url,
            if (embedded) "订阅自带的频道分组(不能单独指定为直播源)" else item.url
        )

        // 勾选圈:普通态 = "当前生效的那条";多选态 = "勾选要删的那条"(不可勾的行收起来)
        holder.setGone(R.id.cb, !embedded && (!selectMode || canSelect))
        holder.setChecked(
            R.id.cb,
            if (selectMode) canSelect && selected.contains(item.url)
            else !embedded && item.checked
        )

        // 「来自:订阅名」徽标:只有订阅导入的才有
        val from = item.fromSubscription
        val hasFrom = !from.isNullOrEmpty()
        holder.setGone(R.id.tv_from, hasFrom)
        if (hasFrom) holder.setText(R.id.tv_from, "来自: $from")

        // 置顶图钉是视频源专用,直播源恒隐藏
        holder.setGone(R.id.iv_pushpin, false)
    }
}

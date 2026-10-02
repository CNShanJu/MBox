package com.github.tvbox.osc.ui.adapter

import android.view.View
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.github.tvbox.osc.R
import com.github.tvbox.osc.util.LiveSourceEntries

/**
 * 订阅管理「直播源」页的条目列(与视频源的 [SubscriptionAdapter] 同构)。
 *
 * 两类条目(见 [LiveSourceEntries]):
 *  - 订阅导入(fromSubscription 非空):显示「来自:<订阅名>」徽标,**不给删除键** —— 跟着订阅走;
 *  - 用户自建:无徽标,带删除键。
 * 订阅里的"内嵌频道分组"(没有单独地址)只作说明:隐藏勾选圈、地址行写明不能单独指定。
 *
 * 长按多选([setSelectMode]):**只有用户自建的能勾**(订阅导入的删不了,勾选圈一并收起),
 * 行上的 ✕ 在多选态收起,删除统一走操作栏那条(与本地视频页同款 [com.github.tvbox.osc.ui.kit.SelectActionBar])。
 */
class LiveSourceAdapter : BaseQuickAdapter<LiveSourceEntries.Entry, BaseViewHolder>(R.layout.item_live_source) {

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

        // 删除键:多选态收起(改用操作栏的"删除");其余只有用户自建的才有
        holder.setGone(R.id.iv_del, !selectMode && item.removable())
        holder.addOnClickListener(R.id.iv_del)

        // ✕ 必须自己吃掉长按:Android 在"没有长按监听"时,长按抬手依旧走 click,
        // 于是长按落在 ✕ 上 = 直接删除(用户口径"为啥我长按会删掉我的直播源")。
        // 行本身的长按由宿主设菜单监听(设了就是 longClickable,抬手不会变成点击)。
        holder.getView<View>(R.id.iv_del).setOnLongClickListener { true }
    }
}

package com.github.tvbox.osc.ui.adapter

import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Subscription

/**
 * 订阅(视频源)列表条目,与直播源 [LiveSourceAdapter] **共用同一布局**
 * (`R.layout.item_subscription`,两页样式统一、改样式只改那一处)。
 *
 * 「本地包」徽标:由本地包(ZIP 或已解压后选包内清单)导入的订阅带一枚与直播源页「来自:X」同款的徽标,
 * 让用户看得出这条不是自己手填的地址,而是包里带的。
 *
 * @param mode 勾选语义(点条目干什么),见 [Mode]
 */
class SubscriptionAdapter : BaseQuickAdapter<Subscription, BaseViewHolder>(R.layout.item_subscription) {

    /**
     * 勾选语义(点条目干什么):
     *  - [NORMAL]:点条目 = 切换"当前订阅"(单选);
     *  - [EXPORT]:点条目 = 勾选**要导出**的订阅(多选,标题栏「导出」进入);
     *  - [DELETE]:点条目 = 勾选**要删除**的订阅(多选,长按菜单「多选」进入)。
     *
     * 两种多选态的列表呈现完全一样(复选框 + 收起置顶标记),只是动作不同。
     */
    enum class Mode { NORMAL, EXPORT, DELETE }

    /** 多选勾选数变化(操作栏"删除"键的可用态看它) */
    fun interface OnSelectCountListener {
        fun onSelectCount(count: Int)
    }

    private var currentMode = Mode.NORMAL

    /** 多选态下已勾选的地址(与列表顺序解耦,列表重排也不丢) */
    private val selected = LinkedHashSet<String>()
    private var selectCountListener: OnSelectCountListener? = null

    override fun convert(helper: BaseViewHolder, item: Subscription) {
        helper.setText(R.id.tv_name, item.name)
            .setText(R.id.tv_url, item.url)

        val fromPackage = Subscription.ORIGIN_PACKAGE == item.origin
        helper.setGone(R.id.tv_from, fromPackage)
        if (fromPackage) helper.setText(R.id.tv_from, "本地包")

        if (currentMode != Mode.NORMAL) {
            // 多选态(导出/删除):复选框表示"选中",置顶标记先收起来
            // (避免与"当前订阅"那颗勾混为一谈)
            helper.setChecked(R.id.cb, item.url != null && selected.contains(item.url))
                .setGone(R.id.iv_pushpin, false)
            return
        }
        helper.setChecked(R.id.cb, item.isChecked())
            .setGone(R.id.iv_pushpin, item.isTop())
    }

    // ------------------------------------------------------------------
    // 多选(导出 / 删除共用同一套勾选)
    // ------------------------------------------------------------------

    fun getMode(): Mode = currentMode

    /** 是否处于"导出选择"态(导出条的显隐与文案看它) */
    val isExportMode: Boolean get() = currentMode == Mode.EXPORT

    /** 是否处于任意多选态 */
    fun inSelectMode(): Boolean = currentMode != Mode.NORMAL

    /** 切换勾选语义(进入/退出多选:一律清空上次勾选) */
    fun setMode(newMode: Mode?) {
        currentMode = newMode ?: Mode.NORMAL
        selected.clear()
        notifyDataSetChanged()
        notifySelectCount()
    }

    /** 兼容旧调用(导出流程用) */
    fun setExportMode(on: Boolean) = setMode(if (on) Mode.EXPORT else Mode.NORMAL)

    fun setOnSelectCountListener(listener: OnSelectCountListener?) {
        selectCountListener = listener
    }

    private fun notifySelectCount() {
        selectCountListener?.onSelectCount(selected.size)
    }

    /** 勾选/取消一条;返回该条当前是否被勾选 */
    fun toggleSelection(url: String?): Boolean {
        if (url == null) return false
        val nowSelected = if (!selected.remove(url)) {
            selected.add(url)
            true
        } else {
            false
        }
        notifyDataSetChanged()
        notifySelectCount()
        return nowSelected
    }

    fun selectedCount(): Int = selected.size

    /** 全选/全不选(按当前列表数据) */
    fun setSelectAll(all: Boolean) {
        selected.clear()
        if (all) {
            for (s in data) {
                val url = s?.url ?: continue
                selected.add(url)
            }
        }
        notifyDataSetChanged()
        notifySelectCount()
    }

    /** 是否已全选(列表非空时才有意义) */
    val isAllSelected: Boolean
        get() {
            val items = data
            if (items == null || items.isEmpty()) return false
            for (s in items) {
                val url = s?.url ?: return false
                if (!selected.contains(url)) return false
            }
            return true
        }

    /** 按列表顺序返回勾选的订阅(导出抓取顺序 / 删除顺序 = 列表顺序) */
    fun selection(): List<Subscription> {
        val out = ArrayList<Subscription>()
        for (s in data) {
            val url = s?.url ?: continue
            if (selected.contains(url)) out.add(s)
        }
        return out
    }

    /**
     * 刷新列表时去重 + 排序(**就地改传入的列表**,与既有行为一致:调用方传的就是页面状态那份)。
     */
    override fun setNewData(data: MutableList<Subscription>?) {
        if (data != null) {
            // 去除 url 重复的订阅
            var i = 0
            while (i < data.size) {
                var j = i + 1
                while (j < data.size) {
                    if (data[i].url == data[j].url) {
                        data.removeAt(j)
                        j--
                    }
                    j++
                }
                i++
            }
            data.sortWith(TOP_FIRST)
        }
        super.setNewData(data)
    }

    companion object {
        /** 置顶优先,其次当前启用的那条 */
        private val TOP_FIRST = Comparator<Subscription> { s1, s2 ->
            when {
                s1.isTop() && !s2.isTop() -> -1
                !s1.isTop() && s2.isTop() -> 1
                s1.isChecked() && !s2.isChecked() -> -1
                !s1.isChecked() && s2.isChecked() -> 1
                else -> 0
            }
        }
    }
}

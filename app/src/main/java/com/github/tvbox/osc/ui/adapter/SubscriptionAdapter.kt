package com.github.tvbox.osc.ui.adapter

import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.chad.library.adapter.base.util.MultiTypeDelegate
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Subscription
import com.github.tvbox.osc.util.subscription.SubscriptionRow
import com.github.tvbox.osc.util.subscription.buildSubscriptionRows
import com.github.tvbox.osc.util.subscription.groupKeyOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 订阅(视频源)列表:平铺条目 + 批次「手风琴」组头,两种行型由 [MultiTypeDelegate] 分发
 * (组头布局 [R.layout.item_subscription_group])。直播源页另有 [LiveSourceAdapter]。
 *
 * 「本地」徽标:由本地包(ZIP 或已解压后选包内清单)导入的订阅带一枚徽标,
 * 让用户看得出这条不是自己手填的地址,而是包里带的。
 *
 * 批次(一次多仓/批量导入):同批条目收在一个组头下,点组头展开/收起,
 * 组上的「本地仓」/长按出批次菜单(更新此批/重命名此批/删除整批);收起时组内条目不占行、不进勾选。
 * 多选态(导出/删除):组头换成与条目同形的勾选框,点组头 = 勾选/取消整批。
 * 展开时组内条目整行左缩进,与组头文字左缘对齐,标明"哪几条属于哪一批"。
 *
 * @param mode 勾选语义(点条目干什么),见 [Mode]
 */
class SubscriptionAdapter : BaseQuickAdapter<SubscriptionRow, BaseViewHolder>(R.layout.item_subscription) {

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

    /** 批次组头上的「本地仓」被点开(页面弹批次菜单;anchor = 整行 itemView,由页面在行内解析气泡锚点) */
    var onGroupManageListener: ((batchKey: String, anchor: android.view.View) -> Unit)? = null

    private var currentMode = Mode.NORMAL

    /** 多选态下已勾选的地址(与列表顺序解耦,列表重排也不丢) */
    private val selected = LinkedHashSet<String>()
    private var selectCountListener: OnSelectCountListener? = null

    /** 收起的批次键(重建行时保留;收起的组只留组头,组内条目不占行、不进勾选) */
    private val collapsedKeys = LinkedHashSet<String>()
    /** 首次建行是否已按「默认收起」初始化过收起态(用户手动展开/收起后不再自动重算) */
    private var collapsedInitialized = false
    /** 最近一次 [setSubscriptions] 的源列表(展开收起/管理菜单都按它重建行) */
    private var sourceSubscriptions: List<Subscription> = emptyList()

    init {
        setMultiTypeDelegate(object : MultiTypeDelegate<SubscriptionRow>() {
            override fun getItemType(row: SubscriptionRow): Int =
                if (row is SubscriptionRow.GroupHeader) TYPE_GROUP else TYPE_ITEM
        })
        multiTypeDelegate.registerItemType(TYPE_ITEM, R.layout.item_subscription)
        multiTypeDelegate.registerItemType(TYPE_GROUP, R.layout.item_subscription_group)
    }

    override fun convert(helper: BaseViewHolder, item: SubscriptionRow) {
        when (item) {
            is SubscriptionRow.GroupHeader -> convertGroup(helper, item)
            is SubscriptionRow.Item -> convertItem(helper, item.subscription)
        }
    }

    private fun convertGroup(helper: BaseViewHolder, header: SubscriptionRow.GroupHeader) {
        helper.setText(R.id.tv_batch_name, header.batchName)
        val meta = buildString {
            append(header.count).append(" 个源")
            if (header.batchTime > 0L) append(" · ").append(TIME_FORMAT.format(Date(header.batchTime)))
        }
        helper.setText(R.id.tv_batch_meta, meta)
        // 「使用中」:组内任一条目是当前生效订阅(实时读,勾选切换后不显旧)
        val using = sourceSubscriptions.any { it.isChecked && groupKeyOf(it) == header.batchKey }
        helper.setGone(R.id.tv_using, using)
        // 箭头:展开 = 朝下(0°);收起 = 朝右(270°)——标准手风琴语义,
        // 收起时指右表示"可向右/向下展开",不会误读成"往上翻"
        helper.getView<android.view.View>(R.id.iv_arrow).rotation = if (header.expanded) 0f else 270f
        // 「本地仓」独立可点:不抢占整行的展开/收起。
        // 回传整行(itemView)而不是按钮本身——按钮只是行内的一个 TextView,
        // 页面取气泡锚点时要在行内找 tv_batch_name;传按钮会把兄弟节点挡在外面(旧 bug:菜单点了不弹)。
        helper.getView<android.view.View>(R.id.tv_manage).setOnClickListener {
            onGroupManageListener?.invoke(header.batchKey, helper.itemView)
        }
        // 多选态(导出/删除):组头换成勾选框(与条目 cb 同形),点组头 = 勾选/取消整批;
        // 箭头(展开收起)与「本地仓」菜单入口在多选态一律隐藏——想展开/管理先退出多选。
        // 注意 BRVAH 的 setGone(viewId, visible) 第二个参数是"是否可见"(不是"是否隐藏")!
        if (currentMode != Mode.NORMAL) {
            helper.setGone(R.id.iv_arrow, false)
            helper.setGone(R.id.tv_manage, false)
            helper.setGone(R.id.cb_group, true)
            helper.setChecked(R.id.cb_group, isGroupFullySelected(header.batchKey))
        } else {
            helper.setGone(R.id.iv_arrow, true)
            helper.setGone(R.id.tv_manage, true)
            helper.setGone(R.id.cb_group, false)
        }
    }

    private fun convertItem(helper: BaseViewHolder, item: Subscription) {
        // 批次内的条目整行左缩进(勾选圈+文字一起,与组头文字左缘对齐),
        // 展开后一眼看出"哪几条属于哪一批";无批次的老数据/平铺条目保持原有左边距。
        // 注意:RV 复用会保留上次的 padding,两个分支都必须显式置,不能只改分支内的。
        val indentDp = if (groupKeyOf(item) != null) MEMBER_INDENT_DP else ROW_PADDING_DP
        val density = helper.itemView.resources.displayMetrics.density
        val startPx = (indentDp * density + 0.5f).toInt()
        val row = helper.itemView
        row.setPaddingRelative(startPx, row.paddingTop, row.paddingEnd, row.paddingBottom)

        helper.setText(R.id.tv_name, item.name)
            .setText(R.id.tv_url, item.url)

        val fromPackage = Subscription.ORIGIN_PACKAGE == item.origin
        helper.setGone(R.id.tv_from, fromPackage)
        if (fromPackage) helper.setText(R.id.tv_from, "本地")

        if (currentMode != Mode.NORMAL) {
            // 多选态(导出/删除):复选框表示"选中",置顶标记先收起来
            // (避免与"当前订阅"那颗勾混为一谈)
            helper.setChecked(R.id.cb, item.url != null && selected.contains(item.url))
                .setGone(R.id.iv_pushpin, false)
            return
        }
        helper.setChecked(R.id.cb, item.isChecked)
            .setGone(R.id.iv_pushpin, item.isTop)
    }

    // ------------------------------------------------------------------
    // 数据(行模型):去重 + 排序 + 建行;收起态跨刷新保留
    // ------------------------------------------------------------------

    /**
     * 页面刷新入口:去重 + 排序(**就地改传入的列表**,与既有行为一致:调用方传的就是页面状态那份),
     * 再按批次建行(组头占每组第一个成员的位置)。
     */
    fun setSubscriptions(list: MutableList<Subscription>) {
        // 去除 url 重复的订阅
        var i = 0
        while (i < list.size) {
            var j = i + 1
            while (j < list.size) {
                if (list[i].url == list[j].url) {
                    list.removeAt(j)
                    j--
                }
                j++
            }
            i++
        }
        list.sortWith(TOP_FIRST)
        sourceSubscriptions = list.toList()
        // 首次建行:默认收起所有批次组,只展开「当前选中订阅」所在的那个组。
        // 用户手动展开/收起过后(collapsedInitialized 置位)不再自动重算,保留其操作。
        if (!collapsedInitialized) {
            collapsedKeys.clear()
            val checkedGroup = list.firstOrNull { it.isChecked }?.let { groupKeyOf(it) }
            for (sub in list) {
                val key = groupKeyOf(sub) ?: continue
                if (key != checkedGroup) collapsedKeys.add(key)
            }
            collapsedInitialized = true
        }
        super.setNewData(buildSubscriptionRows(list, collapsedKeys))
    }

    /** 行数据(组头 + 条目);收起批次只含组头 */
    fun rows(): List<SubscriptionRow> = data.orEmpty()

    fun rowAt(position: Int): SubscriptionRow? = data?.getOrNull(position)

    /** 展开/收起一个批次(重建行,源列表不变) */
    fun toggleGroup(batchKey: String) {
        if (!collapsedKeys.remove(batchKey)) collapsedKeys.add(batchKey)
        super.setNewData(buildSubscriptionRows(sourceSubscriptions, collapsedKeys))
    }

    /** 多选态:勾选/取消整批(组内所有成员;勾选记录按 url,与组是否展开无关) */
    fun toggleGroupSelection(batchKey: String) {
        val members = batchMembers(batchKey)
        if (members.isEmpty()) return
        val allSelected = isGroupFullySelected(batchKey)
        for (m in members) {
            val url = m.url ?: continue
            if (allSelected) selected.remove(url) else selected.add(url)
        }
        notifyDataSetChanged()
        notifySelectCount()
    }

    /** 组内成员是否全部已勾选(组头勾选框的回显态) */
    private fun isGroupFullySelected(batchKey: String): Boolean {
        val members = batchMembers(batchKey)
        return members.isNotEmpty() && members.all { it.url != null && selected.contains(it.url) }
    }

    /** 批次内的订阅(组头菜单/删除整批按它取目标) */
    fun batchMembers(batchKey: String): List<Subscription> =
        sourceSubscriptions.filter { groupKeyOf(it) == batchKey }

    /** 当前可见的条目行(勾选/导出只遍历它们;收起批次天然不在内) */
    private fun itemRows(): List<Subscription> =
        data.orEmpty().filterIsInstance<SubscriptionRow.Item>().map { it.subscription }

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

    /** 全选/全不选(只覆盖当前可见条目;收起批次里的不参与) */
    fun setSelectAll(all: Boolean) {
        selected.clear()
        if (all) {
            for (s in itemRows()) {
                val url = s.url ?: continue
                selected.add(url)
            }
        }
        notifyDataSetChanged()
        notifySelectCount()
    }

    /** 是否已全选(列表非空时才有意义) */
    val isAllSelected: Boolean
        get() {
            val items = itemRows()
            if (items.isEmpty()) return false
            for (s in items) {
                val url = s.url ?: return false
                if (!selected.contains(url)) return false
            }
            return true
        }

    /**
     * 勾选的订阅(导出抓取顺序 / 删除顺序 = 列表顺序)。
     * 按源列表过滤而不是可见行:组头整批勾选会勾上收起组里的成员,
     * 那些条目不占行但仍要参与删除/导出。
     */
    fun selection(): List<Subscription> =
        sourceSubscriptions.filter { selected.contains(it.url) }

    companion object {
        private const val TYPE_ITEM = 1
        private const val TYPE_GROUP = 2
        private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        /** item_subscription 根布局的左右边距(与布局里写死的 padding 保持一致,改布局要同步) */
        private const val ROW_PADDING_DP = 10f

        /**
         * 批次内条目的左缩进 = 组头 paddingHorizontal(10dp) + 箭头宽(24dp) + 文字间距(8dp),
         * 让组内条目整体比组头文字左移一"级",表达批次归属。
         */
        private const val MEMBER_INDENT_DP = 42f

        /** 置顶优先,其次当前启用的那条 */
        private val TOP_FIRST = Comparator<Subscription> { s1, s2 ->
            when {
                s1.isTop && !s2.isTop -> -1
                !s1.isTop && s2.isTop -> 1
                s1.isChecked && !s2.isChecked -> -1
                !s1.isChecked && s2.isChecked -> 1
                else -> 0
            }
        }
    }
}

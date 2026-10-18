package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.ClipboardUtils
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.Source
import com.github.tvbox.osc.bean.Subscription
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.databinding.ActivitySubscriptionBinding
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.ui.adapter.LiveSourceAdapter
import com.github.tvbox.osc.ui.adapter.SubscriptionAdapter
import com.github.tvbox.osc.ui.dialog.AttachActionDialog
import com.github.tvbox.osc.ui.dialog.ChooseSourceDialog
import com.github.tvbox.osc.ui.dialog.ConfirmDialog
import com.github.tvbox.osc.ui.dialog.DialogCoordinator
import com.github.tvbox.osc.ui.dialog.JsonImportDialog
import com.github.tvbox.osc.ui.dialog.LiveApiDialog
import com.github.tvbox.osc.ui.dialog.SubsTipDialog
import com.github.tvbox.osc.ui.dialog.SubsciptionDialog
import com.github.tvbox.osc.ui.dialog.SubsciptionDialog.OnSubsciptionListener
import com.github.tvbox.osc.ui.kit.SelectActionBar
import com.github.tvbox.osc.ui.kit.TabPageAnimator
import com.github.tvbox.osc.ui.kit.TabSwipeHelper
import com.github.tvbox.osc.ui.state.SubscriptionPageState
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.HttpClient
import com.github.tvbox.osc.util.LiveConfig
import com.github.tvbox.osc.util.LiveSourceEntries
import com.github.tvbox.osc.util.LiveSourceExporter
import com.github.tvbox.osc.util.LocalPackageImporter
import com.github.tvbox.osc.util.SubscriptionConfig
import com.github.tvbox.osc.util.SubscriptionLibrary
import com.github.tvbox.osc.util.subscription.LiveSourceCoordinator
import com.github.tvbox.osc.util.subscription.SubscriptionExportCoordinator
import com.github.tvbox.osc.util.subscription.SubscriptionExportHost
import com.github.tvbox.osc.util.subscription.SubscriptionImportCoordinator
import com.github.tvbox.osc.util.subscription.SubscriptionImportHost
import com.github.tvbox.osc.util.subscription.SubscriptionLiveHost
import com.lxj.xpopup.XPopup
import java.io.File

/**
 * 订阅管理页(只绑视图与生命周期;导入/直播源/导出/文件操作全部在协调器与工具类里):
 * 订阅导入 → [SubscriptionImportCoordinator];直播源列表与切换 → [LiveSourceCoordinator];
 * 导出 → [SubscriptionExportCoordinator];内部订阅文件 → [SubscriptionLibrary];
 * 本地包(ZIP 解包/清理)→ [LocalPackageImporter]。
 */
class SubscriptionActivity : BaseVbActivity<ActivitySubscriptionBinding>(),
    SubscriptionImportHost, SubscriptionLiveHost, SubscriptionExportHost {

    companion object {
        /** 调试包数据域提示只弹一次(进程内),避免反复打扰 */
        private var debugDomainWarned = false
    }

    private val state = SubscriptionPageState(
        SubscriptionConfig.getApiUrl(),
        SubscriptionConfig.getSubscriptions()
    )
    /**
     * 协调器持有 Context,必须延迟到 Activity 完成 attach 之后再取 `applicationContext`:
     * 属性初始化器在**构造期**执行,那时 ContextWrapper 的 base 还是 null,
     * 直接写 `applicationContext` 会抛 NPE 使 Activity 无法实例化(Android 16 真机必现)。
     * 统一用 by lazy 延迟到首次使用(页面交互阶段),与 MainActivity / CacheManagementActivity 同写法。
     */
    private val importCoordinator by lazy {
        SubscriptionImportCoordinator(applicationContext, state, this@SubscriptionActivity)
    }
    /** 不持有 Context、构造期无副作用,可即时创建 */
    private val liveCoordinator = LiveSourceCoordinator(state, this)
    private val exportCoordinator by lazy {
        SubscriptionExportCoordinator(applicationContext, this@SubscriptionActivity)
    }

    private var mSubscriptionAdapter = SubscriptionAdapter()
    /** 「直播源」页的列表适配器(订阅导入的 + 用户自建的) */
    private var mLiveSourceAdapter = LiveSourceAdapter()
    /** 底部多选操作栏(公共组件)上的"删除"键:有没有勾选项决定它的可用态 */
    private lateinit var mDeleteAction: TextView
    /** 当前多选作用于哪个列表:true=直播源页,false=订阅源页 */
    private var selectOnLive = false

    override fun init() {

        mBinding.rv.setAdapter(mSubscriptionAdapter)
        setupTabs()
        mBinding.rvLive.setAdapter(mLiveSourceAdapter)
        mLiveSourceAdapter.setOnItemClickListener { _, _, position ->
            val entry = mLiveSourceAdapter.data.getOrNull(position) ?: return@setOnItemClickListener
            if (mLiveSourceAdapter.selectMode) { //多选态:点条目 = 勾选要删的自建直播源
                mLiveSourceAdapter.toggleSelection(entry)
                return@setOnItemClickListener
            }
            if (entry.embedded()) {
                AppBubble.toast("订阅分组无法单独选择")
                return@setOnItemClickListener
            }
            // 单选:点已勾选的那条 = 取消指定(回到"用订阅自带的直播")
            val toast = liveCoordinator.applyLiveSource(if (entry.checked) "" else entry.url)
            renderLiveSources()
            toast?.let { AppBubble.toast(it) }
        }
        setupSelectActionBar()

        mSubscriptionAdapter.setNewData(state.subscriptions)
        updateEmptyState()
        renderLiveSources()
        mBinding.tvLivePreviewState.setOnClickListener {
            if (liveCoordinator.isPreviewFailedForSelected()) {
                liveCoordinator.retryPreview()
                renderLiveSources()
            }
        }
        warnIfDebugDataDomain()
        mBinding.ivUseTip.setOnClickListener {
            XPopup.Builder(this)
                .asCustom(SubsTipDialog(this))
                .show()
        }

        // 导出:视频源页 = 勾选订阅后合并成清单分享;直播源页 = 直接导出自建直播源
        mBinding.ivExport.setOnClickListener {
            if (state.liveTabActive) {
                exportLiveSources()
                return@setOnClickListener
            }
            if (state.subscriptions.isEmpty()) {
                AppBubble.toast("暂无订阅可导出")
            } else if (!mSubscriptionAdapter.isExportMode) {
                enterExportMode()
            }
        }
        mBinding.btnExportCancel.setOnClickListener { exitExportMode() }
        mBinding.tvExportAll.setOnClickListener {
            mSubscriptionAdapter.setSelectAll(!mSubscriptionAdapter.isAllSelected)
            updateExportCount()
        }
        mBinding.btnExportOk.setOnClickListener { exportCoordinator.startExport() }

        mBinding.ivAdd.setOnClickListener {//添加:视频源页 = 添加订阅;直播源页 = 添加自己的直播源
            if (state.liveTabActive) {
                showAddLiveSource()
                return@setOnClickListener
            }
            XPopup.Builder(this)
                .autoFocusEditText(false)
                .asCustom(
                    SubsciptionDialog(
                        this,
                        "订阅: " + (state.subscriptions.size + 1),
                        object : OnSubsciptionListener {
                            override fun onConfirm(
                                name: String,
                                url: String,
                                checked: Boolean
                            ) { //只有addSub2List用到,看注释,单线路才生效,其余方法仅作为参数继续传递
                                for (item in state.subscriptions) {
                                    if (item.url == url) {
                                        AppBubble.toast("订阅地址已存在")
                                        return
                                    }
                                }
                                // 首个订阅(列表为空)时忽略勾选,直接启用,避免添加后无可用订阅
                                val effective = checked || state.subscriptions.isEmpty()
                                importCoordinator.addDirectSubscription(name, url, effective)
                            }

                            override fun chooseLocal(checked: Boolean) { //本地导入(订阅文件/清单 ZIP/本地包)
                                // 首个订阅(列表为空)时忽略勾选,直接启用
                                pickFile(checked || state.subscriptions.isEmpty())
                            }

                            override fun chooseJson(checked: Boolean) { //JSON 导入(粘贴 JSON 文本)
                                // 首个订阅(列表为空)时忽略勾选,直接启用
                                val useNew = checked || state.subscriptions.isEmpty()
                                DialogCoordinator.center(
                                    this@SubscriptionActivity,
                                    JsonImportDialog(this@SubscriptionActivity) { json ->
                                        importCoordinator.importJsonText(json, useNew)
                                    })
                                    .show()
                            }
                        })
                ).show()
        }

        mSubscriptionAdapter.setOnItemClickListener { _: BaseQuickAdapter<*, *>?, _: View?, position: Int ->
            if (mSubscriptionAdapter.inSelectMode()) { //多选态(导出/删除):点条目=勾选,不切换当前订阅
                if (position < state.subscriptions.size) {
                    mSubscriptionAdapter.toggleSelection(state.subscriptions[position].url)
                    if (mSubscriptionAdapter.isExportMode) updateExportCount()
                }
                return@setOnItemClickListener
            }
            //选择订阅
            for (i in state.subscriptions.indices) {
                val subscription = state.subscriptions[i]
                if (i == position) {
                    subscription.setChecked(true)
                    state.selectedUrl = subscription.url
                } else {
                    subscription.setChecked(false)
                }
            }
            LogStore.log(Category.SUBSCRIPTION, "订阅: 切换当前配置")
            //删除/选择只刷新,不触发重新排序
            mSubscriptionAdapter.notifyDataSetChanged()
            renderLiveSources()
        }

        mSubscriptionAdapter.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { adapter: BaseQuickAdapter<*, *>?, view: View, position: Int ->
                if (mSubscriptionAdapter.inSelectMode()) return@OnItemLongClickListener true //多选/导出态不弹长按菜单
                val item = state.subscriptions[position]
                // 长按气泡统一走 AttachActionDialog(主题悬浮面 + text_main 文字色):
                // XPopup 的 asAttachList 用库内固定样式,不吃主题文件,自定义主题下会是一块"外来"的底
                AttachActionDialog.show(
                    view.findViewById(R.id.tv_name),
                    arrayOf(
                        if (item.isTop) "取消置顶" else "置顶",
                        "编辑订阅",
                        "复制地址",
                        "多选"
                    ),
                    intArrayOf(
                        AttachActionDialog.NORMAL,
                        AttachActionDialog.NORMAL,
                        AttachActionDialog.NORMAL,
                        AttachActionDialog.NORMAL
                    )
                ) { index: Int ->
                    when (index) {
                        0 -> {
                            item.isTop = !item.isTop
                            state.subscriptions[position] = item
                            mSubscriptionAdapter.setNewData(state.subscriptions)
                            LogStore.log(
                                Category.SUBSCRIPTION,
                                "订阅: " + (if (item.isTop) "置顶配置" else "取消置顶配置")
                            )
                        }
                        1 -> showEditSubscription(position)
                        2 -> {
                            ClipboardUtils.copyText(state.subscriptions[position].url)
                            AppBubble.toast("已复制")
                        }
                        3 -> enterSelectMode(false) //多选:批量删除订阅
                    }
                }
                true
            }

        // 直播源长按菜单:自建的可编辑(改名/改地址),订阅导入的只能复制/多选;删除统一走长按「多选」。
        mLiveSourceAdapter.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { _: BaseQuickAdapter<*, *>?, view: View, position: Int ->
                val entry = mLiveSourceAdapter.data.getOrNull(position)
                    ?: return@OnItemLongClickListener true
                if (mLiveSourceAdapter.selectMode) return@OnItemLongClickListener true //多选态不弹长按菜单
                val labels = if (entry.removable() && !entry.embedded())
                    arrayOf("编辑", "复制地址", "多选") else arrayOf("复制地址", "多选")
                val kinds = IntArray(labels.size) { AttachActionDialog.NORMAL }
                AttachActionDialog.show(view.findViewById(R.id.tv_name), labels, kinds) { index: Int ->
                    when (labels[index]) {
                        "编辑" -> showEditLiveSource(entry)
                        "复制地址" -> {
                            ClipboardUtils.copyText(entry.url)
                            AppBubble.toast("已复制")
                        }
                        "多选" -> enterSelectMode(true) //多选:批量删除自建直播源
                    }
                }
                true
            }
    }

    // ------------------------------------------------------------------
    // 导出:勾选订阅 → 抓取各自配置 → 合并成一份 txt → 分享
    // ------------------------------------------------------------------

    /** 进入导出态:复选框语义切到"要导出的订阅",底部出现操作条 */
    private fun enterExportMode() {
        mSubscriptionAdapter.setExportMode(true)
        mBinding.llExportBar.visibility = View.VISIBLE
        updateExportCount()
    }

    /** 退出导出态:恢复"当前订阅"勾选显示 */
    private fun exitExportMode() {
        mSubscriptionAdapter.setExportMode(false)
        mBinding.llExportBar.visibility = View.GONE
    }

    /** 刷新"已选 N 项"与全选按钮文案 */
    private fun updateExportCount() {
        val n = mSubscriptionAdapter.selectedCount()
        mBinding.tvExportCount.text = "已选 $n 项"
        mBinding.tvExportAll.text = if (mSubscriptionAdapter.isAllSelected) "全不选" else "全选"
    }

    /** 分享导出的清单(与运行日志导出同一套 FileProvider 通道) */
    private fun shareExport(file: File, title: String = "分享订阅清单") {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                packageName + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = if (file.name.endsWith(".zip", ignoreCase = true)) "application/zip" else "application/json"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, title))
        } catch (t: Throwable) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 分享导出文件失败 (${exceptionType(t)})")
            AppBubble.toast("分享失败")
        }
    }

    /**
     * 导出用户自建的直播源(名称 + 地址)成一份 JSON 分享出去。
     * 订阅自带的直播源不导:它们跟着订阅走,导出去会在别处变成一份重复的"自建"清单。
     */
    private fun exportLiveSources() {
        LiveSourceExporter.export(this, liveCoordinator.exportSnapshot()) { file, error ->
            if (isFinishing || isDestroyed) return@export
            if (file == null) AppBubble.toast(error ?: "导出失败")
            else shareExport(file, "分享直播源清单")
        }
    }

    // ------------------------------------------------------------------
    // 「直播源」标签页:订阅自带的(跟着订阅走,不可删)+ 用户自建的(可加可删)
    // ------------------------------------------------------------------

    private fun setupTabs() {
        mBinding.tabVideo.setOnClickListener { switchTab(false) }
        mBinding.tabLive.setOnClickListener { switchTab(true) }
        // 内容区左右滑动切 tab；条目收到 DOWN 后若确认是横滑，须先发 CANCEL 再切页。
        // 首帧把指示条摆到"视频源"下方(不播动画):等标签行量完尺寸再摆,顺手把它显示出来
        mBinding.llTabs.post { moveIndicator(false) }
    }

    private val pageSwipeMinPx by lazy { TabSwipeHelper.minDistancePx(this) }
    private val pageTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var pageSwipeEligible = false
    private var pageSwipeCaptured = false
    private var pageSwipeDirection = 0
    private var pageSwipeDownX = 0f
    private var pageSwipeDownY = 0f
    private val tabPageAnimator = TabPageAnimator()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pageSwipeEligible = touchInPage(ev)
                pageSwipeCaptured = false
                pageSwipeDirection = 0
                pageSwipeDownX = ev.x
                pageSwipeDownY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (pageSwipeCaptured) return true
                if (pageSwipeEligible) {
                    val dx = ev.x - pageSwipeDownX
                    val dy = ev.y - pageSwipeDownY
                    val direction = TabSwipeHelper.direction(dx, dy, pageSwipeMinPx)
                    if (direction != 0) {
                        capturePageSwipe(ev, direction)
                        return true
                    }
                    if (kotlin.math.abs(dy) > pageTouchSlop.toFloat() && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        pageSwipeEligible = false // 已开始纵向滚动，不再抢列表手势
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pageSwipeEligible = false
                if (pageSwipeCaptured) {
                    pageSwipeDirection = 0
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (pageSwipeCaptured) return true
            MotionEvent.ACTION_UP -> {
                if (!pageSwipeCaptured && pageSwipeEligible) {
                    val direction = TabSwipeHelper.direction(
                        ev.x - pageSwipeDownX, ev.y - pageSwipeDownY, pageSwipeMinPx
                    )
                    if (direction != 0) capturePageSwipe(ev, direction)
                }
                if (pageSwipeCaptured) {
                    val direction = if (pageSwipeDirection == 0) 0 else TabSwipeHelper.direction(
                        ev.x - pageSwipeDownX, ev.y - pageSwipeDownY, pageSwipeMinPx
                    )
                    pageSwipeCaptured = false
                    pageSwipeEligible = false
                    pageSwipeDirection = 0
                    if (direction != 0) switchTab(direction < 0)
                    return true // 子视图只收到 CANCEL，不再收到会触发选源的 UP
                }
                pageSwipeEligible = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pageSwipeCaptured) {
                    pageSwipeCaptured = false
                    pageSwipeEligible = false
                    pageSwipeDirection = 0
                    return true
                }
                pageSwipeEligible = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun capturePageSwipe(ev: MotionEvent, direction: Int) {
        pageSwipeCaptured = true
        pageSwipeDirection = direction
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        try {
            super.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    private fun touchInPage(ev: MotionEvent): Boolean {
        val page = mBinding.tabPageContainer
        val location = IntArray(2)
        page.getLocationOnScreen(location)
        return page.isShown && ev.rawX >= location[0] && ev.rawX < location[0] + page.width &&
            ev.rawY >= location[1] && ev.rawY < location[1] + page.height
    }

    /** 切标签页:同一张卡片换数据;标题栏动作区(导出/添加)跟着换 */
    private fun switchTab(live: Boolean) {
        if (state.liveTabActive == live) return
        tabPageAnimator.finish()
        val outgoing = if (state.liveTabActive) mBinding.flLive else mBinding.flVideo
        val incoming = if (live) mBinding.flLive else mBinding.flVideo
        state.liveTabActive = live
        mBinding.flVideo.visibility = if (live) View.GONE else View.VISIBLE
        mBinding.flLive.visibility = if (live) View.VISIBLE else View.GONE
        styleTab(mBinding.tvTabVideo, !live)
        styleTab(mBinding.tvTabLive, live)
        // 指示条**滑**到目标 tab 下方(不是直接跳过去)
        moveIndicator(true)
        // 导出两页都有:视频源页导订阅清单,直播源页导自建直播源
        mBinding.ivExport.visibility = View.VISIBLE
        if (live) {
            if (mSubscriptionAdapter.isExportMode) exitExportMode()
            if (mSubscriptionAdapter.getMode() == SubscriptionAdapter.Mode.DELETE) exitSelectMode()
            renderLiveSources()
        } else {
            if (mLiveSourceAdapter.selectMode) exitSelectMode()
            updateEmptyState()
        }
        tabPageAnimator.slide(mBinding.tabPageContainer, outgoing, incoming, if (live) -1 else 1)
    }

    /**
     * 把唯一那条指示条放到当前选中 tab 的正下方。
     *
     * @param animate true = 滑过去(切 tab);false = 直接就位(首帧/转屏后重新摆位)
     */
    private fun moveIndicator(animate: Boolean) {
        val tab = if (state.liveTabActive) mBinding.tabLive else mBinding.tabVideo
        val ind = mBinding.indTab
        if (tab.width == 0 || ind.width == 0) return   // 还没量完尺寸,等下一次回调
        val target = tab.left + (tab.width - ind.width) / 2f
        ind.visibility = View.VISIBLE
        if (!animate) {
            ind.animate().cancel()
            ind.x = target
            return
        }
        if (ind.x == target) return
        // 与内容页同为 240ms 减速，指示条和页面同步到位。
        ind.animate().cancel()
        ind.animate().x(target).setDuration(240L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    /** 标签选中态:文字主色 + 加粗(指示条由 [moveIndicator] 统一滑动,不在这里显示/隐藏) */
    private fun styleTab(text: TextView, selected: Boolean) {
        text.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.text_foreground else R.color.text_sub_foreground
            )
        )
        text.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    // ------------------------------------------------------------------
    // 长按 →「多选」:批量删除(与本地视频页同一套:公共组件 SelectActionBar)
    // ------------------------------------------------------------------

    /**
     * 底部多选操作栏:订阅源与直播源共用一条,动作键固定三连
     * (全选 / 删除 / 取消全选),只是作用对象按 [selectOnLive] 切。
     * 键色/背景全部由组件按主题取(普通键=文字主色、危险键=text_danger),页面不自己 setTextColor。
     */
    private fun setupSelectActionBar() {
        mBinding.selectActionBar.addAction("全选", SelectActionBar.Kind.NORMAL) { view: View? ->
            FastClickCheckUtil.check(view)
            if (selectOnLive) mLiveSourceAdapter.setSelectAll(true)
            else mSubscriptionAdapter.setSelectAll(true)
        }
        mDeleteAction = mBinding.selectActionBar.addAction("删除", SelectActionBar.Kind.DANGER) { view: View? ->
            FastClickCheckUtil.check(view)
            confirmDeleteSelection()
        }
        mBinding.selectActionBar.addAction("取消全选", SelectActionBar.Kind.NORMAL) { view: View? ->
            FastClickCheckUtil.check(view)
            if (selectOnLive) mLiveSourceAdapter.setSelectAll(false)
            else mSubscriptionAdapter.setSelectAll(false)
        }
        mBinding.selectActionBar.setActionEnabled(mDeleteAction, false)
        // 勾选数变化 → "删除"键的可用态(颜色由组件按 Kind 决定)
        mSubscriptionAdapter.setOnSelectCountListener { count: Int ->
            if (!selectOnLive) updateDeleteEnabled(count)
        }
        mLiveSourceAdapter.onSelectCountListener = { count: Int ->
            if (selectOnLive) updateDeleteEnabled(count)
        }
    }

    private fun updateDeleteEnabled(count: Int) {
        mBinding.selectActionBar.setActionEnabled(mDeleteAction, count > 0)
    }

    /** 进入多选态(长按菜单「多选」):订阅源=多选删除订阅;直播源=多选删除自建直播源 */
    private fun enterSelectMode(live: Boolean) {
        selectOnLive = live
        if (live != state.liveTabActive) switchTab(live)   // 切页会顺带退出另一种多选态
        if (mSubscriptionAdapter.isExportMode) exitExportMode()
        if (live) mLiveSourceAdapter.setSelectMode(true)
        else mSubscriptionAdapter.setMode(SubscriptionAdapter.Mode.DELETE)
        mBinding.llExportBar.visibility = View.GONE
        mBinding.selectActionBar.visibility = View.VISIBLE
        updateDeleteEnabled(0)
    }

    /** 退出多选态(取消/删完/返回键/切页) */
    private fun exitSelectMode() {
        if (mLiveSourceAdapter.selectMode) mLiveSourceAdapter.setSelectMode(false)
        if (mSubscriptionAdapter.getMode() == SubscriptionAdapter.Mode.DELETE) {
            mSubscriptionAdapter.setMode(SubscriptionAdapter.Mode.NORMAL)
        }
        mBinding.selectActionBar.visibility = View.GONE
    }

    private fun inDeleteSelectMode(): Boolean =
        mLiveSourceAdapter.selectMode ||
            mSubscriptionAdapter.getMode() == SubscriptionAdapter.Mode.DELETE

    /** 多选态下点"删除":先确认(不可逆 → 危险色确认键),再批量删 */
    private fun confirmDeleteSelection() {
        if (selectOnLive) {
            val targets = mLiveSourceAdapter.selection()
            if (targets.isEmpty()) {
                AppBubble.toast("请先勾选要删除的直播源")
                return
            }
            ConfirmDialog.showDanger(
                this, "删除直播源",
                "确定删除所选的 " + targets.size + " 个直播源吗？", "删除"
            ) { deleteSelectedLiveSources(targets) }
        } else {
            val targets = mSubscriptionAdapter.selection()
            if (targets.isEmpty()) {
                AppBubble.toast("请先勾选要删除的订阅")
                return
            }
            val remainingUrls = state.subscriptions
                .filterNot { s -> targets.any { it === s } }.map { it.url }
            val pkgExtra = if (targets.any {
                    LocalPackageImporter.cleanupNote(this, it.url, remainingUrls) != null
                }) " 所选订阅的本地包文件将一并删除。" else ""
            val msg = if (targets.any { it.isChecked }) {
                "所选包含当前正在使用的订阅,删除后将自动切换到其它订阅,确定删除吗？$pkgExtra"
            } else {
                "确定删除所选的 " + targets.size + " 个订阅吗？$pkgExtra"
            }
            ConfirmDialog.showDanger(this, "删除订阅", msg, "删除") {
                deleteSelectedSubscriptions(targets)
            }
        }
    }

    private var subscriptionCleanupInProgress = false

    private fun deleteSelectedSubscriptions(targets: List<Subscription>) {
        if (importCoordinator.isBatchImportInProgress() || subscriptionCleanupInProgress) return
        val removed = targets.filter { state.subscriptions.contains(it) }
        val deletedUrls = removed.map { it.url }.distinct()
        for (sub in removed) removeSubscription(sub)
        persistSubscriptions()
        exitSelectMode()
        subscriptionCleanupInProgress = true
        showLoading("正在清理本地订阅文件…", null)
        val app = applicationContext
        val page = java.lang.ref.WeakReference(this)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        com.github.tvbox.osc.util.HeavyTaskUtil.getSerialExecutorService().execute {
            var cleanupFailed = false
            try {
                com.github.tvbox.osc.transfer.SubscriptionImportFiles.runLocked<Unit> {
                    val remaining = SubscriptionConfig.getSubscriptions().map { it.url }
                    for (url in deletedUrls) {
                        if (url in remaining) continue
                        val file = SubscriptionLibrary.libraryFileOf(app, url)
                        if (file != null && file.isFile && !file.delete()) cleanupFailed = true
                    }
                    if (!LocalPackageImporter.cleanupAfterDelete(app, deletedUrls, remaining)) cleanupFailed = true
                }
            } catch (_: Exception) {
                cleanupFailed = true
            }
            val message = if (cleanupFailed) "订阅已删除，部分本地文件未能清理"
                else "已删除 " + deletedUrls.size + " 个订阅"
            LogStore.log(Category.SUBSCRIPTION, "订阅: $message")
            main.post {
                val activity = page.get() ?: return@post
                activity.subscriptionCleanupInProgress = false
                if (!activity.isPageActive()) return@post
                activity.dismissLoading()
                AppBubble.toast(message)
            }
        }
    }

    private fun deleteSelectedLiveSources(targets: List<LiveSourceEntries.Entry>) {
        val toast = liveCoordinator.deleteSelected(targets)
        exitSelectMode()
        renderLiveSources()
        AppBubble.toast(toast)
    }

    private fun removeSubscription(deleted: Subscription) {
        liveCoordinator.onSubscriptionRemoved(deleted.url)
        state.subscriptions.remove(deleted)
        LogStore.log(Category.SUBSCRIPTION, "订阅: 删除配置")
        if (deleted.isChecked) {
            state.liveSourceChanged = true
            val next = state.subscriptions.firstOrNull { it.isTop } ?: state.subscriptions.firstOrNull()
            for (s in state.subscriptions) s.setChecked(false)
            if (next != null) {
                next.setChecked(true)
                state.selectedUrl = next.url
                LogStore.log(Category.SUBSCRIPTION, "订阅: 删除后自动切换配置")
            } else {
                state.selectedUrl = ""
            }
            LogStore.log(Category.SUBSCRIPTION, "订阅: 删除当前配置，已加载直播源等待新配置刷新")
        }
    }

    /**
     * 重建直播源列表(快照由 [LiveSourceCoordinator] 算好,这里只绑视图):
     * 订阅导入的在前(不可删)、用户自建的在后;勾中的那条 = 当前生效的直播源。
     */
    private fun renderLiveSources() {
        val snapshot = liveCoordinator.refreshEntries()
        mLiveSourceAdapter.setNewData(snapshot.entries)
        val state = mBinding.tvLivePreviewState
        state.isClickable = snapshot.previewClickable
        state.text = snapshot.previewText
        state.visibility = if (snapshot.previewVisible) View.VISIBLE else View.GONE
        mBinding.rvLive.visibility = if (snapshot.empty) View.GONE else View.VISIBLE
        mBinding.llEmptyLive.root.visibility = if (snapshot.empty) View.VISIBLE else View.GONE
        if (snapshot.empty) {
            mBinding.llEmptyLive.tvEmptyText.text = snapshot.emptyText
        }
    }

    /** 添加用户自建的直播源:名称(可选)+ 地址,确认即启用并记入用户历史 */
    private fun showAddLiveSource() {
        val current = SystemConfig.getLiveUrl()
        DialogCoordinator.center(this, LiveApiDialog(
            this,
            initialName = LiveConfig.liveName(current),
            initialUrl = current,
            history = LiveConfig.liveHistory(),
            onDeleteHistory = liveCoordinator::deleteHistory
        ) { name, url ->
            // 只有点"确定"才回调:落库、要不要重载直播配置都在协调器里判
            AppBubble.toast(liveCoordinator.saveNewSource(name, url))
            renderLiveSources()
        }).show()
    }

    /** 编辑一条自建直播源(改名/改地址);订阅导入的与内嵌分组不给编辑入口 */
    private fun showEditLiveSource(entry: LiveSourceEntries.Entry) {
        DialogCoordinator.center(this, LiveApiDialog(
            this,
            title = "编辑直播源",
            confirmText = "保 存",
            initialName = entry.userName,
            initialUrl = entry.url,
            history = LiveConfig.liveHistory(),
            requireUrl = true,
            onDeleteHistory = liveCoordinator::deleteHistory
        ) { name, url ->
            AppBubble.toast(liveCoordinator.applyEdit(entry, name, url))
            renderLiveSources()
        }).show()
    }

    override fun onBackPressed() {
        if (inDeleteSelectMode()) { //多选态:返回键先退出多选(不删)
            exitSelectMode()
            return
        }
        if (mSubscriptionAdapter.isExportMode) { //导出态:返回键先退出选择态
            exitExportMode()
            return
        }
        if (state.liveSourceChanged) {
            // 直播源已就地重载(LiveSourceCoordinator 切完即 reloadLiveSource),无需软重启回首页;
            // 订阅源切换(beforeUrl != selectedUrl)仍走 finish() 里的整进程重启(后续 M2 再免重启)。
            finish()
            return
        }
        super.onBackPressed()
    }

    /**
     * 编辑已有订阅(名称 + 地址就地修改,不再只能"删了重加"):
     * 复用添加弹窗(预填当前名称/地址),保存后按需重载当前订阅。
     */
    private fun showEditSubscription(position: Int) {
        if (position >= state.subscriptions.size) return
        val item = state.subscriptions[position]
        XPopup.Builder(this)
            .autoFocusEditText(false)
            .asCustom(SubsciptionDialog(
                this,
                item.name,
                item.url,
                true,
                object : OnSubsciptionListener {
                    override fun onConfirm(name: String, url: String, checked: Boolean) {
                        applySubscriptionEdit(position, name, url, checked)
                    }

                    // 编辑模式下这两个入口已隐藏,不会被触发
                    override fun chooseLocal(checked: Boolean) {}
                    override fun chooseJson(checked: Boolean) {}
                })
            ).show()
    }

    /** 落地编辑结果:条目就地更新(isChecked/top 保持);改的若是当前订阅地址则立刻重载配置 */
    private fun applySubscriptionEdit(position: Int, name: String, url: String, checked: Boolean) {
        if (position >= state.subscriptions.size) return
        val item = state.subscriptions[position]
        val oldUrl = item.url
        if (url != oldUrl) {
            val dup = state.subscriptions.firstOrNull { it !== item && it.url == url }
            if (dup != null) {
                AppBubble.toast("订阅地址已存在")
                return
            }
        }
        val wasChecked = item.isChecked
        item.name = name
        item.url = url
        if (checked && !wasChecked) {   // 原本未启用时,按弹窗勾选决定是否切到它
            for (s in state.subscriptions) s.setChecked(false)
            item.setChecked(true)
            state.selectedUrl = url
        }
        mSubscriptionAdapter.notifyItemChanged(position)
        updateEmptyState()
        SubscriptionConfig.setSubscriptions(state.subscriptions)
        LogStore.log(Category.SUBSCRIPTION, "订阅: 编辑配置 (" + logSource(url) + ")")
        AppBubble.toast("已保存")
        // 改的就是当前启用订阅的地址 → 与切换订阅走同一套进程重启。
        if (wasChecked && oldUrl != url) {
            state.selectedUrl = url
            finish()
        } else if (state.liveTabActive || checked && !wasChecked) {
            renderLiveSources()
        }
    }

    /**
     * 订阅列表空态:无订阅时展示空态占位(列表隐藏),否则展示列表
     */
    private fun updateEmptyState() {
        val empty = state.subscriptions.isEmpty()
        mBinding.rv.visibility = if (empty) View.GONE else View.VISIBLE
        mBinding.llEmpty.root.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            // 通用空态视图(其他页面也在用)在订阅页换成可操作的文案,别只说"暂无数据"
            mBinding.llEmpty.tvEmptyText.text = "暂无订阅\n点右上角 + 添加,或从文件/JSON 导入"
        }
    }

    /**
     * 调试包与正式包是**两个独立数据域**(build.gradle 给 debug 加了 applicationIdSuffix ".debug",
     * 便于两包共存安装、避免签名不一致要求卸载),订阅/设置各存一份:在调试包刷的订阅,正式包里看不到。
     * 设备同时装有正式包且本包订阅为空时提示一次,避免误判成"订阅被删"(本轮订阅丢失排查结论)。
     * 判定不用 BuildConfig(app 未开启 buildConfig 开关),直接看包名后缀。
     */
    private fun warnIfDebugDataDomain() {
        if (state.subscriptions.isNotEmpty() || debugDomainWarned) return
        // 注意用 applicationContext.packageName:Activity 里裸 packageName 会命中 ViewBinding 的
        // 绑定类方法(ViewDataBinding.getPackageName),拿到的是布局包名而非应用包名
        val appId = applicationContext.packageName
        if (!appId.endsWith(".debug")) return
        val releasePkg = appId.removeSuffix(".debug")
        val installed = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(releasePkg, 0)
            true
        } catch (e: Exception) {
            false
        }
        if (!installed) return
        debugDomainWarned = true
        AppBubble.toast("调试包数据独立，正式包订阅不在此处")
    }

    private var mPendingChecked = true
    private val pickLocalDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importCoordinator.importLocalDoc(it, mPendingChecked) }
    }

    /**
     * 本地导入的文件选择:一律放行全部类型,由导入侧按内容识别
     * (订阅 txt/json、订阅清单 ZIP、本地包 ZIP、已解压本地包里的清单 txt)。
     * 之前给本地包单开过一个只收 zip 的入口,结果用户选不了包里的 txt 清单,现已合并到这里。
     */
    private fun pickFile(checked: Boolean) {
        mPendingChecked = checked
        pickLocalDoc.launch(arrayOf("*/*"))
    }

    /** 订阅地址可能带鉴权信息；业务日志只保留可安全区分的来源类型。 */
    private fun logSource(url: String): String = when {
        url.startsWith("clan://") -> "本地订阅"
        url.startsWith("http://") || url.startsWith("https://") -> "网络订阅"
        else -> "其他来源"
    }

    private fun exceptionType(error: Throwable?): String =
        error?.javaClass?.simpleName ?: "未知错误"

    override fun onPause() {
        super.onPause()
        // 更新缓存
        SubscriptionConfig.setApiUrl(state.selectedUrl)
        SubscriptionConfig.setSubscriptions(state.subscriptions)
    }

    override fun finish() {
        // 不同订阅的 csp.jar 可加载同名 native 库。只清 Activity 任务栈仍在同一进程，
        // 旧 ClassLoader 持有该库时新 ClassLoader 会被 ART 拒绝，首页可能卡到 45 秒看门狗。
        if (state.beforeUrl != state.selectedUrl) {
            // relaunchApp(true) 会结束当前进程；先同步落盘，不能依赖稍后的 onPause。
            liveCoordinator.clearStaleLiveUrlBeforeRestart()
            SubscriptionConfig.setApiUrl(state.selectedUrl)
            SubscriptionConfig.setSubscriptions(state.subscriptions)
            if (packageManager.getLaunchIntentForPackage(packageName) == null) {
                AppBubble.toast("订阅已保存，自动重启失败，请手动重开应用")
                return
            }
            try {
                SystemConfig.markInternalRestart()
                AppUtils.relaunchApp(true)
            } catch (failure: Throwable) {
                android.util.Log.w("MBox-Startup", "切换订阅重启失败", failure)
            }
            // relaunchApp(true) 成功时结束当前进程；若返回则让用户留在本页重试。
            SystemConfig.clearInternalRestart()
            AppBubble.toast("订阅已保存，自动重启失败，请手动重开应用")
            return
        }
        super.finish()
    }

    override fun onDestroy() {
        importCoordinator.onPageDestroyed()
        tabPageAnimator.finish()
        super.onDestroy()
        HttpClient.cancel("get_subscription")
    }

    // ------------------------------------------------------------------
    // SubscriptionImportHost / SubscriptionLiveHost / SubscriptionExportHost
    // ------------------------------------------------------------------

    override fun isPageActive(): Boolean = !isFinishing && !isDestroyed

    override fun post(runnable: Runnable) {
        mBinding.root.post(runnable)
    }

    override fun runOnUi(runnable: Runnable) {
        runOnUiThread(runnable)
    }

    override fun showToast(text: String) {
        AppBubble.toast(text)
    }

    override fun showLoading(hint: String, onCancel: (() -> Unit)?) { // 接口默认值只在接口调用点生效,这里不能重复声明
        showLoadingDialog(hint, if (onCancel != null) Runnable { onCancel() } else null)
    }

    override fun setLoadingHint(hint: String) {
        updateLoadingHint(hint)
    }

    override fun dismissLoading() {
        dismissLoadingDialog()
    }

    override fun onSubscriptionListChanged() {
        mSubscriptionAdapter.setNewData(state.subscriptions)
        updateEmptyState()
    }

    override fun persistSubscriptions() {
        mSubscriptionAdapter.notifyDataSetChanged()   //删除/选择只刷新,不触发重新排序
        updateEmptyState()
        SubscriptionConfig.setApiUrl(state.selectedUrl)
        SubscriptionConfig.setSubscriptions(state.subscriptions)
        renderLiveSources()                           //订阅变了,直播源页的「来自:X」跟着变
    }

    override fun refreshLiveSources() {
        renderLiveSources()
    }

    override fun showStoreHouseChoose(
        sources: List<Source>,
        onPick: (name: String, url: String) -> Unit,
        onDismiss: () -> Unit
    ) {
        XPopup.Builder(this)
            .asCustom(
                ChooseSourceDialog(
                    this,
                    sources,
                    { position: Int, _: String? -> onPick(sources[position].sourceName, sources[position].sourceUrl) },
                    Runnable { onDismiss() }
                ))
            .show()
    }

    override fun onLiveSourcesRenderNeeded() {
        renderLiveSources()
    }

    override fun selectedForExport(): List<Subscription> = mSubscriptionAdapter.selection()

    override fun onExportFinished(file: File, count: Int) {
        exitExportMode()
        shareExport(file)
    }
}

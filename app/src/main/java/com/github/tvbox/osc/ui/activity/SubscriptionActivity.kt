package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.blankj.utilcode.util.ClipboardUtils
import com.blankj.utilcode.util.LogUtils
import com.github.tvbox.osc.util.AppBubble
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.Source
import com.github.tvbox.osc.bean.Subscription
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.databinding.ActivitySubscriptionBinding
import com.github.tvbox.osc.spiderapi.LiveChannelConfigProviders
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
import com.github.tvbox.osc.ui.kit.TabSwipeHelper
import com.github.tvbox.osc.ui.kit.TabPageAnimator
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.spiderapi.CmsApiRules
import com.github.tvbox.osc.util.CmsSiteImporter
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.HCallBack
import com.github.tvbox.osc.util.HtmlSiteImporter
import com.github.tvbox.osc.util.HttpClient
import com.github.tvbox.osc.util.LiveConfig
import com.github.tvbox.osc.util.LiveSourceEntries
import com.github.tvbox.osc.util.LegadoVideoImporter
import com.github.tvbox.osc.util.LegadoVideoRules
import com.github.tvbox.osc.util.SubscriptionConfig
import com.github.tvbox.osc.util.SubscriptionExporter
import com.github.tvbox.osc.util.SubscriptionImportRules
import com.github.tvbox.osc.util.Utils
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lxj.xpopup.XPopup

import java.io.File
import java.io.FileOutputStream
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.function.Consumer

class SubscriptionActivity : BaseVbActivity<ActivitySubscriptionBinding>() {

    companion object {
        /** 调试包数据域提示只弹一次(进程内),避免反复打扰 */
        private var debugDomainWarned = false

        /** 本地导入文件内容判定的读取上限:订阅配置不会这么大,超限就不判定(按旧逻辑原样加入) */
        private const val MAX_JUDGE_BYTES = 2L * 1024 * 1024
    }

    private var mBeforeUrl = SubscriptionConfig.getApiUrl()
    private var mSelectedUrl = ""
    private var mSubscriptions: MutableList<Subscription> = SubscriptionConfig.getSubscriptions().toMutableList()
    private var mSubscriptionAdapter = SubscriptionAdapter()
    /** 「直播源」页的列表适配器(订阅导入的 + 用户自建的) */
    private var mLiveSourceAdapter = LiveSourceAdapter()
    /** 当前是否停在「直播源」标签页(标题栏动作区按页切换) */
    private var liveTabActive = false
    /** 本次进页面是否改过"当前直播源"(退出时需要重载配置,直播页才看得到新源) */
    private var liveSourceChanged = false
    /** 底部多选操作栏(公共组件)上的"删除"键:有没有勾选项决定它的可用态 */
    private lateinit var mDeleteAction: TextView
    /** 当前多选作用于哪个列表:true=直播源页,false=订阅源页 */
    private var selectOnLive = false
    private val mSources: MutableList<Source> = ArrayList()
    private var batchImportInProgress = false
    private var batchProgress = ""
    private var batchCancelAction: (() -> Unit)? = null

    private fun updateImportHint(hint: String) {
        updateLoadingHint(if (batchImportInProgress) "$batchProgress\n$hint" else hint)
    }

    private fun dismissImportLoading() {
        if (!batchImportInProgress) dismissLoadingDialog()
    }

    private fun showImportToast(message: String) {
        if (!batchImportInProgress) AppBubble.toast(message)
    }

    private fun resumeBatchLoading() {
        if (batchImportInProgress && !importCancelled) {
            showCancelableLoading(batchProgress) { batchCancelAction?.invoke() }
        }
    }

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
            applyLiveSource(if (entry.checked) "" else entry.url)
        }
        mLiveSourceAdapter.setOnItemChildClickListener { _, view, position ->
            if (view.id != R.id.iv_del) return@setOnItemChildClickListener
            val entry = mLiveSourceAdapter.data.getOrNull(position) ?: return@setOnItemChildClickListener
            deleteUserLiveSource(entry)
        }
        setupSelectActionBar()
        mSubscriptions.forEach(Consumer { item: Subscription ->
            if (item.isChecked) {
                mSelectedUrl = item.url
            }
        })

        mSubscriptionAdapter.setNewData(mSubscriptions)
        updateEmptyState()
        refreshLiveSources()
        warnIfDebugDataDomain()
        // 打开页面即留一条摘要:条数 / 当前启用 / 各来源分布(排障时先看这条就知道"用户手里有什么")
        val originStat = mSubscriptions.groupingBy { it.origin }.eachCount()
            .entries.joinToString(" ") { "${it.key}=${it.value}" }
        LogStore.log(
            Category.SUBSCRIPTION,
            "订阅: 打开管理页 " + mSubscriptions.size + " 条" +
                (if (mSelectedUrl.isEmpty()) ",当前未启用" else ",当前启用 " + currentName()) +
                (if (originStat.isEmpty()) "" else ",来源 " + originStat)
        )
        mBinding.ivUseTip.setOnClickListener {
            XPopup.Builder(this)
                .asCustom(SubsTipDialog(this))
                .show()
        }

        // 导出:抓取勾选的订阅配置,合并成一份 txt 后分享出去
        mBinding.ivExport.setOnClickListener {
            if (mSubscriptions.isEmpty()) {
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
        mBinding.btnExportOk.setOnClickListener { startExport() }

        mBinding.ivAdd.setOnClickListener {//添加:视频源页 = 添加订阅;直播源页 = 添加自己的直播源
            if (liveTabActive) {
                showAddLiveSource()
                return@setOnClickListener
            }
            XPopup.Builder(this)
                .autoFocusEditText(false)
                .asCustom(
                    SubsciptionDialog(
                        this,
                        "订阅: " + (mSubscriptions.size + 1),
                        object : OnSubsciptionListener {
                            override fun onConfirm(
                                name: String,
                                url: String,
                                checked: Boolean
                            ) { //只有addSub2List用到,看注释,单线路才生效,其余方法仅作为参数继续传递
                                for (item in mSubscriptions) {
                                    if (item.url == url) {
                                        AppBubble.toast("订阅地址已存在")
                                        return
                                    }
                                }
                                // 首个订阅(列表为空)时忽略勾选,直接启用,避免添加后无可用订阅
                                val effective = checked || mSubscriptions.isEmpty()
                                addOrigin = Subscription.ORIGIN_DIRECT   // 弹窗里输入名称+地址 = 直接导入
                                addSubscription(name, url, effective)
                            }

                            override fun chooseLocal(checked: Boolean) { //本地导入
                                // 首个订阅(列表为空)时忽略勾选,直接启用
                                pickFile(checked || mSubscriptions.isEmpty())
                            }

                            override fun chooseJson(checked: Boolean) { //JSON 导入(粘贴 JSON 文本)
                                // 首个订阅(列表为空)时忽略勾选,直接启用
                                val useNew = checked || mSubscriptions.isEmpty()
                                DialogCoordinator.center(
                                    this@SubscriptionActivity,
                                    JsonImportDialog(this@SubscriptionActivity) { json ->
                                        importJsonText(json, useNew)
                                    })
                                    .show()
                            }
                        })
                ).show()
        }

        mSubscriptionAdapter.setOnItemChildClickListener { _: BaseQuickAdapter<*, *>?, view: View, position: Int ->
            LogUtils.d("删除订阅")
            if (view.id == R.id.iv_del) {
                if (position >= mSubscriptions.size) return@setOnItemChildClickListener
                val target = mSubscriptions[position]
                // 允许删除"当前勾选/正在使用"的订阅(如导入坏订阅也能清理):
                // 删除后自动切换当前订阅(置顶优先,否则取首项;删光则清空)
                val delMsg = if (target.isChecked) {
                    if (mSubscriptions.size <= 1) {
                        "该订阅为当前正在使用的订阅,删除后列表将清空,确定删除吗？"
                    } else {
                        "该订阅为当前正在使用的订阅,删除后将自动切换到其它订阅,确定删除吗？"
                    }
                } else {
                    "确定删除订阅吗？"
                }
                com.github.tvbox.osc.ui.dialog.ConfirmDialog.show(
                    this@SubscriptionActivity,
                    "删除订阅",
                    delMsg,
                    "删除"
                ) {
                    if (batchImportInProgress) return@show
                    if (mSubscriptions.none { it === target }) return@show
                    removeSubscription(target)
                    persistSubscriptions()
                }
            }
        }

        mSubscriptionAdapter.setOnItemClickListener { _: BaseQuickAdapter<*, *>?, _: View?, position: Int ->
            if (mSubscriptionAdapter.inSelectMode()) { //多选态(导出/删除):点条目=勾选,不切换当前订阅
                if (position < mSubscriptions.size) {
                    mSubscriptionAdapter.toggleSelection(mSubscriptions[position].url)
                    if (mSubscriptionAdapter.isExportMode) updateExportCount()
                }
                return@setOnItemClickListener
            }
            //选择订阅
            for (i in mSubscriptions.indices) {
                val subscription = mSubscriptions[i]
                if (i == position) {
                    subscription.setChecked(true)
                    mSelectedUrl = subscription.url
                } else {
                    subscription.setChecked(false)
                }
            }
            val chosen = mSubscriptions[position]
            LogStore.log(Category.SUBSCRIPTION, "订阅: 切换到 " + chosen.name)
            //删除/选择只刷新,不触发重新排序
            mSubscriptionAdapter.notifyDataSetChanged()
        }

        mSubscriptionAdapter.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { adapter: BaseQuickAdapter<*, *>?, view: View, position: Int ->
                if (mSubscriptionAdapter.inSelectMode()) return@OnItemLongClickListener true //多选/导出态不弹长按菜单
                val item = mSubscriptions[position]
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
                            mSubscriptions[position] = item
                            mSubscriptionAdapter.setNewData(mSubscriptions)
                            LogStore.log(
                                Category.SUBSCRIPTION,
                                "订阅: " + (if (item.isTop) "置顶 " else "取消置顶 ") + item.name
                            )
                        }
                        1 -> showEditSubscription(position)
                        2 -> {
                            ClipboardUtils.copyText(mSubscriptions.get(position).url)
                            AppBubble.toast("已复制")
                        }
                        3 -> enterSelectMode(false) //多选:批量删除订阅
                    }
                }
                true
            }

        // 直播源长按菜单只保留复制地址/多选；单条删除由行尾的 × 负责。
        mLiveSourceAdapter.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { _: BaseQuickAdapter<*, *>?, view: View, position: Int ->
                val entry = mLiveSourceAdapter.data.getOrNull(position)
                    ?: return@OnItemLongClickListener true
                if (mLiveSourceAdapter.selectMode) return@OnItemLongClickListener true //多选态不弹长按菜单
                val labels = arrayOf("复制地址", "多选")
                val kinds = intArrayOf(AttachActionDialog.NORMAL, AttachActionDialog.NORMAL)
                AttachActionDialog.show(view.findViewById(R.id.tv_name), labels, kinds) { index: Int ->
                    when (labels[index]) {
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
        LogStore.log(Category.SUBSCRIPTION, "订阅: 进入导出选择态,共 " + mSubscriptions.size + " 项")
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

    /** 当前启用订阅的名字(未启用返回空串) */
    private fun currentName(): String = mSubscriptions.firstOrNull { it.isChecked }?.name ?: ""

    /** 可取消的加载框(导入/导出这类长耗时流程):点"取消"执行 onCancel,调用方负责作废在跑的任务并 dismiss */
    private fun showCancelableLoading(hint: String, onCancel: () -> Unit) {
        showLoadingDialog(hint, Runnable { onCancel() })
    }

    /** 组装描述清单 + 落盘 + 分享(不抓取、不合并:远端配置由 App 运行时按 url 自行拉取) */
    private fun startExport() {
        val selected = mSubscriptionAdapter.selection()
        if (selected.isEmpty()) {
            AppBubble.toast("请先勾选要导出的订阅")
            return
        }
        showCancelableLoading("正在导出 " + selected.size + " 个订阅…") {
            // 取消:在跑的导出结果一律作废(已发出的单次请求撤不回,但不会再采用)
            SubscriptionExporter.cancel()
            LogStore.log(Category.SUBSCRIPTION, "订阅: 导出已取消")
            AppBubble.toast("已取消导出")
        }
        LogStore.log(Category.SUBSCRIPTION, "订阅: 导出 " + selected.size + " 项(描述清单)")
        SubscriptionExporter.export(this, selected, object : SubscriptionExporter.Callback {
            override fun onDone(file: File, count: Int) {
                if (isFinishing || isDestroyed) return
                dismissLoadingDialog()
                val text = "已导出 $count 条订阅"
                LogStore.log(Category.SUBSCRIPTION, "订阅: 导出成功 " + file.name + "(" + text + ")")
                AppBubble.toast(text)
                exitExportMode()
                shareExport(file)
            }

            override fun onError(message: String) {
                if (isFinishing || isDestroyed) return
                dismissLoadingDialog()
                LogStore.fail(Category.SUBSCRIPTION, "订阅: 导出失败 " + message)
                AppBubble.toast("导出失败")
            }
        })
    }

    /** 分享导出的订阅清单(与运行日志导出同一套 FileProvider 通道) */
    private fun shareExport(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                packageName + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "application/json"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "分享订阅清单"))
        } catch (t: Throwable) {
            t.printStackTrace()
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 分享导出文件失败 " + t)
            AppBubble.toast("分享失败")
        }
    }

    // ------------------------------------------------------------------
    // 「直播源」标签页:订阅自带的(跟着订阅走,不可删)+ 用户自建的(可加可删)
    // ------------------------------------------------------------------

    private fun setupTabs() {
        mBinding.tabVideo.setOnClickListener { switchTab(false) }
        mBinding.tabLive.setOnClickListener { switchTab(true) }
        // 内容区左右滑动也能切 tab(2026-10-01,用户口径"订阅管理 tab 为啥没法从底下那些区域左右滑动切 tab"):
        // 手势在 dispatchTouchEvent 里统一观察(见 TabSwipeHelper 的说明 —— 挂列表上会被条目吃掉事件)
        swipeTracker = TabSwipeHelper.tracker(this) { dir ->
            if (dir < 0) switchTab(true) else switchTab(false)   // 左滑 = 下一个 tab(直播源)
        }
        // 首帧把指示条摆到"视频源"下方(不播动画):等标签行量完尺寸再摆,顺手把它显示出来
        mBinding.llTabs.post { moveIndicator(false) }
    }

    /** 内容区左右滑动切 tab 的手势追踪(只观察、不消费事件) */
    private var swipeTracker: TabSwipeHelper.Tracker? = null
    private val tabPageAnimator = TabPageAnimator()

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        swipeTracker?.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** 切标签页:同一张卡片换数据;标题栏动作区(导出/添加)跟着换 */
    private fun switchTab(live: Boolean) {
        if (liveTabActive == live) return
        tabPageAnimator.finish()
        val outgoing = if (liveTabActive) mBinding.flLive else mBinding.flVideo
        val incoming = if (live) mBinding.flLive else mBinding.flVideo
        liveTabActive = live
        mBinding.flVideo.visibility = if (live) View.GONE else View.VISIBLE
        mBinding.flLive.visibility = if (live) View.VISIBLE else View.GONE
        styleTab(mBinding.tvTabVideo, !live)
        styleTab(mBinding.tvTabLive, live)
        // 指示条**滑**到目标 tab 下方(不是直接跳过去)
        moveIndicator(true)
        // 导出只服务视频源页
        mBinding.ivExport.visibility = if (live) View.GONE else View.VISIBLE
        if (live) {
            if (mSubscriptionAdapter.isExportMode) exitExportMode()
            if (mSubscriptionAdapter.getMode() == SubscriptionAdapter.Mode.DELETE) exitSelectMode()
            refreshLiveSources()
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
        val tab = if (liveTabActive) mBinding.tabLive else mBinding.tabVideo
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
        if (live != liveTabActive) switchTab(live)   // 切页会顺带退出另一种多选态
        if (mSubscriptionAdapter.isExportMode) exitExportMode()
        if (live) mLiveSourceAdapter.setSelectMode(true)
        else mSubscriptionAdapter.setMode(SubscriptionAdapter.Mode.DELETE)
        mBinding.llExportBar.visibility = View.GONE
        mBinding.selectActionBar.visibility = View.VISIBLE
        updateDeleteEnabled(0)
        LogStore.log(
            Category.SUBSCRIPTION,
            "订阅: 进入多选(" + (if (live) "直播源" else "订阅源") + "),共 " +
                (if (live) mLiveSourceAdapter.data.size else mSubscriptions.size) + " 项"
        )
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
            val msg = if (targets.any { it.isChecked }) {
                "所选包含当前正在使用的订阅,删除后将自动切换到其它订阅,确定删除吗？"
            } else {
                "确定删除所选的 " + targets.size + " 个订阅吗？"
            }
            ConfirmDialog.showDanger(this, "删除订阅", msg, "删除") {
                deleteSelectedSubscriptions(targets)
            }
        }
    }

    private fun deleteSelectedSubscriptions(targets: List<Subscription>) {
        if (batchImportInProgress) return
        for (sub in targets) removeSubscription(sub)
        persistSubscriptions()
        exitSelectMode()
        AppBubble.toast("已删除 " + targets.size + " 个订阅")
        LogStore.log(Category.SUBSCRIPTION, "订阅: 多选删除 " + targets.size + " 个订阅")
    }

    private fun deleteSelectedLiveSources(targets: List<LiveSourceEntries.Entry>) {
        val history = LiveConfig.liveHistory()
        for (entry in targets) {
            history.remove(entry.url)
            // 删掉的正是在用的那条 → 回到"用订阅自带的直播"
            if (entry.url == SystemConfig.getLiveUrl()) SystemConfig.setLiveUrl("")
        }
        LiveConfig.setLiveHistory(history)
        liveSourceChanged = true
        exitSelectMode()
        refreshLiveSources()
        AppBubble.toast("已删除 " + targets.size + " 个直播源")
        LogStore.log(Category.SUBSCRIPTION, "订阅: 多选删除 " + targets.size + " 个自建直播源")
    }

    /**
     * 删掉一条订阅:内部副本文件一并清理,并在删的是"当前订阅"时自动重选
     * (置顶优先,否则取首项;删光则清空当前)。调用方负责 [persistSubscriptions] 落库。
     */
    private fun removeSubscription(deleted: Subscription) {
        if (batchImportInProgress) return
        val provider = LiveChannelConfigProviders.get()
        if (provider.loadedSubscriptionUrl == deleted.url) {
            liveSourceChanged = true
            val selectedLiveUrl = SystemConfig.getLiveUrl()
            if (selectedLiveUrl.isNotEmpty() &&
                provider.subscribeLiveSources.any { it.url == selectedLiveUrl } &&
                !LiveConfig.liveHistory().contains(selectedLiveUrl)
            ) {
                SystemConfig.setLiveUrl("")
                LogStore.log(Category.SUBSCRIPTION,
                    "订阅: 删除 ${deleted.name} 后清除其直播源选中地址 $selectedLiveUrl")
            }
        }
        mSubscriptions.remove(deleted)
        deleteLibraryFileOf(deleted)   // 本地/JSON 导入的内部副本一并删掉,不留垃圾文件
        LogStore.log(Category.SUBSCRIPTION, "订阅: 删除 " + deleted.name)
        if (deleted.isChecked) {
            liveSourceChanged = true
            val next = mSubscriptions.firstOrNull { it.isTop } ?: mSubscriptions.firstOrNull()
            for (s in mSubscriptions) s.setChecked(false)
            if (next != null) {
                next.setChecked(true)
                mSelectedUrl = next.url
                LogStore.log(Category.SUBSCRIPTION, "订阅: 删除后自动切换到 " + next.name)
            } else {
                mSelectedUrl = ""
            }
            LogStore.log(Category.SUBSCRIPTION,
                "订阅: 删除当前配置 ${deleted.name}，已加载直播源等待新配置刷新")
        }
    }

    /** 订阅增删改后统一刷新 + 立即落库(避免 onPause 前被强杀导致改动丢失) */
    private fun persistSubscriptions() {
        mSubscriptionAdapter.notifyDataSetChanged()   //删除/选择只刷新,不触发重新排序
        updateEmptyState()
        if (liveTabActive) refreshLiveSources()       //订阅变了,直播源页的「来自:X」跟着变
        SubscriptionConfig.setApiUrl(mSelectedUrl)
        SubscriptionConfig.setSubscriptions(mSubscriptions)
    }

    /**
     * 重建直播源列表:订阅导入的在前(不可删)、用户自建的在后;勾中的那条 = 当前生效的直播源。
     * 订阅导入的**不落库** —— 每次都从当前订阅配置实时读([LiveChannelConfigProviders]),
     * 所以换订阅/更新订阅后自然"旧的消失、新的出现",与用户口径"跟着视频订阅源走"一致。
     */
    private fun refreshLiveSources() {
        val provider = LiveChannelConfigProviders.get()
        val imported = provider.subscribeLiveSources.takeIf {
            mSelectedUrl.isNotEmpty() && provider.loadedSubscriptionUrl == mSelectedUrl
        }.orEmpty().map {
            LiveSourceEntries.Imported(it.name, it.url)
        }
        val entries = LiveSourceEntries.build(
            currentName(),
            imported,
            LiveConfig.liveHistory(),
            SystemConfig.getLiveUrl()
        )
        mLiveSourceAdapter.setNewData(entries)
        val empty = entries.isEmpty()
        mBinding.rvLive.visibility = if (empty) View.GONE else View.VISIBLE
        mBinding.llEmptyLive.root.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            mBinding.llEmptyLive.tvEmptyText.text =
                "暂无直播源\n点右上角 + 添加自己的直播源\n订阅自带的直播源会自动出现在这里"
        }
    }

    /**
     * 勾选/取消一条直播源:<b>勾中即成为当前直播源</b>(直播页据此拉取);取消 = 回到"用订阅自带的直播"。
     * 内嵌分组(没有单独地址)不可选,由调用方拦掉。
     */
    private fun applyLiveSource(url: String) {
        val target = url ?: ""
        if (target == SystemConfig.getLiveUrl()) {
            refreshLiveSources()
            return
        }
        SystemConfig.setLiveUrl(target)
        liveSourceChanged = true
        refreshLiveSources()
        AppBubble.toast(
            if (target.isEmpty()) "已改用订阅直播源"
            else "直播源已切换"
        )
        LogStore.log(
            Category.SUBSCRIPTION,
            "订阅: 当前直播源改为 " + (if (target.isEmpty()) "(订阅自带)" else target)
        )
    }

    /** 删除用户自建的直播源(订阅导入的不给删:跟着订阅走,要删就删那条订阅) */
    private fun deleteUserLiveSource(entry: LiveSourceEntries.Entry) {
        if (!entry.removable() || entry.embedded()) return
        val message = if (entry.url == SystemConfig.getLiveUrl()) {
            "确定删除「${entry.name}」吗？删除后将改用订阅自带的直播源。"
        } else {
            "确定删除「${entry.name}」吗？"
        }
        ConfirmDialog.showDanger(this, "删除直播源", message, "删除") {
            performDeleteUserLiveSource(entry)
        }
    }

    private fun performDeleteUserLiveSource(entry: LiveSourceEntries.Entry) {
        val history = LiveConfig.liveHistory()
        val inHistory = history.remove(entry.url)
        if (inHistory) LiveConfig.setLiveHistory(history)
        // 删掉的正是在用的那条(含"内置默认源"那类不在用户历史里的补行)→ 回到用订阅自带的直播
        val wasActive = entry.checked || entry.url == SystemConfig.getLiveUrl()
        if (wasActive) SystemConfig.setLiveUrl("")
        if (!inHistory && !wasActive) return
        liveSourceChanged = true
        refreshLiveSources()
        AppBubble.toast("直播源已删除")
        LogStore.log(Category.SUBSCRIPTION, "订阅: 删除自建直播源 " + entry.url)
    }

    /** 添加用户自建的直播源:复用设置页那套输入弹窗(填地址,确认即启用并记入用户历史) */
    private fun showAddLiveSource() {
        val before = SystemConfig.getLiveUrl()
        val popup = DialogCoordinator.center(this, LiveApiDialog(this) {
            // 只有"确定保存"才会回调:地址真变了就标记待重载,并立刻刷新列表勾选态
            if (SystemConfig.getLiveUrl() != before) liveSourceChanged = true
            refreshLiveSources()
        })
        popup.show()
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
        if (liveSourceChanged) {
            // 直播源变了:回首页让配置重载 —— 直播页用的"待拉取直播源"是加载配置时定下的
            // (与设置页改直播源同一个处理:不带 CACHE_CONFIG_CHANGED 回首页重新加载)
            LogStore.log(Category.SUBSCRIPTION, "订阅: 直播源有变更,退出本页时重载配置")
            if (mBeforeUrl == mSelectedUrl) {
                jumpActivity(MainActivity::class.java)
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            }
            finish()
            return
        }
        super.onBackPressed()
    }

    /**
     * 编辑已有订阅(名称 + 地址就地修改,不再只能"删了重加"):
     * 复用添加弹窗(预填当前名称/地址),保存后按需重载当前订阅。
     */
    private fun showEditSubscription(position: Int) {        if (position >= mSubscriptions.size) return
        val item = mSubscriptions[position]
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
        if (position >= mSubscriptions.size) return
        val item = mSubscriptions[position]
        val oldUrl = item.url
        if (url != oldUrl) {
            val dup = mSubscriptions.firstOrNull { it !== item && it.url == url }
            if (dup != null) {
                AppBubble.toast("订阅地址已存在")
                return
            }
        }
        val wasChecked = item.isChecked
        item.name = name
        item.url = url
        if (checked && !wasChecked) {   // 原本未启用时,按弹窗勾选决定是否切到它
            for (s in mSubscriptions) s.setChecked(false)
            item.setChecked(true)
            mSelectedUrl = url
        }
        mSubscriptionAdapter.notifyItemChanged(position)
        updateEmptyState()
        SubscriptionConfig.setSubscriptions(mSubscriptions)
        LogStore.log(Category.SUBSCRIPTION, "订阅: 编辑 " + name)
        AppBubble.toast("已保存")
        // 改的就是当前启用订阅的地址 → 按新地址重载配置(与切换订阅同一套:清任务栈回首页)
        if (wasChecked && oldUrl != url) {
            SubscriptionConfig.setApiUrl(url)
            val intent = Intent(this, MainActivity::class.java)
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }

    /**
     * 订阅列表空态:无订阅时展示空态占位(列表隐藏),否则展示列表
     */
    private fun updateEmptyState() {
        val empty = mSubscriptions.isEmpty()
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
        if (mSubscriptions.isNotEmpty() || debugDomainWarned) return
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

    /**
     * 本地导入(系统 SAF 文件选择器;替代 hedzr 反射 StorageVolume 的老实现):
     * 选择 txt/json 后,主卷文件(clan 服务器可直接按路径读)转真实路径、以 clan:// 引用原文件;
     * 其它存储提供方(下载/云盘/第三方文件管理器等,SAF 授权读取但无主卷路径)复制进应用专属目录后
     * 再以 clan:// 引用副本——任何能在系统文件管理器里打开的文件均可导入。
     * @param checked 是否在导入成功后默认启用该订阅(导入菜单勾选状态,先记录后使用)
     */
    private var mPendingChecked = true
    private val pickLocalDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleLocalDoc(it) }
    }

    private fun pickFile(checked: Boolean) {
        mPendingChecked = checked
        pickLocalDoc.launch(arrayOf("*/*"))
    }

    private fun handleLocalDoc(uri: Uri) {
        try {
            addOrigin = Subscription.ORIGIN_LOCAL   // 本地导入:整条链路(含嗅探出来的单源)都记 local
            val nameRaw = queryDisplayName(uri)
            val name = nameRaw?.trim()
            LogStore.log(Category.SUBSCRIPTION, "订阅: 本地导入选择文件 " + name + " (" + uri + ")")
            if (name.isNullOrEmpty() ||
                !name.lowercase().endsWith(".txt") && !name.lowercase().endsWith(".json")
            ) {
                AppBubble.toast("请选择 txt/json 订阅文件")
                return
            }
            // 本地导入一律**复制一份进应用内部**再当订阅用(不再直接引用原文件):
            // 用户导入完把原文件删了/改了都不影响订阅;导出时也从这份内部副本取内容(见 SubscriptionExporter)
            val importFile = importCopyOf(uri, name)
            if (importFile == null || !isUnderPrimaryStorage(importFile)) {
                AppBubble.toast("文件无法读取，请选本机订阅文件")
                return
            }
            // 订阅清单式文件(形如 [{name,url},...],同本机调试用的默认订阅清单格式):
            // 读取内容解析为多条订阅加入;识别失败则回落为"单个 clan:// 文件源"加入
            if (importSubscriptionList(importFile, name, mPendingChecked)) {
                SubscriptionConfig.setLastImportDir(importFile.parent)
                return
            }
            // 文件内容按"能不能当订阅用"过一遍(与 JSON 粘贴导入同一套识别规则):
            // 既补上"清单/多线路/多仓/单条"的展开,也把「阅读」视频书源交给 JSON 接口或站点识别,
            // 其余不是订阅配置的内容(如频道列表)不落盘——
            // 这类内容存成 clan:// 订阅只会让应用每次启动都"解析配置失败",还会把当前可用订阅挤掉
            val text = readImportText(importFile)
            if (text != null) {
                val root = try {
                    JsonParser.parseString(text)
                } catch (t: Throwable) {
                    null
                }
                if (root != null && importJsonEntries(root, mPendingChecked, "文件", Subscription.ORIGIN_LOCAL)) {
                    SubscriptionConfig.setLastImportDir(importFile.parent)
                    return
                }
                if (CmsApiRules.looksLikeBookSource(text)) {
                    sniffBookSourceSite(text, mPendingChecked)
                    return
                }
                val resolved = resolveSubscriptionContent(text, name)
                if (resolved == null) {
                    return
                }
                if (resolved.rewritten) {
                    // 裸站点条目/数组:补好的 sites 外壳另存副本(clan 服务器读副本,不再引用原文件)
                    saveJsonSubscription(resolved.content, mPendingChecked)
                    return
                }
            }
            addLocalFileSubscription(importFile, name, mPendingChecked)
        } catch (t: Throwable) {
            t.printStackTrace()
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 本地导入读取文件失败")
            AppBubble.toast("读取所选文件失败")
        }
    }

    /** 应用专属导入目录(外部存储根下,clan:// 副本可被本地文件服务器读取,无需额外存储权限) */
    private fun importDir(): File {
        val dir = File(getExternalFilesDir(null), "subscription_import")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 订阅清单文件导入(形如 assets 默认订阅 [{name,url},...]):
     * 读取内容识别为"清单数组"后逐条去重;单条远端地址复用自定义导入的在线识别,
     * 多条清单中的远端地址逐条在线识别;带内嵌内容的本地条目仍可离线恢复;
     * 识别失败/无有效条目返回 false,由调用方按"单个 clan:// 文件源"回落,不影响原有导入。
     * @return true=已按清单处理(可能 0 条新增);false=非目标格式
     */
    private fun importSubscriptionList(file: File, displayName: String, checked: Boolean): Boolean {
        val text = try {
            file.readText(Charsets.UTF_8).trim()
        } catch (t: Throwable) {
            return false
        }
        if (text.isEmpty() || text[0] != '[') return false
        val entries = try {
            JsonParser.parseString(text).asJsonArray
        } catch (t: Throwable) {
            return false // 非目标格式(如单源配置 JSON 对象),回落旧逻辑
        }
        if (CmsApiRules.looksLikeBookSource(text)) return false
        return importSubscriptionEntries(entries, checked, displayName, Subscription.ORIGIN_LOCAL)
    }

    /**
     * 订阅清单逐条加入(本地清单导入与 JSON 粘贴导入共用):
     * 条目兼容三种写法 —— ①**导出清单** `{name,type,origin,url[,file,content]}` ②`{name,url}` ③`{sourceName,sourceUrl}`;
     * 带 `content` 的条目(导出清单里"本地文件"那类订阅)先把原文落成内部库文件、再按 clan:// 加入,
     * 换机导入、原文件已删都能还原;远端地址不论单条或多条都在线识别后再加入。
     * 与本机已有订阅按地址去重。
     * @param origin 条目自身没写 origin 时的兜底来源(本地文件导入=local / JSON 粘贴=json)
     * @return true=已按清单处理(可能 0 条新增);false=无有效条目(调用方回落)
     */
    private fun importSubscriptionEntries(entries: JsonArray, checked: Boolean, label: String,
                                         origin: String, onComplete: (() -> Unit)? = null): Boolean {
        val parsed = ArrayList<Subscription>()
        var fromContent = 0   // 清单里带 content 的条目(导出清单的本地文件订阅)还原计数
        var candidates = 0
        var invalid = 0
        for (el in entries) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            if (!SubscriptionImportRules.mayBeListEntry(obj)) continue
            val name = obj.stringValue("name").trim().ifEmpty { obj.stringValue("sourceName").trim() }
            val url = obj.stringValue("url").trim().ifEmpty { obj.stringValue("sourceUrl").trim() }
            val itemOrigin = obj.stringValue("origin").trim().ifEmpty { origin }
            val rawContent = obj.stringValue("content")
            if (url.isEmpty() && rawContent.isBlank()) continue
            candidates++
            if (rawContent.isNotBlank()) {
                val content = SubscriptionImportRules.normalizedContent(rawContent)
                val file = content?.let { writeLibraryFile(obj.stringValue("file"), name, it) }
                if (file == null) {
                    invalid++
                    LogStore.fail(Category.SUBSCRIPTION,
                        "订阅: 清单条目无效，内部文件写入失败 ${name.ifEmpty { url }}")
                    continue
                }
                parsed.add(Subscription(name.ifEmpty { file.nameWithoutExtension }, clanPathOf(file), itemOrigin))
                fromContent++
                continue
            }
            if (name.isNotEmpty() && SubscriptionImportRules.isSupportedAddress(url)) {
                parsed.add(Subscription(name, url, itemOrigin))
            } else {
                invalid++
                LogStore.fail(Category.SUBSCRIPTION,
                    "订阅: 清单条目无效，名称或地址不符合格式 ${name.ifEmpty { "(无名称)" }} $url")
            }
        }
        if (parsed.isEmpty()) {
            if (candidates == 0) return false
            AppBubble.toast("清单中无有效订阅")
            onComplete?.invoke()
            return true
        }
        if (fromContent > 0) {
            LogStore.log(Category.SUBSCRIPTION, "订阅: 清单还原本地文件条目 " + fromContent + " 个(内嵌内容已写入内部目录)")
        }

        val wasEmpty = mSubscriptions.isEmpty()
        // 只有一条远端地址时按自定义导入拉取并识别内容，避免把无效配置地址直接收进列表。
        if (parsed.size == 1 && parsed[0].url.startsWith("http")) {
            val single = parsed[0]
            addOrigin = single.origin
            addSubscription(single.name, single.url, checked || wasEmpty, onComplete)
            return true
        }
        val pending = parsed.distinctBy { it.url }
        var added = 0
        var failed = invalid
        var duplicates = parsed.size - pending.size
        var index = 0
        var processed = 0
        val parentBatch = batchImportInProgress
        val parentProgress = batchProgress
        if (!parentBatch) {
            importEpoch++
            importCancelled = false
            batchImportInProgress = true
        }
        LogStore.log(Category.SUBSCRIPTION,
            "订阅: 开始清单校验 $label 总数=${parsed.size} 待校验=${pending.size} 格式无效=$invalid 清单重复=${duplicates}")
        if (!parentBatch) {
            batchCancelAction = {
                importCancelled = true
                importEpoch++
                activeImportUrls.clear()
                batchImportInProgress = false
                batchCancelAction = null
                dismissLoadingDialog()
                persistSubscriptions()
                LogStore.log(Category.SUBSCRIPTION,
                    "订阅: 清单校验取消 $label 已处理=$processed/${pending.size} 成功=$added 失败=$failed 重复=$duplicates")
                AppBubble.toast("已取消导入，已保留 $added 条")
            }
            showCancelableLoading("正在准备校验 ${pending.size} 条订阅…") { batchCancelAction?.invoke() }
        }
        fun next() {
            if (isFinishing || isDestroyed || importCancelled) return
            if (index >= pending.size) {
                if (parentBatch) {
                    batchProgress = parentProgress
                } else {
                    batchImportInProgress = false
                    batchCancelAction = null
                    dismissLoadingDialog()
                }
                mSubscriptionAdapter.setNewData(mSubscriptions)
                updateEmptyState()
                if (!parentBatch) persistSubscriptions()
                LogStore.log(Category.SUBSCRIPTION,
                    "订阅: 清单校验完成 " + label + " 成功=" + added + " 失败=" + failed + " 重复=" + duplicates)
                if (!parentBatch) AppBubble.toast("已导入 $added 条" +
                    (if (failed > 0) "，跳过 $failed 条无效或不可用地址" else "") +
                    (if (duplicates > 0) "，重复 $duplicates 条" else ""))
                onComplete?.invoke()
                return
            }
            val item = pending[index++]
            batchProgress = "正在校验订阅 $index/${pending.size}：${item.name}（已导入 $added，失败 $failed，重复 $duplicates）"
            updateImportHint(item.url)
            if (mSubscriptions.any { it.url == item.url }) {
                duplicates++
                processed++
                LogStore.log(Category.SUBSCRIPTION, "订阅: 清单跳过重复项 $index/${pending.size} ${item.name} ${item.url}")
                mBinding.root.post { next() }
                return
            }
            val before = mSubscriptions.size
            addOrigin = item.origin
            addSubscription(item.name, item.url, (checked || wasEmpty) && added == 0,
                onComplete = {
                    if (mSubscriptions.size > before) {
                        val count = mSubscriptions.size - before
                        added += count
                        LogStore.success(Category.SUBSCRIPTION,
                            "订阅: 清单条目成功 $index/${pending.size} ${item.name} ${item.url} 新增=$count")
                    } else {
                        failed++
                        LogStore.fail(Category.SUBSCRIPTION,
                            "订阅: 清单条目失败 $index/${pending.size} ${item.name} ${item.url} 未加入订阅列表")
                    }
                    processed++
                    persistSubscriptions()
                    mBinding.root.post { next() }
                }, loadingHint = batchProgress)
        }
        next()
        return true
    }

    /**
     * JSON 粘贴导入:先按已知订阅格式识别;单条地址复用自定义导入的在线校验,
     * 多条清单逐项校验远端地址,内嵌配置可离线恢复,整份配置按本地文件导入的内容规则判定后落盘。
     * <pre>
     * 1) 清单数组          [{name,url}...] / [{sourceName,sourceUrl}...]
     * 2) 多线路            {"urls":[{name,url}...]}
     * 3) 多仓              {"storeHouse":[{sourceName,sourceUrl}...]} → 弹窗选仓
     * 4) 单条订阅          {"name":..,"url":..} / {"sourceName":..,"sourceUrl":..}
     * 5) 其它(单源规则配置、整份配置等)→ 存为本地 json 并以 clan:// 订阅加入(与本地导入一致)
     * </pre>
     * 「阅读」视频书源先按 sortUrl / 规则字段接入 JSON 接口,其余回落站点识别;
     * 其它内容落盘前过 [resolveSubscriptionContent],
     * 无效内容不存成 clan:// 订阅,避免启动时"解析配置失败"并挤掉当前可用订阅。
     * @param checked 是否在导入成功后默认启用(导入菜单勾选状态)
     */
    private fun importJsonText(json: String, checked: Boolean) {
        addOrigin = Subscription.ORIGIN_JSON   // JSON 导入
        val text = json.trim()
        if (text.isEmpty()) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: JSON 导入内容为空")
            AppBubble.toast("请输入 JSON 内容")
            return
        }
        val root = try {
            JsonParser.parseString(text)
        } catch (t: Throwable) {
            null
        }
        if (root == null) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: JSON 格式不正确 字符=${text.length}")
            AppBubble.toast("JSON 格式不正确")
            return
        }
        LogStore.log(Category.SUBSCRIPTION, "订阅: JSON 导入提交 " + text.length + " 字符")
        // 1) 清单数组 / 多线路 / 多仓 / 单条订阅
        if (importJsonEntries(root, checked, "JSON")) return
        if (CmsApiRules.looksLikeBookSource(text)) {
            sniffBookSourceSite(text, checked)
            return
        }
        // 2) 兜底:存为本地 json 文件,以 clan:// 订阅加入
        val resolved = resolveSubscriptionContent(text, "JSON导入")
        if (resolved == null) {
            return
        }
        saveJsonSubscription(resolved.content, checked)
    }

    /**
     * JSON 文本按"清单数组 / 多线路 / 多仓 / 单条订阅"识别展开(JSON 粘贴导入与本地文件导入共用):
     * 命中即按对应逻辑加入并返回 true;识别不了返回 false,由调用方按"订阅配置/本地文件"兜底。
     * @param label 日志与提示里的来源说明(如 "JSON"/"文件")
     */
    private fun importJsonEntries(root: JsonElement, checked: Boolean, label: String,
                                    origin: String = Subscription.ORIGIN_JSON): Boolean {
        // 1) 清单数组 / 多仓数组
        if (root.isJsonArray && importSubscriptionEntries(root.asJsonArray, checked, label + "清单", origin)) return true
        if (!root.isJsonObject) return false
        val obj = root.asJsonObject
        // 2) 多线路
        val urls = obj.get("urls")
        if (urls != null && urls.isJsonArray
            && importSubscriptionEntries(urls.asJsonArray, checked, label + "多线路", origin)
        ) return true
        // 3) 多仓(列出仓源让用户选择)
        val storeHouse = obj.get("storeHouse")
        if (storeHouse != null && storeHouse.isJsonArray && isStoreHouseList(storeHouse.asJsonArray)) {
            showStoreHouseChoose(storeHouse.asJsonArray, checked)
            return true
        }
        // 4) 单条订阅走自定义地址的同一条拉取/识别链路;清单则保留批量恢复语义。
        if (SubscriptionImportRules.mayBeListEntry(obj)) {
            val single = subscriptionOf(obj, "name", "url") ?: subscriptionOf(obj, "sourceName", "sourceUrl")
            if (single != null) {
                addOrigin = origin
                if (obj.stringValue("content").isNotBlank()) {
                    val one = JsonArray()
                    one.add(obj)
                    return importSubscriptionEntries(one, checked, label + "订阅", origin)
                }
                addSubscription(single.name, single.url, checked)
                return true
            }
        }
        return false
    }

    /** 订阅导入判定结果:@param rewritten 内容被补过 sites 外壳(须另存副本,不能引用原文件) */
    private class SubscriptionContent(val content: String, val rewritten: Boolean)

    /**
     * 判定一段内容能不能当订阅配置(JSON 粘贴导入与本地文件导入共用),并按需补 {"sites":[…]} 外壳。
     * 不能用的内容一律不落盘:存成 clan:// 订阅只会让应用每次启动都"解析配置失败",还会挤掉当前可用订阅。
     * @return null=不是 TVBox 订阅配置(已提示用户并记日志)
     */
    private fun resolveSubscriptionContent(text: String, label: String): SubscriptionContent? {
        val trimmed = text.trim()
        // 裸站点条目/数组(用户从别处复制的单站配置):补外壳后当单源订阅用
        val wrapped = CmsApiRules.wrapSiteJson(trimmed)
        if (wrapped != null && wrapped != trimmed) {
                LogStore.log(Category.SUBSCRIPTION, "订阅: 裸站点内容补 sites 外壳 " + label)
            return SubscriptionContent(wrapped, true)
        }
        return when (CmsApiRules.subscriptionShape(trimmed)) {
            // 完整配置 / 加密套路(加载阶段解码):原样存
            CmsApiRules.SHAPE_CONFIG, CmsApiRules.SHAPE_ENCRYPTED -> SubscriptionContent(trimmed, false)
            // 裸站点类内容在上一段就该被补壳改写(判定与补壳用的是同一套规则),走到这说明补壳失败,不落盘
            else -> {
                rejectSubscriptionContent(trimmed, label)
                null
            }
        }
    }

    /** 拒绝落盘时按内容形态给用户能懂的原因(「阅读」书源 / 只有直播源 / 单站条目 / 缺 sites),并记日志 */
    private fun rejectSubscriptionContent(text: String, label: String) {
        val reason = when (CmsApiRules.subscriptionShape(text)) {
            CmsApiRules.SHAPE_BOOK_SOURCE -> "这是阅读书源，无法作为订阅"
            CmsApiRules.SHAPE_LIVES -> "仅含直播源，无法作为订阅"
            CmsApiRules.SHAPE_SITE -> "单站条目，请用 JSON 导入"
            else -> "缺少站点列表，无法导入"
        }
        LogStore.fail(Category.SUBSCRIPTION, "订阅: 拒绝导入非订阅内容 " + reason + " " + label)
        showImportToast(reason)
    }

    /** 阅读视频书源优先按静态 JSON 接口规则验证；不匹配时再探测 CMS 接口与页面。 */
    private fun sniffBookSourceSite(text: String, checked: Boolean) {
        if (!CmsApiRules.looksLikeBookSource(text)) return
        val videoSpec = LegadoVideoRules.parse(text)
        if (videoSpec != null) {
            importEpoch++
            importCancelled = false
            val epoch = importEpoch
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            legadoImportCancelled?.set(true)
            legadoImportCancelled = cancelled
            LogStore.log(Category.SUBSCRIPTION, "订阅: JSON识别为阅读视频接口源 " + videoSpec.name
                + " 分类=${videoSpec.routes.size} 地址=${videoSpec.host}")
            showCancelableLoading("正在验证阅读视频源…") {
                cancelled.set(true)
                if (legadoImportCancelled === cancelled) legadoImportCancelled = null
                importCancelled = true
                importEpoch++
                dismissLoadingDialog()
                LogStore.log(Category.SUBSCRIPTION, "订阅: 取消阅读视频源导入 ${videoSpec.name}")
                AppBubble.toast("已取消")
            }
            LegadoVideoImporter.probe(videoSpec, importDir(), cancelled, object : LegadoVideoImporter.Callback {
                override fun onFound(name: String, configFile: File) {
                    if (isFinishing || isDestroyed || importCancelled || epoch != importEpoch) return
                    if (legadoImportCancelled === cancelled) legadoImportCancelled = null
                    dismissImportLoading()
                    val before = mSubscriptions.size
                    addLocalFileSubscription(configFile, name, checked)
                    mSubscriptionAdapter.setNewData(mSubscriptions)
                    updateEmptyState()
                    if (mSubscriptions.size > before) showImportToast("阅读视频源已接入")
                }

                override fun onNotFound(reason: String) {
                    if (isFinishing || isDestroyed || importCancelled || epoch != importEpoch) return
                    if (legadoImportCancelled === cancelled) legadoImportCancelled = null
                    dismissImportLoading()
                    showImportToast("书源验证失败：$reason")
                }
            }, LegadoVideoImporter.Progress { hint ->
                if (!isFinishing && !isDestroyed && !importCancelled && epoch == importEpoch) {
                    updateImportHint(hint)
                }
            })
            return
        }
        val siteUrl = CmsApiRules.bookSourceSiteUrl(text)
        LogStore.log(Category.SUBSCRIPTION, "订阅: 阅读书源未匹配静态 JSON 视频规则，转站点接口识别 " + siteUrl)
        if (siteUrl == null || !CmsApiRules.looksLikeSiteUrl(siteUrl)) {
            AppBubble.toast("书源缺少可用站点地址")
            return
        }
        LogStore.log(Category.SUBSCRIPTION, "订阅: 「阅读」书源改用站点地址识别 " + siteUrl)
        showLoadingDialog("正在读取地址…")
        // 带上完整书源文本:抓页面时用 sortUrl 的分类、搜索路径及子目录。
        sniffSource(guessJsonName(text) ?: "", siteUrl, null, CmsSiteImporter.InputKind.SITE, checked, text)
    }

    /**
     * 采集接口走不通时的兜底:按苹果CMS 站点页面结构抓(首页分类 → 分类页 → 详情页 → 播放页),
     * 探通后生成指向 {@code assets://js/lib/maccms.js} 的单源配置加入订阅。
     * 探不通不加(宁缺毋滥:打不开的订阅会让应用每次启动都"解析配置失败")。
     */
    private fun scrapeSite(name: String, siteUrl: String, hintText: String?, checked: Boolean,
                           onComplete: (() -> Unit)? = null) {
        val epoch = importEpoch
        updateImportHint("正在按站点页面识别…\n" + CmsApiRules.displayHost(siteUrl))
        LogStore.log(Category.SUBSCRIPTION, "订阅: 采集接口不可用,改抓站点页面 " + siteUrl)
        HtmlSiteImporter.probe(siteUrl, hintText, importDir(), object : HtmlSiteImporter.Callback {
            override fun onFound(siteName: String, file: File, samplePlayUrl: String) {
                if (isFinishing || isDestroyed || importCancelled || epoch != importEpoch) return
                dismissImportLoading()
                val display = if (isDefaultSubName(name)) siteName else name.trim()
                LogStore.log(Category.SUBSCRIPTION, "订阅: 站点页面抓取成功 " + display)
                addLocalFileSubscription(file, display, checked)
                showImportToast("站点已接入")
                onComplete?.invoke()
            }

            override fun onNotFound() {
                if (isFinishing || isDestroyed || importCancelled || epoch != importEpoch) return
                dismissImportLoading()
                LogStore.fail(Category.SUBSCRIPTION, "订阅: 采集接口与站点页面均不可用 " + siteUrl)
                Log.e("SubscriptionImport", "采集接口与站点页面均不可用: ${CmsApiRules.displayHost(siteUrl)}")
                showImportToast("未找到可用站点接口")
                onComplete?.invoke()
            }
        }, object : HtmlSiteImporter.Progress {
            override fun onStep(hint: String) {
                if (isFinishing || isDestroyed || importCancelled || epoch != importEpoch) return
                updateImportHint(hint)
            }
        })
    }

    /**
     * 远端响应能否直接存成订阅地址:完整配置({@code sites} 数组)与加密套路可用;
     * 裸站点条目/清单不行——那要就地补壳另存,只能走 JSON 粘贴或本地文件导入。
     */
    private fun isLoadableSubscription(text: String): Boolean {
        return when (CmsApiRules.subscriptionShape(text.trim())) {
            CmsApiRules.SHAPE_CONFIG, CmsApiRules.SHAPE_ENCRYPTED -> true
            else -> false
        }
    }

    /**
     * 读本地导入文件文本用于内容判定;文件过大(订阅配置不可能这么大)或读不出返回 null,
     * 调用方按旧逻辑"原样当 clan:// 订阅加入",不因判定失败阻断导入。
     */
    private fun readImportText(file: File): String? {
        if (file.length() > MAX_JUDGE_BYTES) return null
        return try {
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 未识别的 JSON(单源规则配置、整份配置等):按内容摘要存成应用专属目录下的 json 文件,
     * 再以 clan:// 本地订阅加入;启用时按普通订阅配置拉取解析。
     */
    private fun saveJsonSubscription(text: String, checked: Boolean) {
        try {
            val name = guessJsonName(text) ?: "本地JSON导入"
            val base = sanitizeImportName(name)
            val dot = base.lastIndexOf('.')
            val stem = if (dot > 0) base.substring(0, dot) else base
            val file = File(importDir(), stem + "_" + contentDigest(text) + ".json")
            if (!file.exists()) file.writeText(text, Charsets.UTF_8)
            LogStore.log(Category.SUBSCRIPTION, "订阅: JSON 导入 " + name + "(本地文件)")
            addLocalFileSubscription(file, name, checked)
        } catch (t: Throwable) {
            t.printStackTrace()
            LogStore.fail(Category.SUBSCRIPTION, "订阅: JSON 导入保存本地文件失败")
            AppBubble.toast("JSON 导入失败")
        }
    }

    /** 从 JSON 里猜一个订阅名:sourceName / name / title / sites[0].name,取不到返回 null */
    private fun guessJsonName(text: String): String? {
        val root = try {
            JsonParser.parseString(text)
        } catch (t: Throwable) {
            return null
        }
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        for (key in arrayOf("sourceName", "name", "title")) {
            val v = obj.stringValue(key).trim()
            if (v.isNotEmpty()) return v.take(20)
        }
        val sites = obj.get("sites")
        if (sites != null && sites.isJsonArray && sites.asJsonArray.size() > 0 && sites.asJsonArray[0].isJsonObject) {
            val v = sites.asJsonArray[0].asJsonObject.stringValue("name").trim()
            if (v.isNotEmpty()) return v.take(20)
        }
        return null
    }

    /**
     * 以 clan:// 本地文件订阅加入列表(本地导入与 JSON 导入共用):
     * 校验文件位于主存储、记忆导入目录,按 clan:// 地址去重后加入。
     */
    private fun addLocalFileSubscription(file: File, name: String, checked: Boolean) {
        if (!isUnderPrimaryStorage(file)) {
            showImportToast("请选本机存储中的订阅文件")
            return
        }
        // 记忆导入目录(与旧文件选择器一致:以父目录为准)
        SubscriptionConfig.setLastImportDir(file.parent)
        val clanPath = clanPathOf(file)
        for (item in mSubscriptions) {
            if (item.url == clanPath) {
                showImportToast("订阅地址已存在")
                return
            }
        }
        addSubscription(name, clanPath, checked)
    }

    /** 内部订阅文件 → clan:// 订阅地址(clan 本地服务按主存储相对路径取文件) */
    private fun clanPathOf(file: File): String =
        "clan://localhost" + file.absolutePath.removePrefix("/storage/emulated/0")

    /**
     * clan:// 地址 → 内部订阅库里的文件;**只认本应用内部目录下的路径**(防止删订阅时误删别处文件,或
     * 导出时读到不该读的文件);不在内部目录内返回 null。
     */
    private fun libraryFileOf(url: String): File? {
        val prefix = "clan://localhost/"
        if (!url.startsWith(prefix)) return null
        val f = File("/storage/emulated/0" + url.removePrefix(prefix))
        val dir = importDir()
        return if (f.absolutePath.startsWith(dir.absolutePath + File.separator)) f else null
    }

    /**
     * 把文本内容落成内部订阅库文件(导出清单里的本地条目导入时用):
     * 文件名优先沿用清单里的 file(同名内容直接复用),否则按"名字_内容摘要"生成。
     */
    private fun writeLibraryFile(suggestedName: String, displayName: String, content: String): File? {
        return try {
            val base = sanitizeImportName(suggestedName.ifBlank { displayName.ifBlank { "导入订阅" } })
            val dot = base.lastIndexOf('.')
            val stem = if (dot > 0) base.substring(0, dot) else base
            val ext = if (dot > 0) base.substring(dot) else ".json"
            val file = File(importDir(), stem + "_" + contentDigest(content) + ext)
            if (!file.exists()) file.writeText(content, Charsets.UTF_8)
            file
        } catch (t: Throwable) {
            t.printStackTrace()
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 写入内部订阅文件失败 " + t)
            null
        }
    }

    /** 删除订阅时清掉它的内部副本(仅本地导入/JSON 导入的 clan:// 条目有);非内部文件空操作 */
    private fun deleteLibraryFileOf(sub: Subscription?) {
        val f = sub?.let { libraryFileOf(it.url) } ?: return
        try {
            if (f.isFile && f.delete()) {
                LogStore.log(Category.SUBSCRIPTION, "订阅: 删除本地订阅文件 " + f.name)
            }
        } catch (t: Throwable) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 删除订阅内部文件失败 " + t)
        }
    }

    /** 从 JSON 对象取订阅条目(名称与地址都非空才算有效) */
    private fun subscriptionOf(obj: JsonObject, nameKey: String, urlKey: String): Subscription? {
        val name = obj.stringValue(nameKey).trim()
        val url = obj.stringValue(urlKey).trim()
        return if (name.isNotEmpty() && url.isNotEmpty()) Subscription(name, url) else null
    }

    /** 安全取 JSON 字符串字段(缺失/非字符串返回空串) */
    private fun JsonObject.stringValue(key: String): String {
        val el = get(key) ?: return ""
        return if (el.isJsonPrimitive) el.asString else ""
    }

    /** 是否多仓格式:首条为对象且带 sourceName/sourceUrl */
    private fun isStoreHouseList(list: JsonArray): Boolean {
        return list.size() > 0 && list[0].isJsonObject
            && list[0].asJsonObject.has("sourceName") && list[0].asJsonObject.has("sourceUrl")
    }

    /**
     * 是否"配置类"JSON(整份 TVBox 配置或带规则字段的单源配置):
     * 这类内容不能当订阅条目解析,只能存成本地文件按订阅配置加载。
     */
    private fun looksLikeConfigJson(obj: JsonObject): Boolean {
        for (key in obj.keySet()) {
            if (key.startsWith("rule") || key == "sites" || key == "lives" || key == "spider"
                || key == "parses" || key == "wallpaper" || key == "rules" || key == "storeHouse"
            ) return true
        }
        return false
    }

    /** 多仓 {"storeHouse":[{sourceName,sourceUrl},...]}:列出仓源供用户选择后加入(订阅地址返回多仓时同一套逻辑) */
    private fun showStoreHouseChoose(storeHouseList: JsonArray, checked: Boolean,
                                     onComplete: (() -> Unit)? = null) {
        mSources.clear()
        for (el in storeHouseList) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val name = obj.stringValue("sourceName").trim().replace("<|>|《|》|-".toRegex(), "")
            val url = obj.stringValue("sourceUrl").trim()
            if (name.isNotEmpty() && url.isNotEmpty()) mSources.add(Source(name, url))
        }
        if (mSources.isEmpty()) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 多仓配置中没有可用的源")
            showImportToast("多仓配置中没有可用的源")
            onComplete?.invoke()
            return
        }
        XPopup.Builder(this)
            .asCustom(
                ChooseSourceDialog(
                    this,
                    mSources,
                    { position: Int, _: String? ->
                    // 再根据多线路格式获取配置,如果仓内是正常多线路模式,name没用,直接使用线路的命名
                    resumeBatchLoading()
                    addSubscription(
                        mSources[position].sourceName,
                        mSources[position].sourceUrl,
                        checked,
                        onComplete
                    )
                    },
                    Runnable {
                        resumeBatchLoading()
                        onComplete?.invoke()
                    }
                ))
            .show()
    }

    /**
     * 把 SAF 选中的文件内容复制进应用专属导入目录。
     * 使用 URI 摘要作为副本名,重复选择同一文件时复用已有副本。
     */
    private fun importCopyOf(uri: Uri, displayName: String): File? {
        return try {
            val base = sanitizeImportName(displayName)
            val dot = base.lastIndexOf('.')
            val stem = if (dot > 0) base.substring(0, dot) else base
            val ext = if (dot > 0) base.substring(dot) else ""
            val target = File(importDir(), stem + "_" + uriDigest(uri) + ext)
            if (target.exists()) return target
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: return null
            target
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    private fun uriDigest(uri: Uri): String = contentDigest(uri.toString())

    /** 文本内容摘要(导入副本命名去重用:同一文件/同一 JSON 重复导入复用已有副本) */
    private fun contentDigest(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 清理文件名中不能出现在真实路径的字符,并确保带 txt/json 扩展名 */
    private fun sanitizeImportName(displayName: String): String {
        var n = displayName.replace(Regex("[/\\\\:*?\"<>|\\u0000\\s]"), "_").trim()
        if (!n.lowercase().endsWith(".txt") && !n.lowercase().endsWith(".json")) {
            n += ".txt"
        }
        if (n.length > 80) n = n.substring(0, 80)
        return n
    }

    /** 查询所选文档的显示名(取不到时用 uri 末段兜底) */
    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (t: Throwable) {
            uri.lastPathSegment
        }
    }

    private fun isUnderPrimaryStorage(file: File): Boolean {
        return try {
            val root = Environment.getExternalStorageDirectory().canonicalFile
            val candidate = file.canonicalFile
            candidate == root || candidate.path.startsWith(root.path + File.separator)
        } catch (t: Throwable) {
            false
        }
    }

    /** 支持更多存储提供方转真实路径;主要支持 primary/home 等可被 clan 服务器按路径读取的存储 */
    private fun addSubscription(name: String, url: String, checked: Boolean,
                                onComplete: (() -> Unit)? = null,
                                loadingHint: String = "正在读取地址…") {
        LogStore.log(Category.SUBSCRIPTION, "订阅: 开始导入 $name $url (来源=$addOrigin)")
        if (!SubscriptionImportRules.isSupportedAddress(url)) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 地址格式不正确 $url")
            showImportToast("订阅地址格式不正确")
            onComplete?.invoke()
            return
        }
        val duplicate = mSubscriptions.firstOrNull { it.url == url }
        if (duplicate != null) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 地址已存在 $url")
            showImportToast("订阅地址已存在")
            onComplete?.invoke()
            return
        }
        if (url.startsWith("clan://")) {
            LogStore.log(Category.SUBSCRIPTION, "订阅: 新增 " + name + "(clan/" + addOrigin + ")")
            addSub2List(name, url, checked)
            mSubscriptionAdapter.setNewData(mSubscriptions)
            updateEmptyState()
            onComplete?.invoke()
        } else if (url.startsWith("http")) {
            if (!activeImportUrls.add(url)) {
                LogStore.fail(Category.SUBSCRIPTION, "订阅: 清单包含循环地址 $url")
                showImportToast("订阅清单包含循环地址")
                onComplete?.invoke()
                return
            }
            val finish: () -> Unit = {
                activeImportUrls.remove(url)
                onComplete?.invoke()
                Unit
            }
            // 从点击这一刻就把加载态亮出来:拉页面 → 嗅探接口可能要十几秒
            // (以前这里拉完页面先 dismiss、再由嗅探重新 show,中间那段就是"点了没反应")。
            // 可取消:点"取消"把这次添加作废,后面回来的结果一律不再采用
            if (batchImportInProgress) {
                // 批量流程持有同一个加载框；逐条识别时只更新状态，不清掉整批的取消标志。
                updateImportHint("正在读取地址…")
            } else {
                importEpoch++
                importCancelled = false
                showCancelableLoading(loadingHint) {
                    importCancelled = true
                    importEpoch++
                    activeImportUrls.remove(url)
                    dismissLoadingDialog()
                    AppBubble.toast("已取消")
                }
            }
            val epoch = importEpoch
            LogStore.log(Category.SUBSCRIPTION, "订阅: 新增 " + name + "(http/" + addOrigin + ")")
            HttpClient.get(url, null, "get_subscription", object : HCallBack {
                    override fun onSuccess(response: String) {
                        if (isFinishing || isDestroyed || importCancelled || epoch != importEpoch) return
                        // 「图片裹配置」套路(8 位字母/0 + ** + base64,http://www.饭太硬.net/tv 这类分享地址就是):
                        // 响应本身是**二进制/乱码**(图片 + 尾部 base64),直接按 JSON 解析必然失败,
                        // 接着会被当成资源站去嗅探采集接口 —— 用户口径"接口明明可以用啊,为啥弹未识别到采集接口"。
                        // 解出来确认能当配置用,就按 JSON 响应的同一条流程判定;地址仍原样存成订阅
                        // (加载阶段 ApiConfig.FindResult 用的是同一套解包规则,配置更新时能跟着变)。
                        val packed = CmsApiRules.unwrapPackedConfig(response)
                        if (packed != null) {
                            LogStore.log(Category.SUBSCRIPTION,
                                "订阅: 图片裹配置解出配置 " + packed.length + " 字符 " + url)
                        }
                        handleSubscriptionBody(name, url, packed ?: response, checked, finish)
                        mSubscriptionAdapter.setNewData(mSubscriptions)
                        updateEmptyState()
                    }

                    override fun onError(e: Throwable) {
                        if (isFinishing || isDestroyed || importCancelled || epoch != importEpoch) return
                        // 拉取这一步失败:带异常先记一条(排障需要);"新增算不算失败"的结论交给下面两个分支
                        // 各自落(站点形态转嗅探,它自己记成功/失败;非站点形态由紧随的 LogStore.fail 记)——
                        // 这里不再下"新增订阅失败"的结论,否则站点形态后续接入成功时日志里会留一条误导
                        LogStore.fail(Category.SUBSCRIPTION, "订阅: 新增订阅拉取失败 " + name + " " + url + " " + e)
                        Log.e("SubscriptionImport", "拉取订阅失败: ${CmsApiRules.displayHost(url)}", e)
                        if (hasUnknownHost(e)) {
                            dismissImportLoading()
                            showImportToast("域名无法解析，请检查地址或 DNS")
                            finish()
                            return
                        }
                        // 通用兜底:地址像资源站就说"拉不到页面"太苛刻(站点常拦非浏览器 UA/首页超时),
                        // 仍按站点候选路径实探一次,能探到接口就正常接入
                        if (CmsApiRules.looksLikeSiteUrl(url)) {
                            sniffSource(name, url, null, CmsSiteImporter.InputKind.SITE, checked,
                                onComplete = finish)
                        } else {
                            dismissImportLoading()
                            LogStore.fail(Category.SUBSCRIPTION, "订阅: 新增订阅失败 " + name + " 网络错误/地址无效")
                            showImportToast("订阅失败，请检查地址或网络")
                            finish()
                        }
                    }
                })
        } else {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 格式不正确 $url")
            showImportToast("订阅格式不正确")
            onComplete?.invoke()
        }
    }

    private fun hasUnknownHost(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is UnknownHostException) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * 订阅地址的响应按"多线路 / 多仓 / 单线路配置 / 资源站页面"判定并落地。
     *
     * 新增订阅(HTTP)两条来源共用:响应本来就是 JSON,或响应是「图片裹配置」
     * ({@link CmsApiRules#unwrapPackedConfig})解出来的配置正文。地址始终用用户填的那个 ——
     * 完整配置存 URL(配置更新能跟着变),多线路/多仓则展开成各自的地址。
     *
     * @param body 判定用的正文(图片裹配置时是解包结果,其余情况就是响应原文)
     */
    private fun handleSubscriptionBody(name: String, url: String, body: String, checked: Boolean,
                                       onComplete: (() -> Unit)? = null) {
        if (SubscriptionImportRules.isAccessDeniedResponse(body)) {
            dismissImportLoading()
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 地址拒绝访问 $name $url (响应=${body.trim().take(120)})")
            Log.e("SubscriptionImport", "订阅地址拒绝访问: ${CmsApiRules.displayHost(url)}; 响应=${body.trim().take(120)}")
            showImportToast("订阅地址拒绝访问，请检查站点权限或更换地址")
            onComplete?.invoke()
            return
        }
        try {
            val json = JsonParser.parseString(body).asJsonObject
            // 多线路?
            val urls = json["urls"]
            // 多仓?
            val storeHouse = json["storeHouse"]
            if (urls != null && urls.isJsonArray) { // 多线路
                dismissImportLoading()
                if (!importSubscriptionEntries(urls.asJsonArray, checked, "多线路", addOrigin,
                        onComplete)) onComplete?.invoke()
            } else if (storeHouse != null && storeHouse.isJsonArray
                && isStoreHouseList(storeHouse.asJsonArray)
            ) { // 多仓
                dismissLoadingDialog()   // 要让位给选仓弹窗
                showStoreHouseChoose(storeHouse.asJsonArray, checked, onComplete)
            } else if (looksLikeConfigJson(json) || CmsApiRules.detectKind(body) < 0) {
                // 单线路订阅配置(含只有 flags/ads 之类键的最小配置)→ 原样加入;
                // 响应确实是 CMS 采集数据时,若粘的就是采集接口则直接落成单源配置,否则按站点嗅探
                if (looksLikeConfigJson(json)) {
                    // 配置类 JSON 不代表能当订阅加载(缺 sites 的存进去必然"解析配置失败"、
                    // 还会把当前可用订阅挤掉):先判定,不能用的直接说明原因,不再原样加入
                    if (isLoadableSubscription(body)) {
                        dismissImportLoading()
                        addSub2List(name, url, checked)
                        onComplete?.invoke()
                    } else {
                        dismissImportLoading()
                        rejectSubscriptionContent(body.trim(), "订阅地址 " + url)
                        onComplete?.invoke()
                    }
                } else {
                    sniffSource(
                        name, url, body,
                        if (CmsApiRules.looksLikeApi(url)) CmsSiteImporter.InputKind.API
                        else CmsSiteImporter.InputKind.SITE,
                        checked,
                        onComplete = onComplete
                    )
                }
            } else { // 填的是资源站采集接口/首页(典型 MacCMS 站):嗅探并生成单源订阅
                sniffSource(name, url, body, CmsSiteImporter.InputKind.SITE, checked,
                    onComplete = onComplete)
            }
        } catch (th: Throwable) {
            // 不是 JSON 配置:多半是资源站网页,按站点嗅探采集接口
            sniffSource(name, url, body, CmsSiteImporter.InputKind.SITE, checked,
                onComplete = onComplete)
        }
    }

    /**
     * 填入的是资源站地址(首页/栏目页/说明页)或采集接口,而不是订阅配置:
     * 嗅探采集接口并生成单源订阅配置后以 clan:// 加入。
     * <p>
     * 通用接入:只看"这份响应能不能当订阅用"——
     * 能当订阅(多线路/多仓/完整配置)的原样加入;不能当订阅的一律嗅探;响应根本读不到但地址像站点时也嗅探。
     * 嗅探不到接口时不再把站点地址原样存成订阅(那会在加载配置阶段报"解析配置失败"),直接提示用户。
     * <p>
     * 站点类输入(kind=SITE)在找不到采集接口时还会继续走 [scrapeSite]:直接抓站点页面
     * (苹果CMS 站页面结构固定,接口关闭也常能抓),这是"接口不可用"站点的最后一档兜底。
     * <p>
     * 加载态:调用方已经亮起加载框(带"正在读取地址…"),这里接管并显示探测进度;
     * 探测要逐个试候选地址、可能十几秒,结束(成功/失败)才关闭,中途不关。
     *
     * @param hintText 已知的线索文本(书源 JSON / 站点页面),用于推断站点子目录;可为 null
     */
    private fun sniffSource(name: String, inputUrl: String, probedContent: String?,
                            kind: CmsSiteImporter.InputKind, checked: Boolean,
                            hintText: String? = null, onComplete: (() -> Unit)? = null) {
        val epoch = importEpoch
        updateImportHint("正在识别资源站…\n$inputUrl")
        LogStore.log(Category.SUBSCRIPTION, "订阅: 开始识别资源站 " + name + " " + inputUrl + "(形态=" + kind + ")")
        CmsSiteImporter.probeInput(
            inputUrl, probedContent, kind, importDir(),
            object : CmsSiteImporter.Callback {
                override fun onFound(siteName: String, file: File, api: String) {
                    if (isFinishing || isDestroyed || importCancelled || epoch != importEpoch) return
                    dismissImportLoading()
                    val display = if (isDefaultSubName(name)) siteName else name.trim()
                    LogStore.log(Category.SUBSCRIPTION, "订阅: 资源站识别成功 " + display + " " + api)
                    addLocalFileSubscription(file, display, checked)
                    showImportToast("资源站已接入")
                    onComplete?.invoke()
                }

                override fun onNotFound() {
                    if (isFinishing || isDestroyed || importCancelled || epoch != importEpoch) return
                    // 站点类输入:采集接口不可用(如接口关闭)时继续按页面结构抓一次,别让用户只得到一句"没找到"
                    if (kind == CmsSiteImporter.InputKind.SITE && CmsApiRules.looksLikeSiteUrl(inputUrl)) {
                        scrapeSite(name, inputUrl, hintText ?: probedContent, checked, onComplete)
                        return
                    }
                    dismissImportLoading()
                    LogStore.fail(Category.SUBSCRIPTION, "订阅: 资源站未识别到采集接口 " + inputUrl)
                    showImportToast("未找到接口，请换个地址")
                    onComplete?.invoke()
                }
            },
            object : CmsSiteImporter.Progress {
                override fun onProbe(done: Int, total: Int, url: String) {
                    if (isFinishing || isDestroyed || importCancelled || epoch != importEpoch) return
                    // 显示"第几个候选 + 该候选主机名":用户能看到在动,而不是以为点了没反应
                    val shown = if (done <= 0) "正在查说明页…" else "正在探测接口 $done/$total"
                    updateImportHint("识别资源站：$shown\n${CmsApiRules.displayHost(url)}")
                }
            })
    }

    /** 用户没在弹窗里改名(默认"订阅: N")时,用站点自己的名字 */
    private fun isDefaultSubName(name: String): Boolean {
        val n = name.trim()
        return n.isEmpty() || n.startsWith("订阅:") || n.startsWith("订阅：")
    }

    /**
     * 当前这次"添加订阅"的来源({@link Subscription#getOrigin()}):直接导入/本地导入/JSON 导入。
     * 三条路径最后都汇到 {@link #addSub2List} 与 {@link #addLocalFileSubscription},用字段传递比把参数
     * 穿过十几个函数干净;流程都是模态的(带加载框),不存在并发交错。
     */
    private var addOrigin: String = Subscription.ORIGIN_DIRECT
    private val activeImportUrls = HashSet<String>()

    /** 导入/嗅探被用户取消:之后回来的异步结果一律丢弃 */
    private var importCancelled = false
    private var importEpoch = 0
    private var legadoImportCancelled: java.util.concurrent.atomic.AtomicBoolean? = null

    /**
     * 仅当选中本地文件和添加的为单线路时,使用此订阅生效。多线路会直接解析全部并添加,多仓会展开并选择,最后也按多线路处理,直接添加
     * @param name
     * @param url
     * @param checkNewest
     */
    private fun addSub2List(name: String, url: String, checkNewest: Boolean) {
        if (checkNewest) { //选中最新的,清除以前的选中订阅
            for (subscription in mSubscriptions) {
                if (subscription.isChecked) {
                    subscription.setChecked(false)
                }
            }
            mSelectedUrl = url
            mSubscriptions.add(Subscription(name, url, addOrigin).setChecked(true))
        } else {
            mSubscriptions.add(Subscription(name, url, addOrigin).setChecked(false))
        }
        LogStore.success(Category.SUBSCRIPTION, "订阅: 导入成功 $name $url (来源=$addOrigin)")
    }

    override fun onPause() {
        super.onPause()
        // 更新缓存
        SubscriptionConfig.setApiUrl(mSelectedUrl)
        SubscriptionConfig.setSubscriptions(mSubscriptions)
    }

    override fun finish() {
        //切换了订阅地址
        if (mBeforeUrl != mSelectedUrl) {
            val intent = Intent(this, MainActivity::class.java)
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        super.finish()
    }

    override fun onDestroy() {
        legadoImportCancelled?.set(true)
        tabPageAnimator.finish()
        super.onDestroy()
        HttpClient.cancel("get_subscription")
    }
}

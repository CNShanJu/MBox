package com.github.tvbox.osc.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.view.Gravity
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import com.angcyo.tablayout.delegate.ViewPager1Delegate.Companion.install
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.ScreenUtils
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.R
import com.github.tvbox.osc.spiderapi.SourceConfigProviders
import com.github.tvbox.osc.spiderapi.SourceLoaderApi
import com.github.tvbox.osc.spiderapi.SourceLoaderProviders
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseLazyFragment
import com.github.tvbox.osc.base.BaseVbFragment
import com.github.tvbox.osc.state.SystemStateMonitor
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.MovieSort.SortData
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.FragmentHomeBinding
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.CollectActivity
import com.github.tvbox.osc.ui.activity.FastSearchActivity
import com.github.tvbox.osc.ui.activity.HistoryActivity
import com.github.tvbox.osc.ui.activity.MainActivity
import com.github.tvbox.osc.ui.activity.SubscriptionActivity
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter.SelectDialogInterface
import com.github.tvbox.osc.ui.dialog.LastViewedDialog
import com.github.tvbox.osc.ui.dialog.SelectDialog
import com.github.tvbox.osc.ui.dialog.TipDialog
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.SubscriptionConfig
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.lxj.xpopup.XPopup
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : BaseVbFragment<FragmentHomeBinding>() {

    /**
     * 提供给主页返回操作
     */
    val tabIndex: Int
        get() = mBinding.tabLayout.currentItemIndex

    /**
     * 提供给主页返回操作
     */
    val allFragments: List<BaseLazyFragment>
        get() = fragments

    private var sourceViewModel: SourceViewModel? = null
    private val fragments: MutableList<BaseLazyFragment> = ArrayList()
    private val mHandler = Handler()

    companion object {
        /** "上次看到"气泡的展示时长:自动检查更新要等它消失后再做 */
        private const val BUBBLE_SHOW_MS = 4000L
        /** 气泡消失后再多等一点,避开消失动画 */
        private const val CHECK_AFTER_BUBBLE_MS = BUBBLE_SHOW_MS + 600L
        /** 没有气泡(无痕浏览/本机无历史)时的默认延时:给首页留出首屏渲染时间 */
        private const val CHECK_DEFAULT_DELAY_MS = 4000L
        /**
         * 首屏加载看门狗(同 GridFragment 的 45s):某些失败路径压根不回结果
         * (断网被网络层快速失败、源自己抛异常、VM 侧提前 return),不兜底就会让首页永久停在 loading,
         * 而 loading 视图会盖住内容、用户连"长按刷新"都点不到。
         */
        private const val LOAD_WATCHDOG_MS = 45_000L
    }

    /**
     * 顶部tabs分类集合,用于渲染tab页,每个tab对应fragment内的数据
     */
    private var mSortDataList: List<SortData> = ArrayList()
    private var dataInitOk = false
    private var jarInitOk = false

    // ---- 首屏"必然收尾 + 自愈"(离线冷启动 / 从无网络页返回的场景) ----
    /** 首屏加载轮次:看门狗按它作废在途的那一轮(同 GridFragment 的做法) */
    private var loadEpoch = 0
    /** 是否有一轮首屏加载在途 */
    private var loadInFlight = false
    /** 是否成功拿到过首页数据(拿到过就不再自动补,免得和用户操作打架) */
    private var loadedOnce = false
    /** 网络状态是否已订阅 */
    private var netBound = false

    /** "上次看到"气泡预计消失的时间点(uptimeMillis);无气泡时保持 0,自动检查按默认延时走 */
    private var bubbleUntil = 0L

    /** 排队的自动检查任务(重复排队时先撤掉,只保留最后一次) */
    private val pendingAutoCheck = Runnable { runAutoUpdateCheck() }

    var errorTipDialog: TipDialog? = null

    /**
     * 当前提示弹窗用的文案。TipDialog 的文案在构造时定死(内容绑定只在 onCreate 执行一次),
     * 换了原因(如"解析配置失败"→"该订阅不是 TVBox 配置")必须重建弹窗,否则显示的还是旧原因
     */
    private var errorTipMsg: String? = null

    /**
     * true: 配置变更重载
     * false: 全部重载(api变更、重启app等)
     */
    var onlyConfigChanged = false

    override fun init() {
        ControlManager.get().startServer()
        mBinding.nameContainer.setOnClickListener {
            if (dataInitOk && jarInitOk) {
                showSiteSwitch()
            } else {
                AppBubble.toast("数据源未加载，长按刷新或切换订阅")
            // 用户能看到的订阅故障:首页拿不到任何源 → 记一条失败业务日志(排障时和"配置拉取/解析失败"对上)
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 数据源未加载(首页无可用源)")
            }
        }
        mBinding.nameContainer.setOnLongClickListener {
            refreshHomeSources()
            true
        }
        mBinding.search.setOnClickListener {
            if (!hasSubscription()) {
                AppBubble.toast("请先设置订阅")
                return@setOnClickListener
            }
            jumpActivity(FastSearchActivity::class.java)
        }
        mBinding.ivHistory.setOnClickListener {
            jumpActivity(HistoryActivity::class.java)
        }
        mBinding.ivCollect.setOnClickListener {
            jumpActivity(CollectActivity::class.java)
        }
        setLoadSir(mBinding.contentLayout)
        initViewModel()
        initData()
    }

    private fun initViewModel() {
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        sourceViewModel?.sortResult?.observe(this) { absXml: AbsSortXml? ->
            // 收到任何结果(数据/空/null)都算本轮结束:作废看门狗、清"在途"标记(同 GridFragment)
            loadEpoch++
            loadInFlight = false
            if (absXml != null) loadedOnce = true
            showSuccess()
            mSortDataList =
                if (absXml?.classes != null && absXml.classes.sortList != null) {
                    DefaultConfig.adjustSort(
                        SourceConfigProviders.get().homeSourceBean.key,
                        absXml.classes.sortList,
                        true
                    )
                } else {
                    DefaultConfig.adjustSort(SourceConfigProviders.get().homeSourceBean.key, ArrayList(), true)
                }
            initViewPager(absXml)
        }
    }

    private fun initData() {
        val mainActivity = mActivity as MainActivity
        onlyConfigChanged = mainActivity.useCacheConfig

        val home = SourceConfigProviders.get().homeSourceBean
        if (home != null && !home.name.isNullOrEmpty()) {
            mBinding.tvName.text = home.name
            mBinding.tvName.postDelayed({ mBinding.tvName.isSelected = true }, 2000)
        }

        // 启动自动检查更新:挂在首页数据初始化上(而不是"有上次播放记录"那支),
        // 否则无痕浏览/本机无历史时"上次看到"气泡不弹,自动检查就永远不跑。
        // 内部按"气泡展示时长"延时并做进程级去重(见 scheduleAutoUpdateCheck)。
        scheduleAutoUpdateCheck()

        showLoading()
        startLoadWatchdog()
        when{
            dataInitOk && jarInitOk -> {
                //正常初始化会先加载,最终到这,此时数据有以下几种情况
                // 1. api/jar/spider等均加载完,正常显示数据。2. 缺失spider(存疑?)/api配置有问题同样加载(最后空布局 或 只有豆瓣首页)
                sourceViewModel?.getSort(SourceConfigProviders.get().homeSourceBean.key)
            }
            dataInitOk && !jarInitOk -> {
                loadJar()
            }
            else -> {
                loadConfig()
            }
        }
    }

    private fun loadConfig(){
        SourceLoaderProviders.get().loadConfig(onlyConfigChanged, object : SourceLoaderApi.Callback {

            override fun retry() {
                mHandler.post { initData() }
            }

            override fun success() {
                dataInitOk = true
                if (SourceLoaderProviders.get().spider.isEmpty()) {
                    jarInitOk = true
                }
                mHandler.postDelayed({ initData() }, 50)
            }

            override fun error(msg: String) {
                if (msg.equals("-1", ignoreCase = true)) {
                    mHandler.post {
                        if (!hasSubscription()) {
                            // 未设置订阅:首屏不加载,提示用户先去订阅管理设置
                            showNoSubscriptionTip()
                            return@post
                        }
                        dataInitOk = true
                        jarInitOk = true
                        initData()
                    }
                } else {
                    // 拉取/解析失败:先收尾首屏(loading 视图会盖住内容、挡住长按刷新),再把原因告知用户
                    settleFirstScreen()
                    showTipDialog(msg)
                }
            }
        }, activity)
    }

    private fun loadJar(){
        if (!SourceLoaderProviders.get().spider.isNullOrEmpty()) {
            SourceLoaderProviders.get().loadJar(
                onlyConfigChanged,
                SourceLoaderProviders.get().spider,
                object : SourceLoaderApi.Callback {
                    override fun success() {
                        jarInitOk = true
                        mHandler.postDelayed({
                            if (!onlyConfigChanged) {
                                queryHistory()
                            }
                            initData()
                        }, 50)
                    }

                    override fun retry() {}
                    override fun error(msg: String) {
                        jarInitOk = true
                        mHandler.post {
                            AppBubble.toast("更新订阅失败")
                            initData()
                        }
                    }
                })
        }
    }

    private fun showTipDialog(msg: String) {
        if (errorTipDialog == null || errorTipMsg != msg) {
            errorTipDialog?.hide()
            errorTipMsg = msg
            errorTipDialog =
                TipDialog(requireActivity(), msg, "重试", "取消", object : TipDialog.OnListener {
                    override fun left() {
                        mHandler.post {
                            initData()
                            errorTipDialog?.hide()
                        }
                    }

                    override fun right() {
                        dataInitOk = true
                        jarInitOk = true
                        mHandler.post {
                            initData()
                            errorTipDialog?.hide()
                        }
                    }

                    override fun cancel() {
                        dataInitOk = true
                        jarInitOk = true
                        mHandler.post {
                            initData()
                            errorTipDialog?.hide()
                        }
                    }

                    override fun onTitleClick() {
                        errorTipDialog?.hide()
                        jumpActivity(SubscriptionActivity::class.java)
                    }
                })
        }
        if (!errorTipDialog!!.isShowing) errorTipDialog!!.show()
    }

    /**
     * 是否已勾选订阅(以订阅管理写入的接口地址为准)
     */
    private fun hasSubscription(): Boolean {
        return !TextUtils.isEmpty(SubscriptionConfig.getApiUrl())
    }

    /**
     * 未设置订阅时的提示:首屏不加载,引导去订阅管理设置
     */
    private fun showNoSubscriptionTip() {
        showEmpty()
        if (errorTipDialog == null) {
            // 复用同一条错误提示弹窗:文案不同,清掉记录以免后续 showTipDialog 误复用本弹窗
            errorTipMsg = null
            errorTipDialog =
                TipDialog(requireActivity(), "尚未设置订阅,请先在订阅管理中设置订阅地址", "去设置", "取消", object : TipDialog.OnListener {
                    override fun left() {
                        errorTipDialog?.hide()
                        jumpActivity(SubscriptionActivity::class.java)
                    }

                    override fun right() {
                        errorTipDialog?.hide()
                    }

                    override fun cancel() {
                        errorTipDialog?.hide()
                    }

                    override fun onTitleClick() {
                        errorTipDialog?.hide()
                        jumpActivity(SubscriptionActivity::class.java)
                    }
                })
        }
        if (!errorTipDialog!!.isShowing) errorTipDialog!!.show()
    }

    private fun getTabTextView(text: String): TextView {
        val textView = TextView(mContext)
        textView.text = text
        textView.gravity = Gravity.CENTER
        textView.setPadding(
            ConvertUtils.dp2px(20f),
            ConvertUtils.dp2px(10f),
            ConvertUtils.dp2px(5f),
            ConvertUtils.dp2px(10f)
        )
        return textView
    }

    private fun initViewPager(absXml: AbsSortXml?) {
        // 顶部导航的文字色显式走主题:库的选中/未选中色只从 XML 属性取(编译期固定),
        // 自定义主题下不会变(用户清单第 5 条"首页顶部当前选中的导航栏对象的文字颜色")。
        // 选中色 = **文字主色**(text_foreground → text_main),不是"强调文字":用户口径
        // "顶部导航栏当前文字的选择色(例如主页)没走主要文字色"。
        mBinding.tabLayout.configTabLayoutConfig {
            tabSelectColor = androidx.core.content.ContextCompat.getColor(
                requireContext(), com.github.tvbox.osc.R.color.text_foreground)
            tabDeselectColor = androidx.core.content.ContextCompat.getColor(
                requireContext(), com.github.tvbox.osc.R.color.text_sub_foreground)
        }
        // 选中文字下面那条"微笑曲线"要和**选中文字同色**(同为文字主色):
        // 曲线的底是矢量 indicator_flash,库内是 `typedArray.getDrawable()` 从 XML 属性里取的 ——
        // 矢量的 fillColor 是**编译期**资源,那条通道绕不过换肤(ThemeResources/ThemeDrawables 都够不到),
        // 自定义主题下它会停在**内置**的文字主色,而同一行的选中文字走的是上面的运行时取色 →
        // 于是"曲线颜色没走文字主色"(用户口径)。库自己的 indicatorColor 就是"过滤指示器 drawable 的颜色"
        // 那一档(设了就用它 tint 整条曲线),这里按当前主题给一次,和上面的 tabSelectColor 同源同值。
        mBinding.tabLayout.tabIndicator.indicatorColor =
            androidx.core.content.ContextCompat.getColor(
                requireContext(), com.github.tvbox.osc.R.color.text_foreground)
        if (mSortDataList.isNotEmpty()) {
            mBinding.tabLayout.removeAllViews()
            fragments.clear()
            for (data in mSortDataList) {
                mBinding.tabLayout.addView(getTabTextView(data.name))
                if (data.id == "my0") { //tab是主页,添加主页fragment 根据设置项显示豆瓣热门/站点推荐(每个源不一样)/历史记录
                    if (SystemConfig.getHomeRec() == 1 && absXml != null && absXml.videoList != null && absXml.videoList.size > 0
                    ) { //站点推荐
                        fragments.add(UserFragment.newInstance(absXml.videoList))
                    } else { //豆瓣热门/历史记录
                        fragments.add(UserFragment.newInstance(null))
                    }
                } else { //来自源的分类
                    fragments.add(GridFragment.newInstance(data))
                }
            }
            if (SystemConfig.getHomeRec() == 2) { //关闭主页
                mBinding.tabLayout.removeViewAt(0)
                fragments.removeAt(0)
            }

            //重新渲染vp
            mBinding.mViewPager.adapter =
                object : FragmentStatePagerAdapter(getChildFragmentManager()) {
                    override fun getItem(position: Int): Fragment {
                        return fragments[position]
                    }

                    override fun getCount(): Int {
                        return fragments.size
                    }
                }
            //tab和vp绑定
            install(mBinding.mViewPager, mBinding.tabLayout, true)
        }
    }

    /**
     * 提供给主页返回操作
     */
    fun scrollToFirstTab(): Boolean {
        return if (mBinding.tabLayout.currentItemIndex != 0) {
            mBinding.mViewPager.setCurrentItem(0, false)
            true
        } else {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        // "断网/恢复网络"的事件常常发生在页面不可见期间(被无网络页盖住、切到别的页),那时监听是注销的,
        // 回来时已经错过 → 这里按当前网络状态补一次收尾或补一次加载。真机反馈:断网冷启动进无网络页、
        // 点"返回"回首页,lading 一直转、恢复网络也不动 —— 就是这条时序没接上。
        bindNetworkState()
    }

    override fun onPause() {
        unbindNetworkState()
        super.onPause()
        mHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        unbindNetworkState()
        mHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    /** 订阅系统网络状态(幂等;只在可见期间订阅),并处理"事件在页面不可见期间发生"的时序 */
    private fun bindNetworkState() {
        if (isOffline()) {
            settleFirstScreen()
        } else if (!loadedOnce && !loadInFlight) {
            // 从未加载成功 + 现在有网:补一次初始化
            LogStore.log(Category.SYSTEM, "首页: 页面可见且从未加载成功,补一次初始化")
            mHandler.post {
                if (!loadedOnce && !loadInFlight) initData()
            }
        } else if (loadInFlight) {
            // 页面不可见期间看门狗被 onPause 清掉了,重新武装,别让 loading 无限等
            startLoadWatchdog()
        }
        if (netBound) return
        SystemStateMonitor.registerSafe(netListener, SystemStateMonitor.TYPE_NETWORK)
        netBound = true
    }

    private fun unbindNetworkState() {
        if (!netBound) return
        SystemStateMonitor.unregisterSafe(netListener)
        netBound = false
    }

    private val netListener = SystemStateMonitor.Listener { e ->
        if (e == null || e.type != SystemStateMonitor.TYPE_NETWORK) return@Listener
        if (SystemStateMonitor.VAL_NONE == e.value) {
            // 断网:在途请求已被网络层快速失败,不会再有回调 → 立即收尾
            settleFirstScreen()
        } else if (!loadedOnce) {
            // 恢复联网且从未加载成功:自动补一次(否则用户只能重启或长按刷新)
            mHandler.post {
                if (!loadedOnce && !loadInFlight) initData()
            }
        }
    }

    private fun isOffline(): Boolean {
        // 口径统一走系统状态单点(未 init/读不到按有网);别再各写一份 catch 语义相反的判定
        return SystemStateMonitor.isOfflineNow()
    }

    /** 起一轮首屏加载看门狗(带轮次号;收到任何结果即被观察者作废) */
    private fun startLoadWatchdog() {
        val epoch = ++loadEpoch
        loadInFlight = true
        mHandler.postDelayed({
            if (epoch != loadEpoch) return@postDelayed   // 已收尾/已换轮:这条作废
            LogStore.log(Category.SYSTEM, "首页: 加载看门狗触发(请求无结果),收尾显示空态")
            settleFirstScreen()
        }, LOAD_WATCHDOG_MS)
    }

    /**
     * 首屏在途加载收尾:作废看门狗 + 清"在途"标记;从未拿到过内容时显示空态。
     * <p>
     * 为什么必须有:结束 loading 的唯一入口是 {@code sortResult} 的观察者,而断网时请求被网络层
     * 快速失败、异常被上层吞成"无结果" → LiveData 永不发射 → 首屏 loading 一直转(用户从无网络页
     * 点"返回/我知道了"回来看到的就是它,且 loading 视图盖着内容连长按刷新都点不到)。
     * <p>
     * 已经加载成功过就只收尾、不动已有内容(别把用户的列表刷掉)。
     */
    private fun settleFirstScreen() {
        loadEpoch++
        loadInFlight = false
        if (!loadedOnce) showEmpty()
    }

    private fun showSiteSwitch() {
        val sites = SourceConfigProviders.get().sourceBeanList
        if (sites.size > 0) {
            val dialog = SelectDialog<SourceBean>(requireActivity())
            dialog.setListLayoutManager(V7GridLayoutManager(dialog.context, 2))
            dialog.setDynamicHeightByScreen(true) // 源列表按屏高分档动态撑高:大屏60%/小屏铺满/区间50%
            dialog.setTip("请选择首页数据源")
            dialog.setAdapter(object : SelectDialogInterface<SourceBean?> {
                override fun click(value: SourceBean?, pos: Int) {
                    SourceConfigProviders.get().setSourceBean(value)
                    refreshHomeSources()
                }

                override fun getDisplay(source: SourceBean?): String {
                    return if (source == null) "" else source.name
                }
            }, object : DiffUtil.ItemCallback<SourceBean>() {
                override fun areItemsTheSame(oldItem: SourceBean, newItem: SourceBean): Boolean {
                    return oldItem === newItem
                }

                override fun areContentsTheSame(oldItem: SourceBean, newItem: SourceBean): Boolean {
                    return oldItem.key.contentEquals(newItem.key)
                }
            }, sites, sites.indexOf(SourceConfigProviders.get().homeSourceBean))
            dialog.show()
        } else {
            AppBubble.toastLong("暂无可用数据源")
        }
    }

    private fun refreshHomeSources() {
        val intent = Intent(App.getInstance(), MainActivity::class.java)
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val bundle = Bundle()
        bundle.putBoolean(IntentKey.CACHE_CONFIG_CHANGED, true)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        ControlManager.get().stopServer()
    }

    private fun queryHistory() {
        lifecycleScope.launch {
            val vodInfoList = withContext(Dispatchers.IO) {
                // 源是否存在/历史保留上限由 UI 层判定(与旧 RoomDataManger 内聚逻辑等价;storage 不再依赖业务配置)
                val allVodRecord = com.github.tvbox.osc.repo.HistoryRepositories.history().query(
                    100,
                    { key -> SourceConfigProviders.get().getSource(key) != null },
                    com.github.tvbox.osc.util.HistoryHelper.getHisNum(
                        com.github.tvbox.osc.config.SystemConfig.getHistoryNum()
                    )
                )
                val vodInfoList: MutableList<VodInfo?> = ArrayList()
                for (vodInfo in allVodRecord) {
                    if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty()) vodInfo.note =
                        vodInfo.playNote
                    vodInfoList.add(vodInfo)
                }
                vodInfoList
            }

            // 查询完成后更新UI
            if (vodInfoList.isNotEmpty() && vodInfoList[0] != null) {
                val shownAt = android.os.SystemClock.uptimeMillis()
                bubbleUntil = shownAt + BUBBLE_SHOW_MS
                XPopup.Builder(context)
                    .hasShadowBg(false)
                    .isDestroyOnDismiss(true)
                    .isCenterHorizontal(true)
                    .isTouchThrough(true)
                    // 距屏幕底部约155dp(转px),不同密度设备位置一致;配合 maxLines=1 气泡高度固定不截断
                    .offsetY(ScreenUtils.getAppScreenHeight() - ConvertUtils.dp2px(155f + 44f))
                    .asCustom(LastViewedDialog(requireContext(), vodInfoList[0]))
                    .show()
                    .delayDismiss(BUBBLE_SHOW_MS)
                // 气泡真的出现了:把自动检查往后排到它消失之后(可能已由 initData 排过一次)
                rescheduleAutoUpdateCheckAfterBubble()
            }
        }
    }

    private fun rescheduleAutoUpdateCheckAfterBubble() {
        if (!com.github.tvbox.osc.config.SystemConfig.isAutoCheckUpdate()) return
        mHandler.removeCallbacks(pendingAutoCheck)
        mHandler.postDelayed(pendingAutoCheck, CHECK_AFTER_BUBBLE_MS)
    }

    /**
     * 启动自动检查更新:等首页"上次看到"气泡消失(4s)后再检查,有新版本由 {@link UpdateCheck}
     * 弹出更新说明弹窗(与「我的-关于-检查更新」同一套动作)。
     * <p>
     * 受"自动检查更新"开关控制(设置页,默认开);无痕浏览/本机无历史时气泡不弹,这里按默认延时照常检查
     * (不能挂在"有历史记录"分支里,否则那种情况下自动检查永远不生效)。
     * 检查本身由 UpdateCheck 做进程级去重,每次启动最多一次;重复排队时只保留最后一次。
     */
    private fun scheduleAutoUpdateCheck() {
        if (!com.github.tvbox.osc.config.SystemConfig.isAutoCheckUpdate()) return
        mHandler.removeCallbacks(pendingAutoCheck)
        // 气泡若已排好,等到它消失;否则(无历史/无痕)用默认延时
        val now = android.os.SystemClock.uptimeMillis()
        val remain = bubbleUntil - now
        mHandler.postDelayed(pendingAutoCheck, if (remain > 0) remain + 600L else CHECK_DEFAULT_DELAY_MS)
    }

    /** 真正执行自动检查(主线程):进程级去重与开关判定在 UpdateCheck 内 */
    private fun runAutoUpdateCheck() {
        val act = activity ?: return
        if (isAdded && !act.isFinishing && !act.isDestroyed) {
            com.github.tvbox.osc.update.UpdateCheck.autoCheckOnce(act, null)
        }
    }
}
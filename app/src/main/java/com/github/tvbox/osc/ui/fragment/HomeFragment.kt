package com.github.tvbox.osc.ui.fragment

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import com.angcyo.tablayout.delegate.ViewPager1Delegate.Companion.install
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.ScreenUtils
import com.blankj.utilcode.util.ActivityUtils
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.R
import com.github.tvbox.osc.spiderapi.SourceConfigProviders
import com.github.tvbox.osc.spiderapi.SpiderFaultProviders
import com.github.tvbox.osc.spiderapi.SourceLoaderApi
import com.github.tvbox.osc.spiderapi.SourceLoaderProviders
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseLazyFragment
import com.github.tvbox.osc.base.BaseVbFragment
import com.github.tvbox.osc.state.SystemStateMonitor
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.MovieSort.SortData
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.FragmentHomeBinding
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.service.LanServerService
import com.github.tvbox.osc.ui.activity.CollectActivity
import com.github.tvbox.osc.ui.activity.FastSearchActivity
import com.github.tvbox.osc.ui.activity.HistoryActivity
import com.github.tvbox.osc.ui.activity.MainActivity
import com.github.tvbox.osc.ui.activity.SubscriptionActivity
import com.github.tvbox.osc.ui.dialog.LastViewedDialog
import com.github.tvbox.osc.ui.dialog.HomeSourceChoices
import com.github.tvbox.osc.ui.dialog.HomeSourceDialog
import com.github.tvbox.osc.ui.dialog.TipDialog
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.SubscriptionConfig
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.lxj.xpopup.XPopup
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
    /** 开屏只预取数据；Fragment 创建后接收同一个请求结果，不再重复加载配置和首页分类。 */
    private var awaitingStartupPrefetch = false
    private var startupHistoryRequested = false
    private var startupHistoryPending = false
    private var suppressNextAutomaticRetry = false
    private var pendingStartupError: String? = null

    /** 首个可见页面绑定数据或显示空态时通知启动页；只触发一次。 */
    var onInitialContentReady: (() -> Unit)? = null

    fun onFirstPageSettled(fragment: BaseLazyFragment) {
        if (fragments.firstOrNull() === fragment) notifyInitialContentReady()
    }

    private fun notifyInitialContentReady() {
        val callback = onInitialContentReady ?: return
        onInitialContentReady = null
        callback()
    }

    companion object {
        /** "上次看到"气泡的展示时长:自动检查更新要等它消失后再做 */
        private const val BUBBLE_SHOW_MS = 6000L
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
    /** 配置或爬虫包已就绪，但页面尚未恢复到可继续初始化的状态。 */
    private var pendingInit = false

    // ---- 首屏"必然收尾 + 自愈"(离线冷启动 / 从无网络页返回的场景) ----
    /** 首屏加载轮次:看门狗按它作废在途的那一轮(同 GridFragment 的做法) */
    private var loadEpoch = 0
    /** 是否有一轮首屏加载在途 */
    private var loadInFlight = false
    /** 是否成功拿到过首页数据(拿到过就不再自动补,免得和用户操作打架) */
    private var loadedOnce = false
    /** 本轮首屏是不是被看门狗判成"请求无结果"收尾的(用于空态文案:超时 vs 本来就没数据) */
    private var loadTimedOut = false
    /** 网络状态是否已订阅 */
    private var netBound = false

    /** "上次看到"气泡预计消失的时间点(uptimeMillis);无气泡时保持 0,自动检查按默认延时走 */
    private var bubbleUntil = 0L
    private var lastViewedBubble: LastViewedDialog? = null
    private var mainPager: ViewPager? = null
    private val mainPageListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            if (position != 0) dismissLastViewedBubble()
        }
    }
    /** 历史查询可能晚于更新检查返回，届时不能再把气泡盖到更新弹窗上。 */
    private var autoCheckStarted = false

    /** 排队的自动检查任务(重复排队时先撤掉,只保留最后一次) */
    private val pendingAutoCheck = Runnable { runAutoUpdateCheck() }

    var errorTipDialog: TipDialog? = null

    /**
     * 当前提示弹窗用的文案。TipDialog 的文案在构造时定死(内容绑定只在 onCreate 执行一次),
     * 换了原因(如"解析配置失败"→"该订阅不是 TVBox 配置")必须重建弹窗,否则显示的还是旧原因
     */
    private var errorTipMsg: String? = null

    /** 启动遮罩退场后才允许独立窗口出现；旧 Fragment 或被其他页面盖住时作废。 */
    private fun canShowStartupUi(host: MainActivity, expectedView: View?): Boolean =
        isAdded && view === expectedView && isResumed && activity === host &&
            !host.isFinishing && !host.isDestroyed && ActivityUtils.getTopActivity() === host

    private fun showStartupAwareToast(message: String) {
        val host = activity as? MainActivity
        if (host?.isStartupSplashVisible() == true) {
            val expectedView = view
            host.runAfterStartupSplash(Runnable {
                if (canShowStartupUi(host, expectedView)) AppBubble.toast(message)
            })
        } else {
            AppBubble.toast(message)
        }
    }

    /**
     * true: 配置变更重载
     * false: 全部重载(api变更、重启app等)
     */
    var onlyConfigChanged = false

    override fun init() {
        // 开屏预取已在共享执行器启动服务并等待就绪；首页提前入场时不要在主线程抢跑。
        if ((activity as? MainActivity)?.hasStartupHomePrefetch() != true) {
            ControlManager.get().startServer()
        }
        if (SystemConfig.isLanServerEnabled()) LanServerService.start(requireContext())
        // 搜索框是 Fragment 内的自定义视图；显式应用主题令牌，避免 inflater 未覆盖时停在包内配色。
        com.github.tvbox.osc.theme.ThemeDrawables.applyBackground(
            mBinding.search, R.drawable.bg_search_round_float
        )
        com.github.tvbox.osc.theme.ThemeRuntime.colorPalette()?.let { colors ->
            mBinding.search.setTextColor(colors.get("text_main"))
            mBinding.search.setHintTextColor(colors.get("text_hint"))
            mBinding.search.compoundDrawableTintList =
                android.content.res.ColorStateList.valueOf(colors.get("text_sub"))
        }
        mBinding.nameContainer.setOnClickListener {
            if (!hasSubscription()) {
                jumpActivity(SubscriptionActivity::class.java)
                return@setOnClickListener
            }
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
        mBinding.addSubscriptionButton.setOnClickListener {
            jumpActivity(SubscriptionActivity::class.java)
        }
        mBinding.search.setOnClickListener {
            if (!hasSubscription()) {
                AppBubble.toast("请先设置订阅")
                return@setOnClickListener
            }
            openFastSearch()
        }
        mBinding.ivHistory.setOnClickListener {
            jumpActivity(HistoryActivity::class.java)
        }
        mBinding.ivCollect.setOnClickListener {
            jumpActivity(CollectActivity::class.java)
        }
        setLoadSir(mBinding.contentLayout)
        awaitingStartupPrefetch = (activity as? MainActivity)?.hasStartupHomePrefetch() == true
        if (awaitingStartupPrefetch) {
            // 先进入等待态再观察 LiveData：预取已完成时观察者会立即重放最后的结果。
            initData()
            initViewModel()
            onStartupHomePrefetchSettled()
        } else {
            initViewModel()
            initData()
        }
    }

    /** 首页搜索框和首页剧集卡片共用展开入口，返回时才能收回到同一个搜索框。 */
    fun openFastSearch(extras: Bundle? = null) {
        val intent = Intent(requireContext(), FastSearchActivity::class.java)
            .putExtra(FastSearchActivity.EXTRA_HOME_SEARCH_TRANSITION, true)
        if (extras != null) intent.putExtras(extras)
        val options = ActivityOptions.makeSceneTransitionAnimation(
            requireActivity(), mBinding.search, FastSearchActivity.HOME_SEARCH_TRANSITION_NAME
        )
        startActivity(intent, options.toBundle())
    }

    private fun initViewModel() {
        sourceViewModel = if ((activity as? MainActivity)?.hasStartupHomePrefetch() == true) {
            ViewModelProvider(requireActivity()).get(SourceViewModel::class.java)
        } else {
            ViewModelProvider(this).get(SourceViewModel::class.java)
        }
        sourceViewModel?.sortResult?.observe(viewLifecycleOwner) { absXml: AbsSortXml? ->
            if (!hasSubscription()) {
                val consumedStartupResult = awaitingStartupPrefetch
                awaitingStartupPrefetch = false
                showNoSubscriptionState()
                if (consumedStartupResult) (activity as? MainActivity)?.consumeStartupHomePrefetch()
                return@observe
            }
            val consumedStartupResult = awaitingStartupPrefetch
            if (consumedStartupResult) {
                // 同一个活动级 VM 的预取结果；之后的用户重试仍走 Fragment 原有加载流程。
                awaitingStartupPrefetch = false
                dataInitOk = true
                jarInitOk = true
                suppressNextAutomaticRetry = !isResumed
                refreshHomeSourceName()
                requestStartupHistoryOnce()
            }
            // 收到任何结果(数据/空/null)都算本轮结束:作废看门狗、清"在途"标记(同 GridFragment)
            loadEpoch++
            loadInFlight = false
            if (absXml != null) {
                loadedOnce = true
                showSuccess()
            } else {
                // 分类都拿不到:别只切"成功态"留下一个连 tab 都没有的空壳 —— 走收尾,让空态带上原因
                // 仍需先装好下面的默认页，再通知依赖首屏的浮层调度。
                settleFirstScreen(notifySplash = false)
            }
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
            if (consumedStartupResult) (activity as? MainActivity)?.consumeStartupHomePrefetch()
        }
    }

    private fun initData() {
        val mainActivity = mActivity as MainActivity
        onlyConfigChanged = mainActivity.useCacheConfig

        val hasSubscription = hasSubscription()
        if (hasSubscription) mBinding.noSubscriptionView.visibility = View.GONE
        refreshHomeSourceName()
        mBinding.tvName.postDelayed({ mBinding.tvName.isSelected = true }, 2000)

        // 启动自动检查更新:挂在首页数据初始化上(而不是"有上次播放记录"那支),
        // 否则无痕浏览/本机无历史时"上次看到"气泡不弹,自动检查就永远不跑。
        // 仅用户主动启动才排队，内部按"气泡展示时长"延时并做进程级去重。
        scheduleAutoUpdateCheck()

        if (!hasSubscription) {
            showNoSubscriptionState()
            // 让加载器同步清理已删除订阅留下的源列表与首页源；不启动首屏看门狗。
            if (!awaitingStartupPrefetch) loadConfig()
            return
        }
        showLoading()
        startLoadWatchdog()
        if (awaitingStartupPrefetch) {
            // 启动页预取在 MainActivity 继续进行；超时入场时这里只等同一轮请求。
            onStartupHomePrefetchSettled()
            return
        }
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

    private fun refreshHomeSourceName() {
        val home = if (hasSubscription()) SourceConfigProviders.get().homeSourceBean else null
        mBinding.tvName.text = when {
            !hasSubscription() -> getString(R.string.home_source_unconfigured)
            !home?.name.isNullOrEmpty() -> home?.name.orEmpty()
            else -> getString(R.string.app_name)
        }
    }

    /** 预取发生在 Fragment 创建前；配置失败也必须能在超时入场后通知已出现的页面。 */
    fun onStartupHomePrefetchSettled() {
        if (!awaitingStartupPrefetch || !isAdded || view == null) return
        val host = activity as? MainActivity ?: return
        if (!host.isStartupHomeDataReady()) return
        val error = host.startupHomeError()
        when {
            error == "-1" || !hasSubscription() -> {
                awaitingStartupPrefetch = false
                showNoSubscriptionState()
                host.consumeStartupHomePrefetch()
            }
            error != null -> {
                awaitingStartupPrefetch = false
                suppressNextAutomaticRetry = !isResumed
                settleFirstScreen()
                if (isResumed) showTipDialog(error) else pendingStartupError = error
                host.consumeStartupHomePrefetch()
            }
            else -> {
                // 正常结果由活动级 sortResult 重放/送达，不能在这里再发一次 getSort。
                dataInitOk = true
                jarInitOk = true
                refreshHomeSourceName()
                requestStartupHistoryOnce()
            }
        }
    }

    private fun requestStartupHistoryOnce() {
        if (startupHistoryRequested || onlyConfigChanged || !isAdded ||
            (activity as? MainActivity)?.isUserInitiatedLaunch() != true) return
        if (!isResumed) {
            startupHistoryPending = true
            return
        }
        startupHistoryPending = false
        startupHistoryRequested = true
        queryHistory()
    }

    private fun loadConfig(){
        SourceLoaderProviders.get().loadConfig(onlyConfigChanged, object : SourceLoaderApi.Callback {

            override fun retry() {
                continueInit()
            }

            override fun success() {
                dataInitOk = true
                if (SourceLoaderProviders.get().spider.isEmpty()) {
                    jarInitOk = true
                }
                continueInit()
            }

            override fun error(msg: String) {
                if (msg.equals("-1", ignoreCase = true)) {
                    mHandler.post {
                        if (!hasSubscription()) {
                            // 未设置订阅:首屏不加载,提示用户先去订阅管理设置
                            if (loadInFlight) showNoSubscriptionState()
                            return@post
                        }
                        dataInitOk = true
                        jarInitOk = true
                        initData()
                    }
                } else {
                    if (!hasSubscription()) {
                        showNoSubscriptionState()
                        return
                    }
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
                        continueInit()
                        requestStartupHistoryOnce()
                    }

                    override fun retry() {}
                    override fun error(msg: String) {
                        jarInitOk = true
                        mHandler.post {
                            showStartupAwareToast("更新订阅失败")
                            initData()
                        }
                    }
                })
        }
    }

    private fun showTipDialog(msg: String) {
        val host = activity as? MainActivity
        if (host?.isStartupSplashVisible() == true) {
            val expectedView = view
            val expectedEpoch = loadEpoch
            host.runAfterStartupSplash(Runnable {
                if (loadEpoch == expectedEpoch && canShowStartupUi(host, expectedView)) {
                    showTipDialog(msg)
                }
            })
            return
        }
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
    private fun showNoSubscriptionState() {
        loadEpoch++
        loadInFlight = false
        loadTimedOut = false
        loadedOnce = false
        dataInitOk = false
        jarInitOk = false
        mBinding.tvName.text = getString(R.string.home_source_unconfigured)
        mSortDataList = emptyList()
        mBinding.mViewPager.adapter = null
        mBinding.tabLayout.removeAllViews()
        fragments.clear()
        errorTipDialog?.hide()
        errorTipDialog = null
        errorTipMsg = null
        mBinding.noSubscriptionView.visibility = View.VISIBLE
        notifyInitialContentReady()
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
            val homeRec = SystemConfig.getHomeRec()
            mSortDataList.forEachIndexed { index, data ->
                mBinding.tabLayout.addView(getTabTextView(data.name))
                if (data.id == "my0") { //tab是主页,添加主页fragment 根据设置项显示豆瓣热门/站点推荐(每个源不一样)/历史记录
                    if (homeRec == 1 && absXml != null && absXml.videoList != null && absXml.videoList.size > 0
                    ) { //站点推荐
                        fragments.add(UserFragment.newInstance(absXml.videoList))
                    } else { //豆瓣热门/历史记录
                        fragments.add(UserFragment.newInstance(null))
                    }
                } else { //来自源的分类
                    val useStartupList = homeRec == 2 && index == 1 &&
                        (activity as? MainActivity)?.isStartupFirstGridPrefetchFor(data.id) == true
                    fragments.add(GridFragment.newInstance(data, useStartupList))
                }
            }
            if (homeRec == 2) { //关闭主页
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
            if (absXml == null || fragments.isEmpty()) {
                // null 结果的具体错误说明已由 settleFirstScreen 显示，别盖回默认空态。
                if (absXml != null && fragments.isEmpty()) showEmpty()
                notifyInitialContentReady()
            }
        } else {
            // null 结果的具体错误说明已由 settleFirstScreen 显示，别盖回默认空态。
            if (absXml != null) showEmpty()
            notifyInitialContentReady()
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
        val pager = (activity as? MainActivity)?.findViewById<ViewPager>(R.id.vp)
        if (mainPager !== pager) {
            mainPager?.removeOnPageChangeListener(mainPageListener)
            mainPager = pager
            pager?.addOnPageChangeListener(mainPageListener)
        }
        if (pager?.currentItem != 0) dismissLastViewedBubble()
        if (pendingInit) continueInit()
        // "断网/恢复网络"的事件常常发生在页面不可见期间(被无网络页盖住、切到别的页),那时监听是注销的,
        // 回来时已经错过 → 这里按当前网络状态补一次收尾或补一次加载。真机反馈:断网冷启动进无网络页、
        // 点"返回"回首页,lading 一直转、恢复网络也不动 —— 就是这条时序没接上。
        bindNetworkState()
        if (startupHistoryPending) requestStartupHistoryOnce()
        pendingStartupError?.let { error ->
            pendingStartupError = null
            showTipDialog(error)
        }
        // onPause 会撤掉待执行的自动检查；气泡停留期间离开再返回时补排一次。
        if (!autoCheckStarted) scheduleAutoUpdateCheck()
    }

    override fun onPause() {
        unbindNetworkState()
        mainPager?.removeOnPageChangeListener(mainPageListener)
        mainPager = null
        dismissLastViewedBubble()
        super.onPause()
        mHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        unbindNetworkState()
        pendingInit = false
        pendingStartupError = null
        onInitialContentReady = null
        mHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    /** 阶段回调可能早于 onResume 或晚于 onPause，恢复时继续，不依赖会被清掉的延迟任务。 */
    private fun continueInit() {
        if (!isAdded || view == null) return
        if (!isResumed) {
            pendingInit = true
            return
        }
        pendingInit = false
        initData()
    }

    /** 订阅系统网络状态(幂等;只在可见期间订阅),并处理"事件在页面不可见期间发生"的时序 */
    private fun bindNetworkState() {
        if (!hasSubscription()) return
        if (isOffline()) {
            settleFirstScreen()
        } else if (!loadedOnce && !loadInFlight) {
            if (suppressNextAutomaticRetry) {
                // 预取的失败/空结果刚被重放，首帧不应立即重复发起同一个请求。
                suppressNextAutomaticRetry = false
            } else {
                // 从未加载成功 + 现在有网:补一次初始化
                mHandler.post {
                    if (!loadedOnce && !loadInFlight) initData()
                }
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
        if (!hasSubscription()) return@Listener
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
        loadTimedOut = false
        mHandler.postDelayed({
            if (epoch != loadEpoch) return@postDelayed   // 已收尾/已换轮:这条作废
            LogStore.log(Category.SYSTEM, "首页: 加载看门狗触发(请求无结果),收尾显示空态")
            // 标记成"超时":空态文案要说清是"没响应",而不是让用户以为这个源本来就没内容
            loadTimedOut = true
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
    private fun settleFirstScreen(notifySplash: Boolean = true) {
        if (!hasSubscription()) {
            showNoSubscriptionState()
            return
        }
        loadEpoch++
        loadInFlight = false
        if (!loadedOnce) {
            val reason = emptyReason()
            // 留痕:下次现场只看日志就能知道空态是"插件故障 / 断网 / 超时 / 源没返回内容"哪一档
            LogStore.log(Category.SYSTEM, "首页: 收尾显示空态,原因=" + (if (reason.isNullOrEmpty()) "默认(暂无数据)" else reason.replace('\n', ' ')))
            showEmpty(reason)
        }
        if (notifySplash) notifyInitialContentReady()
    }

    /**
     * 首页拿不到内容时,给一句能说清原因的空态文案(口径同 DetailActivity 的"能说清原因就说原因,
     * 说不清才退回暂无数据")。
     * <p>
     * 优先级:源插件故障({@code SpiderFaults},如插件会让 App 闪退 / 缺少站点声明的类)> 断网 >
     * 请求超时(看门狗判定的"无结果")> 源返回了空内容 > 默认「暂无数据」。
     * 为什么需要:看门狗只知道"没人回结果",页面原来只显示「暂无数据」—— 用户分不清是源坏了、
     * 插件坏了还是 App 坏了(真机现象:首页一直 loading,最后只留一个"暂无数据")。
     */
    private fun emptyReason(): String? {
        val key = try {
            SourceConfigProviders.get().homeSourceBean?.key
        } catch (th: Throwable) {
            null
        }
        val fault = if (key.isNullOrEmpty()) null else SpiderFaultProviders.unavailableReason(key)
        if (!fault.isNullOrEmpty()) return fault
        if (isOffline()) return getString(R.string.empty_reason_offline)
        if (loadTimedOut) return getString(R.string.empty_reason_source_timeout)
        // 请求回来了但没内容(源自己返回空/分类为空):也要说清,别只留"暂无数据"
        return getString(R.string.empty_reason_source_empty)
    }

    private fun showSiteSwitch() {
        val sites = SourceConfigProviders.get().sourceBeanList
        HomeSourceDialog(requireActivity(),
            sites.map { HomeSourceChoices.Row(it.key, it.name) },
            SourceConfigProviders.get().homeSourceBean?.key,
            { key ->
                val source = SourceConfigProviders.get().getSource(key)
                if (source != null) {
                    SourceConfigProviders.get().setSourceBean(source)
                    refreshHomeSources()
                } else {
                    AppBubble.toast("数据源已失效，请重新选择")
                }
            },
            { jumpActivity(SubscriptionActivity::class.java) }
        ).show()
    }

    private fun refreshHomeSources() {
        SystemConfig.markInternalRestart()
        val intent = Intent(App.getInstance(), MainActivity::class.java)
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val bundle = Bundle()
        bundle.putBoolean(IntentKey.CACHE_CONFIG_CHANGED, true)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 局域网由前台服务承载；首页销毁或手机锁屏不能把电脑连接一起关闭。
        if (!ControlManager.get().isLanServing()) ControlManager.get().stopServer()
    }

    private fun queryHistory() {
        if ((activity as? MainActivity)?.isUserInitiatedLaunch() != true) return
        lifecycleScope.launch {
            val vodInfoList = withContext(Dispatchers.IO) {
                // 上次观看保留旧订阅的记录；点击不可用来源时进入当前订阅同名搜索。
                val allVodRecord = com.github.tvbox.osc.repo.HistoryRepositories.history().query(
                    100,
                    null,
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

            // 历史数据照常查询，气泡等开屏退场后再创建独立窗口。
            val host = activity as? MainActivity ?: return@launch
            val vod = vodInfoList.firstOrNull() ?: return@launch
            if (host.isStartupSplashVisible()) {
                val expectedView = view
                host.runAfterStartupSplash(Runnable {
                    if (canShowStartupUi(host, expectedView)) showLastViewedBubble(host, vod)
                })
            } else {
                showLastViewedBubble(host, vod)
            }
        }
    }

    private fun showLastViewedBubble(host: MainActivity, vod: VodInfo) {
        if (!host.isUserInitiatedLaunch() || !canShowStartupUi(host, view) ||
            !host.isOnlineContentVisible() ||
            host.isStartupSplashVisible() || autoCheckStarted ||
            lastViewedBubble?.isShow == true) return
        val shownAt = android.os.SystemClock.uptimeMillis()
        bubbleUntil = shownAt + BUBBLE_SHOW_MS
        val visibleContent = fragments.getOrNull(mBinding.mViewPager.currentItem)
        val liveBubble = visibleContent?.view?.findViewById<View>(R.id.btn_live)
        val bubble = LastViewedDialog(host, vod, mBinding.root, liveBubble)
        lastViewedBubble = bubble
        XPopup.Builder(host)
            .hasShadowBg(false)
            .isDestroyOnDismiss(true)
            .isCenterHorizontal(true)
            .isTouchThrough(true)
            // 没有可见直播球时沿用原位置；有直播球时保持水平居中并按空间选择同排或上移。
            .offsetY(ScreenUtils.getAppScreenHeight() - ConvertUtils.dp2px(155f + 44f))
            .asCustom(bubble)
            .show()
            .delayDismiss(BUBBLE_SHOW_MS)
        mHandler.postDelayed({
            if (lastViewedBubble === bubble) lastViewedBubble = null
        }, CHECK_AFTER_BUBBLE_MS + 1000L)
        // 气泡真的出现了:把自动检查往后排到它消失之后。
        rescheduleAutoUpdateCheckAfterBubble()
    }

    private fun dismissLastViewedBubble() {
        lastViewedBubble?.dismiss()
        lastViewedBubble = null
        bubbleUntil = 0L
    }

    private fun rescheduleAutoUpdateCheckAfterBubble() {
        if ((activity as? MainActivity)?.isUserInitiatedLaunch() != true ||
            !SystemConfig.isAutoCheckUpdate()) return
        mHandler.removeCallbacks(pendingAutoCheck)
        mHandler.postDelayed(pendingAutoCheck, CHECK_AFTER_BUBBLE_MS)
    }

    /**
     * 启动自动检查更新:等首页"上次看到"气泡消失后再检查,有新版本由 {@link UpdateCheck}
     * 弹出更新说明弹窗(与「我的-关于-检查更新」同一套动作)。
     * <p>
     * 受"自动检查更新"开关控制(设置页,默认开);无痕浏览/本机无历史时气泡不弹,这里按默认延时照常检查
     * (不能挂在"有历史记录"分支里,否则那种情况下自动检查永远不生效)。
     * 仅用户主动启动时排队；检查本身由 UpdateCheck 做进程级去重,每次启动最多一次;
     * 重复排队时只保留最后一次。
     */
    private fun scheduleAutoUpdateCheck() {
        mHandler.removeCallbacks(pendingAutoCheck)
        val host = activity as? MainActivity
        if (host?.isUserInitiatedLaunch() != true || !SystemConfig.isAutoCheckUpdate()) return
        if (host?.isStartupSplashVisible() == true) {
            val expectedView = view
            host.runAfterStartupSplash(Runnable {
                if (canShowStartupUi(host, expectedView) && !autoCheckStarted) {
                    scheduleAutoUpdateCheck()
                }
            })
            return
        }
        // 气泡若已排好,等到它消失;否则(无历史/无痕)用默认延时
        val now = android.os.SystemClock.uptimeMillis()
        val remain = bubbleUntil - now
        mHandler.postDelayed(pendingAutoCheck, if (remain > 0) remain + 600L else CHECK_DEFAULT_DELAY_MS)
    }

    /** 真正执行自动检查(主线程):进程级去重与开关判定在 UpdateCheck 内 */
    private fun runAutoUpdateCheck() {
        val act = activity as? MainActivity ?: return
        if (!act.isUserInitiatedLaunch()) return
        if (act.isStartupSplashVisible()) {
            scheduleAutoUpdateCheck()
            return
        }
        if (isAdded && isResumed && !act.isFinishing && !act.isDestroyed &&
            ActivityUtils.getTopActivity() === act) {
            autoCheckStarted = true
            val bubble = lastViewedBubble
            lastViewedBubble = null
            bubbleUntil = 0L
            if (bubble?.isShow == true) {
                // 先等独立窗口的气泡退场，再允许更新弹窗入场，避免窗口层级倒置。
                bubble.dismissWith {
                    if (!act.isFinishing && !act.isDestroyed) {
                        com.github.tvbox.osc.update.UpdateCheck.autoCheckOnce(act, null)
                    }
                }
            } else {
                com.github.tvbox.osc.update.UpdateCheck.autoCheckOnce(act, null)
            }
        }
    }
}

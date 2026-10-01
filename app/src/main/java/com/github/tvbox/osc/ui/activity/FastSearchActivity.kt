package com.github.tvbox.osc.ui.activity

import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.transition.ChangeBounds
import android.transition.ChangeClipBounds
import android.transition.ChangeTransform
import android.transition.Fade
import android.transition.Transition
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.angcyo.tablayout.DslTabLayout
import com.blankj.utilcode.util.GsonUtils
import com.blankj.utilcode.util.KeyboardUtils
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ScreenUtils
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.DoubanSuggestBean
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.databinding.ActivityFastSearchBinding
import com.github.tvbox.osc.spiderapi.SourceConfigProviders
import com.github.tvbox.osc.spiderapi.SourceLoaderProviders
import com.github.tvbox.osc.theme.ThemeDrawables
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.ui.RefreshUiEnvFactory
import com.github.tvbox.osc.ui.adapter.FastSearchAdapter
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter.SelectDialogInterface
import com.github.tvbox.osc.ui.kit.ListEndTipController
import com.github.tvbox.osc.ui.dialog.AttachActionDialog
import com.github.tvbox.osc.ui.dialog.ConfirmDialog
import com.github.tvbox.osc.ui.dialog.DoubanSuggestDialog
import com.github.tvbox.osc.ui.dialog.SearchCheckboxDialog
import com.github.tvbox.osc.ui.dialog.SelectDialog
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.SearchPagingState
import com.github.tvbox.osc.util.SearchSourceHealth
import com.github.tvbox.osc.util.HCallBack
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.github.tvbox.osc.util.HttpClient
import com.github.tvbox.osc.util.SearchFilter
import com.github.tvbox.osc.util.SearchHelper
import com.github.tvbox.osc.util.SubscriptionConfig
import com.github.tvbox.osc.util.Utils
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.interfaces.SimpleCallback
import com.github.tvbox.osc.ui.kit.FlowTagLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FastSearchActivity : BaseVbActivity<ActivityFastSearchBinding>(), TextWatcher {

    companion object {
        const val EXTRA_HOME_SEARCH_TRANSITION = "home_search_transition"
        const val HOME_SEARCH_TRANSITION_NAME = "home_search_expand"
        private const val HOME_SEARCH_TRANSITION_MS = 360L

        private var mCheckSources: HashMap<String, String>? = null
        fun setCheckedSourcesForSearch(checkedSources: HashMap<String, String>?) {
            mCheckSources = checkedSources
        }

        /** 一次用户手势内自动续拉的最大轮数(列表不满一屏时自动补页,避免用户无从"上拉") */
        private const val MAX_AUTO_PAGING_CHAIN = 3

        /** 第一波收尾兜底:个别源卡死/不回包时,到点也要把第二波放出去(否则慢源就被永远压在后面) */
        private const val PRIMARY_WAVE_FALLBACK_MS = 12_000L

        /**
         * 整轮搜索看门狗:某源 getSearch 抛异常(断网时很常见)时批次不会被投递 → allRunCount 不归零 →
         * "搜索中"永远转、"到底了"永不出现。到点强制收尾(两波 + 重试都留足余量)。
         */
        private const val SEARCH_WATCHDOG_MS = 60_000L
    }

    private lateinit var sourceViewModel : SourceViewModel
    private var fromHomeSearch = false
    private var homeSearchOverlay: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        fromHomeSearch = savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_HOME_SEARCH_TRANSITION, false)
        if (fromHomeSearch) {
            window.sharedElementEnterTransition = homeSearchTransition()
            window.sharedElementReturnTransition = homeSearchTransition()
            postponeEnterTransition()
        }
        super.onCreate(savedInstanceState)
        if (fromHomeSearch) prepareHomeSearchTransition()
    }

    private fun homeSearchTransition(): Transition = TransitionSet().apply {
        addTransition(ChangeBounds())
        addTransition(ChangeClipBounds())
        addTransition(ChangeTransform())
        duration = HOME_SEARCH_TRANSITION_MS
        interpolator = DecelerateInterpolator()
    }

    private fun prepareHomeSearchTransition() {
        // 单独的共享元素盖住搜索页内容：从首页胶囊扩展为整页，返回时再收回原位置。
        val content = findViewById<FrameLayout>(android.R.id.content)
        val overlay = View(this).apply {
            transitionName = HOME_SEARCH_TRANSITION_NAME
            background = ThemeDrawables.themedDrawable(R.drawable.bg_search_round_float, resources)
        }
        content.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        homeSearchOverlay = overlay
        mBinding.root.alpha = 0f
        window.sharedElementEnterTransition.addListener(object : Transition.TransitionListener {
            override fun onTransitionStart(transition: Transition) = Unit
            override fun onTransitionEnd(transition: Transition) = revealSearchContent()
            override fun onTransitionCancel(transition: Transition) = revealSearchContent()
            override fun onTransitionPause(transition: Transition) = Unit
            override fun onTransitionResume(transition: Transition) = Unit
        })
        overlay.post { if (!isFinishing && !isDestroyed) startPostponedEnterTransition() }
    }

    private fun revealSearchContent() {
        val overlay = homeSearchOverlay ?: return
        if (isFinishing || isDestroyed) return
        mBinding.root.animate().alpha(1f).setDuration(160L).start()
        overlay.animate().alpha(0f).setDuration(160L).withEndAction {
            overlay.visibility = View.GONE
        }.start()
    }

    private var searchAdapter = FastSearchAdapter()
    private var searchAdapterFilter = FastSearchAdapter()
    private var searchTitle: String? = ""
    private var spNames = HashMap<String, String>()
    private var isFilterMode = false
    private var searchFilterKey: String? = "" // 过滤的key
    private var resultVods = HashMap<String, MutableList<Movie.Video>>()

    /** 「最近热搜」固定两列，宽屏只调整榜单宽度。 */
    private val mHotRankAdapter = com.github.tvbox.osc.ui.adapter.HotRankAdapter()

    /** 搜索是否已全部完成(全部来源返回后置真;"到底了"仅完成态显示) */
    private var searchFinished = false

    // ------------------------------------------------------------------
    // 聚合搜索"加载更多"(翻页):记账在 SearchPagingState(纯逻辑,有 JVM 单测),本页只负责发请求/追加列表
    // ------------------------------------------------------------------

    /** 每源页码/总页数与一轮翻页的 发起-回收-到底 判定(纯逻辑,有 JVM 单测) */
    private val paging = SearchPagingState()
    /** 列表不满一屏时自动续拉的次数上限(一次用户手势内最多自动补几页,防无限自动翻页) */
    private var autoPagingChain = 0
    /** "到底了"统一控制器 */
    private var mEndTipController: ListEndTipController? = null

    /**
     * 顶部下拉"静默刷新":不动来源抽屉/历史热词/整页 loading,结果先暂存,
     * 全部来源返回后一次性换列表(避免中途清屏/反复 relayout 造成抖动);
     * 若用户正开着来源列,保持原样,只更新结果数据。
     */
    /** 下拉刷新本轮是否已提前收起反馈圈(首批结果到达即收;慢源不再拖住下拉反馈) */
    private var refreshSpinnerDismissed = false

    /** 是否已勾选订阅(以订阅管理写入的接口地址为准) */
    private fun hasSubscription(): Boolean {
        return !TextUtils.isEmpty(SubscriptionConfig.getApiUrl())
    }

    override fun init() {
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        // 主搜索批次结果直调:VM 回调线程不保证主线程,统一切主线程喂 searchData(替代 TYPE_SEARCH_RESULT 订阅)
        sourceViewModel.setSearchBatchListener { data ->
            val call = syncCall.get()
            if (call != null) {
                // 同步返回(jar/JS 与 typed HTTP 都在 getSearch 内直调回来):置位后由 launchSearchTask 统一记账
                if (data != null) call.delivered = true
            } else if (data != null) {
                // 异步回调(HTTP 源回退旧 HttpClient 路径时):批次里带来源 key,据此补记实测耗时(失败批次归不到来源,不记)
                data.movie?.videoList?.firstOrNull()?.sourceKey?.let { recordSourceCost(it) }
            }
            runOnUiThread { searchData(data, call?.isRetry == true) }
        }
        // 翻页批次单独一路:不与首屏批次混算,宿主按来源记账(见 searchPageData)
        sourceViewModel.setSearchPageBatchListener { key, data, page ->
            runOnUiThread { searchPageData(key, data, page) }
        }
        initView()
        initData()
        //历史搜索
        initHistorySearch()
        // 最近热搜(360 影视排行;与 360kan 排行页同源,NewBox 搜索页同款)
        initHotRank()
    }

    override fun onResume() {
        super.onResume()
        resumeSearches()
    }

    /** 屏幕旋转或窗口尺寸变化时，重算结果卡片列数与搜索区宽度。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 仅宫格/通栏是自适应列数;单列列表列数为 1,重算无影响,统一走 applyResultLayout 即可
        applyResultLayout(SystemConfig.getSearchResultLayout())
        mBinding.scrollSearchSuggest.post {
            updateSearchSectionWidths(mBinding.scrollSearchSuggest.width)
        }
    }

    private fun initView() {
        mBinding.etSearch.setOnEditorActionListener { _: TextView?, actionId: Int, _: KeyEvent? ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(mBinding.etSearch.text.toString())
                return@setOnEditorActionListener true
            }
            false
        }
        mBinding.etSearch.addTextChangedListener(this)
        mBinding.ivFilter.setOnClickListener { filterSearchSource() }
        mBinding.ivBack.setOnClickListener {
            // 与系统返回同一条链:结果页先回搜索页,搜索页再按返回才退出(见 onBackPressed)
            onBackPressed()
        }
        mBinding.ivSearch.setOnClickListener {
            search(mBinding.etSearch.text.toString())
        }
        mBinding.ivMore.setOnClickListener { showMoreActions() }
        mBinding.tabLayout.configTabLayoutConfig {
            // 来源列表的文字色显式走主题:库的选中/未选中色只从 XML 属性取(编译期固定),
            // 自定义主题下不会变(用户清单第 9 条"右滑的来源列表文字都改成正文颜色")
            tabSelectColor = androidx.core.content.ContextCompat.getColor(
                this@FastSearchActivity, R.color.text_foreground)
            tabDeselectColor = androidx.core.content.ContextCompat.getColor(
                this@FastSearchActivity, R.color.text_sub_foreground)
            onSelectViewChange  = { _, selectViewList, _, _ ->
                    val tvItem: TextView = selectViewList.first() as TextView
                    filterResult(tvItem.text.toString())
                    closeSourceDrawer() // 选中来源后收起抽屉,结果区回到全屏
                }
        }
        // 选中来源那一行的底(库的 tab 指示器)同样要按主题给:它来自 XML 属性
        // app:tab_indicator_drawable="@drawable/bg_small_round_float",而库内是
        // typedArray.getDrawable() 取的 —— **属性里的 drawable 是 native 取法**,换肤通道
        // (ThemeResources/ThemeInflaterFactory/ThemeDrawables 的自动注入)都拦不到,
        // 自定义主题下它会停在编译期的 bg_surface,与同一行已经按主题取色的文字不同底。
        // 这里显式取"按主题重建过"的那份(只换颜色,圆角/描边/尺寸原样保留;
        // 内置主题下返回的就是系统那份,观感不变 —— 见 ThemeDrawables#themedDrawable)。
        mBinding.tabLayout.tabIndicator.indicatorDrawable =
            ThemeDrawables.themedDrawable(R.drawable.bg_small_round_float, resources)
        mBinding.mGridView.setHasFixedSize(true)
        mBinding.mGridView.adapter = searchAdapter
        mBinding.mGridViewFilter.adapter = searchAdapterFilter
        // 结果两个列表(普通/单来源过滤)共用一套点击/长按逻辑;适配器同时服务列表与宫格布局,
        // 因此无论单列还是宫格,点击进详情、长按弹评分均生效
        bindResultAdapter(searchAdapter)
        bindResultAdapter(searchAdapterFilter)

        // 按已保存布局(单列/宫格)初始化结果列表
        applyResultLayout(SystemConfig.getSearchResultLayout())

        setLoadSir(mBinding.llLayout)
        // 来源列表(左页)与结果列表(右页)同容器并排;默认显示右页(结果全屏)
        mBinding.llSearchResult.setPages(mBinding.llWord, mBinding.llLayout)
        setupResultRefreshAndEndTip()
    }

    /**
     * 结果列表交互统一:顶部下拉 = 拉伸回弹+刷新转圈混合(重新搜索);
     * 到底悬浮"到底了"(与首页/分类一致:仅完成态、确实到底且超一屏才显示,由列表自身承担上推回弹)。
     */
    private fun setupResultRefreshAndEndTip() {
        // 环境注入:动画/toast/业务日志由 app 组合根组装,页面不直连 LoadingAnim/AppBubble/LogStore
        val env = RefreshUiEnvFactory.create()
        mBinding.llLayout.setEnv(env)
        mBinding.llLayout.setOnRefreshListener {
            pullRefreshSearch()
        }
        // 刷新中上拉打断:中止本轮并保留当前结果
        mBinding.llLayout.setOnRefreshCancelListener {
            cancelQuietRefresh()
        }
        // "到底了"统一控制器(普通/过滤两列表共用;按当前可见列表判定)
        val tip = findViewById<View>(R.id.end_tip)
        if (tip != null) {
            mEndTipController = ListEndTipController(tip, object : ListEndTipController.State {
                override fun list(): RecyclerView? = visibleResultList()
                override fun hasData(): Boolean = visibleResultAdapter()?.data?.isNotEmpty() == true
                override fun endReached(): Boolean = searchFinished && !paging.hasMore()
                override fun busy(): Boolean = !searchFinished || paging.inRound()
            }, env)
            mEndTipController!!.attach(mBinding.mGridView)
            mEndTipController!!.attach(mBinding.mGridViewFilter)
        }
        // 上拉到底自动加载下一页(每个还有下一页的来源各取一页,逐批追加)
        val pagingScroll = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val last = lastVisiblePosition(rv)
                val total = rv.adapter?.itemCount ?: 0
                if (last >= total - 3) {
                    autoPagingChain = 0 // 用户主动上拉:重新给足自动续拉额度
                    startPagingIfNeeded()
                }
            }
        }
        mBinding.mGridView.addOnScrollListener(pagingScroll)
        mBinding.mGridViewFilter.addOnScrollListener(pagingScroll)
    }

    /** 可见列表最后一个条目位置(单列/宫格/瀑布流三种布局都要覆盖) */
    private fun lastVisiblePosition(rv: RecyclerView): Int {
        return when (val lm = rv.layoutManager) {
            is StaggeredGridLayoutManager -> lm.findLastVisibleItemPositions(null).maxOrNull() ?: -1
            is LinearLayoutManager -> lm.findLastVisibleItemPosition()
            else -> -1
        }
    }

    /**
     * 发起一轮"加载更多":对"已加载页 < 总页数"的来源各取下一页(共享大池并发,逐批追加)。
     * 首屏未完成、已有翻页在途、或所有源都到底时不做任何事。
     * 请求走 [HeavyTaskUtil] 共享执行器(JS 源取页是同步网络调用,不能在主线程发起)。
     */
    private fun startPagingIfNeeded() {
        if (!searchFinished || paging.inRound()) return
        val keys = paging.keysWithMore()
        if (keys.isEmpty() || !paging.beginRound(keys)) {
            updateEndTip()
            return
        }
        if (!refreshSpinnerDismissed) refreshSpinnerDismissed = true
        LogStore.log(Category.OTHER, "搜索: 加载更多 " + keys.size + " 个来源")
        for (key in keys) {
            val page = paging.pageOf(key) + 1
            HeavyTaskUtil.getBigTaskExecutorService().execute {
                sourceViewModel.getSearchPaged(key, searchTitle ?: "", page)
            }
        }
        updateEndTip()
    }

    /** 翻页一轮收尾:没回有效批次的源标记到底,刷新"到底了";列表不满一屏时自动再补一页(有次数上限) */
    private fun finishPagingRound(appended: Boolean) {
        paging.finishRound()
        updateEndTip()
        val rv = visibleResultList()
        if (appended && rv != null && paging.hasMore() && !rv.canScrollVertically(1)
            && autoPagingChain < MAX_AUTO_PAGING_CHAIN
        ) {
            autoPagingChain++
            startPagingIfNeeded()
        }
    }

    /** 复位翻页状态(新一轮搜索/下拉重刷时调用) */
    private fun resetPagingState() {
        paging.reset()
        autoPagingChain = 0
    }

    /** 当前可见的结果列表(普通结果 或 单来源过滤结果) */
    private fun visibleResultList(): RecyclerView? = when {
        mBinding.mGridView.visibility == View.VISIBLE -> mBinding.mGridView
        mBinding.mGridViewFilter.visibility == View.VISIBLE -> mBinding.mGridViewFilter
        else -> null
    }

    /** 当前可见结果对应的 adapter(判定是否有数据) */
    private fun visibleResultAdapter(): FastSearchAdapter? = when {
        mBinding.mGridView.visibility == View.VISIBLE -> searchAdapter
        mBinding.mGridViewFilter.visibility == View.VISIBLE -> searchAdapterFilter
        else -> null
    }

    /** 同步刷新"到底了"(滚动由控制器监听触发;保留方法供调用点复用) */
    private fun refreshEndTip() {
        mEndTipController?.refresh()
    }

    /** 数据/滚动变化后调度刷新(列表可能尚未完成布局,post 到下一帧再判) */
    private fun updateEndTip() {
        mEndTipController?.update()
    }

    /** 翻到来源列表页(左页);结果页(右页)默认显示,横滑吸附由 HorizontalSlidePagesLayout 处理 */
    private fun openSourceDrawer() {
        if (mBinding.llSearchResult.visibility != View.VISIBLE) return
        if (!mBinding.llSearchResult.isLeftShown) {
            mBinding.llSearchResult.showLeft()
        }
    }

    private fun closeSourceDrawer() {
        if (mBinding.llSearchResult.visibility != View.VISIBLE) return
        if (mBinding.llSearchResult.isLeftShown) {
            mBinding.llSearchResult.showRight()
        }
    }

    /**
     * 返回链:来源抽屉(展开时先收起)→ **结果页回到搜索页** → 搜索页再按返回才退出本页。
     * <p>
     * 用户口径:"搜索结果页返回为啥直接跳到首页了" —— 以前 ivBack/返回键一律 finish(),
     * 而本页是从首页拉起来的,finish 就等于"跳回首页",把刚搜的东西丢了。
     */
    override fun onBackPressed() {
        if (mBinding.llSearchResult.visibility == View.VISIBLE) {
            if (mBinding.llSearchResult.isLeftShown) {
                closeSourceDrawer()   // 来源列表还展着:先收回结果页(与遥控返回键同一条链)
                return
            }
            backToSearchPage()
            return
        }
        if (fromHomeSearch) {
            KeyboardUtils.hideSoftInput(this)
            mBinding.root.animate().cancel()
            homeSearchOverlay?.animate()?.cancel()
            homeSearchOverlay?.apply {
                alpha = 1f
                visibility = View.VISIBLE
            }
            mBinding.root.alpha = 0f
            finishAfterTransition()
        } else {
            super.onBackPressed()
        }
    }

    /**
     * 从结果页回到搜索页:停掉在跑的搜索 + 清空输入框 —— 清空会走 [afterTextChanged] 切回建议态
     * (露出「最近热搜 + 搜索历史」,相关搜索收起);用户的**搜索历史**不受影响(只有输入框内容被清掉)。
     */
    private fun backToSearchPage() {
        cancel()
        KeyboardUtils.hideSoftInput(this)
        mBinding.etSearch.setText("")
    }

    /** 遥控适配:左方向键翻到来源列表;来源页展开时右方向键/返回键翻回结果 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {        if (mBinding.llSearchResult.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!mBinding.llSearchResult.isLeftShown) {
                    openSourceDrawer()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK ->
                    if (mBinding.llSearchResult.isLeftShown) {
                        closeSourceDrawer()
                        return true
                    }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 指定搜索源(过滤)
     */
    private fun filterSearchSource() {
        val allSourceBean = SourceConfigProviders.get().sourceBeanList
        if (allSourceBean.isNotEmpty()) {
            val searchAbleSource: MutableList<SourceBean> = ArrayList()
            for (sourceBean: SourceBean in allSourceBean) {
                if (sourceBean.isSearchable) {
                    searchAbleSource.add(sourceBean)
                }
            }
            val mSearchCheckboxDialog = SearchCheckboxDialog(this@FastSearchActivity, searchAbleSource, mCheckSources)
            mSearchCheckboxDialog.show()
        }

    }

    // ── 结果布局切换入口:三点→气泡列表→「切换布局」→三层选项(单列/宫格/通栏) ──
    private val resultLayoutNames = arrayOf("单列列表", "宫格/网格", "通栏卡片")

    /**
     * 顶栏三点(⋮)入口:弹出气泡列表。当前仅一项「切换布局」,后续可继续加项。
     * <p>
     * <b>不要用 XPopup 的 `asAttachList`</b>:它的气泡面与文字色来自库内固定样式,不吃主题文件,
     * 自定义主题下这块气泡会与全站"另一个面"割裂(用户口径:搜索页这个 tip 的颜色不对)。
     * 全站长按/点按气泡统一走 {@link AttachActionDialog}(主题悬浮面 + text_main 文字色)。
     */
    private fun showMoreActions() {
        AttachActionDialog.show(
            mBinding.ivMore,
            arrayOf("切换布局"),
            intArrayOf(AttachActionDialog.NORMAL)
        ) { index: Int ->
            if (index == 0) {
                showResultLayoutDialog()
            }
        }
    }

    /**
     * 「切换布局」选项弹窗:单列列表 / 宫格网格 / 通栏卡片。
     * 选中后立即按 new pos 应用布局。
     */
    private fun showResultLayoutDialog() {
        val dialog = SelectDialog<String>(this@FastSearchActivity)
        dialog.setTip("切换布局")
        val current = SystemConfig.getSearchResultLayout().coerceIn(0, resultLayoutNames.size - 1)
        dialog.setAdapter(object : SelectDialogInterface<String?> {
            override fun click(value: String?, pos: Int) {
                SystemConfig.setSearchResultLayout(pos)
                applyResultLayout(pos)
                val layoutName = resultLayoutNames.getOrElse(pos) { value ?: "" }
                LogStore.success(Category.OTHER, "搜索: 切换结果布局为 " + layoutName)
                AppBubble.toast("已切换为$layoutName")
                dialog.dismiss()
            }

            override fun getDisplay(value: String?): String {
                return value ?: ""
            }
        }, SelectDialogAdapter.stringDiff, resultLayoutNames.toList(), current)
        dialog.show()
    }

    /**
     * 应用结果布局：0 单列列表(LinearLayoutManager+列表行)，1 宫格/网格(GridLayoutManager+3:4 宫格卡)，
     * 2 通栏卡片(GridLayoutManager+2:1 横卡,多列并排)。仅作用于结果区中间布局，不动来源抽屉/刷新/到底了。
     */
    private fun applyResultLayout(mode: Int) {
        when (mode) {
            FastSearchAdapter.MODE_GRID -> {
                // 瀑布流(StaggeredGridLayoutManager):3:4 图固定,但下方文字行数不同(有些隐藏),
                // 卡片高度错落、按列自然堆叠;列数用与首页完全一致的「单卡最大宽 190dp 自适应」计算,
                // 由最终可并排的卡片数决定(屏宽越宽列数越多),不写死、也不按自定义宽度反推
                val span = Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)
                val lm = StaggeredGridLayoutManager(span, StaggeredGridLayoutManager.VERTICAL)
                lm.gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
                mBinding.mGridView.layoutManager = lm
                val lm2 = StaggeredGridLayoutManager(span, StaggeredGridLayoutManager.VERTICAL)
                lm2.gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
                mBinding.mGridViewFilter.layoutManager = lm2
            }
            FastSearchAdapter.MODE_BANNER -> {
                // 2:1 横卡:限高→单卡最大宽=2×限高;屏宽除以单卡最大宽得列数(四舍五入到整数列),
                // 使多卡并排撑满屏宽,放不下就缩列宽(高度随之为列宽/2)
                val banSpan = Utils.getAdaptiveGridSpan(bannerCardMaxWidthDp(), 1, 0)
                mBinding.mGridView.layoutManager = GridLayoutManager(this, banSpan)
                mBinding.mGridViewFilter.layoutManager = GridLayoutManager(this, banSpan)
            }
            else -> {
                // 单列列表
                mBinding.mGridView.layoutManager = LinearLayoutManager(this)
                mBinding.mGridViewFilter.layoutManager = LinearLayoutManager(this)
            }
        }
        searchAdapter.setMode(mode)
        searchAdapterFilter.setMode(mode)
        mEndTipController?.refresh()
    }

    /** 通栏 2:1 横卡最大显示高度(dp,变小即更矮) */
    private fun bannerMaxHeightDp(): Int = 210

    /** 通栏单卡最大宽 = 2×限高(保持 2:1) */
    private fun bannerCardMaxWidthDp(): Float = 2f * bannerMaxHeightDp()

    /**
     * 绑一个结果列表适配器的点击/长按:统一「点击进详情、长按弹评分」。
     * searchAdapter / searchAdapterFilter 为同一 FastSearchAdapter 实例,同时服务列表与宫格两种布局,
     * 因此该逻辑对两种布局一致生效(组件复用:评分弹窗走 [showDoubanSuggest])。
     */
    private fun bindResultAdapter(adapter: FastSearchAdapter) {
        adapter.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            openDetail(adapter.data.getOrNull(position))
        }
        adapter.setOnItemLongClickListener { _, _, position ->
            showDoubanSuggest(adapter.data.getOrNull(position)?.name)
            true
        }
    }

    /** 点击结果项进详情(两类列表共用) */
    private fun openDetail(video: Movie.Video?) {
        if (video == null) return
        pauseSearch()
        val bundle = Bundle()
        bundle.putString("id", video.id)
        bundle.putString("sourceKey", video.sourceKey)
        bundle.putString("vodName", video.name)
        jumpActivity(DetailActivity::class.java, bundle)
    }

    private fun filterResult(spName: String) {
        if (spName === "全部显示") {
            mBinding.mGridView.visibility = View.VISIBLE
            mBinding.mGridViewFilter.visibility = View.GONE
            updateEndTip()
            return
        }
        mBinding.mGridView.visibility = View.GONE
        mBinding.mGridViewFilter.visibility = View.VISIBLE
        val key = spNames[spName]
        if (key.isNullOrEmpty()) return
        if (searchFilterKey === key) return
        searchFilterKey = key
        val list: List<Movie.Video> = (resultVods[key])!!
        searchAdapterFilter.setNewData(list)
        updateEndTip()
    }

    private fun initData() {
        mCheckSources = SearchHelper.getSourcesForSearch()
        if (intent != null && intent.hasExtra("title")) {
            val title = intent.getStringExtra("title")
            if (!TextUtils.isEmpty(title)) {
                showLoading()
                search(title)
            }
        }
    }

    private fun hideHotAndHistorySearch(isHide: Boolean) {
        if (isHide) {
            mBinding.scrollSearchSuggest.visibility = View.GONE
            mBinding.llSearchResult.visibility = View.VISIBLE
        } else {
            mBinding.scrollSearchSuggest.visibility = View.VISIBLE
            mBinding.llSearchResult.visibility = View.GONE
        }
    }

    private fun initHistorySearch() {
        val mSearchHistory: List<String> = SubscriptionConfig.getSearchHistory()
        mBinding.llHistory.visibility = if (mSearchHistory.isNotEmpty()) View.VISIBLE else View.GONE
        mBinding.flHistory.adapter = object : FlowTagLayout.TagAdapter<String?>(mSearchHistory) {
            override fun getView(parent: FlowTagLayout, position: Int, s: String?): View {
                val tv: TextView = LayoutInflater.from(this@FastSearchActivity).inflate(
                    R.layout.item_search_word_hot,
                    mBinding.flHistory, false
                ) as TextView
                // 点击型小组件按钮:按下整键透明度 80% 再恢复(用户口径)
                com.github.tvbox.osc.ui.kit.WidgetPressEffect.attach(tv)
tv.text = s
                return tv
            }
        }
        mBinding.flHistory.setOnTagClickListener { _, position, _ ->
            search(mSearchHistory[position])
            true
        }
        findViewById<View>(R.id.iv_clear_history).setOnClickListener {
            ConfirmDialog.showDanger(this, "清空搜索历史", "确定清空全部搜索历史吗？", "清空") {
                SubscriptionConfig.clearSearchHistory()
                initHistorySearch()
            }
        }
    }

    /**
     * 最近热搜:360 影视排行(NewBox 搜索页同款接口 {@code rank?cat=3}),固定两列。
     * 解析与上限在 [com.github.tvbox.osc.util.SearchApiParsers] 里(纯逻辑,有 JVM 单测)。
     * 拿不到/为空时标题与整块一起收起,不留空标题。
     */
    private fun initHotRank() {
        // 标题与卡片按主题令牌绑定；RecyclerView 的背景不能只依赖 XML inflate 时的注入。
        com.github.tvbox.osc.theme.ThemeSweep.applyTextColor(mBinding.tvHotTitle, R.color.text_foreground)
        ThemeDrawables.applyBackground(mBinding.rvHotRank, R.drawable.bg_large_round_float)
        mBinding.rvHotRank.adapter = mHotRankAdapter
        mBinding.scrollSearchSuggest.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                mBinding.scrollSearchSuggest.post {
                    updateSearchSectionWidths(mBinding.scrollSearchSuggest.width)
                }
            }
        }
        mHotRankAdapter.setOnItemClickListener { _, _, position ->
            mHotRankAdapter.wordAt(position)?.let { search(it) }
        }
        HttpClient.get(com.github.tvbox.osc.util.SearchApiParsers.HOT_RANK_URL, null, null, null, object : HCallBack {
            override fun onSuccess(response: String) {
                val hots = com.github.tvbox.osc.util.SearchApiParsers.parseHotRank(response)
                mHotRankAdapter.setNewData(hots)
                updateHotRankVisibility(hots.isNotEmpty() && !isTyping())
            }

            override fun onError(e: Throwable) {
                // 拿不到热搜不打扰用户:整块收起(搜索本身不受影响)
                mHotRankAdapter.setNewData(emptyList())
                updateHotRankVisibility(false)
            }
        })
    }

    /** 热搜与历史搜索使用相同的最大宽度，标题、卡片和清除键左右对齐。 */
    private fun updateSearchSectionWidths(viewportWidth: Int) {
        if (viewportWidth <= 0) return
        // AutoSize 会改 Activity 的 displayMetrics.density；用配置宽度反推真实屏宽比例。
        val screenWidthDp = resources.configuration.screenWidthDp
        if (screenWidthDp <= 0) return
        val pixelsPerDp = viewportWidth.toFloat() / screenWidthDp
        val maxWidth = (520f * pixelsPerDp + 0.5f).toInt()
        val availableWidth = viewportWidth - mBinding.llSearchSuggest.paddingLeft -
            mBinding.llSearchSuggest.paddingRight
        if (availableWidth <= 0) return
        val contentWidth = minOf(availableWidth, maxWidth)
        for (section in arrayOf(mBinding.llHotRank, mBinding.llHistory)) {
            val params = section.layoutParams
            if (params.width != contentWidth) {
                params.width = contentWidth
                section.layoutParams = params
            }
        }
    }

    /** 热搜仅在隐藏→显示时淡入；布局变化一起过渡，避免历史搜索突然下跳。 */
    private fun updateHotRankVisibility(show: Boolean) {
        val block = mBinding.llHotRank
        val target = if (show) View.VISIBLE else View.GONE
        if (block.visibility == target) return
        if (show && mBinding.llSearchSuggest.isShown && mBinding.llSearchSuggest.isLaidOut) {
            TransitionManager.beginDelayedTransition(mBinding.llSearchSuggest,
                TransitionSet().apply {
                    ordering = TransitionSet.ORDERING_TOGETHER
                    addTransition(ChangeBounds())
                    addTransition(Fade(Fade.IN))
                    duration = 220L
                    interpolator = DecelerateInterpolator()
                })
        }
        block.visibility = target
    }

    /** 输入框里有没有内容(有 = 页面处于"输入态",只显示相关搜索) */
    private fun isTyping(): Boolean =
        !TextUtils.isEmpty(mBinding.etSearch.text.toString().trim())

    /**
     * 输入态只留「相关搜索」,清空态露出「最近热搜 + 搜索历史」(与 NewBox 搜索页一致):
     * 输入时被替换掉的两块不会同时挂在页面上,免得同屏两个热搜/联想列表打架。
     */
    private fun applySuggestionSections() {
        val typing = isTyping()
        val hasHot = mHotRankAdapter.data.isNotEmpty()
        updateHotRankVisibility(!typing && hasHot)
        val hasHistory = SubscriptionConfig.getSearchHistory().isNotEmpty()
        mBinding.llHistory.visibility = if (!typing && hasHistory) View.VISIBLE else View.GONE
    }

    /** 相关搜索 chip(爱奇艺联想结果):内联展示,点一下直接搜 */
    private fun updateSuggestChips(list: List<String>) {
        mBinding.flSuggest.adapter = object : FlowTagLayout.TagAdapter<String?>(list as List<String?>?) {
            override fun getView(parent: FlowTagLayout, position: Int, s: String?): View {
                val tv: TextView = LayoutInflater.from(this@FastSearchActivity).inflate(
                    R.layout.item_search_word_hot,
                    mBinding.flSuggest, false
                ) as TextView
                com.github.tvbox.osc.ui.kit.WidgetPressEffect.attach(tv)
                tv.text = s
                return tv
            }
        }
        mBinding.flSuggest.setOnTagClickListener { _, position, _ ->
            list.getOrNull(position)?.let { search(it) }
            true
        }
        val show = list.isNotEmpty()
        mBinding.tvSuggestTitle.visibility = if (show) View.VISIBLE else View.GONE
        mBinding.flSuggest.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun clearSuggestChips() {
        mBinding.tvSuggestTitle.visibility = View.GONE
        mBinding.flSuggest.visibility = View.GONE
    }

    /**
     * 相关搜索(输入联想):爱奇艺联想接口(NewBox 搜索页同款 {@code suggest.video.iqiyi.com/?if=mobile&key=}),
     * 吃拼音出中文。解析在 [com.github.tvbox.osc.util.SearchApiParsers](纯逻辑,有 JVM 单测)。
     * 结果**内联**成 chip(不再弹浮层),拿不到就收起这一块。
     */
    private fun getSuggest(text: String) {
        if (text.isBlank()) {
            clearSuggestChips()
            return
        }
        val url = com.github.tvbox.osc.util.SearchApiParsers.suggestUrl(text)
        HttpClient.get(url, null, object : HCallBack {
            override fun onSuccess(response: String) {
                val titles = com.github.tvbox.osc.util.SearchApiParsers.parseSuggest(response)
                // 期间用户又改了输入:结果作废(按当前输入再取一次,避免旧词覆盖新词)
                if (mBinding.etSearch.text.toString().trim() != text.trim()) return
                if (titles.isEmpty()) clearSuggestChips() else updateSuggestChips(titles)
            }

            override fun onError(e: Throwable) {
                clearSuggestChips()
            }
        })
    }

    private fun saveSearchHistory(searchWord: String?) {
        if (!searchWord.isNullOrEmpty()) {
            val history = SubscriptionConfig.getSearchHistory().toMutableList()
            if (!history.contains(searchWord)) {
                history.add(0, searchWord)
            } else {
                history.remove(searchWord)
                history.add(0, searchWord)
            }
            if (history.size > 30) {
                history.removeAt(30)
            }
            SubscriptionConfig.setSearchHistory(history)
        }
    }

    private fun search(title: String?) {
        if (title.isNullOrEmpty()) {
            AppBubble.toast("请输入搜索内容")
            return
        }

        if (!hasSubscription()) {
            AppBubble.toast("请先设置订阅")
            return
        }

        //先移除监听,避免重新设置要搜索的文字触发搜索建议(内联相关搜索)
        mBinding.etSearch.removeTextChangedListener(this)
        mBinding.etSearch.setText(title)
        mBinding.etSearch.setSelection(title.length)
        mBinding.etSearch.addTextChangedListener(this)
        if (!SystemConfig.isPrivateBrowsing()) { //无痕浏览不存搜索历史
            saveSearchHistory(title)
        }
        hideHotAndHistorySearch(true)
        clearSuggestChips()
        closeSourceDrawer() // 新一次搜索:来源抽屉默认收起
        KeyboardUtils.hideSoftInput(this)
        cancel()
        showLoading()
        searchTitle = title
        LogStore.log(Category.OTHER, "搜索: " + title)
        //fenci();
        mBinding.mGridView.visibility = View.INVISIBLE
        mBinding.mGridViewFilter.visibility = View.GONE
        searchAdapter.setNewData(ArrayList())
        searchAdapterFilter.setNewData(ArrayList())
        resultVods.clear()
        refreshSpinnerDismissed = false // 新一轮搜索:下拉刷新提前收圈状态复位
        searchFilterKey = ""
        isFilterMode = false
        spNames.clear()
        mBinding.tabLayout.removeAllViews()
        searchFinished = false // 新轮搜索未完成:"到底了"先隐藏
        updateEndTip()
        searchResult()
    }

    /**
     * 顶部下拉刷新 = 完整重刷:复用 {@link #searchResult()} 同一套结果编排
     * (清空旧列表 → 来源逐批返回逐批上屏),三布局(单列/宫格/通栏)共用同一数据流。
     * 反馈圈不等全部来源:收到首批有效结果即提前收起,其余来源后台继续收(见 searchData)。
     */
    private fun pullRefreshSearch() {
        val word = searchTitle
        if (word.isNullOrEmpty()) {
            mBinding.llLayout.setRefreshing(false)
            return
        }
        synchronized(searchLock) {
            searchEpoch++
            searchPaused = false
            pendingSearchKeys.clear()
        }
        if (searchSessionActive) {
            searchSessionActive = false
            SourceLoaderProviders.get().stopAllSourceTasks()
        }
        refreshSpinnerDismissed = false
        // 完整重刷:清空结果(含当前"全部显示"列表),逐批上屏
        searchAdapter.setNewData(ArrayList())
        searchAdapterFilter.setNewData(ArrayList())
        resultVods.clear()
        isFilterMode = false
        searchFilterKey = ""
        mBinding.mGridView.visibility = View.VISIBLE
        mBinding.mGridViewFilter.visibility = View.GONE
        searchFinished = false
        updateEndTip()
        searchResult()
    }

    /**
     * 用户上拉打断下拉刷新:中止本轮搜索(不再发起新来源),保留已上屏的结果;
     * 容器转圈与回弹由容器自己负责收起。epoch 递增让在途任务自弃。
     */
    private fun cancelQuietRefresh() {
        synchronized(searchLock) {
            searchEpoch++
            searchPaused = false
            pendingSearchKeys.clear()
        }
        if (searchSessionActive) {
            searchSessionActive = false
            SourceLoaderProviders.get().stopAllSourceTasks()
        }
    }

    /** 搜索编排状态:epoch=当前轮次;暂停后未发起的源进 pending,页面回前台续跑(替代页面自建 10 线程池) */
    private val searchLock = Object()
    private var searchEpoch = 0L
    private var searchPaused = false
    private var searchSessionActive = false
    private val pendingSearchKeys = ArrayList<String>()
    private val allRunCount = AtomicInteger(0)

    // ------------------------------------------------------------------
    // 两波投递:慢源不再堵住大部队(画像/分波规则在 SearchSourceHealth,纯逻辑有 JVM 单测)
    //
    // 真机日志实测:所有来源共用 5 线程的应用级共享池,一个源从发起到回包全程占一个槽位,
    // 慢源(6~15s 才超时)占满后后面的源根本发不出去 —— 现象是"先出来三四个,卡住十几秒,
    // 再陆陆续续冒出来"。故:第一波只投"上一轮不慢"的源且快的在前(快源先占槽位,首屏最快出结果),
    // 判慢的源与"本轮第一波里慢且没出结果"的源,等第一波收尾后再单独投第二波。
    // ------------------------------------------------------------------

    /** 每源快慢画像(进程内共享,页面重建后仍记得谁慢) */
    private val sourceHealth = SearchSourceHealth.shared()

    /** 第一波待回包数 / 第一波是否已收尾(收尾即投第二波) */
    private var primaryPending = 0
    private var primaryDone = true

    /** 本轮第二波要投的慢源(第一波收尾前先存着) */
    private var deferredWaveKeys = ArrayList<String>()

    /** 本轮各源发起时刻(type3 同步调用、HTTP 异步回调都要用它算实测耗时) */
    private val sourceStart = ConcurrentHashMap<String, Long>()

    /** 来源是否同步通道:type3=jar/JS 同步调用(返回时即可判定有没有结果),0/1/4=HttpClient 异步回调 */
    private val sourceSync = HashMap<String, Boolean>()

    /** 同步调用上下文:jar/JS 的结果直调在同一线程同步回来,借此把批次归到来源上 */
    private class SyncCall(val key: String, val isRetry: Boolean) {
        var delivered = false
    }

    private val syncCall = ThreadLocal<SyncCall>()

    /** 第一波收尾兜底:源卡死也要放第二波(见 PRIMARY_WAVE_FALLBACK_MS) */
    private val deferredWaveFallback = Runnable {
        if (!primaryDone) {
            primaryDone = true
            launchDeferredWave()
        }
    }

    /** 整轮搜索看门狗(见 SEARCH_WATCHDOG_MS):本轮没人收尾时强制收尾 */
    private val searchWatchdog = Runnable {
        android.util.Log.w("FastSearch", "搜索看门狗触发:本轮无收尾,强制收尾")
        finishSearchRound()
    }

    /**
     * 本轮搜索收尾(幂等):清"完成态"标记、没结果就空态、收起反馈圈、刷新"到底了"。
     * "全部来源已返回"与看门狗都走这里,避免两条路各写一份。
     */
    private fun finishSearchRound() {
        if (searchFinished) return
        searchFinished = true
        if (searchAdapter.data.size <= 0) {
            showEmpty()
        }
        cancel()
        if (mBinding.llLayout.isRefreshing) {
            mBinding.llLayout.setRefreshing(false)
        }
        updateEndTip()
    }
    private fun getSiteTextView(text: String): TextView {
        val textView = TextView(this)
        textView.text = text
        textView.gravity = Gravity.CENTER
        // 字号与搜索结果条目里"更新至XX集"(tvNote 12sp)保持一致,高亮来源条观感小巧协调
        textView.textSize = 12f
        val params = DslTabLayout.LayoutParams(-2, -2)
        params.topMargin = 20
        params.bottomMargin = 20
        textView.setPadding(20, 10, 20, 10)
        textView.layoutParams = params
        return textView
    }

    private fun searchResult() {
        synchronized(searchLock) {
            searchEpoch++
            searchPaused = false
            pendingSearchKeys.clear()
        }
        if (searchSessionActive) {
            // 旧实现每轮 shutdownNow 上一轮页面线程池并停 jar 引擎;共享池下由 epoch 让过期任务自弃
            searchSessionActive = false
            SourceLoaderProviders.get().stopAllSourceTasks()
        }
        searchAdapter.setNewData(ArrayList())
        searchAdapterFilter.setNewData(ArrayList())
        allRunCount.set(0)
        val searchRequestList: MutableList<SourceBean> = ArrayList()
        searchRequestList.addAll(SourceConfigProviders.get().sourceBeanList)
        val home = SourceConfigProviders.get().homeSourceBean
        searchRequestList.remove(home)
        searchRequestList.add(0, home)
        val siteKey = ArrayList<String>()
        mBinding.tabLayout.addView(getSiteTextView("全部显示"))
        mBinding.tabLayout.setCurrentItem(0, true, false)
        for (bean: SourceBean in searchRequestList) {
            if (!bean.isSearchable) {
                continue
            }
            if (mCheckSources != null && !mCheckSources!!.containsKey(bean.key)) {
                continue
            }
            siteKey.add(bean.key)
            spNames[bean.name] = bean.key
            sourceSync[bean.key] = bean.type == 3 // jar/JS 同步通道;HTTP 源结果走异步回调
            allRunCount.incrementAndGet()
        }
        if (siteKey.isNotEmpty()) {
            searchSessionActive = true
        }
        // 一轮新搜索:复位翻页记账(各源从第 1 页重新开始)
        resetPagingState()
        // 整轮看门狗:任何"没人投递批次"的路径都不允许把"搜索中"永久留着(断网 + 某源抛异常即触发)
        mBinding.root.removeCallbacks(searchWatchdog)
        mBinding.root.postDelayed(searchWatchdog, SEARCH_WATCHDOG_MS)
        // 分两波投递(见类内"两波投递"注释):第一波快源先行,判慢的源等第一波收尾后单独跑。
        // allRunCount 记的是全部来源(第一波+第二波),故"全部来源已返回"仍等两波都回完。
        sourceHealth.beginRound()
        val waves = sourceHealth.split(siteKey)
        deferredWaveKeys = ArrayList(waves.deferred)
        primaryPending = waves.primary.size
        primaryDone = waves.primary.isEmpty()
        mBinding.root.removeCallbacks(deferredWaveFallback)
        if (!primaryDone) {
            mBinding.root.postDelayed(deferredWaveFallback, PRIMARY_WAVE_FALLBACK_MS)
        }
        for (key in waves.primary) {
            launchSearch(key)
        }
        if (primaryDone) {
            launchDeferredWave() // 全是被判慢的源:直接开第二波,不必空等
        }
    }

    /**
     * 提交单个源搜索到应用级共享大池;真正发起前校验轮次/暂停,暂停任务进 pending(续跑再派)。
     * @param isRetry 第二波里对第一波"慢且没出结果"来源的重跑:只补数据上屏,不计入完成态(见 searchData)
     */
    private fun launchSearch(key: String, isRetry: Boolean = false) {
        val epoch = searchEpoch
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            launchSearchTask(key, epoch, isRetry)
        }
    }

    private fun launchSearchTask(key: String, epoch: Long, isRetry: Boolean) {
        synchronized(searchLock) {
            if (epoch != searchEpoch) return // 新一轮已发起:过期任务自弃(等价旧 shutdownNow)
            if (searchPaused) {
                pendingSearchKeys.add(key) // 跳详情暂停:未发起的源进 pending,onResume 续跑
                return
            }
        }
        val started = SystemClock.elapsedRealtime()
        sourceStart[key] = started
        val call = SyncCall(key, isRetry)
        syncCall.set(call)
        try {
            sourceViewModel.getSearch(key, searchTitle)
        } catch (_: Exception) {
        } finally {
            syncCall.remove()
        }
        // 同步返回的来源在这里判定"这一波有没有出结果":
        // - type3(jar/JS)本身同步;
        // - HTTP 源(type0/1/4)走 typed 契约时**同样是同步返回**(结果在 getSearch 内直调回来),
        //   只有回退到旧 HttpClient 路径才异步(那条由 recordSourceCost 记账)。
        // 原判定只看 sourceSync(type==3),导致 typed HTTP 源既不记耗时也不参与分波 ——
        // 慢的 HTTP 源永远留在第一波,"别让慢源占住池位"实际没生效。
        if (call.delivered || sourceSync[key] == true) {
            sourceHealth.onSourceDone(key, SystemClock.elapsedRealtime() - started, call.delivered)
        }
    }

    /** 异步源(HTTP)批次到达:按发起时刻补记实测耗时,供下一轮排序与分波 */
    private fun recordSourceCost(key: String) {
        val started = sourceStart[key] ?: return
        sourceHealth.onSourceDone(key, SystemClock.elapsedRealtime() - started, true)
    }

    /**
     * 第一波收尾(或兜底超时)后投第二波:上一轮判慢的源 + 本轮第一波"慢且没出结果"的重跑。
     * 此时共享池槽位已空出来,慢源只在自己的小批次里互相排队,不再堵住大部队。
     */
    private fun launchDeferredWave() {
        mBinding.root.removeCallbacks(deferredWaveFallback)
        val retryKeys = sourceHealth.retryKeys()
        val keys = LinkedHashSet<String>()
        keys.addAll(deferredWaveKeys)
        keys.addAll(retryKeys)
        deferredWaveKeys = ArrayList()
        for (k in keys) {
            launchSearch(k, isRetry = retryKeys.contains(k))
        }
    }

    /** 暂停:不再发起新的源搜索(旧实现 shutdownNow 收集未启动任务;共享池下由 launchSearch 自检暂存) */
    private fun pauseSearch() {
        synchronized(searchLock) {
            searchPaused = true
        }
        if (searchSessionActive) {
            searchSessionActive = false
            SourceLoaderProviders.get().stopAllSourceTasks()
        }
    }

    /** 页面回前台:续跑被暂停未发起的源搜索(旧实现 onResume 重建 10 线程池重放 pauseRunnable) */
    private fun resumeSearches() {
        val keys: ArrayList<String>
        synchronized(searchLock) {
            searchPaused = false
            if (pendingSearchKeys.isEmpty()) return
            keys = ArrayList(pendingSearchKeys)
            pendingSearchKeys.clear()
        }
        allRunCount.set(keys.size)
        searchSessionActive = true
        for (key in keys) {
            launchSearch(key)
        }
    }

    /**
     * 添加到最后面并返回最后一个key
     * @param key
     * @return
     */
    private fun addWordAdapterIfNeed(key: String): String {
        try {
            var name = ""
            for (n: String in spNames.keys) {
                if ((spNames[n] == key)) {
                    name = n
                }
            }
            if ((name == "")) return key
            for (i in 0 until mBinding.tabLayout.childCount) {
                val item = mBinding.tabLayout.getChildAt(i) as TextView
                if ((name == item.text.toString())) {
                    return key
                }
            }
            mBinding.tabLayout.addView(getSiteTextView(name))
            return key
        } catch (e: Exception) {
            return key
        }
    }

    private fun searchData(absXml: AbsXml?, isRetry: Boolean = false) {
        var lastSourceKey = ""
        if ((absXml != null) && (absXml.movie != null) && (absXml.movie.videoList != null) && (absXml.movie.videoList.size > 0)) {
            val data: MutableList<Movie.Video> = ArrayList()
            for (video: Movie.Video in absXml.movie.videoList) {
                if (!SearchFilter.matches(video.name, searchTitle)) continue
                data.add(video)
                if (!resultVods.containsKey(video.sourceKey)) {
                    resultVods[video.sourceKey] = ArrayList()
                }
                resultVods[video.sourceKey]!!.add(video)
                if (video.sourceKey !== lastSourceKey) { // 添加到最后面并记录最后一个key用于下次判断
                    lastSourceKey = addWordAdapterIfNeed(video.sourceKey)
                }
            }
            if (searchAdapter.data.size > 0) {
                searchAdapter.addData(data)
            } else {
                showSuccess()
                if (!isFilterMode) mBinding.mGridView.visibility = View.VISIBLE
                searchAdapter.setNewData(data)
                // 首批有效结果已上屏:提前收起下拉反馈圈,其余慢源后台继续收
                if (!refreshSpinnerDismissed && mBinding.llLayout.isRefreshing) {
                    refreshSpinnerDismissed = true
                    mBinding.llLayout.setRefreshing(false)
                }
            }
            // 该源总页数(供"上拉到底还能不能继续翻"判断)
            val key = absXml.movie.videoList[0].sourceKey
            paging.recordFirstPage(key, absXml.movie.pagecount)
        }
        if (isRetry) return // 重试批次只补数据上屏,不参与"全部来源已返回"的记账
        val count = allRunCount.decrementAndGet()
        // 第一波回包计数到位即投第二波:慢源不再压在队首堵住大部队
        if (!primaryDone) {
            primaryPending--
            if (primaryPending <= 0) {
                primaryDone = true
                launchDeferredWave()
            }
        }
        if (count <= 0 && !searchFinished) {
            // 全部来源已返回:进入"完成态"(与看门狗走同一条收尾,见 finishSearchRound)
            finishSearchRound()
        }
    }

    /**
     * 翻页批次(某来源的下一页):追加到结果与各来源缓存,并交给记账推进页码;
     * 空页/失败即视为该源到底(避免反复请求同一页);一轮内所有源都返回后收尾刷新"到底了"。
     */
    private fun searchPageData(key: String, absXml: AbsXml?, page: Int) {
        if (!paging.inRound()) return // 没有在途轮次:过期批次直接丢弃
        val videos = absXml?.movie?.videoList
        var appended = false
        if (!videos.isNullOrEmpty()) {
            val data: MutableList<Movie.Video> = ArrayList()
            for (video: Movie.Video in videos) {
                if (!SearchFilter.matches(video.name, searchTitle)) continue
                data.add(video)
                if (!resultVods.containsKey(video.sourceKey)) {
                    resultVods[video.sourceKey] = ArrayList()
                }
                resultVods[video.sourceKey]!!.add(video)
                addWordAdapterIfNeed(video.sourceKey)
            }
            if (data.isNotEmpty()) {
                appended = true
                searchAdapter.addData(data)
                if (isFilterMode && searchFilterKey == key) {
                    searchAdapterFilter.setNewData(ArrayList(resultVods[key] ?: ArrayList()))
                }
            }
        }
        val totalPages = absXml?.movie?.pagecount ?: 0
        if (paging.reply(key, page, appended, totalPages)) {
            finishPagingRound(appended)
        }
    }

    private fun cancel() {
        HttpClient.cancel("search")
    }

    override fun onDestroy() {
        super.onDestroy()
        sourceViewModel.setSearchBatchListener(null) // 断开结果直调,防悬垂回调
        sourceViewModel.setSearchPageBatchListener(null) // 断开翻页批次直调
        cancel()
        mBinding.root.removeCallbacks(deferredWaveFallback) // 页面销毁后别再放第二波
        mBinding.root.removeCallbacks(searchWatchdog) // 页面销毁后不必再强制收尾
        synchronized(searchLock) {
            searchEpoch++
            searchPaused = false
            pendingSearchKeys.clear()
        }
        if (searchSessionActive) {
            searchSessionActive = false
            SourceLoaderProviders.get().resetSources()
        }
    }

    override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
    override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
    override fun afterTextChanged(editable: Editable) {
        val text = editable.toString()
        // 输入/清空都要回到"建议页"(结果页只在真正发起搜索时显示,见 search() 的 hideHotAndHistorySearch(true)):
        // 有输入 → 只显示内联「相关搜索」;清空 → 回到「最近热搜 + 搜索历史」。
        // 原来清空时是靠 hideHotAndHistorySearch(false) 切回来的,这次改造一度漏了这一步(清空后仍停在结果页)。
        hideHotAndHistorySearch(false)
        applySuggestionSections()
        if (TextUtils.isEmpty(text)) {
            clearSuggestChips()
            return
        }
        getSuggest(text)
    }

    /** 长按弹评分弹窗(复用 DoubanSuggestDialog 组件);name 为空则忽略 */
    private fun showDoubanSuggest(name: String?) {
        if (name.isNullOrEmpty()) return
        HttpClient.get("https://movie.douban.com/j/subject_suggest?q="+name.trim(), null, object : HCallBack {
                override fun onSuccess(response: String) {
                    val list = GsonUtils.fromJson<List<DoubanSuggestBean>>(
                        response,
                        object : TypeToken<List<DoubanSuggestBean>>() {}.type
                    )

                    //暂时只保留第一个,分数查询接口有限制
                    val filterList = list.filter {
                        it.title == name
                    }
                    if (filterList.isEmpty()){
                        AppBubble.toast("暂无评分信息")
                        return
                    }

                    XPopup.Builder(this@FastSearchActivity)
                        .maxHeight(ScreenUtils.getScreenHeight() - (ScreenUtils.getScreenHeight() / 4))
                        .asCustom(DoubanSuggestDialog(this@FastSearchActivity,filterList.subList(0,1)))
                        .show()
                }

                override fun onError(e: Throwable) {
                }
            })
    }
}

package com.github.tvbox.osc.ui.activity

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener
import com.blankj.utilcode.util.ActivityUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.calendar.HolidayCatalog
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.databinding.ActivityMainBinding
import com.github.tvbox.osc.databinding.MainHomeShellBinding
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.service.LanServerService
import com.github.tvbox.osc.ui.fragment.GridFragment
import com.github.tvbox.osc.ui.fragment.HomeFragment
import com.github.tvbox.osc.ui.fragment.MyFragment
import com.github.tvbox.osc.ui.kit.FireworksView
import com.github.tvbox.osc.ui.startup.AppLaunchSource
import com.github.tvbox.osc.ui.startup.UserStartupGate
import com.github.tvbox.osc.spiderapi.SourceConfigProviders
import com.github.tvbox.osc.spiderapi.SourceLoaderApi
import com.github.tvbox.osc.spiderapi.SourceLoaderProviders
import com.github.tvbox.osc.theme.ThemeRuntime
import com.github.tvbox.osc.ui.splash.SplashContent
import com.github.tvbox.osc.ui.splash.SplashContentSelector
import com.github.tvbox.osc.util.HomeHotPreloader
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.github.tvbox.osc.util.SubscriptionConfig
import com.github.tvbox.osc.util.holiday.HolidayCalendarClock
import com.github.tvbox.osc.util.holiday.HolidayCatalogRepository
import com.github.tvbox.osc.util.holiday.HolidayFireworksLaunchCoordinator
import com.github.tvbox.osc.viewmodel.SourceViewModel
import kotlin.system.exitProcess
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : BaseVbActivity<ActivityMainBinding>(), UserStartupGate {

    companion object {
        const val EXTRA_STARTUP_SPLASH = "com.github.tvbox.osc.STARTUP_SPLASH"
        private const val POST_ANIMATION_HOLD_MS = 300L
        private const val ANIMATION_LOAD_TIMEOUT_MS = 10_000L
        private const val ANIMATION_PLAYBACK_GRACE_MS = 3_000L
    }

    private var fragments: List<Fragment> = emptyList()
    private var mainShellBinding: MainHomeShellBinding? = null
    var useCacheConfig = false
    private var exitTime = 0L
    private var showStartupSplashOnCreate = false
    private var launchSource = AppLaunchSource.OTHER
    private var holidayFireworksCheckedForLaunch = false
    private val holidayCatalogRepository by lazy { HolidayCatalogRepository(applicationContext) }
    private var startupSplashVisible = false
    private var startupContentReady = false
    private var startupAnimationEnded = false
    private var startupAnimationTailReady = false
    private var startupUiForeground = false
    private var pendingStartupUiDispatch = false
    private var disclaimerLaunching = false
    private var startupFirstHomeFramePending = false
    private var startupFirstHomePreDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var startupSplashPreDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var startupShellPreDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var startupHomePrefetchActive = false
    private var startupHomePrefetchStarted = false
    private var startupHomeDataReady = false
    private var startupSortPending = false
    private var startupHomeErrorMessage: String? = null
    private var startupHomeHotPreloader: HomeHotPreloader? = null
    private var startupFirstGridCategoryId: String? = null
    private var startupFirstGridSourceKey: String? = null
    private var startupFirstGridReady = true
    private var startupFirstGridPrefetchActive = false
    private var startupLanTimeout: Runnable? = null
    private val pendingAfterStartupSplash = ArrayList<Runnable>()
    private val startStartupHomePrefetch = Runnable {
        // 目录解析与 Lottie 首次装配错开，至少先让开屏画出一帧。
        holidayCatalogRepository.loadAsync(null)
        startStartupHomePrefetch()
    }
    private val attachHomePagerAfterSplash = Runnable {
        if (!isFinishing && !isDestroyed) {
            inflateMainShell()
            attachHomePager()
            if (mainShellBinding?.vp?.adapter != null) waitForFirstHomeFrame()
        }
    }
    private val finishStartupSplash = Runnable {
        startupAnimationTailReady = true
        maybeDismissStartupSplash()
    }
    private val animationLoadTimeout = Runnable {
        // 素材解析失败或过慢时退场；正常动画按播完后停留 300ms 退场。
        startupAnimationTailReady = true
        maybeDismissStartupSplash()
    }
    private val animationPlaybackTimeout = Runnable {
        // composition 已就绪却没有播放结束回调时，也不能永久停在开屏。
        startupAnimationTailReady = true
        maybeDismissStartupSplash()
    }
    private val dispatchAfterStartupSplash = Runnable {
        if (isFinishing || isDestroyed) {
            pendingAfterStartupSplash.clear()
            pendingStartupUiDispatch = false
        } else if (startupUiForeground && !startupSplashVisible &&
            !startupFirstHomeFramePending && mainShellBinding != null && pendingStartupUiDispatch) {
            if (!SystemConfig.isDisclaimerAccepted()) {
                // 开屏和首页首帧都结束后再显示声明；其他启动浮层继续排队。
                if (!disclaimerLaunching) {
                    disclaimerLaunching = true
                    startActivity(Intent(this, DisclaimerActivity::class.java))
                }
            } else {
                pendingStartupUiDispatch = false
                if (showStartupSplashOnCreate) {
                    com.github.tvbox.osc.update.UpdateFloatIndicator.get(this).attach(this)
                    if (isUserInitiatedLaunch() && !holidayFireworksCheckedForLaunch) {
                        holidayFireworksCheckedForLaunch = true
                        mainShellBinding?.bottomNav?.post { maybeLaunchHolidayFireworks() }
                    }
                }
                val actions = pendingAfterStartupSplash.toList()
                pendingAfterStartupSplash.clear()
                actions.forEach { it.run() }
                showPendingSafeModeNotice()
            }
        }
    }

    /** 首页展示联网内容；“我的”页可能正在查看本地内容，不主动弹无网页。 */
    fun isOnlineContentVisible(): Boolean = mainShellBinding?.vp?.currentItem != 1

    /** 开屏、首页首帧和首次声明结束前，启动提示都应继续等待。 */
    fun isStartupSplashVisible(): Boolean =
        startupSplashVisible || pendingStartupUiDispatch || !SystemConfig.isDisclaimerAccepted()

    /** 启动专属动作统一使用这次主页创建时确定的来源，不再各自消费重启标记。 */
    override fun isUserInitiatedLaunch(): Boolean = launchSource.allowsStartupActions()

    /** 开屏阶段的数据预取结果由首页首次装配消费，避免重新拉取订阅和分类。 */
    fun hasStartupHomePrefetch(): Boolean = startupHomePrefetchActive

    fun startupHomeError(): String? = startupHomeErrorMessage

    fun isStartupHomeDataReady(): Boolean = startupHomeDataReady

    fun startupHomeHotPreloader(): HomeHotPreloader? = startupHomeHotPreloader

    fun consumeStartupHomeHotPreloader() {
        startupHomeHotPreloader = null
    }

    /** 关闭主页推荐时，分类页的首批影片同样在开屏阶段预取。 */
    fun isStartupFirstGridPrefetchFor(categoryId: String?): Boolean =
        startupFirstGridPrefetchActive && startupFirstGridCategoryId == categoryId &&
            startupFirstGridSourceKey == SourceConfigProviders.get().homeSourceBean?.key

    fun consumeStartupFirstGridPrefetch() {
        startupFirstGridPrefetchActive = false
    }

    /** 首次首页已消费预取结果；后续 Fragment 重建应走自己的新一轮加载。 */
    fun consumeStartupHomePrefetch() {
        startupHomePrefetchActive = false
    }

    /** 开屏关闭后才允许气泡、弹窗等可见组件挂到窗口上。 */
    fun runAfterStartupSplash(action: Runnable) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mBinding.root.post { runAfterStartupSplash(action) }
            return
        }
        if (isFinishing || isDestroyed) return
        if (startupSplashVisible || !SystemConfig.isDisclaimerAccepted() ||
            !startupUiForeground || pendingStartupUiDispatch) {
            pendingAfterStartupSplash.add(action)
            pendingStartupUiDispatch = true
            if (!startupSplashVisible && startupUiForeground) {
                mBinding.root.post(dispatchAfterStartupSplash)
            }
        } else {
            action.run()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // FragmentPagerAdapter 会恢复旧 Fragment；重建时不能把回调挂到新建的 fragments[0]。
        val fromStartupPage = intent.getBooleanExtra(EXTRA_STARTUP_SPLASH, false)
        launchSource = AppLaunchSource.resolve(
            fromStartupPage, savedInstanceState != null, SystemConfig.consumeInternalRestart()
        )
        showStartupSplashOnCreate = savedInstanceState == null &&
            fromStartupPage
        if (showStartupSplashOnCreate) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        super.onCreate(savedInstanceState)
    }

    override fun init() {

        LogStore.log(Category.SYSTEM, "应用启动来源: $launchSource")

        // 主题真重启会清空 Intent 标志；只在下一进程的一次主页创建中复用磁盘配置缓存。
        if (SystemConfig.consumeThemeRestartUseCache()) {
            intent.putExtra(IntentKey.CACHE_CONFIG_CHANGED, true)
        }
        useCacheConfig = intent.getBooleanExtra(IntentKey.CACHE_CONFIG_CHANGED, false)

        intent.removeExtra(EXTRA_STARTUP_SPLASH)
        if (showStartupSplashOnCreate) {
            showStartupSplash()
            HeavyTaskUtil.executeBigTask {
                HolidayFireworksLaunchCoordinator.clearPreviousDay()
            }
        } else {
            inflateMainShell()
            attachHomePager()
        }
        if (showStartupSplashOnCreate) {
            scheduleStartupHomePrefetchAfterSplashFrame()
        } else {
            pendingStartupUiDispatch = true
            mBinding.root.post(dispatchAfterStartupSplash)
        }
        runAfterStartupSplash(Runnable {
            com.github.tvbox.osc.transfer.LocalBackupRepository.cleanupOnStartup(applicationContext)
        })
    }

    private fun maybeLaunchHolidayFireworks() {
        if (!isUserInitiatedLaunch() || !showStartupSplashOnCreate ||
            pendingStartupUiDispatch || !startupUiForeground ||
            startupSplashVisible || startupFirstHomeFramePending || isFinishing || isDestroyed ||
            mainShellBinding?.bottomNav?.isShown != true) {
            LogStore.log(Category.SYSTEM, "节日烟花: 启动界面未就绪，本次不检查发射")
            return
        }
        HeavyTaskUtil.executeBigTask {
            val claim = try {
                val catalog: HolidayCatalog? = holidayCatalogRepository.getOrLoad()
                HolidayFireworksLaunchCoordinator.claim(catalog)
            } catch (error: Throwable) {
                LogStore.fail(Category.SYSTEM, "节日烟花: 检查启动资格失败，原因=${error.javaClass.simpleName}")
                return@executeBigTask
            } ?: return@executeBigTask
            runOnUiThread {
                val nav = mainShellBinding?.bottomNav
                val launchClock = HolidayCalendarClock.now()
                val sameDay = HolidayFireworksLaunchCoordinator.isClaimForDate(claim, launchClock)
                val groupCount = if (startupUiForeground && !startupSplashVisible &&
                    !startupFirstHomeFramePending && !isFinishing && !isDestroyed &&
                    sameDay && nav != null && nav.isShown) try {
                    launchHolidayGroupsFromNav(nav)
                } catch (error: Throwable) {
                    LogStore.fail(Category.SYSTEM, "节日烟花: 发射失败，原因=${error.javaClass.simpleName}")
                    0
                } else 0
                val started = groupCount > 0
                if (!started) {
                    LogStore.log(Category.SYSTEM, if (sameDay)
                        "节日烟花: 底栏或动画不可用，本次未发射" else
                        "节日烟花: 检查后跨日，本次未发射")
                }
                val fireAtMillis = if (started) launchClock.timeInMillis else 0L
                HeavyTaskUtil.executeBigTask {
                    try {
                        HolidayFireworksLaunchCoordinator.finish(claim, started, fireAtMillis, groupCount)
                    } catch (error: Throwable) {
                        LogStore.fail(Category.SYSTEM, "节日烟花: 确认发射记录失败，原因=${error.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun launchHolidayGroupsFromNav(nav: View): Int {
        val home = nav.findViewById<View>(R.id.navigation_home)
        val mine = nav.findViewById<View>(R.id.navigation_dashboard)
        if (home == null || mine == null) return 0
        val iconId = com.google.android.material.R.id.navigation_bar_item_icon_view
        val homeIcon = home.findViewById<View>(iconId)?.takeIf { it.width > 0 && it.height > 0 } ?: home
        val myIcon = mine.findViewById<View>(iconId)?.takeIf { it.width > 0 && it.height > 0 } ?: mine
        val navPosition = IntArray(2)
        val homePosition = IntArray(2)
        val myPosition = IntArray(2)
        nav.getLocationOnScreen(navPosition)
        homeIcon.getLocationOnScreen(homePosition)
        myIcon.getLocationOnScreen(myPosition)
        val homeX = homePosition[0] - navPosition[0] + homeIcon.width / 2f
        val myX = myPosition[0] - navPosition[0] + myIcon.width / 2f
        val iconY = (homePosition[1] + homeIcon.height / 2f +
            myPosition[1] + myIcon.height / 2f) / 2f - navPosition[1]
        val middleX = (homeX + myX) / 2f
        val widthDp = nav.width / nav.resources.displayMetrics.density
        val launchXs = if (widthDp >= 600f) {
            floatArrayOf(homeX / 2f, homeX, middleX, myX, (myX + nav.width) / 2f)
        } else {
            floatArrayOf(homeX, middleX, myX)
        }
        return if (FireworksView.celebrateGroups(nav, launchXs, iconY)) launchXs.size else 0
    }

    private fun inflateMainShell(): MainHomeShellBinding {
        mainShellBinding?.let { return it }
        val shell = MainHomeShellBinding.bind(mBinding.mainContentStub.inflate())
        mainShellBinding = shell
        // BaseActivity 的第二轮兼容换肤扫描已在 init() 后执行；开屏退场时补扫新挂载的视图。
        com.github.tvbox.osc.theme.ThemeSweep.apply(shell.root)
        shell.bottomNav.setOnNavigationItemSelectedListener { menuItem: MenuItem ->
            shell.vp.setCurrentItem(menuItem.order, false)
            updateNavIcons(menuItem.order)
            true
        }
        shell.vp.addOnPageChangeListener(object : SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                shell.bottomNav.menu.getItem(position).setChecked(true)
                updateNavIcons(position)
            }
        })
        updateNavIcons(0)
        return shell
    }

    private fun attachHomePager() {
        val shell = mainShellBinding ?: return
        if (shell.vp.adapter != null) return
        fragments = listOf(HomeFragment(), MyFragment())
        shell.vp.adapter = object : FragmentPagerAdapter(supportFragmentManager) {
            override fun getItem(position: Int): Fragment = fragments[position]

            override fun getCount(): Int = fragments.size
        }
    }

    override fun onResume() {
        super.onResume()
        startupUiForeground = true
        if (!SystemConfig.isDisclaimerAccepted()) disclaimerLaunching = false
        if (!startupSplashVisible && pendingStartupUiDispatch) {
            mBinding.root.post(dispatchAfterStartupSplash)
        }
    }

    override fun onPause() {
        startupUiForeground = false
        super.onPause()
    }

    private fun showPendingSafeModeNotice() {
        if (App.getInstance().consumePendingSafeModeNotice()) {
            AppBubble.toastLong("连续崩溃，已进入安全模式（JS 源未加载）")
        }
    }

    private fun scheduleStartupHomePrefetchAfterSplashFrame() {
        // 缓存订阅的解析可能同步执行；先让开屏至少画出一帧，再开始取数据。
        val overlay = mBinding.startupSplashOverlay
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                overlay.viewTreeObserver.removeOnPreDrawListener(this)
                startupSplashPreDraw = null
                overlay.post(startStartupHomePrefetch)
                return true
            }
        }
        startupSplashPreDraw = listener
        overlay.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun startStartupHomePrefetch() {
        if (!startupHomePrefetchActive || startupHomePrefetchStarted || isFinishing || isDestroyed) return
        startupHomePrefetchStarted = true
        val sourceViewModel = ViewModelProvider(this)[SourceViewModel::class.java]
        sourceViewModel.listResult.observe(this) {
            if (startupFirstGridPrefetchActive && !startupFirstGridReady) {
                startupFirstGridReady = true
                maybeMarkStartupContentReady()
            }
        }
        sourceViewModel.sortResult.observe(this) { sort ->
            if (startupSortPending) {
                startupSortPending = false
                startStartupFirstGridPrefetch(sourceViewModel, sort)
                settleStartupHomeData(null)
            }
        }
        if (SystemConfig.getHomeRec() == 0 && !SubscriptionConfig.getApiUrl().isNullOrEmpty()) {
            startupHomeHotPreloader = HomeHotPreloader().also { preloader ->
                preloader.videos().observe(this) { maybeMarkStartupContentReady() }
                preloader.start()
            }
        }
        // 局域网监听由前台服务在通知就绪后启动；配置预取等待它完成绑定。
        if (SystemConfig.isLanServerEnabled()) {
            val completed = AtomicBoolean(false)
            val timeout = Runnable {
                if (completed.compareAndSet(false, true) && startupHomePrefetchActive &&
                    !isFinishing && !isDestroyed) {
                    startupLanTimeout = null
                    // 等待端口绑定和关闭都在共享后台池完成，避免超时回调卡住开屏主线程。
                    HeavyTaskUtil.executeBigTask {
                        try {
                            LanServerService.disable(applicationContext)
                            ControlManager.get().startServer()
                        } catch (error: RuntimeException) {
                            android.util.Log.e("TVBox-Server", "局域网启动超时后恢复回环失败", error)
                        }
                        runOnUiThread { loadStartupConfigIfActive(sourceViewModel) }
                    }
                }
            }
            startupLanTimeout = timeout
            mBinding.root.postDelayed(timeout, 15_000L)
            LanServerService.start(this, object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (!completed.compareAndSet(false, true)) return
                    mBinding.root.removeCallbacks(timeout)
                    startupLanTimeout = null
                    if (resultCode == LanServerService.START_ACTIVE) {
                        loadStartupConfigIfActive(sourceViewModel)
                    } else {
                        startLoopbackThenLoad(sourceViewModel)
                    }
                }
            })
            return
        }
        startLoopbackThenLoad(sourceViewModel)
    }

    private fun startLoopbackThenLoad(sourceViewModel: SourceViewModel) {
        // 本地订阅需先等回环服务监听成功；端口绑定放到共享执行器。
        HeavyTaskUtil.executeBigTask {
            try {
                ControlManager.get().startServer()
            } catch (error: Throwable) {
                android.util.Log.e("TVBox-Server", "开屏预取启动本机服务失败", error)
            }
            runOnUiThread {
                loadStartupConfigIfActive(sourceViewModel)
            }
        }
    }

    private fun loadStartupConfigIfActive(sourceViewModel: SourceViewModel) {
        if (startupHomePrefetchActive && !startupHomeDataReady && !isFinishing && !isDestroyed) {
            loadStartupConfig(sourceViewModel)
        }
    }

    private fun loadStartupConfig(sourceViewModel: SourceViewModel) {
        SourceLoaderProviders.get().loadConfig(useCacheConfig, object : SourceLoaderApi.Callback {
            override fun retry() {
                mBinding.root.post {
                    if (startupHomePrefetchActive && !startupHomeDataReady &&
                        !isFinishing && !isDestroyed) {
                        loadStartupConfig(sourceViewModel)
                    }
                }
            }

            override fun success() {
                runStartupPrefetchCallback {
                    val spider = SourceLoaderProviders.get().spider
                    if (spider.isNullOrEmpty()) {
                        requestStartupSort(sourceViewModel)
                    } else {
                        loadStartupJar(sourceViewModel, spider)
                    }
                }
            }

            override fun error(msg: String) {
                runStartupPrefetchCallback { settleStartupHomeData(msg) }
            }
        }, this)
    }

    private fun loadStartupJar(sourceViewModel: SourceViewModel, spider: String) {
        SourceLoaderProviders.get().loadJar(useCacheConfig, spider, object : SourceLoaderApi.Callback {
            override fun retry() {}

            override fun success() {
                runStartupPrefetchCallback { requestStartupSort(sourceViewModel) }
            }

            override fun error(msg: String) {
                runStartupPrefetchCallback {
                    runAfterStartupSplash(Runnable { AppBubble.toast("更新订阅失败") })
                    // 订阅仍可能包含可用的非 Jar 源，延续原首页的分类加载行为。
                    requestStartupSort(sourceViewModel)
                }
            }
        })
    }

    private fun requestStartupSort(sourceViewModel: SourceViewModel) {
        if (startupHomeDataReady || startupSortPending) return
        startupSortPending = true
        sourceViewModel.getSort(SourceConfigProviders.get().homeSourceBean?.key)
    }

    private fun startStartupFirstGridPrefetch(sourceViewModel: SourceViewModel, sort: AbsSortXml?) {
        if (SystemConfig.getHomeRec() != 2 || sort?.classes?.sortList == null) return
        val sourceKey = SourceConfigProviders.get().homeSourceBean?.key ?: return
        try {
            val firstCategory = DefaultConfig.adjustSort(sourceKey, sort.classes.sortList, true)
                .drop(1).firstOrNull() ?: return
            if (firstCategory.id.isNullOrEmpty()) return
            startupFirstGridCategoryId = firstCategory.id
            startupFirstGridSourceKey = sourceKey
            startupFirstGridReady = false
            startupFirstGridPrefetchActive = true
            val request = MovieSort.SortData(firstCategory.id, firstCategory.name)
            request.filterSelect = HashMap(firstCategory.filterSelect ?: emptyMap())
            sourceViewModel.getList(request, 1)
        } catch (error: Throwable) {
            // 源在首屏切换或抛异常时，分类页恢复自己的正常加载路径。
            startupFirstGridPrefetchActive = false
            startupFirstGridReady = true
        }
    }

    private fun runStartupPrefetchCallback(action: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mBinding.root.post { runStartupPrefetchCallback(action) }
            return
        }
        if (!startupHomePrefetchActive || startupHomeDataReady || isFinishing || isDestroyed) return
        action()
    }

    private fun settleStartupHomeData(error: String?) {
        if (startupHomeDataReady) return
        startupHomeErrorMessage = error
        startupHomeDataReady = true
        startupSortPending = false
        activeHomeFragment()?.takeIf { it.isAdded }?.onStartupHomePrefetchSettled()
        maybeMarkStartupContentReady()
    }

    private fun maybeMarkStartupContentReady() {
        if (startupHomeDataReady &&
            (startupHomeErrorMessage != null ||
                (startupHomeHotPreloader?.isSettled != false && startupFirstGridReady))) {
            startupContentReady = true
            maybeDismissStartupSplash()
        }
    }

    private fun showStartupSplash() {
        val themeBackground = ThemeRuntime.runtimePalette()?.get("bg_body")
            ?: ContextCompat.getColor(this, R.color.bg_body)
        val overlay = mBinding.startupSplashOverlay
        startupSplashVisible = true
        startupHomePrefetchActive = true
        startupHomePrefetchStarted = false
        startupHomeDataReady = false
        startupHomeErrorMessage = null
        startupContentReady = false
        startupAnimationEnded = false
        startupAnimationTailReady = false
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        overlay.visibility = View.VISIBLE
        // 仅对素材解析加兜底；正常播放不被超时截断。
        overlay.postDelayed(animationLoadTimeout, ANIMATION_LOAD_TIMEOUT_MS)
        val hasBackgroundImage = SystemConfig.getPageBackgroundPath().isNotEmpty()
        // 开屏素材立即开始；节日目录在首帧后由预取任务后台加载。
        val content = SplashContentSelector.select(
            themeBackground, hasBackgroundImage,
            holidayCatalogRepository.getCached(), HolidayCalendarClock.now()
        )
        startStartupSplashContent(content)
    }

    private fun startStartupSplashContent(content: SplashContent) {
        val overlay = mBinding.startupSplashOverlay
        when (content) {
            is SplashContent.Lottie -> {
                overlay.setBackgroundColor(content.backgroundColor)
                mBinding.startupSplashAnimation.apply {
                    repeatCount = 0
                    setFailureListener {
                        if (startupSplashVisible) overlay.post(animationLoadTimeout)
                    }
                    addAnimatorListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (startupSplashVisible) {
                                overlay.removeCallbacks(animationPlaybackTimeout)
                                startupAnimationEnded = true
                                overlay.postDelayed(finishStartupSplash, POST_ANIMATION_HOLD_MS)
                            }
                        }
                    })
                    addLottieOnCompositionLoadedListener { composition ->
                        overlay.removeCallbacks(animationLoadTimeout)
                        // 解析超时后不再启动迟到的动画，否则首屏就绪会中途撤掉它。
                        if (startupSplashVisible && !startupAnimationTailReady) {
                            overlay.postDelayed(
                                animationPlaybackTimeout,
                                composition.duration.toLong() * 2 + ANIMATION_PLAYBACK_GRACE_MS
                            )
                            playAnimation()
                        }
                    }
                    setAnimation(content.assetPath)
                }
            }
        }
    }

    private fun maybeDismissStartupSplash() {
        if (startupSplashVisible &&
            ((startupContentReady && !startupAnimationEnded) || startupAnimationTailReady)) {
            dismissStartupSplash()
        }
    }

    private fun dismissStartupSplash(runDeferredComponents: Boolean = true) {
        if (!startupSplashVisible) return
        startupSplashVisible = false
        startupSplashPreDraw?.let { listener ->
            val observer = mBinding.startupSplashOverlay.viewTreeObserver
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
        }
        startupSplashPreDraw = null
        // 开屏未能绘制首帧的极端情况，改在首页首次显示后启动数据预取。
        mBinding.startupSplashOverlay.removeCallbacks(startStartupHomePrefetch)
        mBinding.startupSplashOverlay.removeCallbacks(finishStartupSplash)
        mBinding.startupSplashOverlay.removeCallbacks(animationLoadTimeout)
        mBinding.startupSplashOverlay.removeCallbacks(animationPlaybackTimeout)
        mBinding.startupSplashAnimation.cancelAnimation()
        mBinding.startupSplashOverlay.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (runDeferredComponents) {
            pendingStartupUiDispatch = true
            // 先画一帧主题背景，之后才装配首页组件和 Fragment。
            startupFirstHomeFramePending = true
            val content = mBinding.root
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    startupShellPreDraw = null
                    content.post(attachHomePagerAfterSplash)
                    return true
                }
            }
            startupShellPreDraw = listener
            content.viewTreeObserver.addOnPreDrawListener(listener)
        } else {
            startupFirstHomeFramePending = false
            pendingAfterStartupSplash.clear()
            pendingStartupUiDispatch = false
        }
    }

    private fun waitForFirstHomeFrame() {
        val content = mainShellBinding?.root ?: return
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                content.viewTreeObserver.removeOnPreDrawListener(this)
                startupFirstHomePreDraw = null
                startupFirstHomeFramePending = false
                content.post(dispatchAfterStartupSplash)
                if (!startupHomePrefetchStarted) content.post(startStartupHomePrefetch)
                return true
            }
        }
        startupFirstHomePreDraw = listener
        content.viewTreeObserver.addOnPreDrawListener(listener)
    }

    override fun onDestroy() {
        dismissStartupSplash(false)
        startupHomePrefetchActive = false
        startupLanTimeout?.let { mBinding.root.removeCallbacks(it) }
        startupLanTimeout = null
        startupSplashPreDraw?.let { listener ->
            val observer = mBinding.startupSplashOverlay.viewTreeObserver
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
        }
        startupSplashPreDraw = null
        startupShellPreDraw?.let { listener ->
            val observer = mBinding.root.viewTreeObserver
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
        }
        startupShellPreDraw = null
        startupFirstHomePreDraw?.let { listener ->
            val observer = mainShellBinding?.root?.viewTreeObserver
            if (observer?.isAlive == true) observer.removeOnPreDrawListener(listener)
        }
        startupFirstHomePreDraw = null
        mBinding.startupSplashOverlay.removeCallbacks(startStartupHomePrefetch)
        mBinding.startupSplashOverlay.removeCallbacks(animationPlaybackTimeout)
        mBinding.root.removeCallbacks(attachHomePagerAfterSplash)
        mBinding.root.removeCallbacks(dispatchAfterStartupSplash)
        mainShellBinding?.root?.removeCallbacks(dispatchAfterStartupSplash)
        pendingAfterStartupSplash.clear()
        super.onDestroy()
    }

    /** 底部导航图标: 选中项换"选中"变体(与未选中图形区分), 颜色仍由 itemIconTint 按状态着色 */
    private fun updateNavIcons(position: Int) {
        val menu = mainShellBinding?.bottomNav?.menu ?: return
        if (menu.size() >= 2) {
            menu.getItem(0).setIcon(
                if (position == 0) R.drawable.ic_nav_home_sel else R.drawable.ic_nav_home
            )
            menu.getItem(1).setIcon(
                if (position == 1) R.drawable.ic_nav_my_sel else R.drawable.ic_nav_my
            )
        }
    }

    private fun activeHomeFragment(): HomeFragment? {
        val viewPagerId = mainShellBinding?.vp?.id ?: return null
        val restored = supportFragmentManager.findFragmentByTag("android:switcher:$viewPagerId:0")
        return (restored as? HomeFragment) ?: (fragments.firstOrNull() as? HomeFragment)
    }

    override fun onBackPressed() {
        if (startupSplashVisible || startupFirstHomeFramePending) return
        val shell = mainShellBinding ?: return
        if (shell.vp.currentItem != 0) { // 非首页(我的)按返回回首页
            shell.vp.currentItem = 0
            return
        }
        val homeFragment = activeHomeFragment()
        if (homeFragment == null || !homeFragment.isAdded) { // 资源不足销毁重建时未挂载到activity时getChildFragmentManager会崩溃
            confirmExit()
            return
        }
        val childFragments = homeFragment.allFragments
        if (childFragments.isEmpty()) { //加载中(没有tab)
            confirmExit()
            return
        }
        val tabIndex = homeFragment.tabIndex
        if (tabIndex !in childFragments.indices) {
            // TabLayout may report -1 between rebuilding tabs and its next measure pass.
            confirmExit()
            return
        }
        val fragment: Fragment = childFragments[tabIndex]
        if (fragment is GridFragment) { // 首页数据源动态加载的tab
            if (!fragment.restoreView()) { // 有回退的view,先回退(AList等文件夹列表),没有可回退的,返到主页tab
                if (!homeFragment.scrollToFirstTab()) {
                    confirmExit()
                }
            }
        } else {
            confirmExit()
        }
    }

    private fun confirmExit() {
        if (System.currentTimeMillis() - exitTime > 2000) {
            AppBubble.toast("再按一次退出程序")
            exitTime = System.currentTimeMillis()
        } else {
            ActivityUtils.finishAllActivities(true)
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }
}

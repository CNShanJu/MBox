package com.github.tvbox.osc.ui.startup

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.ui.activity.SplashActivity
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.HeavyTaskUtil
import java.util.concurrent.atomic.AtomicBoolean

/** One MainActivity launch, from its origin through the first drawn home frame. */
class AppStartupCoordinator private constructor(
    val launchSource: AppLaunchSource,
    val showSplashOnCreate: Boolean
) {
    enum class Phase {
        CREATED, SPLASH, SPLASH_FIRST_FRAME, PREFETCH, HOME_SHELL, HOME_FIRST_FRAME, DESTROYED
    }

    var phase = Phase.CREATED
        private set
    var splashVisible = false
        private set
    var prefetchActive = false
        private set
    var prefetchStarted = false
        private set
    var homeDataReady = false
        private set
    var firstHomeFramePending = false
        private set
    var foreground = false
        private set
    var pendingUiDispatch = true
        private set

    private var splashFirstFrameDrawn = false
    private var firstHomeFrameDrawn = false
    private var contentReady = false
    private var animationEnded = false
    private var animationTailReady = false
    private var fireworksChecked = false
    private var bubbleUntilUptimeMs = 0L
    private var fireworksUntilUptimeMs = 0L
    private var fireworksCheckPending = false
    private var fireworksCheckDeadlineUptimeMs = 0L
    private val visibleActions = ArrayList<Runnable>()
    private val backgroundAfterFirstFrame = ArrayList<Runnable>()

    fun onSplashShown() {
        if (phase == Phase.DESTROYED) return
        phase = Phase.SPLASH
        splashVisible = true
        prefetchActive = true
        prefetchStarted = false
        homeDataReady = false
        splashFirstFrameDrawn = false
        contentReady = false
        animationEnded = false
        animationTailReady = false
    }

    fun onSplashFirstFrame() {
        if (!splashVisible || phase == Phase.DESTROYED) return
        splashFirstFrameDrawn = true
        phase = Phase.SPLASH_FIRST_FRAME
    }

    /** Prefetch never starts while the splash's initial Lottie frame is being composed. */
    fun claimPrefetch(): Boolean {
        if (!prefetchActive || prefetchStarted || phase == Phase.DESTROYED ||
            (!splashFirstFrameDrawn && !firstHomeFrameDrawn)) return false
        prefetchStarted = true
        phase = Phase.PREFETCH
        return true
    }

    fun onHomeDataReady(): Boolean {
        if (!prefetchActive || homeDataReady || phase == Phase.DESTROYED) return false
        homeDataReady = true
        return true
    }

    fun onContentReady(): Boolean {
        contentReady = true
        return shouldDismissSplash()
    }

    fun onAnimationEnded() {
        animationEnded = true
    }

    fun onAnimationTailReady(): Boolean {
        animationTailReady = true
        return shouldDismissSplash()
    }

    val animationTailIsReady: Boolean get() = animationTailReady

    /** Data can end any splash early; completed animation keeps its 300 ms last frame. */
    fun shouldDismissSplash(): Boolean = splashVisible &&
        ((contentReady && !animationEnded) || animationTailReady)

    fun onSplashDismissed(continueToHome: Boolean) {
        if (!splashVisible) return
        splashVisible = false
        if (continueToHome) {
            pendingUiDispatch = true
            firstHomeFramePending = true
            phase = Phase.HOME_SHELL
        } else {
            prefetchActive = false
            firstHomeFramePending = false
            visibleActions.clear()
            backgroundAfterFirstFrame.clear()
            pendingUiDispatch = false
        }
    }

    fun onShellAttached() {
        if (phase == Phase.DESTROYED) return
        firstHomeFramePending = true
        phase = Phase.HOME_SHELL
    }

    fun onFirstHomeFrame() {
        if (phase == Phase.DESTROYED || firstHomeFrameDrawn) return
        firstHomeFrameDrawn = true
        firstHomeFramePending = false
        phase = Phase.HOME_FIRST_FRAME
        val tasks = backgroundAfterFirstFrame.toList()
        backgroundAfterFirstFrame.clear()
        tasks.forEach { task -> HeavyTaskUtil.executeBigTask { task.run() } }
    }

    fun runInBackgroundAfterFirstHomeFrame(task: Runnable) {
        if (phase == Phase.DESTROYED) return
        if (firstHomeFrameDrawn) {
            HeavyTaskUtil.executeBigTask { task.run() }
        } else {
            backgroundAfterFirstFrame.add(task)
        }
    }

    fun runProcessCleanupAfterFirstHomeFrame(task: Runnable) {
        runInBackgroundAfterFirstHomeFrame(Runnable {
            if (processCleanupScheduled.compareAndSet(false, true)) task.run()
        })
    }

    fun onResume() {
        foreground = true
    }

    fun onPause() {
        foreground = false
    }

    fun canEnterStartupUi(hasShell: Boolean): Boolean =
        phase != Phase.DESTROYED && foreground && firstHomeFrameDrawn &&
            !splashVisible && !firstHomeFramePending && hasShell

    fun canDispatchStartupUi(hasShell: Boolean): Boolean =
        pendingUiDispatch && canEnterStartupUi(hasShell)

    /** Returns false when the action can run immediately on the current main-thread call. */
    fun queueVisibleAction(action: Runnable, disclaimerAccepted: Boolean, hasShell: Boolean): Boolean {
        if (phase == Phase.DESTROYED) return true
        if (!disclaimerAccepted || pendingUiDispatch || !canEnterStartupUi(hasShell)) {
            visibleActions.add(action)
            pendingUiDispatch = true
            return true
        }
        return false
    }

    fun takeVisibleActions(): List<Runnable> {
        pendingUiDispatch = false
        val actions = visibleActions.toList()
        visibleActions.clear()
        return actions
    }

    fun canRunUserStartupActions(hasShell: Boolean): Boolean =
        launchSource.allowsStartupActions() && showSplashOnCreate &&
            !pendingUiDispatch && canEnterStartupUi(hasShell)

    fun claimFireworksDispatch(hasShell: Boolean): Boolean {
        if (fireworksChecked || !canRunUserStartupActions(hasShell)) return false
        fireworksChecked = true
        return true
    }

    fun onBubbleVisible(untilUptimeMs: Long) {
        bubbleUntilUptimeMs = untilUptimeMs
    }

    fun onBubbleDismissed() {
        bubbleUntilUptimeMs = 0L
    }

    fun bubbleDelayMs(): Long =
        (bubbleUntilUptimeMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)

    fun startupVisualDelayMs(): Long {
        val now = SystemClock.uptimeMillis()
        expireFireworksCheckIfNeeded(now)
        val delay = (maxOf(bubbleUntilUptimeMs, fireworksUntilUptimeMs) - now)
            .coerceAtLeast(0L)
        // A check may outlive the initial reservation. Poll at most twice per second until
        // its bounded deadline; after that, other startup windows may proceed.
        return if (fireworksCheckPending) {
            maxOf(delay, minOf(500L, fireworksCheckDeadlineUptimeMs - now))
        } else delay
    }

    private fun expireFireworksCheckIfNeeded(now: Long) {
        if (fireworksCheckPending && now >= fireworksCheckDeadlineUptimeMs) {
            fireworksCheckPending = false
            fireworksUntilUptimeMs = 0L
        }
    }

    /** A result arriving after the visual lane opens must not start fireworks over another window. */
    fun isFireworksCheckCurrent(): Boolean {
        expireFireworksCheckIfNeeded(SystemClock.uptimeMillis())
        return fireworksCheckPending && phase != Phase.DESTROYED
    }

    /** Bound the holiday catalog check so it cannot starve other startup windows. */
    fun reserveFireworksCheck() {
        val now = SystemClock.uptimeMillis()
        fireworksCheckPending = true
        fireworksCheckDeadlineUptimeMs = now + 15_000L
        fireworksUntilUptimeMs = now + 2_000L
    }

    fun onFireworksQueued() {
        fireworksCheckPending = false
        fireworksCheckDeadlineUptimeMs = 0L
        // Last group may start 2 seconds after the first; rockets/particles outlive the launch.
        fireworksUntilUptimeMs = SystemClock.uptimeMillis() + 4_000L
    }

    fun onFireworksSkipped() {
        fireworksCheckPending = false
        fireworksCheckDeadlineUptimeMs = 0L
        fireworksUntilUptimeMs = 0L
    }

    fun consumePrefetch() {
        prefetchActive = false
    }

    fun blocksVisibleUi(): Boolean = splashVisible || firstHomeFramePending || pendingUiDispatch

    fun onDestroy() {
        phase = Phase.DESTROYED
        prefetchActive = false
        splashVisible = false
        foreground = false
        bubbleUntilUptimeMs = 0L
        fireworksUntilUptimeMs = 0L
        fireworksCheckPending = false
        fireworksCheckDeadlineUptimeMs = 0L
        visibleActions.clear()
        backgroundAfterFirstFrame.clear()
        pendingUiDispatch = false
    }

    companion object {
        private val processCleanupScheduled = AtomicBoolean(false)

        /** Configuration changes that rebuild the task still take the same splash/prefetch path. */
        @JvmStatic
        fun launchInternalReload(context: Context, useCacheConfig: Boolean): Boolean {
            try {
                SystemConfig.markInternalRestart()
                context.startActivity(Intent(context, SplashActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra(IntentKey.CACHE_CONFIG_CHANGED, useCacheConfig)
                    putExtra(AppLaunchSource.EXTRA_INTERNAL_RELOAD, true)
                })
                return true
            } catch (failure: RuntimeException) {
                try { SystemConfig.clearInternalRestart() } catch (_: Throwable) { }
                android.util.Log.e("MBox-Startup", "内部刷新启动失败", failure)
                AppBubble.toast("重启应用失败，请重试")
                return false
            }
        }

        @JvmStatic
        fun create(fromStartupPage: Boolean, activityRecreated: Boolean,
                   internalRestart: Boolean, nonUserEntry: Boolean): AppStartupCoordinator {
            return AppStartupCoordinator(
                AppLaunchSource.resolve(fromStartupPage, activityRecreated, internalRestart, nonUserEntry),
                fromStartupPage && !activityRecreated
            )
        }
    }
}

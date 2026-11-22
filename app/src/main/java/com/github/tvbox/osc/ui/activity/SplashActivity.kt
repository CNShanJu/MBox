package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.os.Bundle
import cat.ereza.customactivityoncrash.CustomActivityOnCrash
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.ActivitySplashBinding
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.ui.startup.AppLaunchSource
import com.github.tvbox.osc.ui.startup.ShortcutTarget

class SplashActivity : BaseVbActivity<ActivitySplashBinding>() {
    private var restoredBySystem = false

    override fun onCreate(savedInstanceState: Bundle?) {
        restoredBySystem = savedInstanceState != null
        super.onCreate(savedInstanceState)
    }

    override fun allowDirectColdStart(): Boolean = true

    override fun init() {
        App.getInstance().isNormalStart = true
        val crashRestart = intent.getBooleanExtra(CustomActivityOnCrash.EXTRA_CRASH_RESTART, false)
        if (crashRestart) {
            // A crash can happen after an internal restart is marked but before Main consumes it.
            // Clear that stale marker so the next real launcher start keeps its own source.
            try {
                SystemConfig.clearInternalRestart()
                SystemConfig.clearThemeRestartUseCache()
            } catch (failure: RuntimeException) {
                android.util.Log.w("MBox-Startup", "清理崩溃前的重启标记失败", failure)
            }
        }
        val launcherEntry = intent.action == Intent.ACTION_MAIN &&
            (intent.hasCategory(Intent.CATEGORY_LAUNCHER) ||
                intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
        // 桌面长按菜单进来的也是用户主动启动:不能被当成通知/系统拉起,否则启动动作与
        // 快捷方式落地页都会被抑制。目标页本身由主页在首页首帧后打开。
        val shortcutTarget = ShortcutTarget.fromAction(intent.action)
        val nonUserEntry = restoredBySystem || crashRestart ||
            (!launcherEntry && shortcutTarget == null) ||
            intent.getBooleanExtra(AppLaunchSource.EXTRA_NON_USER_ENTRY, false)
        if (shortcutTarget != null) {
            LogStore.log(Category.SYSTEM, "桌面快捷方式启动: ${shortcutTarget.name}")
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_STARTUP_SPLASH, true)
                .putExtra(AppLaunchSource.EXTRA_NON_USER_ENTRY, nonUserEntry)
                .putExtra(AppLaunchSource.EXTRA_INTERNAL_RELOAD,
                    intent.getBooleanExtra(AppLaunchSource.EXTRA_INTERNAL_RELOAD, false))
                .putExtra(ShortcutTarget.EXTRA_TARGET, shortcutTarget?.action)
                .putExtra(NotificationEntryActivity.EXTRA_DETAIL_ID,
                    intent.getStringExtra(NotificationEntryActivity.EXTRA_DETAIL_ID))
                .putExtra(NotificationEntryActivity.EXTRA_DETAIL_SOURCE_KEY,
                    intent.getStringExtra(NotificationEntryActivity.EXTRA_DETAIL_SOURCE_KEY))
                .putExtra(NotificationEntryActivity.EXTRA_DETAIL_NAME,
                    intent.getStringExtra(NotificationEntryActivity.EXTRA_DETAIL_NAME))
                .putExtra(IntentKey.CACHE_CONFIG_CHANGED,
                    intent.getBooleanExtra(IntentKey.CACHE_CONFIG_CHANGED, false))
        )
        overridePendingTransition(0, 0)
        finish()
    }
}

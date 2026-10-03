package com.github.tvbox.osc.ui.activity

import android.content.Intent
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivitySplashBinding

class SplashActivity : BaseVbActivity<ActivitySplashBinding>() {
    override fun init() {
        App.getInstance().isNormalStart = true
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_STARTUP_SPLASH, true)
        )
        overridePendingTransition(0, 0)
        finish()
    }
}

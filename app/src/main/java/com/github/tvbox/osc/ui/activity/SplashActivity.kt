package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.os.Handler
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivitySplashBinding
import com.github.tvbox.osc.theme.ThemeRuntime

class SplashActivity : BaseVbActivity<ActivitySplashBinding>() {
    override fun init() {
        App.getInstance().isNormalStart = true
        // 布局根节点是实色背景，会盖住窗口底色；首帧直接用当前主题的 body 色。
        ThemeRuntime.colorPalette()?.let { mBinding.root.setBackgroundColor(it.get("bg_body")) }

        mBinding.root.postDelayed({
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            finish()
        },500)

    }
}

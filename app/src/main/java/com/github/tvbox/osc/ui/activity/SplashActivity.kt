package com.github.tvbox.osc.ui.activity

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import androidx.core.content.ContextCompat
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivitySplashBinding
import com.github.tvbox.osc.theme.ThemeRuntime
import com.github.tvbox.osc.ui.splash.SplashContent
import com.github.tvbox.osc.ui.splash.SplashContentSelector

class SplashActivity : BaseVbActivity<ActivitySplashBinding>() {
    private var leaving = false
    private val fallbackNavigation = Runnable { openMain() }

    override fun init() {
        App.getInstance().isNormalStart = true

        // 布局根节点会盖住窗口底色，首帧直接用当前运行时主题的背景色。
        val backgroundColor = ThemeRuntime.runtimePalette()?.get("bg_body")
            ?: ContextCompat.getColor(this, R.color.bg_body)
        mBinding.root.setBackgroundColor(backgroundColor)

        val content = SplashContentSelector.select(backgroundColor)
        when (content) {
            is SplashContent.Lottie -> showLottie(content)
        }

        // 素材解析失败或动画回调未到时，仍能进入首页。
        mBinding.root.postDelayed(fallbackNavigation, MAX_SPLASH_MS)
    }

    private fun showLottie(content: SplashContent.Lottie) {
        mBinding.splashAnimation.apply {
            repeatCount = 0
            setFailureListener {
                if (!leaving && !isFinishing && !isDestroyed) {
                    mBinding.root.post(fallbackNavigation)
                }
            }
            addAnimatorListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = openMain()
            })
            addLottieOnCompositionLoadedListener {
                if (!leaving && !isFinishing && !isDestroyed) playAnimation()
            }
            setAnimation(content.assetPath)
        }
    }

    private fun openMain() {
        if (leaving || isFinishing || isDestroyed) return
        leaving = true
        mBinding.root.removeCallbacks(fallbackNavigation)
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        leaving = true
        mBinding.root.removeCallbacks(fallbackNavigation)
        mBinding.splashAnimation.cancelAnimation()
        super.onDestroy()
    }

    private companion object {
        const val MAX_SPLASH_MS = 3_000L
    }
}

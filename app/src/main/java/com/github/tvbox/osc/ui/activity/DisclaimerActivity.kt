package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.databinding.ActivityDisclaimerBinding

/** 首次打开时要求确认；从「关于」进入时可再次查看。 */
class DisclaimerActivity : BaseVbActivity<ActivityDisclaimerBinding>() {

    companion object {
        const val EXTRA_REVIEW_MODE = "com.github.tvbox.osc.DISCLAIMER_REVIEW"
    }

    private var decisionMade = false

    override fun init() {
        if (intent.getBooleanExtra(EXTRA_REVIEW_MODE, false)) {
            mBinding.disagreeButton.visibility = View.GONE
            mBinding.agreeButton.setText(R.string.disclaimer_acknowledge)
            val buttonLayout = mBinding.agreeButton.layoutParams as LinearLayout.LayoutParams
            buttonLayout.marginStart = 0
            mBinding.agreeButton.layoutParams = buttonLayout
            mBinding.agreeButton.setOnClickListener { finish() }
            return
        }
        mBinding.agreeButton.setOnClickListener {
            if (decisionMade) return@setOnClickListener
            decisionMade = true
            SystemConfig.acceptDisclaimer()
            if (isTaskRoot) {
                // 正常启动时首页在下层；任务栈被系统回收后恢复为单页时补回首页。
                startActivity(Intent(this, MainActivity::class.java))
            }
            finish()
        }
        mBinding.disagreeButton.setOnClickListener { exitWithoutAccepting() }
    }

    override fun onBackPressed() {
        if (intent.getBooleanExtra(EXTRA_REVIEW_MODE, false)) {
            finish()
        } else {
            exitWithoutAccepting()
        }
    }

    private fun exitWithoutAccepting() {
        if (decisionMade) return
        decisionMade = true
        SystemConfig.declineDisclaimer()
        finishAffinity()
    }
}

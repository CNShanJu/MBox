package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.R
import com.github.tvbox.osc.databinding.ActivityDownloadBinding
import com.github.tvbox.osc.ui.dialog.DownloadSettingsDialog
import com.github.tvbox.osc.ui.fragment.DownloadFragment
import com.github.tvbox.osc.util.Utils
import com.lxj.xpopup.XPopup

/**
 * 下载页(我的-下载):内部按 正在下载 / 下载完成 两个 tab 展示。
 * 标题栏:返回 + "下载管理" + 右侧齿轮(下载设置弹窗:并发 SelectDialog + 仅WiFi 开关,与全局设置页同一事实源)。
 */
class DownloadActivity : BaseVbActivity<ActivityDownloadBinding>() {
    override fun init() {
        supportFragmentManager.beginTransaction()
            .replace(mBinding.container.id, DownloadFragment())
            .commitAllowingStateLoss()
        // 标题栏右侧齿轮:自绘图标(尺寸/位置完全可控,与标题垂直居中)
        mBinding.titleBar.setRightIconCustom(R.drawable.ic_setting, 22f, 22f, 12f)
        // 点击 = 下载设置弹窗(跟随主题:并发 SelectDialog + 仅WiFi 开关)
        mBinding.titleBar.rightView.setOnClickListener {
            XPopup.Builder(this)
                .isDarkTheme(Utils.isDarkTheme()) // 遮罩/弹窗样式跟随主题
                .asCustom(DownloadSettingsDialog(this))
                .show()
        }
    }

    override fun onBackPressed() {
        val f = supportFragmentManager.findFragmentById(mBinding.container.id) as? DownloadFragment
        if (f != null && f.onBackPressed()) return
        super.onBackPressed()
    }
}

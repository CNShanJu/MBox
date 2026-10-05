package com.github.tvbox.osc.ui.activity

import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityLanDevicesBinding
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.server.RemoteServer
import com.github.tvbox.osc.ui.dialog.ConfirmDialog
import java.text.DateFormat
import java.util.Date

/** 局域网服务的下级页：查看和管理已配对设备。 */
class LanDevicesActivity : BaseVbActivity<ActivityLanDevicesBinding>() {
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshDevices()
            refreshHandler.postDelayed(this, 10_000L)
        }
    }

    override fun init() {
        mBinding.btnRefreshDevices.setOnClickListener { refreshDevices() }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.removeCallbacks(refreshTask)
        refreshTask.run()
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun refreshDevices() {
        val manager = ControlManager.get()
        mBinding.llDevices.removeAllViews()
        if (!manager.isLanServing) {
            mBinding.tvDeviceCount.text = "局域网服务未运行"
            mBinding.llDevices.addView(label("开启局域网服务后，在线设备会显示在这里。", false))
            return
        }
        val devices = manager.connectedDevices()
        mBinding.tvDeviceCount.text = "在线 ${devices.size} 台设备"
        if (devices.isEmpty()) {
            mBinding.llDevices.addView(label("暂无在线设备，请在其他设备上打开局域网页面。", false))
            return
        }
        devices.forEach(::addDevice)
    }

    private fun addDevice(device: RemoteServer.LanDevice) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_large_round_float)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val type = if (device.kind == "browser") "浏览器" else "MBox"
        row.addView(label("${device.name} · $type", true))
        val idle = ((System.currentTimeMillis() - device.lastSeen) / 1000).coerceAtLeast(0)
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(device.connectedAt))
        val playing = device.currentTitle()
        row.addView(label("地址 ${device.ip}\n连接于 $time · 最近活动 $idle 秒前" +
            if (playing.isEmpty()) "" else "\n已推送 $playing", false))
        row.addView(label("踢出并撤销配对", true).apply {
            setTextColor(ContextCompat.getColor(this@LanDevicesActivity, R.color.text_danger))
            setPadding(0, dp(9), 0, 0)
            setOnClickListener {
                ConfirmDialog.showDanger(this@LanDevicesActivity, "踢出设备",
                    "确定断开“${device.name}”（${device.ip}）吗？它需要重新输入配对码才能访问。",
                    "踢出") {
                    ControlManager.get().kickDevice(device.id)
                    refreshDevices()
                }
            }
        })
        mBinding.llDevices.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
    }

    private fun label(text: String, primary: Boolean) = TextView(this).apply {
        setText(text)
        textSize = if (primary) 14f else 12f
        setTextColor(ContextCompat.getColor(this@LanDevicesActivity,
            if (primary) R.color.text_foreground else R.color.text_sub_foreground))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}

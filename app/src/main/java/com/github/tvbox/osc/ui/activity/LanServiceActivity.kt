package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.ClipboardUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityLanServiceBinding
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.server.RemoteServer
import com.github.tvbox.osc.service.LanServerService
import com.github.tvbox.osc.transfer.ConfigImportSession
import com.github.tvbox.osc.ui.dialog.ConfirmDialog
import com.github.tvbox.osc.ui.dialog.DialogCoordinator
import com.github.tvbox.osc.ui.dialog.LanImportDialog
import com.github.tvbox.osc.ui.dialog.TextTipDialog
import com.github.tvbox.osc.util.AppBubble
import com.lxj.xpopup.XPopup
import java.text.DateFormat
import java.util.Date

/** 局域网设置的二级页：地址、设备控制台和解析导入都在这里。 */
class LanServiceActivity : BaseVbActivity<ActivityLanServiceBinding>() {
    companion object {
        const val EXTRA_FROM_NOTIFICATION = "from_lan_notification"
    }
    private val importSession = ConfigImportSession.get()
    private val importListener = ConfigImportSession.Listener { renderImportConnection() }

    override fun init() {
        // 前台服务可在无 Activity 的进程里被系统恢复；通知直达本页属于正常入口。
        if (intent?.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false) == true) {
            com.github.tvbox.osc.base.App.getInstance().isNormalStart = true
        }
        mBinding.btnRefreshDevices.setOnClickListener { refreshDevices() }
        mBinding.btnRotatePairingCode.setOnClickListener {
            ConfirmDialog.show(this, "更新配对码",
                "更新后，已配对设备会断开连接，需要输入新配对码重新连接。", "确认更新") {
                if (ControlManager.get().rotatePairingCode().isNotEmpty()) {
                    renderStatus()
                    refreshDevices()
                    AppBubble.toast("配对码已更新")
                }
            }
        }
        mBinding.btnLanImport.setOnClickListener {
            if (importSession.hasConnection()) {
                openImportPage()
            } else {
                DialogCoordinator.bottom(this, LanImportDialog(this) {
                    if (!isFinishing && !isDestroyed) openImportPage()
                }, 0).show()
            }
        }
        mBinding.btnLanExport.setOnClickListener {
            val urls = ControlManager.get().lanAccessUrls
            val code = ControlManager.get().pairingCode
            if (!ControlManager.get().isLanServing || urls.isEmpty() || code.isEmpty()) {
                AppBubble.toast("请先开启局域网服务并重启应用")
            } else {
                XPopup.Builder(this).asCustom(TextTipDialog(this, "导出配置",
                    "在另一台 MBox 打开「局域网配置导入导出」并连接本机，输入下方地址和配对码，然后选择要导入的配置。\n\n地址：${urls.first()}\n配对码：$code\n\n选中的配置会在传输时打包为 ZIP。"
                )).show()
            }
        }
        mBinding.btnLanRestart.setOnClickListener { restartAppForLan() }
        mBinding.btnLanHelp.setOnClickListener {
            XPopup.Builder(this).asCustom(TextTipDialog(this,
                getString(R.string.lan_server_title), getString(R.string.setting_lan_server_tip))).show()
        }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        // 每次进入页面读取一次；之后仅由用户点击「刷新」或踢出设备时更新。
        refreshDevices()
        renderImportConnection()
    }

    override fun onStart() {
        super.onStart()
        importSession.addListener(importListener)
    }

    override fun onStop() {
        importSession.removeListener(importListener)
        super.onStop()
    }

    private fun openImportPage() {
        startActivity(Intent(this, LanImportActivity::class.java))
    }

    private fun renderImportConnection() {
        mBinding.btnLanImport.text = if (importSession.hasConnection()) "导入配置" else "连接服务器"
        mBinding.tvImportConnection.text = when (importSession.state()) {
            ConfigImportSession.State.CONNECTED -> "已连接：${importSession.address()}"
            ConfigImportSession.State.UNREACHABLE -> "服务器暂时无法访问，正在重试：${importSession.address()}"
            ConfigImportSession.State.KICKED -> "服务器已移除本机配对，请重新连接"
            else -> "尚未连接其他 MBox"
        }
        mBinding.tvImportConnection.setTextColor(ContextCompat.getColor(this,
            if (importSession.state() == ConfigImportSession.State.KICKED) R.color.text_danger
            else R.color.text_sub_foreground))
    }

    private fun renderStatus() {
        val manager = ControlManager.get()
        val state = manager.lanState()
        mBinding.tvLanState.text = when (state) {
            ControlManager.LAN_ACTIVE -> getString(R.string.lan_server_state_active)
            ControlManager.LAN_PENDING_RESTART -> getString(R.string.lan_server_state_pending)
            ControlManager.LAN_PENDING_CLOSE -> getString(R.string.lan_server_state_pending_close)
            else -> getString(R.string.lan_server_state_off)
        }
        mBinding.tvLanState.setTextColor(ContextCompat.getColor(this,
            if (manager.isLanServing) R.color.text_highlight else R.color.text_sub_foreground))
        mBinding.tvLanLead.text = when (state) {
            ControlManager.LAN_ACTIVE -> getString(R.string.lan_server_lead)
            ControlManager.LAN_PENDING_RESTART -> getString(R.string.lan_server_lead_pending)
            ControlManager.LAN_PENDING_CLOSE -> getString(R.string.lan_server_lead_pending_close)
            else -> getString(R.string.lan_server_lead_off)
        }
        mBinding.llLanAddresses.removeAllViews()
        val urls = manager.lanAccessUrls
        if (urls.isEmpty()) {
            mBinding.llLanAddresses.addView(label(getString(R.string.lan_server_addr_empty), false))
        } else {
            urls.forEach { url ->
                val row = label(url, true)
                row.setTextColor(ContextCompat.getColor(this, R.color.text_highlight))
                row.setPadding(0, dp(6), 0, dp(6))
                row.minHeight = dp(40)
                row.gravity = Gravity.CENTER_VERTICAL
                row.contentDescription = "复制地址：$url"
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.topMargin = dp(2)
                row.layoutParams = params
                row.setOnClickListener {
                    ClipboardUtils.copyText(url)
                    AppBubble.toast("已复制地址：$url")
                }
                mBinding.llLanAddresses.addView(row)
            }
        }
        val code = manager.pairingCode
        mBinding.tvLanPairingCode.visibility = if (code.isEmpty()) View.GONE else View.VISIBLE
        mBinding.btnRotatePairingCode.visibility = if (code.isEmpty()) View.GONE else View.VISIBLE
        mBinding.tvLanPairingCode.text = code
        mBinding.btnCopyPairingCode.visibility = if (code.isEmpty()) View.GONE else View.VISIBLE
        mBinding.btnCopyPairingCode.setOnClickListener {
            if (code.isNotEmpty()) {
                ClipboardUtils.copyText(code)
                AppBubble.toast("已复制配对码")
            }
        }
        mBinding.btnLanRestart.visibility = if (state == ControlManager.LAN_PENDING_RESTART
            || state == ControlManager.LAN_PENDING_CLOSE) View.VISIBLE else View.GONE
        val serving = manager.isLanServing
        mBinding.panelLanDevices.visibility = if (serving) View.VISIBLE else View.GONE
    }

    private fun refreshDevices() {
        if (!ControlManager.get().isLanServing) return
        val devices = ControlManager.get().pairedDevices()
        mBinding.tvDeviceCount.text = "已配对 ${devices.size} 台设备"
        mBinding.llDevices.removeAllViews()
        if (devices.isEmpty()) {
            mBinding.llDevices.addView(label("暂无设备，配对后会显示在这里。", false))
            return
        }
        devices.forEach { device -> addDevice(device) }
    }

    private fun addDevice(device: RemoteServer.LanDevice) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(14))
        }
        val type = if (device.kind == "browser") "浏览器" else "MBox"
        row.addView(label("${device.name} · $type", true))
        val idle = ((System.currentTimeMillis() - device.lastSeen) / 1000).coerceAtLeast(0)
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(device.connectedAt))
        val status = if (idle <= 60) "在线" else "暂时离线"
        val playing = device.currentTitle()
        row.addView(label("地址 ${device.ip}\n连接于 $time · $status · 最近活动 $idle 秒前" +
            if (playing.isEmpty()) "" else "\n已推送 $playing", false))
        val kick = label("踢出并撤销配对", true).apply {
            setTextColor(ContextCompat.getColor(this@LanServiceActivity, R.color.text_danger))
            setPadding(0, dp(9), 0, 0)
            setOnClickListener {
                ConfirmDialog.showDanger(this@LanServiceActivity, "踢出设备",
                    "确定断开“${device.name}”（${device.ip}）吗？它需要重新输入配对码才能访问。",
                    "踢出") {
                    ControlManager.get().kickDevice(device.id)
                    refreshDevices()
                }
            }
        }
        row.addView(kick)
        mBinding.llDevices.addView(row)
    }

    private fun label(text: String, primary: Boolean) = TextView(this).apply {
        setText(text)
        textSize = if (primary) 14f else 12f
        setTextColor(ContextCompat.getColor(this@LanServiceActivity,
            if (primary) R.color.text_foreground else R.color.text_sub_foreground))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun restartAppForLan() {
        com.github.tvbox.osc.config.SystemConfig.markInternalRestart()
        try {
            LanServerService.stop(this)
            ControlManager.get().stopServer()
        } catch (error: Throwable) {
            Log.w("MBox-Lan", "重启前停止服务失败", error)
        }
        try {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launch)
                return
            }
        } catch (error: Throwable) {
            Log.w("MBox-Lan", "重启应用失败，尝试兜底方式", error)
        }
        try { AppUtils.relaunchApp(true) }
        catch (error: Throwable) { AppBubble.toast("重启失败，请手动重开应用") }
    }
}

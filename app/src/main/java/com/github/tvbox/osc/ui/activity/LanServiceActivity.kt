package com.github.tvbox.osc.ui.activity

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.ClipboardUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.databinding.ActivityLanServiceBinding
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.service.LanServerService
import com.github.tvbox.osc.transfer.ConfigImportSession
import com.github.tvbox.osc.ui.dialog.ConfirmDialog
import com.github.tvbox.osc.ui.dialog.DialogCoordinator
import com.github.tvbox.osc.ui.dialog.LanImportDialog
import com.github.tvbox.osc.ui.dialog.LanPairQrDialog
import com.github.tvbox.osc.ui.dialog.TextTipDialog
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.github.tvbox.osc.util.LanPairQr
import com.lxj.xpopup.XPopup

/** 局域网设置的二级页：访问地址、配置导入导出和设备控制台入口。 */
class LanServiceActivity : BaseVbActivity<ActivityLanServiceBinding>() {
    companion object {
        const val EXTRA_FROM_NOTIFICATION = "from_lan_notification"
    }
    private val importSession = ConfigImportSession.get()
    private val importListener = ConfigImportSession.Listener { renderImportConnection() }
    private var scanDraftAddress = ""
    private var scanDraftCode = ""
    private var importNavigationToken = 0L
    private var lanStartToken = 0L
    private var lanStartInFlight = false
    private var lanStartTimeout: Runnable? = null
    private val lanStateListener = LanServerService.StateListener {
        if (!isFinishing && !isDestroyed) renderStatus()
    }
    private val scanLanQr = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        ++importNavigationToken
        val raw = if (result.resultCode == Activity.RESULT_OK)
            result.data?.getStringExtra(LanQrScanActivity.EXTRA_QR_RESULT) else null
        val pair = raw?.let(LanPairQr::decode)
        if (raw != null && pair == null) AppBubble.toast("二维码不是有效的 MBox 连接信息")
        if (pair == null) openConnectDialog(scanDraftAddress, scanDraftCode)
        else openConnectDialog(pair.address, pair.code, connectImmediately = true)
    }
    private val requestLanNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableLanWithNotification() else showNotificationSettingsNeeded()
    }

    override fun init() {
        // 前台服务可在无 Activity 的进程里被系统恢复；通知直达本页属于正常入口。
        if (intent?.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false) == true) {
            com.github.tvbox.osc.base.App.getInstance().isNormalStart = true
        }
        mBinding.llLanServer.setOnClickListener { view ->
            FastClickCheckUtil.check(view)
            val enabled = SystemConfig.isLanServerEnabled()
            // 仅撤销尚未生效的开启设置时，直接关闭；真实对外监听仍需确认断开设备。
            if (enabled && ControlManager.get().isLanServing) {
                ConfirmDialog.show(this, "关闭局域网服务",
                    "关闭会清除已配对设备，并立即停止局域网访问；本机回环服务会继续运行。",
                    "确认关闭") { updateLanServerEnabled(false) }
            } else {
                updateLanServerEnabled(!enabled)
            }
        }
        mBinding.btnOpenLanConsole.setOnClickListener { openConsole() }
        mBinding.btnRotatePairingCode.setOnClickListener {
            ConfirmDialog.show(this, "更新配对码",
                "更新后，已配对设备会断开连接，需要输入新配对码重新连接。", "确认更新") {
                if (ControlManager.get().rotatePairingCode().isNotEmpty()) {
                    renderStatus()
                    AppBubble.toast("配对码已更新")
                }
            }
        }
        mBinding.btnLanImport.setOnClickListener {
            ++importNavigationToken
            if (importSession.hasConnection()) {
                openImportPage()
            } else {
                openConnectDialog()
            }
        }
        mBinding.btnShowLanQr.setOnClickListener { showLanQr() }
        mBinding.btnLanExport.setOnClickListener {
            val urls = ControlManager.get().lanAccessUrls
            val code = ControlManager.get().pairingCode
            if (!ControlManager.get().isLanServing || urls.isEmpty() || code.isEmpty()) {
                AppBubble.toast("请先开启局域网服务")
            } else {
                XPopup.Builder(this).asCustom(TextTipDialog(this, "导出配置",
                    "在另一台 MBox 打开「局域网配置导入导出」并连接本机，输入下方地址和配对码，然后选择要导入的配置。\n\n地址：${urls.first()}\n配对码：$code\n\n选中的配置会在传输时打包为 ZIP。"
                )).show()
            }
        }
        mBinding.btnLanRestart.setOnClickListener { restartAppForLan() }
        mBinding.ivLanHelp.setOnClickListener {
            XPopup.Builder(this).asCustom(TextTipDialog(this,
                getString(R.string.lan_server_title), getString(R.string.setting_lan_server_tip))).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (SystemConfig.isLanServerEnabled() && !LanServerService.canShowNotification(this)) {
            ++lanStartToken
            lanStartInFlight = false
            lanStartTimeout?.let { mBinding.root.removeCallbacks(it) }
            lanStartTimeout = null
            LanServerService.disable(this)
            AppBubble.toast(if (ControlManager.get().lanState() == ControlManager.LAN_PENDING_CLOSE)
                "通知不可见，局域网监听关闭异常，请重启应用" else "通知不可见，局域网服务已关闭")
        }
        renderStatus()
        renderImportConnection()
    }

    override fun onStart() {
        super.onStart()
        LanServerService.addStateListener(lanStateListener)
        importSession.addListener(importListener)
    }

    override fun onStop() {
        ++importNavigationToken
        LanServerService.removeStateListener(lanStateListener)
        importSession.removeListener(importListener)
        super.onStop()
    }

    override fun onDestroy() {
        lanStartTimeout?.let { mBinding.root.removeCallbacks(it) }
        lanStartTimeout = null
        super.onDestroy()
    }

    private fun openImportPage() {
        startActivity(Intent(this, LanImportActivity::class.java))
    }

    private fun openConsole() {
        startActivity(Intent(this, LanDevicesActivity::class.java))
    }

    private fun openConnectDialog(address: String = "", code: String = "",
                                  connectImmediately: Boolean = false) {
        val navigationToken = ++importNavigationToken
        if (isFinishing || isDestroyed) return
        val dialog = LanImportDialog(this, address, code, connectImmediately,
            Runnable {
                if (navigationToken == importNavigationToken &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    !isFinishing && !isDestroyed &&
                    importSession.state() == ConfigImportSession.State.CONNECTED) {
                    openImportPage()
                }
            },
            LanImportDialog.ScanRequest { draftAddress, draftCode ->
                ++importNavigationToken
                scanDraftAddress = draftAddress
                scanDraftCode = draftCode
                scanLanQr.launch(Intent(this, LanQrScanActivity::class.java))
            })
        DialogCoordinator.center(this, dialog).show()
    }

    private fun showLanQr() {
        val manager = ControlManager.get()
        val addresses = manager.lanAccessUrls
        val code = manager.pairingCode
        if (!manager.isLanServing || addresses.isEmpty() || code.isEmpty()) {
            AppBubble.toast("请先开启局域网服务")
            return
        }
        DialogCoordinator.center(this, LanPairQrDialog(this, addresses.toList(), code)).show()
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
        mBinding.switchLanServer.setChecked(SystemConfig.isLanServerEnabled())
        val showServiceCards = state == ControlManager.LAN_ACTIVE
        mBinding.panelLanAccess.visibility = if (showServiceCards) View.VISIBLE else View.GONE
        mBinding.btnOpenLanConsole.visibility = if (showServiceCards) View.VISIBLE else View.GONE
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
        mBinding.btnShowLanQr.visibility = if (manager.isLanServing && code.isNotEmpty()
            && urls.isNotEmpty()) View.VISIBLE else View.GONE
        mBinding.btnCopyPairingCode.setOnClickListener {
            if (code.isNotEmpty()) {
                ClipboardUtils.copyText(code)
                AppBubble.toast("已复制配对码")
            }
        }
        mBinding.btnLanRestart.visibility = if ((state == ControlManager.LAN_PENDING_RESTART
            && !lanStartInFlight) || state == ControlManager.LAN_PENDING_CLOSE)
            View.VISIBLE else View.GONE
    }

    private fun label(text: String, primary: Boolean) = TextView(this).apply {
        setText(text)
        textSize = if (primary) 14f else 12f
        setTextColor(ContextCompat.getColor(this@LanServiceActivity,
            if (primary) R.color.text_foreground else R.color.text_sub_foreground))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun updateLanServerEnabled(enabled: Boolean) {
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestLanNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            enableLanWithNotification()
            return
        }
        ++lanStartToken
        lanStartInFlight = false
        lanStartTimeout?.let { mBinding.root.removeCallbacks(it) }
        lanStartTimeout = null
        LanServerService.disable(this)
        renderStatus()
        AppBubble.toast(if (ControlManager.get().lanState() == ControlManager.LAN_PENDING_CLOSE)
            "局域网监听未能停止，请重启应用" else "局域网服务已关闭")
    }

    private fun enableLanWithNotification() {
        if (!LanServerService.canShowNotification(this)) {
            showNotificationSettingsNeeded()
            return
        }
        val startToken = ++lanStartToken
        lanStartInFlight = true
        val timeout = Runnable {
            if (startToken == lanStartToken && lanStartInFlight && !isFinishing && !isDestroyed) {
                ++lanStartToken
                lanStartInFlight = false
                lanStartTimeout = null
                HeavyTaskUtil.executeBigTask {
                    try {
                        LanServerService.disable(applicationContext)
                    } catch (error: RuntimeException) {
                        Log.w("MBox-Lan", "启动超时后停止局域网服务失败", error)
                    }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            renderStatus()
                            AppBubble.toast(if (ControlManager.get().lanState() == ControlManager.LAN_PENDING_CLOSE)
                                "局域网监听关闭异常，请重启应用" else "局域网服务启动超时，已保持关闭")
                        }
                    }
                }
            }
        }
        lanStartTimeout = timeout
        mBinding.root.postDelayed(timeout, 15_000L)
        SystemConfig.setLanServerEnabled(true)
        LanServerService.start(this, object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (startToken != lanStartToken || isFinishing || isDestroyed) return
                lanStartInFlight = false
                mBinding.root.removeCallbacks(timeout)
                lanStartTimeout = null
                renderStatus()
                if (resultCode != LanServerService.START_ACTIVE) {
                    AppBubble.toast(if (ControlManager.get().lanState() == ControlManager.LAN_PENDING_CLOSE)
                        "局域网监听关闭异常，请重启应用" else "局域网服务启动失败，已保持关闭")
                }
            }
        })
        renderStatus()
    }

    private fun showNotificationSettingsNeeded() {
        LanServerService.canShowNotification(this) // 确保目标频道已创建，系统设置可直接定位。
        renderStatus()
        ConfirmDialog.show(this, "需要显示局域网服务通知",
            "开启前请允许 MBox 通知，并让“局域网服务”频道显示状态栏图标；频道关闭或最小化时无法持续提醒。",
            "去系统设置") {
            try {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val manager = getSystemService(android.app.NotificationManager::class.java)
                    val appBlocked = manager?.areNotificationsEnabled() != true ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(this,
                                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    if (appBlocked) {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    } else {
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, LanServerService.CHANNEL_ID)
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"))
                }
                startActivity(intent)
            } catch (error: RuntimeException) {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                } catch (fallback: RuntimeException) {
                    AppBubble.toast("无法打开系统通知设置，请在应用信息中开启通知")
                }
            }
        }
    }

    private fun restartAppForLan() {
        com.github.tvbox.osc.config.SystemConfig.markInternalRestart()
        try {
            ControlManager.get().stopServer()
            if (ControlManager.get().isLanServing) {
                com.github.tvbox.osc.config.SystemConfig.clearInternalRestart()
                AppBubble.toast("局域网监听未能停止，请稍后再试")
                return
            }
            LanServerService.stop(this)
        } catch (error: Throwable) {
            Log.w("MBox-Lan", "重启前停止服务失败", error)
            com.github.tvbox.osc.config.SystemConfig.clearInternalRestart()
            AppBubble.toast("局域网监听未能停止，请稍后再试")
            return
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
        catch (error: Throwable) {
            com.github.tvbox.osc.config.SystemConfig.clearInternalRestart()
            AppBubble.toast("重启失败，请手动重开应用")
        }
    }
}

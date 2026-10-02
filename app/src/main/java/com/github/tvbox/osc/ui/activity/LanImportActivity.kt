package com.github.tvbox.osc.ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityLanImportBinding
import com.github.tvbox.osc.transfer.ConfigImportSession
import com.github.tvbox.osc.ui.dialog.ConfirmDialog
import com.google.gson.JsonObject

/** 成功连接后展示的配置选择页；连接归 ConfigImportSession 管理。 */
class LanImportActivity : BaseVbActivity<ActivityLanImportBinding>() {
    companion object { private var notificationPermissionRequested = false }
    private val session = ConfigImportSession.get()
    private val connectionListener = ConfigImportSession.Listener { renderConnection() }
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { }
    private var importing = false

    override fun init() {
        showCategories(session.catalog())
        mBinding.btnImportSelected.setOnClickListener { confirmImport() }
        renderConnection()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationPermissionRequested &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionRequested = true
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        session.addListener(connectionListener)
        renderConnection()
    }

    override fun onStop() {
        session.removeListener(connectionListener)
        super.onStop()
    }

    private fun renderConnection() {
        mBinding.tvPeerAddress.text = session.address().ifEmpty { "尚未连接服务器" }
        mBinding.tvConnectionState.text = when (session.state()) {
            ConfigImportSession.State.CONNECTED -> "已连接，可选择配置导入"
            ConfigImportSession.State.UNREACHABLE -> "暂时无法访问服务器，正在重试"
            ConfigImportSession.State.KICKED -> "服务器已移除这台设备，请返回重新连接"
            else -> "连接已断开，请返回重新连接"
        }
        mBinding.tvConnectionState.setTextColor(ContextCompat.getColor(this, when (session.state()) {
            ConfigImportSession.State.CONNECTED -> R.color.text_highlight
            ConfigImportSession.State.KICKED -> R.color.text_danger
            else -> R.color.text_sub_foreground
        }))
        mBinding.btnImportSelected.isEnabled = !importing && session.state() == ConfigImportSession.State.CONNECTED
    }

    private fun showCategories(catalog: JsonObject?) {
        val allowed = setOf("subscriptions", "live", "themes", "settings", "history")
        val categories = catalog?.getAsJsonArray("categories")
        val checkboxColors = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(
                ContextCompat.getColor(this, R.color.text_disable),
                ContextCompat.getColor(this, R.color.text_foreground)))
        mBinding.llCategories.removeAllViews()
        categories?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val item = element.asJsonObject
            val id = runCatching { item.get("id")?.asString.orEmpty() }.getOrDefault("")
            val count = runCatching { item.get("count")?.asInt ?: 0 }.getOrDefault(0)
            if (id !in allowed) return@forEach
            val name = runCatching { item.get("label")?.asString.orEmpty() }.getOrDefault("").ifEmpty { id }
            val check = CheckBox(this).apply {
                tag = id
                text = "$name（$count）"
                textSize = 14f
                setButtonDrawable(R.drawable.button_checkbox_square)
                setTextColor(checkboxColors)
                buttonTintList = checkboxColors
                isEnabled = count > 0
                minHeight = dp(44)
                setPadding(dp(4), dp(7), dp(4), dp(7))
            }
            mBinding.llCategories.addView(check)
        }
        if (mBinding.llCategories.childCount == 0) {
            mBinding.llCategories.addView(TextView(this).apply {
                text = "暂无可导入的配置"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@LanImportActivity, R.color.text_sub_foreground))
            })
        }
    }

    private fun confirmImport() {
        val selected = mutableSetOf<String>()
        for (index in 0 until mBinding.llCategories.childCount) {
            val check = mBinding.llCategories.getChildAt(index) as? CheckBox ?: continue
            if (check.isChecked) selected.add(check.tag as String)
        }
        if (selected.isEmpty()) {
            mBinding.tvImportStatus.text = "请先选择要导入的配置"
            return
        }
        val impact = if ("settings" in selected)
            "所选内容会合并到本机；我的设置会覆盖对应的现有设置。"
        else "所选内容会合并到本机，已有记录会保留。"
        ConfirmDialog.show(this, "确认导入", impact + "导入完成后建议重启应用。", "开始导入") {
            importSelected(selected)
        }
    }

    private fun importSelected(selected: Set<String>) {
        importing = true
        renderConnection()
        mBinding.tvImportStatus.text = "正在导入所选配置…"
        session.importSelected(selected) { result, error ->
            if (isFinishing || isDestroyed) return@importSelected
            importing = false
            renderConnection()
            mBinding.tvImportStatus.text = if (error == null) result else "导入中断：$error"
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}

package com.github.tvbox.osc.ui.activity

import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.DiffUtil
import com.github.tvbox.osc.log.LogConfig
import com.github.tvbox.osc.player.PlayerTrackHelper
import com.github.tvbox.osc.player.api.IjkCodecConfigProviders
import com.github.tvbox.osc.player.api.PlayConfig
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.BackgroundPlaySettings
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.IJKCode
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.ActivitySettingBinding
import com.github.tvbox.osc.download.DownloadFacade
import com.github.tvbox.osc.util.ThrottlePolicy
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter.SelectDialogInterface
import com.github.tvbox.osc.ui.dialog.BackupDialog
import com.github.tvbox.osc.ui.dialog.LoadingAnimPreviewDialog
import com.github.tvbox.osc.ui.dialog.SelectDialog
import com.github.tvbox.osc.ui.dialog.TextTipDialog
import com.github.tvbox.osc.ui.dialog.ThemePickerDialog
import com.github.tvbox.osc.storage.theme.ThemeStore
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.github.tvbox.osc.util.cache.CacheCatalog
import com.github.tvbox.osc.util.cache.CacheSizeText
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.LoadingAnim
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.PlayerHelper
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.util.Utils
import com.blankj.utilcode.util.AppUtils
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.lxj.xpopup.XPopup
import okhttp3.HttpUrl
import java.lang.ref.WeakReference

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
class SettingActivity : BaseVbActivity<ActivitySettingBinding>() {

    private var homeRec = SystemConfig.getHomeRec()
    private var dnsOpt = SystemConfig.getDohUrl()

    /** init() 是否已跑完(onResume 刷新显示前要确认控件已就绪) */
    private var inited = false
    private var cacheSizeEpoch = 0

    /** 主题颜色弹窗引用(编辑页返回后若它还开着,就地刷新列表) */
    private var themeDialog: com.github.tvbox.osc.ui.dialog.ThemePickerDialog? = null
    /** 进过主题编辑页并保存/删除过:弹窗关闭时要提交并重启生效(见 showThemePicker) */
    private var themeEditedWhileOpen = false

    private companion object {
        /** 主题编辑页请求码 */
        const val REQ_THEME_EDITOR = 0x0E10

        /** 调试包的主题自检提示:整个进程只弹一次(见 showThemeProbeOnce) */
        private var themeProbeShown = false
    }

    override fun init() {

        // 返回键与系统返回同一口径(统一头部的返回行为在这里接管)
        mBinding.titleBar.setOnBackClickListener { onBackPressed() }
        mBinding.tvMediaCodec.text = PlayConfig.getIjkCodec()

        // 下载设置:仅WiFi / 并发数 / 保存位置(与下载页标题栏齿轮共用 DownloadConfig,单一事实源)
        initDownloadSettings()
        // 加载动画:默认 / Glowing Fish(全局 LoadSir 加载动画,播放器与下载不受影响)
        initLoadingAnimSetting()

        // 用 dohLabel 而不是直接下标:老备份/历史版本里 doh_url 可能是 4~6,而当前列表只有 4 项
        // (直接 dnsHttpsList[getDohUrl()] 会在设置页 IndexOutOfBounds 崩)
        mBinding.tvDns.text = OkGoHelper.dohLabel(SystemConfig.getDohUrl())
        mBinding.tvHomeRec.text = getHomeRecName(SystemConfig.getHomeRec())
        mBinding.tvHistoryNum.text =
            HistoryHelper.getHistoryNumName(SystemConfig.getHistoryNum())
        mBinding.tvScaleType.text = PlayerHelper.getScaleName(PlayConfig.getScaleType())
        mBinding.tvPlay.text = PlayerHelper.getPlayerName(PlayConfig.getPlayType())
        mBinding.tvRenderType.text =
            PlayerHelper.getRenderName(PlayConfig.getRenderType())

        mBinding.switchPrivateBrowsing.setChecked(SystemConfig.isPrivateBrowsing())
        mBinding.llPrivateBrowsing.setOnClickListener { view: View? ->
            val newConfig = !SystemConfig.isPrivateBrowsing()
            mBinding.switchPrivateBrowsing.setChecked(newConfig)
            SystemConfig.setPrivateBrowsing(newConfig)
        }

        // 局域网服务的开关和运行状态统一在管理页显示。
        mBinding.llLanAddress.setOnClickListener { openLanServicePage() }
        com.github.tvbox.osc.theme.ThemeSweep.applyImageTint(
            mBinding.ivLanManageArrow, R.color.text_foreground)

        // 证书例外只针对用户输入的精确主机名；旧版全局开关没有主机名时默认拒绝。
        val ignoreSsl = SystemConfig.isIgnoreSslError()
        mBinding.switchIgnoreSsl.setChecked(ignoreSsl)
        mBinding.tvIgnoreSslTitle.setOnLongClickListener {
            showSettingTip("忽略证书错误", R.string.setting_ignore_ssl_tip)
            true
        }
        mBinding.llIgnoreSsl.setOnClickListener { view: View? ->
            FastClickCheckUtil.check(view)
            if (SystemConfig.isIgnoreSslError()) {
                SystemConfig.setIgnoreSslError(false)
                mBinding.switchIgnoreSsl.setChecked(false)
                AppBubble.toast("已恢复证书校验")
            } else {
                val input = EditText(this@SettingActivity)
                input.setSingleLine(true)
                input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                input.hint = "example.com"
                input.setText(SystemConfig.getSslExceptionHost())
                AlertDialog.Builder(this@SettingActivity)
                    .setTitle("指定证书例外网站")
                    .setMessage("只对填写的精确主机名忽略证书错误；该网站的连接可能被截获。请输入域名，不含协议、端口或路径。")
                    .setView(input)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("启用") { _, _ ->
                        if (SystemConfig.setSslExceptionHost(input.text.toString())) {
                            SystemConfig.setIgnoreSslError(true)
                            mBinding.switchIgnoreSsl.setChecked(true)
                            AppBubble.toast("仅 ${SystemConfig.getSslExceptionHost()} 已启用证书例外，网络请求重启应用后生效")
                        } else {
                            AppBubble.toast("请输入有效的单个域名")
                        }
                    }
                    .show()
            }
        }

        // 直播源已移到「订阅管理 - 直播源」页(订阅自带的跟着订阅走、用户自建的在那儿加),
        // 设置页不再提供入口,避免两处各配一份(2026-10-01)

        mBinding.tvBackgroundPlayType.text = BackgroundPlaySettings.currentLabel()
        mBinding.llBackgroundPlay.setOnClickListener { view: View? ->
            FastClickCheckUtil.check(view)
            val currentMode = BackgroundPlaySettings.currentMode()
            val dialog = SelectDialog<String>(this@SettingActivity)
            dialog.setTip("请选择")
            dialog.setAdapter(object : SelectDialogInterface<String?> {
                override fun click(value: String?, pos: Int) {
                    BackgroundPlaySettings.select(this@SettingActivity, pos)
                    mBinding.tvBackgroundPlayType.text = BackgroundPlaySettings.currentLabel()
                }

                override fun getDisplay(name: String?): String {
                    return name?:""
                }
            },SelectDialogAdapter.stringDiff, BackgroundPlaySettings.MODES, currentMode)
            dialog.show()
        }

        mBinding.tvSpeed.text = PlayConfig.getVideoSpeed().toString()
        mBinding.llPressSpeed.setOnClickListener {
            val types = ArrayList<String>()
            types.add("2.0")
            types.add("3.0")
            types.add("4.0")
            types.add("5.0")
            val defaultPos = types.indexOf(PlayConfig.getVideoSpeed().toString())
            val dialog = SelectDialog<String>(this@SettingActivity)
            dialog.setTip("请选择")
            dialog.setAdapter(object : SelectDialogInterface<String?> {
                override fun click(value: String?, pos: Int) {
                    PlayConfig.setVideoSpeed(value?.toFloat() ?: 2.0f)
                    mBinding.tvSpeed.text = value
                }

                override fun getDisplay(name: String?): String {
                    return name ?: ""
                }
            }, SelectDialogAdapter.stringDiff, types, defaultPos)
            dialog.show()
        }

        mBinding.llBackup.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            if (XXPermissions.isGranted(this@SettingActivity, Permission.MANAGE_EXTERNAL_STORAGE)) {
                val dialog = BackupDialog(this@SettingActivity)
                dialog.show()
            } else {
                XXPermissions.with(this@SettingActivity)
                    .permission(Permission.MANAGE_EXTERNAL_STORAGE)
                    .request(object : OnPermissionCallback {
                        override fun onGranted(permissions: List<String>, all: Boolean) {
                            if (all) {
                                val dialog = BackupDialog(this@SettingActivity)
                                dialog.show()
                            }
                        }

                        override fun onDenied(permissions: List<String>, never: Boolean) {
                            if (never) {
                                AppBubble.toast("请在系统设置中授予存储权限")
                                XXPermissions.startPermissionActivity(
                                    this@SettingActivity,
                                    permissions
                                )
                            } else {
                                AppBubble.toast("获取存储权限失败")
                            }
                        }
                    })
            }
        }

        mBinding.llDns.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val dohUrl = SystemConfig.getDohUrl()
            val dialog = SelectDialog<String>(this@SettingActivity)
            dialog.setTip("请选择安全DNS")
            dialog.setAdapter(object : SelectDialogInterface<String?> {
                override fun click(value: String?, pos: Int) {
                    mBinding.tvDns.text = OkGoHelper.dnsHttpsList[pos]
                    SystemConfig.setDohUrl(pos)
                    // 幂等:门面变更订阅里已复核并重建过一次,这里再调一次只为"设置页自身也直接触发"
                    // (值未变时 OkGoHelper 内部早退,不会重建连接池)
                    OkGoHelper.refreshDnsOverHttps()
                    PlayerTrackHelper.toggleDotPort(pos > 0)
                }

                override fun getDisplay(name: String?): String {
                    return name ?: ""
                }
            },SelectDialogAdapter.stringDiff, OkGoHelper.dnsHttpsList, dohUrl)
            dialog.show()
        }

        mBinding.llMediaCodec.setOnClickListener { v: View? ->
            val ijkCodes = IjkCodecConfigProviders.get().ijkCodes
            if (ijkCodes == null || ijkCodes.size == 0) return@setOnClickListener
            FastClickCheckUtil.check(v)
            var defaultPos = 0
            val ijkSel = PlayConfig.getIjkCodec()
            for (j in ijkCodes.indices) {
                if (ijkSel == ijkCodes[j].name) {
                    defaultPos = j
                    break
                }
            }
            val dialog = SelectDialog<IJKCode>(this@SettingActivity)
            dialog.setTip("请选择IJK解码")
            dialog.setAdapter(object : SelectDialogInterface<IJKCode?> {
                override fun click(value: IJKCode?, pos: Int) {
                    value?.selected(true)
                    // 真正落库:内核取值走 PlayConfig.getIjkCodec()(IJKCode.selected() 只改内存字段),
                    // 原来只 selected() 不写配置 → 弹窗选了、标题变了,起播仍用旧解码,重进设置页又回旧值
                    value?.name?.let { PlayConfig.setIjkCodec(it) }
                    mBinding.tvMediaCodec.text = value?.name
                }

                override fun getDisplay(code: IJKCode?): String {
                    return code?.name ?: ""
                }
            }, object : DiffUtil.ItemCallback<IJKCode>() {
                override fun areItemsTheSame(oldItem: IJKCode, newItem: IJKCode): Boolean {
                    return oldItem === newItem
                }

                override fun areContentsTheSame(oldItem: IJKCode, newItem: IJKCode): Boolean {
                    return oldItem.name.contentEquals(newItem.name)
                }
            }, ijkCodes, defaultPos)
            dialog.show()
        }

        mBinding.llScale.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val defaultPos = PlayConfig.getScaleType()
            val players = ArrayList<Int>()
            players.add(0)
            players.add(1)
            players.add(2)
            players.add(3)
            players.add(4)
            players.add(5)
            val dialog = SelectDialog<Int>(this@SettingActivity)
            dialog.setTip("请选择画面缩放")
            dialog.setAdapter(object : SelectDialogInterface<Int?> {
                override fun click(value: Int?, pos: Int) {
                    PlayConfig.setScaleType(value ?: 0)
                    mBinding.tvScaleType.text = value?.let { PlayerHelper.getScaleName(it) }
                }

                override fun getDisplay(value: Int?): String {
                    return PlayerHelper.getScaleName(value ?: 0)
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, players, defaultPos)
            dialog.show()
        }

        mBinding.llPlay.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val playerType = PlayConfig.getPlayType()
            var defaultPos = 0
            val players = PlayerHelper.getExistPlayerTypes()
            val renders = ArrayList<Int>()
            for (p in players.indices) {
                renders.add(p)
                if (players[p] == playerType) {
                    defaultPos = p
                }
            }
            val dialog = SelectDialog<Int>(this@SettingActivity)
            dialog.setTip("请选择默认播放器")
            dialog.setAdapter(object : SelectDialogInterface<Int?> {
                override fun click(value: Int?, pos: Int) {
                    val thisPlayerType = players[pos]
                    PlayConfig.setPlayType(thisPlayerType)
                    mBinding.tvPlay.text = PlayerHelper.getPlayerName(thisPlayerType)
                    PlayerHelper.init()
                }

                override fun getDisplay(value: Int?): String {
                    return PlayerHelper.getPlayerName(players[value?:0])
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, renders, defaultPos)
            dialog.show()
        }

        mBinding.llRender.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val defaultPos = PlayConfig.getRenderType()
            val renders = ArrayList<Int>()
            renders.add(0)
            renders.add(1)
            val dialog = SelectDialog<Int>(this@SettingActivity)
            dialog.setTip("请选择默认渲染方式")
            dialog.setAdapter(object : SelectDialogInterface<Int?> {
                override fun click(value: Int?, pos: Int) {
                    PlayConfig.setRenderType(value ?: 0)
                    mBinding.tvRenderType.text = PlayerHelper.getRenderName(value?:0)
                    PlayerHelper.init()
                }

                override fun getDisplay(value: Int?): String {
                    return PlayerHelper.getRenderName(value?:0)
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, renders, defaultPos)
            dialog.show()
        }
        mBinding.llHomeRec.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val defaultPos = SystemConfig.getHomeRec()
            val types = ArrayList<Int>()
            types.add(0)
            types.add(1)
            types.add(2)
            val dialog = SelectDialog<Int>(this@SettingActivity)
            dialog.setTip("主页内容显示")
            dialog.setAdapter(object : SelectDialogInterface<Int?> {
                override fun click(value: Int?, pos: Int) {
                    SystemConfig.setHomeRec(value ?: 0)
                    mBinding.tvHomeRec.text = getHomeRecName(value?:0)
                }

                override fun getDisplay(value: Int?): String {
                    return getHomeRecName(value?:0)
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, types, defaultPos)
            dialog.show()
        }
        
        mBinding.llHistoryNum.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val defaultPos = SystemConfig.getHistoryNum()
            val types = ArrayList<Int>()
            types.add(0)
            types.add(1)
            types.add(2)
            val dialog = SelectDialog<Int>(this@SettingActivity)
            dialog.setTip("保留历史记录数量")
            dialog.setAdapter(object : SelectDialogInterface<Int?> {
                override fun click(value: Int?, pos: Int) {
                    SystemConfig.setHistoryNum(value ?: 0)
                    mBinding.tvHistoryNum.text = HistoryHelper.getHistoryNumName(value?:0)
                }

                override fun getDisplay(value: Int?): String {
                    return HistoryHelper.getHistoryNumName(value?:0)
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, types, defaultPos)
            dialog.show()
        }
        mBinding.llClearCache.setOnClickListener { view: View ->
            FastClickCheckUtil.check(view)
            jumpActivity(CacheManagementActivity::class.java)
        }
        // 启动时自动检查更新(默认开):开关只记配置,触发时机见 HomeFragment(上次看到气泡消失后)
        mBinding.switchAutoCheckUpdate.setChecked(SystemConfig.isAutoCheckUpdate())
        mBinding.llAutoCheckUpdate.setOnClickListener { view: View? ->
            FastClickCheckUtil.check(view)
            val newVal = !SystemConfig.isAutoCheckUpdate()
            SystemConfig.setAutoCheckUpdate(newVal)
            mBinding.switchAutoCheckUpdate.setChecked(newVal)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            mBinding.llTheme.visibility = View.GONE
        }
        updateThemeValue()
        // 用法说明放标题长按(横排设置行塞不下长说明,与「忽略证书错误」等同一套口径)
        mBinding.tvThemeTitle.setOnLongClickListener {
            showSettingTip("主题颜色", R.string.setting_theme_tip)
            true
        }
        mBinding.llTheme.setOnClickListener(View.OnClickListener { view: View? ->
            FastClickCheckUtil.check(view)
            showThemePicker()
        })

        // 背景图设置(二级页):展示当前背景图,支持换图/拖动缩放位置/调遮罩透明度/恢复默认
        updatePageBackgroundValue()
        updatePageBackgroundVisibility()
        mBinding.llPageBackground.setOnClickListener(View.OnClickListener { view: View? ->
            FastClickCheckUtil.check(view)
            jumpActivity(BackgroundSettingActivity::class.java)
        })

        mBinding.switchVideoPurify.setChecked(PlayConfig.isVideoPurify())
        // toggle purify video -------------------------------------
        mBinding.llVideoPurify.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val newConfig = !PlayConfig.isVideoPurify()
            mBinding.switchVideoPurify.setChecked(newConfig)
            PlayConfig.setVideoPurify(newConfig)
        }
        mBinding.switchIjkCachePlay.setChecked(PlayConfig.isIjkCachePlay())
        mBinding.llIjkCachePlay.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val newConfig = !PlayConfig.isIjkCachePlay()
            mBinding.switchIjkCachePlay.setChecked(newConfig)
            PlayConfig.setIjkCachePlay(newConfig)
        }
        // 业务日志开关(默认关闭):走 LogConfig 配置门面(查询+发通知+订阅)。
        // 只控 Room 结构化业务日志;错误日志(logcat)常驻记录,不随此开关。
        mBinding.switchSubscriptionLog.setChecked(LogConfig.isEnabled())
        mBinding.llSubscriptionLog.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val newConfig = !LogConfig.isEnabled()
            mBinding.switchSubscriptionLog.setChecked(newConfig)
            LogConfig.setEnabled(newConfig) // 内部持久化 + 广播变更
        }
        // 查看日志入口常显:错误日志常驻,开关关闭也能看
        mBinding.llSubscriptionLogView.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            jumpActivity(LogActivity::class.java)
        }
        inited = true
    }

    /** 下载设置分组:仅WiFi开关 + 并发数选择 + 保存位置只读,统一走 DownloadFacade(门禁:UI 不触内部实现) */
    private fun initDownloadSettings() {
        // 仅 Wi-Fi 下载开关
        mBinding.switchDlWifiOnly.setChecked(DownloadFacade.get().isWifiOnly())
        mBinding.llDlWifiOnly.setOnClickListener {
            val newVal = !DownloadFacade.get().isWifiOnly()
            DownloadFacade.get().setWifiOnly(newVal)
            mBinding.switchDlWifiOnly.setChecked(newVal)
            AppBubble.toast("仅 Wi-Fi 下载已" + if (newVal) "开启" else "关闭")
        }
        // 同时下载任务数(**1-3**;2026-10-01 上限由 5 收到 3,与下载页齿轮弹窗同一事实源)
        val refreshConcurrent = {
            mBinding.tvDlConcurrent.text = DownloadFacade.get().getMaxConcurrent().toString() + " 个"
        }
        refreshConcurrent()
        mBinding.llDlConcurrent.setOnClickListener {
            FastClickCheckUtil.check(it)
            val types = ArrayList<String>()
            for (i in 1..3) types.add("并发 " + i)
            val defaultPos = DownloadFacade.get().getMaxConcurrent().coerceIn(1, 3) - 1
            val dialog = SelectDialog<String>(this@SettingActivity)
            dialog.setTip("选择同时下载任务数")
            dialog.setAdapter(object : SelectDialogInterface<String?> {
                override fun click(value: String?, pos: Int) {
                    DownloadFacade.get().setMaxConcurrent(pos + 1)
                    refreshConcurrent()
                }

                override fun getDisplay(name: String?): String {
                    return name ?: ""
                }
            }, SelectDialogAdapter.stringDiff, types, defaultPos)
            dialog.show()
        }
        // 下载限速(不限速 / 512KB/s / 1MB/s / 2MB/s / 5MB/s):与下载页齿轮弹窗同一事实源,
        // 改完对运行中的任务当场生效(见 DownloadPolicy.setSpeedLimitBytesPerSec)
        val refreshSpeed = {
            mBinding.tvDlSpeed.text = ThrottlePolicy.label(DownloadFacade.get().getSpeedLimitBytesPerSec())
        }
        refreshSpeed()
        mBinding.llDlSpeed.setOnClickListener {
            FastClickCheckUtil.check(it)
            val labels = ArrayList<String>()
            for (bps in ThrottlePolicy.PRESET_BYTES_PER_SEC) labels.add(ThrottlePolicy.label(bps))
            val defaultPos = ThrottlePolicy.presetIndex(DownloadFacade.get().getSpeedLimitBytesPerSec())
            val dialog = SelectDialog<String>(this@SettingActivity)
            dialog.setTip("选择下载限速(不限速即跑满带宽)")
            dialog.setAdapter(object : SelectDialogInterface<String?> {
                override fun click(value: String?, pos: Int) {
                    val bps = ThrottlePolicy.PRESET_BYTES_PER_SEC[pos]
                    DownloadFacade.get().setSpeedLimitBytesPerSec(bps)
                    refreshSpeed()
                }

                override fun getDisplay(name: String?): String {
                    return name ?: ""
                }
            }, SelectDialogAdapter.stringDiff, labels, defaultPos)
            dialog.show()
        }
    }

    /** 加载动画选项:默认 + assets/loading/ 下的动画文件夹(每个文件夹一个动画 + config.json) */
    private fun initLoadingAnimSetting() {
        val files = LoadingAnim.getAvailableAnimFiles()
        val display = ArrayList<String>()
        for (f in files) display.add(LoadingAnim.displayName(f))
        val refresh = {
            mBinding.tvLoadingAnim.text = LoadingAnim.displayName(LoadingAnim.getAnimName())
        }
        refresh()
        mBinding.llLoadingAnim.setOnClickListener {
            FastClickCheckUtil.check(it)
            // 当前选中项定位到选项列表(找不到默认第0项)
            var defaultPos = 0
            val cur = LoadingAnim.getAnimName()
            for (i in files.indices) {
                if (files[i] == cur || LoadingAnim.displayName(files[i]) == LoadingAnim.displayName(cur)) {
                    defaultPos = i
                    break
                }
            }
            // 切换前的动画名:弹窗关闭后若有变化,与主题切换一致,重启主页立即生效
            val oldAnim = LoadingAnim.getAnimName()
            val dialog = SelectDialog<String>(this@SettingActivity)
            dialog.setTip("选择加载动画（长按预览）")
            dialog.setAdapter(object : SelectDialogInterface<String?> {
                override fun click(value: String?, pos: Int) {
                    // 存动画文件夹名:默认存空串(回退默认),其余存文件夹名
                    val selected = files[pos]
                    SystemConfig.setLoadingAnim(if (selected == LoadingAnim.DEFAULT_NAME) "" else selected)
                    refresh()
                }

                override fun getDisplay(name: String?): String {
                    return name ?: ""
                }
            }, SelectDialogAdapter.stringDiff, display, defaultPos)
            dialog.setOnItemLongClickListener { _, pos, _ ->
                if (pos !in files.indices) return@setOnItemLongClickListener false
                LoadingAnimPreviewDialog.show(this@SettingActivity, files[pos], display[pos])
                true
            }
            // 与主题颜色切换同一套"重启"逻辑:值有变化时带缓存配置重载主页,立即生效,不再提示"下次启动生效"
            dialog.setOnDismissListener { dialog1: DialogInterface? ->
                if (oldAnim != LoadingAnim.getAnimName()) {
                    val bundle = Bundle()
                    bundle.putBoolean(IntentKey.CACHE_CONFIG_CHANGED, true)
                    SystemConfig.markInternalRestart()
                    jumpActivity(MainActivity::class.java, bundle)
                }
            }
            dialog.show()
        }
    }

    override fun onBackPressed() {
        if (homeRec != SystemConfig.getHomeRec() || dnsOpt != SystemConfig.getDohUrl()
        ) { // 首页类型/dns/doh 有更改,需重载页面(直播源已移到订阅管理页,不在这里比)
            val bundle = Bundle()
            bundle.putBoolean(IntentKey.CACHE_CONFIG_CHANGED, true)
            SystemConfig.markInternalRestart()
            jumpActivity(MainActivity::class.java, bundle)
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCacheSize()
        mBinding.tvBackgroundPlayType.text = BackgroundPlaySettings.currentLabel()
        // 背景图设置页返回后刷新取值(默认/自定义)
        if (inited) {
            updatePageBackgroundValue()
            updatePageBackgroundVisibility()
            updateThemeValue()
        }
    }

    // ------------------------------------------------------------------
    // 主题颜色:弹窗(列表 + 自定义入口) + 编辑页 + 关闭弹窗后重启生效
    // ------------------------------------------------------------------

    /** 设置页那一行显示的当前主题名(内置亮/暗显示"浅色/深色",自定义显示主题名) */
    private fun updateThemeValue() {
        mBinding.tvTheme.text = ThemeStore.activeDisplayName()
    }

    /**
     * 主题颜色弹窗。<b>一切改动都只在弹窗关闭后一次性提交并重启生效</b>(用户口径):
     * 主题切换本身要重启 App,提前生效会看到"弹窗还开着、界面已经变了"的半生效状态;
     * 删除正在使用的主题更是必须等选中项一起落定。
     */
    private fun showThemePicker() {
        val dialog = ThemePickerDialog(this)
        dialog.setListener(object : ThemePickerDialog.Listener {
            override fun onEditTheme(def: com.github.tvbox.osc.bean.theme.ThemeDef) {
                startThemeEditor(def.id)
            }

            override fun onCreateTheme() {
                startThemeEditor("")
            }
        })
        dialog.setOnDismissListener {
            val changed = dialog.commitIfDirty()
            if (changed || themeEditedWhileOpen) {
                themeEditedWhileOpen = false
                updateThemeValue()
                applyThemeAndRestart()
            }
        }
        dialog.show()
        themeDialog = dialog
    }

    /** 进主题编辑页(带结果返回:保存/删除过就要重新提交并重启) */
    private fun startThemeEditor(themeId: String) {
        val intent = Intent(this, ThemeEditorActivity::class.java)
        intent.putExtra(ThemeEditorActivity.EXTRA_THEME_ID, themeId)
        startActivityForResult(intent, REQ_THEME_EDITOR)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_THEME_EDITOR) return
        updateThemeValue()
        // 编辑页保存/删除过:主题文件已经落盘,但"生效"要回到这里统一走重启。
        // 弹窗若还在(XPopup 不会因为跳 Activity 自动关)就地刷新列表;不在了就重新弹一次 ——
        // 用户口径是"保存后返回上级页面,主题弹窗还在,新主题插在自定义入口前面"。
        if (resultCode == RESULT_OK) themeEditedWhileOpen = true
        val popup = themeDialog
        if (popup != null && popup.isShow) {
            popup.refreshList()
        } else if (resultCode == RESULT_OK) {
            showThemePicker()
        }
    }

    /**
     * 生效:**真重启进程**。
     *
     * <p>以前这里是"带标志重载主页"({@code jumpActivity(MainActivity)}),只在**内置浅色/深色**之间切换时才够用 ——
     * 那条链路靠 AppCompat 夜间模式重建 Activity。而**自定义主题**靠的是运行时换肤层:
     * 布局里的 {@code @color} 是 inflate 那一刻定下的,进程和所有已建视图都还在时,
     * 光"重载主页"只会让页面继续按旧调色板画 —— 用户口径就是
     * "我改了主题、App 也重启了,卡片和弹窗一点变化都没有"(把页面底色改成大红也不变)。
     * 所以这里改成真重启,与弹窗文案「关闭后会重启应用生效」一致。
     *
     * <p>配置不会丢:选择关系在 {@link ThemePickerDialog#commitIfDirty()} 里已**同步落盘**
     * ({@code PrefsDataStore} 的写是阻塞式),重启后按新配置解析(见 {@code ThemeRuntime.install})。
     * 主题没有改订阅，只给这次真重启标记一次配置缓存快速路径，避免主页额外等待配置网络拉取。
     */
    private fun applyThemeAndRestart() {
        Utils.initTheme()
        SystemConfig.markInternalRestart()
        SystemConfig.markThemeRestartUseCache()
        AppUtils.relaunchApp(true)
    }

    /**
     * 调试包专用(每次进程一次):把"这次到底解析成了什么配色"直接摆出来,省去翻日志 ——
     * 内容包括生效主题名、面的实际 ARGB、浮层的实际 ARGB,以及**面/浮层 drawable 能否按主题重建**。
     * 有了这三项就能立刻分清:"没选中自定义主题" / "派生出的透明度不对" / "drawable 重建通道没通"。
     */
    private fun showThemeProbeOnce() {
        // 用户口径:"主题自检先移除" —— 自检提示已停用(保留空实现,免得 removed 掉调用点后
        // 有人又照旧文档去找这个弹窗)。要临时再开,把下面这段恢复即可:
        //   按 debuggable 判断 → 打印 主题名 / 面 / 浮层 / 重建 / 包装 / 文本 / 圆角 / 主题文件里的值
    }

    /** 背景图取值:默认(跟随主题) / 自定义(用户自己设过,含显式纯色) */
    private fun updatePageBackgroundValue() {
        mBinding.tvPageBackground.text =
            if (SystemConfig.isPageBackgroundUserSet()) "自定义" else "默认"
    }

    /**
     * 背景图入口只对内置主题(含目录预设)显示:用户主题自带背景(在主题编辑页设),
     * 生效时盖过全局背景图,全局入口留着只会和主题自己的背景打架。
     */
    private fun updatePageBackgroundVisibility() {
        val active = ThemeStore.resolveActive()
        mBinding.llPageBackground.visibility =
            if (active != null && ThemeStore.isUserTheme(active.id)) View.GONE else View.VISIBLE
    }

    private fun refreshCacheSize() {
        val epoch = ++cacheSizeEpoch
        mBinding.tvCacheSize.text = "计算中…"
        val catalog = CacheCatalog(applicationContext)
        val pageRef = WeakReference(this)
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            val size = runCatching { catalog.scan() }
            Handler(Looper.getMainLooper()).post {
                val page = pageRef.get() ?: return@post
                if (epoch == page.cacheSizeEpoch && !page.isFinishing && !page.isDestroyed) {
                    page.mBinding.tvCacheSize.text = size.fold(
                        { if (it.logSizeUnavailable) "部分读取失败" else CacheSizeText.format(it.totalBytes) },
                        { "读取失败" }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        cacheSizeEpoch++
        super.onDestroy()
    }

    private fun getHomeRecName(type: Int): String {
        return when (type) {
            0 -> "豆瓣热播"
            1 -> "站点推荐"
            else -> "关闭"
        }
    }

    /** 设置项说明:横排设置行塞不下长说明,统一放 tip(观感与「订阅提示」一致),由标题长按调出 */
    private fun showSettingTip(title: String, contentRes: Int) {
        XPopup.Builder(this)
            .asCustom(TextTipDialog(this, title, getString(contentRes)))
            .show()
    }

    private fun openLanServicePage() {
        jumpActivity(LanServiceActivity::class.java)
    }

}

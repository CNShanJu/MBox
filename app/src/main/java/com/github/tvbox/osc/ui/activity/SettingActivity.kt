package com.github.tvbox.osc.ui.activity

import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.DiffUtil
import com.github.tvbox.osc.log.LogConfig
import com.github.tvbox.osc.player.PlayerTrackHelper
import com.github.tvbox.osc.player.api.IjkCodecConfigProviders
import com.github.tvbox.osc.player.api.PlayConfig
import com.github.tvbox.osc.util.AppBubble
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
import com.github.tvbox.osc.ui.dialog.LiveApiDialog
import com.github.tvbox.osc.ui.dialog.SelectDialog
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.FileUtils
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.LoadingAnim
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.PlayerHelper
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.util.Utils
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.lxj.xpopup.XPopup
import okhttp3.HttpUrl
import java.io.File

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
class SettingActivity : BaseVbActivity<ActivitySettingBinding>() {

    private var homeRec = SystemConfig.getHomeRec()
    private var dnsOpt = SystemConfig.getDohUrl()
    private var currentLiveApi = SystemConfig.getLiveUrl()

    /** init() 是否已跑完(onResume 刷新显示前要确认控件已就绪) */
    private var inited = false

    /** 设置操作业务日志:写结构化业务日志(SYSTEM) */
    private fun biz(msg: String) {
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "设置: " + msg)
    }

    override fun init() {

        mBinding.titleBar.leftView.setOnClickListener { onBackPressed() }
        mBinding.tvMediaCodec.text = PlayConfig.getIjkCodec()

        // 下载设置:仅WiFi / 并发数 / 保存位置(与下载页标题栏齿轮共用 DownloadConfig,单一事实源)
        initDownloadSettings()
        // 加载动画:默认 / Glowing Fish(全局 LoadSir 加载动画,播放器与下载不受影响)
        initLoadingAnimSetting()

        mBinding.tvDns.text = OkGoHelper.dnsHttpsList[SystemConfig.getDohUrl()]
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
            biz(if (newConfig) "开启无痕浏览" else "关闭无痕浏览")
        }

        // 局域网服务开关(默认关闭):关闭时 HTTP 服务仅监听 127.0.0.1(订阅/本地播放/代理不受影响);
        // 开启后局域网设备可访问 web 控制台与文件共享,管理型请求需携带进程令牌(见 RemoteServer)。
        val lanEnabled = SystemConfig.isLanServerEnabled()
        mBinding.switchLanServer.setChecked(lanEnabled)
        updateLanServerDesc(lanEnabled)
        mBinding.llLanServer.setOnClickListener { view: View? ->
            FastClickCheckUtil.check(view)
            val newVal = !SystemConfig.isLanServerEnabled()
            mBinding.switchLanServer.setChecked(newVal)
            SystemConfig.setLanServerEnabled(newVal)
            updateLanServerDesc(newVal)
            biz(if (newVal) "开启局域网服务(重启后生效)" else "关闭局域网服务(仅本机)")
            AppBubble.toast(
                if (newVal) "已开启局域网服务,重启应用后生效" else "已关闭局域网服务(仅本机),重启应用后生效"
            )
        }

        // 忽略证书错误(默认关闭,会降低 TLS 安全性):个别自签名/证书异常站点打不开时再开启;
        // WebView 即时生效,网络请求(OkHttp)在应用重启后按开关重建客户端时生效。
        val ignoreSsl = SystemConfig.isIgnoreSslError()
        mBinding.switchIgnoreSsl.setChecked(ignoreSsl)
        updateIgnoreSslDesc(ignoreSsl)
        mBinding.llIgnoreSsl.setOnClickListener { view: View? ->
            FastClickCheckUtil.check(view)
            val newVal = !SystemConfig.isIgnoreSslError()
            mBinding.switchIgnoreSsl.setChecked(newVal)
            SystemConfig.setIgnoreSslError(newVal)
            updateIgnoreSslDesc(newVal)
            biz(if (newVal) "开启忽略证书错误(重启网络重建后对 OkHttp 生效)" else "关闭忽略证书错误")
            AppBubble.toast(
                if (newVal) "已开启忽略证书错误(仅用于个别自签名站点)" else "已关闭忽略证书错误(恢复证书校验)"
            )
        }

        mBinding.llLiveApi.setOnClickListener {
            XPopup.Builder(mContext)
                .autoFocusEditText(false)
                .asCustom(LiveApiDialog(this))
                .show()
        }

        val defaultBgPlayTypePos = PlayConfig.getBackgroundPlayType()
        val bgPlayTypes = ArrayList<String>()
        bgPlayTypes.add("关闭")
        bgPlayTypes.add("开启")
        bgPlayTypes.add("画中画")
        mBinding.tvBackgroundPlayType.text = bgPlayTypes[defaultBgPlayTypePos]
        mBinding.llBackgroundPlay.setOnClickListener { view: View? ->
            FastClickCheckUtil.check(view)
            val dialog = SelectDialog<String>(this@SettingActivity)
            dialog.setTip("请选择")
            dialog.setAdapter(object : SelectDialogInterface<String?> {
                override fun click(value: String?, pos: Int) {
                    mBinding.tvBackgroundPlayType.text = value
                    PlayConfig.setBackgroundPlayType(pos)
                    // 后台播放=开启:Android 13+ 需通知权限,通知栏才有播放控制/关闭按钮
                    if (pos == 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && !XXPermissions.isGranted(this@SettingActivity, Permission.NOTIFICATION_SERVICE)
                    ) {
                        XXPermissions.with(this@SettingActivity)
                            .permission(Permission.NOTIFICATION_SERVICE)
                            .request(object : OnPermissionCallback {
                                override fun onGranted(permissions: List<String>, all: Boolean) {
                                    AppBubble.toast("后台播放通知已开启")
                                }

                                override fun onDenied(permissions: List<String>, never: Boolean) {
                                    AppBubble.toast("未授予通知权限,后台播放时通知栏将不可见")
                                }
                            })
                    }
                }

                override fun getDisplay(name: String?): String {
                    return name?:""
                }
            },SelectDialogAdapter.stringDiff, bgPlayTypes, defaultBgPlayTypePos)
            dialog.show()
        }

        mBinding.tvSpeed.text = PlayConfig.getVideoSpeed().toString()
        mBinding.llPressSpeed.setOnClickListener {
            val types = ArrayList<String>()
            types.add("2.0")
            types.add("3.0")
            types.add("4.0")
            types.add("5.0")
            types.add("6.0")
            types.add("8.0")
            types.add("10.0")
            val defaultPos = types.indexOf(PlayConfig.getVideoSpeed().toString())
            val dialog = SelectDialog<String>(this@SettingActivity)
            dialog.setTip("请选择")
            dialog.setAdapter(object : SelectDialogInterface<String?> {
                override fun click(value: String?, pos: Int) {
                    PlayConfig.setVideoSpeed(value?.toFloat() ?: 2.0f)
                    biz("播放倍速: " + (value ?: "2.0"))
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
                                AppBubble.toastLong("获取存储权限失败,请在系统设置中开启")
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
                    biz("安全DNS: " + OkGoHelper.dnsHttpsList[pos])
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
                    biz("IJK解码: " + (value?.name ?: "未知"))
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
                    biz("画面缩放: " + PlayerHelper.getScaleName(value ?: 0))
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
                    biz("默认播放器: " + PlayerHelper.getPlayerName(thisPlayerType))
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
                    biz("渲染方式: " + PlayerHelper.getRenderName(value ?: 0))
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
                    biz("主页内容: " + getHomeRecName(value ?: 0))
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
                    biz("保留历史数量: " + HistoryHelper.getHistoryNumName(value ?: 0))
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
            com.github.tvbox.osc.ui.dialog.ConfirmDialog.show(this, "提示", "确定清空缓存吗？", "清空", {
                onClickClearCache(view)
            })
        }
        // 启动时自动检查更新(默认开):开关只记配置,触发时机见 HomeFragment(上次看到气泡消失后)
        mBinding.switchAutoCheckUpdate.setChecked(SystemConfig.isAutoCheckUpdate())
        mBinding.llAutoCheckUpdate.setOnClickListener { view: View? ->
            FastClickCheckUtil.check(view)
            val newVal = !SystemConfig.isAutoCheckUpdate()
            SystemConfig.setAutoCheckUpdate(newVal)
            mBinding.switchAutoCheckUpdate.setChecked(newVal)
            biz("启动自动检查更新: " + if (newVal) "开" else "关")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            mBinding.llTheme.visibility = View.GONE
        }
        val oldTheme = SystemConfig.getTheme()
        val themes = arrayOf("跟随系统", "浅色", "深色")
        mBinding.tvTheme.text = themes[oldTheme]
        mBinding.llTheme.setOnClickListener(View.OnClickListener { view: View? ->
            FastClickCheckUtil.check(view)
            val types = ArrayList<Int>()
            types.add(0)
            types.add(1)
            types.add(2)
            val dialog = SelectDialog<Int>(this@SettingActivity)
            dialog.setTip("请选择")
            dialog.setAdapter(object : SelectDialogInterface<Int?> {
                override fun click(value: Int?, pos: Int) {
                    mBinding.tvTheme.text = themes[value?:0]
                    SystemConfig.setTheme(value ?: 0)
                    biz("主题: " + themes[value ?: 0])
                }

                override fun getDisplay(value: Int?): String {
                    return themes[value?:0]
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, types, oldTheme)
            dialog.setOnDismissListener { dialog1: DialogInterface? ->
                if (oldTheme != SystemConfig.getTheme()) {
                    Utils.initTheme()
                    val bundle = Bundle()
                    bundle.putBoolean(IntentKey.CACHE_CONFIG_CHANGED, true)
                    jumpActivity(MainActivity::class.java, bundle)
                }
            }
            dialog.show()
        })

        // 背景图设置(二级页):展示当前背景图,支持换图/拖动缩放位置/调遮罩透明度/恢复默认
        updatePageBackgroundValue()
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
            biz(if (newConfig) "开启净化视频(免广告)" else "关闭净化视频")
        }
        mBinding.switchIjkCachePlay.setChecked(PlayConfig.isIjkCachePlay())
        mBinding.llIjkCachePlay.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val newConfig = !PlayConfig.isIjkCachePlay()
            mBinding.switchIjkCachePlay.setChecked(newConfig)
            PlayConfig.setIjkCachePlay(newConfig)
            biz(if (newConfig) "开启IJK边缓存边播" else "关闭IJK缓存播放")
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
            biz("下载仅WiFi: " + if (newVal) "开启" else "关闭")
            AppBubble.toast("仅 Wi-Fi 下载已" + if (newVal) "开启" else "关闭")
        }
        // 同时下载任务数(1-5)
        val refreshConcurrent = {
            mBinding.tvDlConcurrent.text = DownloadFacade.get().getMaxConcurrent().toString() + " 个"
        }
        refreshConcurrent()
        mBinding.llDlConcurrent.setOnClickListener {
            FastClickCheckUtil.check(it)
            val types = ArrayList<String>()
            for (i in 1..5) types.add("并发 " + i)
            val defaultPos = DownloadFacade.get().getMaxConcurrent() - 1
            val dialog = SelectDialog<String>(this@SettingActivity)
            dialog.setTip("选择同时下载任务数")
            dialog.setAdapter(object : SelectDialogInterface<String?> {
                override fun click(value: String?, pos: Int) {
                    DownloadFacade.get().setMaxConcurrent(pos + 1)
                    biz("同时下载任务数: " + (pos + 1))
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
                    biz("下载限速: " + ThrottlePolicy.label(bps))
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
            dialog.setTip("选择加载动画")
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
            // 与主题颜色切换同一套"重启"逻辑:值有变化时带缓存配置重载主页,立即生效,不再提示"下次启动生效"
            dialog.setOnDismissListener { dialog1: DialogInterface? ->
                if (oldAnim != LoadingAnim.getAnimName()) {
                    val bundle = Bundle()
                    bundle.putBoolean(IntentKey.CACHE_CONFIG_CHANGED, true)
                    jumpActivity(MainActivity::class.java, bundle)
                }
            }
            dialog.show()
        }
    }

    override fun onBackPressed() {
        if (homeRec != SystemConfig.getHomeRec() || dnsOpt != SystemConfig.getDohUrl()
            || currentLiveApi != SystemConfig.getLiveUrl()
        ) { // 首页类型/dns/doh/直播源有更改,需重载页面
            //AppManager.getInstance().finishAllActivity()
            if (currentLiveApi == SystemConfig.getLiveUrl()) { //未更改直播源,不需重载api等
                val bundle = Bundle()
                bundle.putBoolean(IntentKey.CACHE_CONFIG_CHANGED, true)
                jumpActivity(MainActivity::class.java, bundle)
            } else {
                jumpActivity(MainActivity::class.java)
            }
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        // 背景图设置页返回后刷新取值(默认/自定义)
        if (inited) updatePageBackgroundValue()
    }

    /** 背景图取值:默认(跟随主题) / 自定义(用户自己设过,含显式纯色) */
    private fun updatePageBackgroundValue() {
        mBinding.tvPageBackground.text =
            if (SystemConfig.isPageBackgroundUserSet()) "自定义" else "默认"
    }

    private fun onClickClearCache(v: View) {
        FastClickCheckUtil.check(v)
        val cachePath = FileUtils.getCachePath()
        val cacheDir = File(cachePath)
        if (!cacheDir.exists()) return
        Thread {
            try {
                FileUtils.cleanDirectory(cacheDir)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
        AppBubble.toastLong("缓存已清空")
    }

    private fun getHomeRecName(type: Int): String {
        return when (type) {
            0 -> "豆瓣热播"
            1 -> "站点推荐"
            else -> "关闭"
        }
    }

    /** 局域网服务描述行:开关状态一目了然(开启需重启应用生效) */
    private fun updateLanServerDesc(enabled: Boolean) {
        mBinding.tvLanServerDesc.text = if (enabled) "局域网可访问" else "仅本机"
    }

    /** 忽略证书错误描述行 */
    private fun updateIgnoreSslDesc(enabled: Boolean) {
        mBinding.tvIgnoreSslDesc.text = if (enabled) "已忽略(不安全)" else "校验证书"
    }
}

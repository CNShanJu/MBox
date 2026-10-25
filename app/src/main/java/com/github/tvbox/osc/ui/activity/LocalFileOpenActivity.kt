package com.github.tvbox.osc.ui.activity

import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.bean.theme.ThemeDef
import com.github.tvbox.osc.transfer.LocalBackupRepository
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter
import com.github.tvbox.osc.ui.dialog.ConfirmDialog
import com.github.tvbox.osc.ui.dialog.SelectDialog
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.google.gson.JsonParser
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.interfaces.SimpleCallback
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 本地文件「打开方式」的统一入口(txt / json / zip)。
 *
 * <p><b>为什么需要这一层</b>:系统把「用 MBox 打开」的 Intent 直接给到某个页面,而内容 URI 的读权限
 * 只挂在**收到 Intent 的这个页面**上 —— 本页一 finish,下游页(订阅管理 / 主题编辑)再去读就
 * `SecurityException`。所以本页先在自己的生命周期内把文件落成应用缓存里的**普通文件**,
 * 再把路径交给下游页;还原备份直接在本页做完(它本来就是"读一份 ZIP 落盘再覆盖"的流程)。
 *
 * <p><b>怎么分流</b>(与用户口径一致):
 * <ul>
 *   <li>{@code .txt} → 订阅源导入(订阅管理页直接吃到导入结果);</li>
 *   <li>{@code .json} → 主题包(带 {@code kind=mbox-theme})进主题导入,其余进订阅源导入;</li>
 *   <li>{@code .zip} → 弹动作列表:订阅源导入 / 还原备份 / 导入主题(压缩包内容看不出来,交给用户选)。</li>
 * </ul>
 */
class LocalFileOpenActivity : BaseActivity() {

    companion object {
        /**
         * 待导入文件的绝对路径(应用缓存里的副本)。
         * 订阅管理页 / 主题编辑页都读这个 extra;传路径而不是 URI 是为了绕开上面说的授权生命周期问题。
         */
        const val EXTRA_PENDING_FILE = "com.github.tvbox.osc.extra.PENDING_FILE"

        /** 待导入文件的原始显示名(用于订阅名 / 批次名;副本名可能被清洗过) */
        const val EXTRA_PENDING_NAME = "com.github.tvbox.osc.extra.PENDING_NAME"

        /** 缓存里放待导入副本的目录(每次进入先清空,不留上一轮残留) */
        private const val STAGE_DIR = "open_with"

        /** 压缩包上限(与订阅清单包的 64 MB 对齐),主题包远小于此 */
        private const val MAX_STAGE_BYTES = 64L * 1024 * 1024

        /** 判定"是不是主题包"只需读头部;主题 JSON 正常只有几 KB */
        private const val MAX_THEME_PROBE_BYTES = 1024 * 1024
    }

    private lateinit var progress: ProgressBar
    private lateinit var hint: TextView

    /** 动作列表里已经选了某一项(选完还要接着走确认/落盘,页面不能跟着弹窗一起关) */
    private var actionChosen = false
    /** 还原已经在跑(确认弹窗的关闭回调据此判断"是被取消还是已经开跑") */
    private var restoreStarted = false

    override fun getLayoutResID(): Int = R.layout.activity_local_file_open

    /** 外部「打开方式」可在冷进程里直接建页,由 BaseActivity 标成正常启动 */
    protected override fun allowDirectColdStart(): Boolean = intent?.data != null

    protected override fun init() {
        progress = findViewById(R.id.progress)
        hint = findViewById(R.id.tv_hint)

        val source = intent?.data
        if (source == null) {
            AppBubble.toast("没有拿到要打开的文件")
            finish()
            return
        }
        val name = displayNameOf(source)
        when (extensionOf(name)) {
            "txt" -> stageThenImport(source, name) { file -> openSubscriptionImport(file, name) }
            "json" -> stageThenImport(source, name) { file -> routeJson(file, name) }
            "zip" -> chooseZipAction(source, name)
            else -> {
                AppBubble.toast("暂不支持这种文件")
                finish()
            }
        }
    }

    // ------------------------------------------------------------------
    // 分流:json / txt
    // ------------------------------------------------------------------

    /**
     * 落盘后按内容分流 JSON:主题包(导出时写了 {@code kind=mbox-theme})进主题编辑页,
     * 其余一律按订阅源处理 —— 用户从文件管理器点开的 JSON 绝大多数就是订阅配置,多弹一层只是多一次点击。
     */
    private fun routeJson(file: File, name: String) {
        if (looksLikeTheme(file)) openThemeImport(file) else openSubscriptionImport(file, name)
    }

    /** 只认导出时写入的 kind 标记,不靠文件名/字段猜(误判会把订阅配置送进主题编辑页) */
    private fun looksLikeTheme(file: File): Boolean {
        if (file.length() > MAX_THEME_PROBE_BYTES) return false
        return try {
            val root = JsonParser.parseString(file.readText(Charsets.UTF_8))
            root.isJsonObject && ThemeDef.KIND == root.asJsonObject.get("kind")?.asString
        } catch (t: Throwable) {
            false
        }
    }

    /** 交给订阅管理页:页面就绪后调 importLocalDoc(和"订阅 → 本地导入"同一条链路) */
    private fun openSubscriptionImport(file: File, name: String) {
        startActivity(
            android.content.Intent(this, SubscriptionActivity::class.java)
                .putExtra(EXTRA_PENDING_FILE, file.absolutePath)
                .putExtra(EXTRA_PENDING_NAME, name)
        )
        finish()
    }

    /** 交给主题编辑页:导入结果先落成草稿,用户还能检查/改名再保存(与页内「导入主题」一致) */
    private fun openThemeImport(file: File) {
        startActivity(
            android.content.Intent(this, ThemeEditorActivity::class.java)
                .putExtra(EXTRA_PENDING_FILE, file.absolutePath)
        )
        finish()
    }

    // ------------------------------------------------------------------
    // 分流:zip(同一个压缩包可能是订阅清单 / 备份 / 主题包,交给用户选)
    // ------------------------------------------------------------------

    private fun chooseZipAction(source: Uri, name: String) {
        progress.visibility = View.GONE
        hint.setText(R.string.local_open_choose)
        val actions = listOf(
            getString(R.string.local_open_action_subscribe),
            getString(R.string.local_open_action_restore),
            getString(R.string.local_open_action_theme)
        )
        val dialog = SelectDialog<String>(this)
        dialog.setTip(getString(R.string.local_open_tip_zip))
        dialog.setAdapter(object : SelectDialogAdapter.SelectDialogInterface<String?> {
            override fun click(value: String?, pos: Int) {
                // 先置位再 dismiss:关闭回调据此判断"用户已经选了动作",不把本页收掉
                actionChosen = true
                dialog.dismiss()
                when (pos) {
                    0 -> stageThenImport(source, name) { file -> openSubscriptionImport(file, name) }
                    1 -> confirmRestore(source)
                    else -> stageThenImport(source, name) { file -> openThemeImport(file) }
                }
            }

            override fun getDisplay(value: String?): String = value ?: ""
        }, SelectDialogAdapter.stringDiff, actions, -1)
        // 直接返回 = 什么都不做,别留一个空页面
        dialog.setOnDismissListener { hint.post { finishIfIdle() } }
        dialog.show()
    }

    /**
     * 还原备份在本页做完:内容 URI 的读权限只在本页有效,而还原链路第一步就是把 ZIP 读到缓存,
     * 正好落在授权有效期内;同时省掉"再复制一份交给下游页"的开销。
     */
    private fun confirmRestore(source: Uri) {
        val popup = ConfirmDialog(this, "还原备份",
            "将用所选 ZIP 覆盖本机设置、订阅源文件和历史记录，不会覆盖日志。" +
                "恢复前会自动保留当前数据供失败时回滚；恢复成功后应用会自动重启。确认还原吗？",
            "开始还原", { startRestore(source) }, false)
        // XPopup 2.10 的 asCustom() 返回的是 BasePopupView(不是 Builder)，所以回调要先设:
        // 链在 asCustom 后面在 Kotlin 里解析不到 setPopupCallback。
        val builder = XPopup.Builder(this)
            .setPopupCallback(object : SimpleCallback() {
                // onConfirm 与 dismiss 谁先谁后由弹窗内部决定:推迟到本次点击结束再判断,
                // 已经在还原就不要把页面关掉;被取消则退出本页(没有别的可做)
                override fun onDismiss(popupView: BasePopupView) {
                    hint.post { if (!restoreStarted && !isFinishing && !isDestroyed) finish() }
                }
            })
        builder.asCustom(popup).show()
    }

    private fun startRestore(source: Uri) {
        restoreStarted = true
        showBusy(R.string.local_open_restoring)
        val repository = LocalBackupRepository(applicationContext)
        HeavyTaskUtil.getSerialExecutorService().execute {
            var restored: String? = null
            var error: String? = null
            try {
                restored = repository.restoreExternal(source)
            } catch (failure: Exception) {
                error = failure.message ?: failure.javaClass.simpleName
            }
            val failure = error
            val result = restored
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (failure != null) {
                    AppBubble.toast("恢复失败：" + failure)
                    finish()
                } else {
                    // 还原成功必须整进程重启(与备份弹窗同一套语义)
                    com.github.tvbox.osc.ui.dialog.BackupDialog.restartApp(result ?: "数据已恢复")
                }
            }
        }
    }

    /** 弹窗关掉后:已经有动作在走就留着页面,否则退出(外部打开不该留空页) */
    private fun finishIfIdle() {
        if (actionChosen || restoreStarted) return
        if (!isFinishing && !isDestroyed) finish()
    }

    // ------------------------------------------------------------------
    // 落盘 / 状态
    // ------------------------------------------------------------------

    /** 在当前页持有授权期间把文件读成缓存副本,成功后再交给下游页 */
    private fun stageThenImport(source: Uri, name: String, onReady: (File) -> Unit) {
        showBusy(R.string.local_open_staging)
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            var staged: File? = null
            var error: Throwable? = null
            try {
                staged = stage(source, name)
            } catch (failure: Throwable) {
                error = failure
            }
            val file = staged
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (file == null) {
                    val reason = error?.message
                    AppBubble.toast(if (reason.isNullOrBlank()) "文件读不出来，请换个文件管理器再试" else reason)
                    finish()
                } else {
                    onReady(file)
                }
            }
        }
    }

    /** 内容 URI → 缓存文件(保留扩展名,下游按扩展名分流);超过上限按失败处理 */
    @Throws(IOException::class)
    private fun stage(source: Uri, name: String): File {
        val dir = File(cacheDir, STAGE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法准备临时目录")
        // 上一轮的副本至此已无用,顺手清掉,不给缓存留垃圾
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, safeFileName(name))
        val input = contentResolver.openInputStream(source) ?: throw IOException("文件打不开")
        input.use { stream ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_STAGE_BYTES) throw IOException("文件超过 64 MB，无法导入")
                    output.write(buffer, 0, read)
                }
            }
        }
        return target
    }

    private fun showBusy(textRes: Int) {
        progress.visibility = View.VISIBLE
        hint.setText(textRes)
    }

    // ------------------------------------------------------------------
    // 文件名
    // ------------------------------------------------------------------

    /** 显示名:先问 Provider,取不到用路径末段(两者都没有时给个通用名) */
    private fun displayNameOf(source: Uri): String {
        var queried: String? = null
        try {
            contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) queried = cursor.getString(0) }
        } catch (t: Throwable) {
            queried = null
        }
        val name = queried ?: source.lastPathSegment ?: "本地文件"
        return name
    }

    /** 小写扩展名;没有扩展名返回空串(调用方按"不支持"处理) */
    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /** 副本文件名:只留安全字符,并保住扩展名(下游按扩展名分流) */
    private fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("[/\\\\:*?\"<>|\\u0000\\r\\n\\t]"), "_").trim()
        val base = if (cleaned.isEmpty()) "open_with.bin" else cleaned
        return if (base.length > 80) base.takeLast(80) else base
    }
}

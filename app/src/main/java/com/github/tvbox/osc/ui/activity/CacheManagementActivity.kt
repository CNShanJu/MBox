package com.github.tvbox.osc.ui.activity

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityCacheManagementBinding
import com.github.tvbox.osc.databinding.ItemCacheCategoryBinding
import com.github.tvbox.osc.ui.dialog.ConfirmDialog
import com.github.tvbox.osc.ui.dialog.DialogCoordinator
import com.github.tvbox.osc.ui.dialog.LoadingDialog
import com.github.tvbox.osc.ui.dialog.TextTipDialog
import com.github.tvbox.osc.ui.kit.SelectActionBar
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.github.tvbox.osc.util.cache.CacheCatalog
import com.github.tvbox.osc.util.cache.CacheSizeText
import com.lxj.xpopup.XPopup
import java.lang.ref.WeakReference

/** 可逐项清理的缓存页；目录扫描和删除都交给共享后台执行器。 */
class CacheManagementActivity : BaseVbActivity<ActivityCacheManagementBinding>() {
    private val catalog by lazy { CacheCatalog(applicationContext) }
    private val selectedIds = linkedSetOf<String>()
    private var snapshot: CacheCatalog.Snapshot? = null
    private var requestEpoch = 0
    private var busy = false
    private var selectMode = false
    private lateinit var selectAllAction: TextView
    private lateinit var deleteAction: TextView
    private lateinit var cancelAllAction: TextView
    private var logoutProgress: LoadingDialog? = null

    companion object {
        /** logout 整体超时：单个源网络卡住也不能把重启框永远堵住。 */
        private const val LOGOUT_TIMEOUT_MS = 15_000L

        /**
         * 清理凭据时是否顺带调用各源 {@code action("logout")}。
         * <p>默认关闭：logout 会把源的服务端会话一并登出（如 MDrive），副作用是 detail/播放
         * 所需的会话也失效、且不会自动重新弹二维码，导致「清理后反而播不了」。
         * 若未来要做独立的「退出登录并重新登录」入口，再把这个开关打开或迁移到那里。
         */
        private const val LOGOUT_ON_CLEAR = false
    }

    override fun init() {
        mBinding.titleBar.setOnBackClickListener { onBackPressed() }
        selectAllAction = mBinding.selectActionBar.addAction("全选", SelectActionBar.Kind.NORMAL) {
            selectAll()
        }
        deleteAction = mBinding.selectActionBar.addAction("删除", SelectActionBar.Kind.DANGER) {
            confirmClear(selectedIds.toSet())
        }
        cancelAllAction = mBinding.selectActionBar.addAction("取消全选", SelectActionBar.Kind.NORMAL) {
            cancelAllSelection()
        }
        updateSelection()
    }

    override fun onResume() {
        super.onResume()
        // A clear already computes its own post-delete snapshot. Starting a scan here would
        // invalidate its epoch and could display a pre-delete result while deletion is running.
        if (!busy) refresh()
    }

    override fun onDestroy() {
        requestEpoch++
        super.onDestroy()
    }

    private fun refresh() {
        val epoch = ++requestEpoch
        busy = true
        updateSelection()
        val cacheCatalog = catalog
        val pageRef = WeakReference(this)
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            val result = runCatching { cacheCatalog.scan() }
            Handler(Looper.getMainLooper()).post {
                val page = pageRef.get() ?: return@post
                if (!page.accept(epoch)) return@post
                page.busy = false
                result.onSuccess { page.render(it) }
                    .onFailure {
                        page.mBinding.tvTotalSize.text = "读取失败"
                        page.mBinding.tvSizeDetail.text = "请返回后重试"
                        AppBubble.toast("缓存大小读取失败")
                    }
                page.updateSelection()
            }
        }
    }

    private fun accept(epoch: Int): Boolean =
        epoch == requestEpoch && !isFinishing && !isDestroyed

    /** 可清理、且选了有意义：网盘凭据是个动作（重置网页登录），0 字节也要能点。 */
    private fun selectable(entry: CacheCatalog.Entry): Boolean =
        entry.clearable && (entry.sizeBytes > 0 || entry.id == CacheCatalog.CLOUD_CREDENTIALS)

    private fun render(data: CacheCatalog.Snapshot) {
        snapshot = data
        selectedIds.retainAll(data.entries.filter(::selectable).map { it.id }.toSet())
        mBinding.tvTotalSize.text = (if (data.logSizeUnavailable) "已知 " else "") +
            CacheSizeText.format(data.totalBytes)
        mBinding.tvSizeDetail.text = if (data.logSizeUnavailable) {
            "日志大小读取失败，未计入总量；其他分类仍可清理"
        } else if (data.totalBytes == 0L) {
            "暂无可统计的缓存和日志；下载、收藏和观看记录不计入"
        } else {
            "可清理 ${CacheSizeText.format(data.clearableBytes)} · 必要 ${CacheSizeText.format(data.protectedBytes)}"
        }
        mBinding.categoryList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (entry in data.entries) {
            val row = ItemCacheCategoryBinding.inflate(inflater, mBinding.categoryList, false)
            row.categoryTitle.text = entry.title
            row.categoryDescription.text = entry.description
            row.categorySize.text = when {
                data.logSizeUnavailable && entry.id == CacheCatalog.LOGS -> "读取失败"
                // 凭据这一项 0 字节的含义是"本地没有登录痕迹"，不是"0B 缓存"：
                // 很多源扫码是把账号绑在服务端，本地本来就不落文件，写 0 B 会被读成登录失败。
                entry.id == CacheCatalog.CLOUD_CREDENTIALS && entry.sizeBytes <= 0 -> "无本地凭据"
                else -> CacheSizeText.format(entry.sizeBytes) + if (entry.clearable) "" else " · 保留"
            }
            // 「网盘登录凭据」即使一个字节都没统计到也要可点：登录状态可能只在 WebView 里，
            // 那种情况下清理动作（重置 cookie）才是唯一的出路。
            val available = selectable(entry)
            row.categoryCheckbox.visibility = if (selectMode && available) View.VISIBLE else View.GONE
            row.categoryCheckbox.isChecked = selectedIds.contains(entry.id)
            row.root.isEnabled = !busy
            row.root.setOnClickListener {
                if (busy) return@setOnClickListener
                if (!selectMode || !available) {
                    showCategoryTip(entry)
                    return@setOnClickListener
                }
                if (!selectedIds.add(entry.id)) selectedIds.remove(entry.id)
                row.categoryCheckbox.isChecked = selectedIds.contains(entry.id)
                updateSelection()
            }
            row.root.setOnLongClickListener {
                if (busy) return@setOnLongClickListener true
                if (!available) {
                    showCategoryTip(entry)
                    return@setOnLongClickListener true
                }
                if (!selectMode) selectMode = true
                selectedIds.add(entry.id)
                render(data)
                true
            }
            mBinding.categoryList.addView(row.root, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        updateSelection()
    }

    private fun showCategoryTip(entry: CacheCatalog.Entry) {
        val content = when {
            !entry.clearable -> entry.description
            entry.id == CacheCatalog.CLOUD_CREDENTIALS -> buildString {
                append(entry.description)
                append("\n\n")
                append(entry.detail ?: "没有找到凭据文件。")
                append("\n\n长按此项进行清理，将删除本地凭据文件、重置网页登录状态，")
                append("并重建爬虫兼容库（databases/tv，不含应用自身数据），")
                append("之后相关源需要重新扫码登录。")
                append("\n\n如果这里没有本地凭据但扫码后能正常播放，说明登录态在源服务器侧——")
                append("扫码是把账号和设备绑在服务端，本地不落文件。这种情况下清理不会让它退出登录，")
                append("那属于源自己的账号绑定，App 侧清不掉。")
            }
            entry.id == CacheCatalog.TEMP_FILES && entry.sizeBytes <= 0 ->
                "导入导出工作文件通常在操作结束后自动删除；当前没有超过 24 小时的可清理副本。"
            entry.id == CacheCatalog.LOGS && entry.sizeBytes <= 0 ->
                "业务日志记录与错误日志文件按内容约大小统计。当前没有可清理的日志；新日志可能继续产生。"
            entry.sizeBytes <= 0 -> "${entry.description}\n\n当前没有可清理的磁盘缓存。"
            entry.id == CacheCatalog.LOGS ->
                "${entry.description}\n\n清理会移除业务日志记录和错误日志文件，历史排障信息无法恢复；后续新日志仍可能产生。长按此项可选择清理。"
            entry.id == CacheCatalog.TEMP_FILES ->
                "${entry.description}\n\n只清理超过 24 小时的副本。已保存到相册或选定位置的正式文件不会删除。长按此项可选择清理。"
            else -> "${entry.description}\n\n长按此项可选择清理。"
        }
        XPopup.Builder(this).asCustom(TextTipDialog(this, entry.title, content)).show()
    }

    private fun selectAll() {
        if (busy || !selectMode) return
        val available = snapshot?.entries?.filter { it.clearable && it.sizeBytes > 0 }?.map { it.id }
            ?: return
        if (available.isEmpty()) return
        selectedIds.addAll(available)
        snapshot?.let(::render)
    }

    private fun cancelAllSelection() {
        if (busy || !selectMode || selectedIds.isEmpty()) return
        selectedIds.clear()
        snapshot?.let(::render)
    }

    private fun updateSelection() {
        val count = selectedIds.size
        val bytes = snapshot?.entries?.filter { selectedIds.contains(it.id) }?.sumOf { it.sizeBytes } ?: 0L
        mBinding.tvSelection.text = if (!selectMode) "存储分类 · 长按可选择清理" else
            "已选 $count 项 · ${CacheSizeText.format(bytes)}"
        val available = snapshot?.entries?.filter { it.clearable && it.sizeBytes > 0 }?.map { it.id }
            ?: emptyList()
        mBinding.selectActionBar.visibility = if (selectMode) View.VISIBLE else View.GONE
        mBinding.selectActionBar.setActionEnabled(selectAllAction,
            !busy && available.isNotEmpty() && !selectedIds.containsAll(available))
        mBinding.selectActionBar.setActionEnabled(deleteAction, !busy && count > 0)
        mBinding.selectActionBar.setActionEnabled(cancelAllAction, !busy && count > 0)
    }

    private fun confirmClear(ids: Set<String>) {
        if (busy || ids.isEmpty()) return
        val entries = snapshot?.entries?.filter { ids.contains(it.id) && selectable(it) } ?: return
        if (entries.isEmpty()) return
        val validIds = entries.map { it.id }.toSet()
        val names = entries.joinToString("、") { "「${it.title}」" }
        val size = CacheSizeText.format(entries.sumOf { it.sizeBytes })
        val impact = buildString {
            if (validIds.contains(CacheCatalog.IMAGE_HTTP)) append("\n图片清理后会重新加载。")
            if (validIds.contains(CacheCatalog.TEMP_FILES)) append("\n旧导出文件的分享链接可能失效。")
            if (validIds.contains(CacheCatalog.PLAYBACK_CACHE))
                append("\n播放缓存清理后，已缓存的视频片段需重新缓存。")
            if (validIds.contains(CacheCatalog.CLOUD_CREDENTIALS))
                append("\n网盘登录凭据清除后，相关网盘源需要重新扫码登录，否则仍会提示无播放地址。")
            if (validIds.contains(CacheCatalog.LOGS))
                append("\n将清空业务日志记录、本应用错误日志文件及旧版日志残留，历史排障信息无法恢复；新日志仍可能继续产生。")
        }
        val sizeLabel = if (validIds.contains(CacheCatalog.LOGS)) "内容约 $size" else size
        ConfirmDialog.showDanger(this, "清除所选数据", "确定清除$names（$sizeLabel）吗？$impact", "清除", {
            clear(validIds)
        })
    }

    private fun clear(ids: Set<String>) {
        val epoch = ++requestEpoch
        busy = true
        updateSelection()
        val cacheCatalog = catalog
        val pageRef = WeakReference(this)
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            val result = runCatching { cacheCatalog.clear(ids) }
            Handler(Looper.getMainLooper()).post {
                val page = pageRef.get() ?: return@post
                if (!page.accept(epoch)) return@post
                page.busy = false
                result.onSuccess {
                    page.selectedIds.removeAll(it.clearedIds.toSet())
                    if (it.failures.isEmpty()) page.selectMode = false
                    page.render(it.after)
                    AppBubble.toast(if (it.failures.isEmpty()) "所选数据已清除" else "部分数据清除失败，请重试")
                    if (it.clearedIds.contains(CacheCatalog.CLOUD_CREDENTIALS)) {
                        if (LOGOUT_ON_CLEAR) {
                            page.logoutThenAskRestart()
                        } else {
                            page.askRestartAfterClear()
                        }
                    }
                }.onFailure {
                    AppBubble.toast("清除失败，请重试")
                    page.refresh()
                }
                page.updateSelection()
            }
        }
    }

    /**
     * 清完凭据后：先在后台把每个源的 logout 跑完（带超时），再把结果连同重启确认一起呈现。
     * <p>顺序必须是「logout 完成 → 弹重启框」：如果先弹框、用户一点重启就 {@code System.exit}，
     * 后台 logout 任务会被进程终结打断，日志与效果都看不到（上一版就是这样）。
     */
    /**
     * 清完凭据后：先弹可见的加载框，再在后台把每个源的 logout 跑完（带超时），
     * 完成后收起加载框、把结果连同重启确认一起呈现。
     * <p>顺序必须是「logout 完成 → 弹重启框」：如果先弹框、用户一点重启就 {@code System.exit}，
     * 后台 logout 任务会被进程终结打断，日志与效果都看不到（上一版就是这样）。
     * <p>logout 在后台可能要等十几秒，不能只让用户干等——前台挂一个可见的加载框。
     */
    private fun logoutThenAskRestart() {
        // 先在主线程弹可见加载框，让用户知道正在做事
        val hint = "正在向各源请求退出登录…"
        Handler(Looper.getMainLooper()).post {
            val page = this
            if (page.isFinishing || page.isDestroyed) return@post
            val dialog = DialogCoordinator.loading(page) as? LoadingDialog
            if (dialog != null) {
                dialog.setHint(hint)
                if (!dialog.isShow()) dialog.show()
                page.logoutProgress = dialog
            }
        }
        val pageRef = WeakReference(this)
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            // logout 可能碰上网卡住的 jar：整体加超时，避免一直挂住、弹不出重启框。
            val summary = try {
                java.util.concurrent.CompletableFuture.supplyAsync(
                    { tryLogoutAllSources() },
                    HeavyTaskUtil.getBigTaskExecutorService()
                ).get(LOGOUT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                android.util.Log.w("MBoxCredential", "logout 超时或执行失败", t)
                "logout 未在 ${LOGOUT_TIMEOUT_MS / 1000}s 内完成（可能有源卡住），请重启后查看日志"
            }
            Handler(Looper.getMainLooper()).post {
                val page = pageRef.get() ?: return@post
                if (page.isFinishing || page.isDestroyed) return@post
                page.logoutProgress?.let { if (it.isShow()) it.dismiss() }
                page.logoutProgress = null
                page.askRestartForLogout(summary)
            }
        }
    }

    /**
     * 清完凭据后的默认路径：只提示重启，不调 logout。
     * <p>清理只删本地落盘的凭证文件，不影响源的服务端会话，正常播放不受影响。
     * 需要重启进程，是因为爬虫插件把「已弹过登录框 / 登录中」这类状态放在自己的静态字段里，
     * 而承载它的 DexClassLoader 在整个进程内被复用，只有新进程才能复位。
     */
    private fun askRestartAfterClear() {
        ConfirmDialog.show(this, "凭据已清除",
            "本地保存的登录凭证已清除，需要重启应用使变更生效。\n\n现在重启应用？",
            "现在重启", { restartProcess() })
    }

    /**
     * 清完凭据还不够：爬虫插件把「已弹过登录框 / 登录中」这类状态放在自己的静态字段里，
     * 而承载它的 DexClassLoader 在整个进程内被复用（JarLoader 有意不清，否则插件原生库会被
     * 二次 System.load 而被 ART 拒绝）。所以重进源、换线路、CLEAR_TASK 重载界面都不会复位它，
     * 只有新进程会 —— 这就是"清了缓存、重开了 App，源还是不弹二维码"的真正原因。
     */
    private fun askRestartForLogout(logoutSummary: String) {
        ConfirmDialog.show(this, "凭据已清除",
            "扫码登录状态还留在爬虫插件的进程内存里，不重启进程的话源仍然不会弹二维码。" +
                "\n\n服务端退出登录：$logoutSummary" +
                "\n\n现在重启应用？",
            "现在重启", { restartProcess() })
    }

    /**
     * 尝试让每个源服务端的登录态失效，并返回一行可展示的结果文本。
     * <p>本地删凭据只清得掉「落盘」的那类源；服务端设备绑定型源（本地不落 token）只有调
     * jar 的 {@code action("logout")} 一条路。jar 未实现该动作会返回 null，静默跳过。
     * <p>必须<b>同步等它跑完</b>再重启：之前是「提交后台任务 → 立刻弹重启框 → 一点就
     * {@code System.exit}」，任务还没被线程池调度进程就被杀了，日志一个字都打不出来。
     */
    private fun tryLogoutAllSources(): String {
        android.util.Log.i("MBoxCredential", "开始尝试 logout")
        val sources = try {
            com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().sourceBeanList
        } catch (t: Throwable) {
            android.util.Log.w("MBoxCredential", "读取源列表失败", t)
            return "读取源列表失败，未尝试 logout"
        }
        var hit = 0
        for (sb in sources) {
            val key = sb.key ?: continue
            val result = try {
                com.github.tvbox.osc.spiderapi.SpiderContentProviders.get().action(key, "logout")
            } catch (t: Throwable) {
                android.util.Log.w("MBoxCredential", "source[$key] logout 调用异常", t)
                null
            }
            if (result != null) {
                hit++
                android.util.Log.i("MBoxCredential", "source[$key] logout -> $result")
            }
        }
        val summary = "共 ${sources.size} 个源，其中 $hit 个返回了结果"
        android.util.Log.i("MBoxCredential", "logout 尝试完成：$summary")
        return summary
    }

    /**
     * 结束进程并重新拉起：只重载界面（CLEAR_TASK）无法复位插件静态状态。
     * <p>改走全 App 统一的重启入口 {@code AppUtils.relaunchApp(true)}（内部是
     * {@code makeRestartActivityTask + System.exit}），而不是自己用 Alarm+PendingIntent 再拉一次。
     * 后者在部分 ROM 上会与「进程被杀后的任务栈恢复」叠加，导致首页加载完又被重建一次，
     * 表现就是"我手动重启了一次、它又自动重启了一次"。
     */
    private fun restartProcess() {
        com.github.tvbox.osc.config.SystemConfig.markInternalRestart()
        com.blankj.utilcode.util.AppUtils.relaunchApp(true)
        // relaunchApp(true) 成功时结束当前进程；若返回说明未能发起，退回让用户留在本页。
    }

    override fun onBackPressed() {
        if (selectMode && !busy) {
            if (selectedIds.isNotEmpty()) {
                cancelAllSelection()
            } else {
                selectMode = false
                snapshot?.let(::render)
                updateSelection()
            }
        } else {
            super.onBackPressed()
        }
    }
}

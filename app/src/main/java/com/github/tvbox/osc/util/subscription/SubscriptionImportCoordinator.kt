package com.github.tvbox.osc.util.subscription

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import com.github.tvbox.osc.bean.Source
import com.github.tvbox.osc.bean.Subscription
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.share.ShareArchives
import com.github.tvbox.osc.spiderapi.CmsApiRules
import com.github.tvbox.osc.transfer.ConfigBundle
import com.github.tvbox.osc.transfer.SubscriptionImportFiles
import com.github.tvbox.osc.ui.state.SubscriptionPageState
import com.github.tvbox.osc.util.CmsSiteImporter
import com.github.tvbox.osc.util.HCallBack
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.github.tvbox.osc.util.HtmlSiteImporter
import com.github.tvbox.osc.util.HttpClient
import com.github.tvbox.osc.util.LegadoVideoImporter
import com.github.tvbox.osc.util.LegadoVideoRules
import com.github.tvbox.osc.util.LocalPackageImporter
import com.github.tvbox.osc.util.SubscriptionConfig
import com.github.tvbox.osc.util.SubscriptionImportRules
import com.github.tvbox.osc.util.SubscriptionLibrary
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.UnknownHostException

/**
 * 订阅导入协调器(原 SubscriptionActivity 里约 1200 行导入流程的原样抽出):
 * 本地文件/JSON 粘贴/清单 ZIP/站点嗅探/多线路/多仓等全部入口与批处理都在这,
 * 弹窗/加载框/列表刷新等视图动作经 [SubscriptionImportHost] 回投给页面。
 * 只依赖页面状态 [SubscriptionPageState] 与配置门面,不碰视图。
 */
class SubscriptionImportCoordinator(
    private val context: Context,
    private val state: SubscriptionPageState,
    private val host: SubscriptionImportHost
) {

    // ------------------------------------------------------------------
    // 导入流程状态：仅顶层入口换代，嵌套清单沿用同一会话。
    // ------------------------------------------------------------------

    private var batchImportInProgress = false
    private var batchProgress = ""
    private var batchCancelAction: (() -> Unit)? = null
    /** 当前这次"添加订阅"的来源,三条路径最后都汇到 addSub2List 与 addLocalFileSubscription */
    private var addOrigin: String = Subscription.ORIGIN_DIRECT
    private val session = ImportSession()
    private val importEpoch: Long get() = session.current
    private var expansion = ImportExpansionGuard()
    private var legadoImportCancelled: java.util.concurrent.atomic.AtomicBoolean? = null
    private val main = Handler(Looper.getMainLooper())

    /** 批量清单校验是否在跑(删除入口据此拒绝操作,与导入互斥) */
    fun isBatchImportInProgress(): Boolean = batchImportInProgress

    fun onPageDestroyed() {
        invalidateImport()
    }

    private fun invalidateImport() {
        session.invalidate()
        legadoImportCancelled?.set(true)
        legadoImportCancelled = null
        expansion = ImportExpansionGuard()
        batchImportInProgress = false
        batchCancelAction = null
    }

    private fun beginImport(origin: String): Long {
        // 先失效旧回调，再关闭旧加载框；其延迟取消回调不能取消新任务。
        invalidateImport()
        host.dismissLoading()
        addOrigin = origin
        return importEpoch
    }

    private fun isCurrent(epoch: Long): Boolean = session.isCurrent(epoch) && host.isPageActive()

    private fun cancelImport(epoch: Long) {
        if (!isCurrent(epoch)) return
        invalidateImport()
        host.dismissLoading()
        host.showToast("已取消")
    }

    // ------------------------------------------------------------------
    // 加载态工具(批量导入与单条导入共用同一个加载框)
    // ------------------------------------------------------------------

    private fun updateImportHint(hint: String) {
        host.setLoadingHint(if (batchImportInProgress) "$batchProgress\n$hint" else hint)
    }

    private fun dismissImportLoading() {
        if (!batchImportInProgress) host.dismissLoading()
    }

    private fun showImportToast(message: String) {
        if (!batchImportInProgress) host.showToast(message)
    }

    private fun showCancelableLoading(hint: String, onCancel: () -> Unit) {
        val epoch = importEpoch
        host.showLoading(hint) { if (isCurrent(epoch)) onCancel() }
    }

    private fun resumeBatchLoading() {
        if (batchImportInProgress) {
            val cancel = batchCancelAction
            showCancelableLoading(batchProgress) { cancel?.invoke() }
        }
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /** 弹窗里输入名称+地址 = 直接导入 */
    fun addDirectSubscription(name: String, url: String, checked: Boolean) {
        beginImport(Subscription.ORIGIN_DIRECT)
        addSubscription(name, url, checked)
    }

    /**
     * 本地导入(系统 SAF 文件选择器;替代 hedzr 反射 StorageVolume 的老实现):
     * 选择 txt/json 后,主卷文件(clan 服务器可直接按路径读)转真实路径、以 clan:// 引用原文件;
     * 其它存储提供方(下载/云盘/第三方文件管理器等,SAF 授权读取但无主卷路径)复制进应用专属目录后
     * 再以 clan:// 引用副本——任何能在系统文件管理器里打开的文件均可导入。
     *
     * <p>压缩包与已解压的本地包都在这里自动识别(弹窗不再单开「本地包」入口):
     * ZIP 先按本应用导出的订阅清单认,认不出按 TVBox 本地包解包;
     * txt/json 先探一次是不是本地包清单(用户自己解压好的包,直接选包里的清单即可)。
     * @param checked 是否在导入成功后默认启用该订阅(导入菜单勾选状态,先记录后使用)
     */
    fun importLocalDoc(uri: Uri, checked: Boolean) {
        val epoch = beginImport(Subscription.ORIGIN_LOCAL)
        showCancelableLoading("正在读取文件…") { cancelImport(epoch) }
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            if (!session.isCurrent(epoch)) return@execute
            val name = runCatching { SubscriptionLibrary.queryDisplayName(context, uri)?.trim() }.getOrNull()
            main.post {
                if (!isCurrent(epoch)) return@post
                if (name.isNullOrEmpty() ||
                    !name.lowercase().endsWith(".txt") && !name.lowercase().endsWith(".json")
                    && !name.lowercase().endsWith(".zip")
                ) {
                    dismissImportLoading()
                    host.showToast("请选择 txt/json 订阅文件、订阅清单 ZIP 或本地包")
                } else if (name.lowercase().endsWith(".zip")) {
                    importArchive(uri, name, checked)
                } else {
                    probeExtractedPackage(uri, name, checked)
                }
            }
        }
    }

    /**
     * 用户自己解压好的本地包:先在共享执行器探一次所选文件是包里的**清单**还是包里的**配置**。
     *  - 清单({"urls":[…]}):展开成多条订阅,地址指向包内原文件;
     *  - 配置(带 ./jar、./js 同目录依赖):就地以 clan:// 引用,不复制(复制会让依赖全废);
     * 两者都不是(普通订阅文件/网络多线路清单)再走本地文件复制与内容识别，仍由共享执行器读取。
     */
    private fun probeExtractedPackage(uri: Uri, name: String, checked: Boolean) {
        val epoch = importEpoch
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            if (!session.isCurrent(epoch)) return@execute
            val entries = try {
                LocalPackageImporter.expandExtractedManifest(context, uri)
                    ?: if (session.isCurrent(epoch)) LocalPackageImporter.extractedConfigEntry(uri) else null
            } catch (t: Throwable) {
                null
            }
            main.post {
                if (!isCurrent(epoch)) return@post
                if (entries == null || entries.size() == 0) {
                    importLocalDocument(uri, name, checked)
                    return@post
                }
                dismissImportLoading()
                addOrigin = Subscription.ORIGIN_PACKAGE
                if (!importSubscriptionEntries(entries, checked, name, Subscription.ORIGIN_PACKAGE)) {
                    showImportToast("本地包中没有可用的订阅")
                }
            }
        }
    }

    /** 本地订阅文件导入(复制进应用内部目录后按内容识别;识别规则见各分支注释) */
    private fun importLocalDocument(uri: Uri, name: String, checked: Boolean) {
        val epoch = importEpoch
        addOrigin = Subscription.ORIGIN_LOCAL
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            if (!session.isCurrent(epoch)) return@execute
            try {
                // SAF 复制、文件读取和内容判定都不占用主线程。
                val file = SubscriptionLibrary.importCopyOf(context, uri, name) { !session.isCurrent(epoch) }
                if (!session.isCurrent(epoch)) return@execute
                if (file == null || !SubscriptionLibrary.isUnderPrimaryStorage(file)) {
                    main.post {
                        if (isCurrent(epoch)) {
                            dismissImportLoading()
                            host.showToast("文件无法读取，请选本机订阅文件")
                        }
                    }
                    return@execute
                }
                val large = name.lowercase().endsWith(".json") && file.length() > SubscriptionLibrary.MAX_JUDGE_BYTES
                val list = if (large) null else readSubscriptionList(file)
                val text = if (large) null else SubscriptionLibrary.readImportText(file)
                val root = text?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }
                val bookSource = text != null && CmsApiRules.looksLikeBookSource(text)
                val resolved = text?.let { classifySubscriptionContent(it) }
                if (!session.isCurrent(epoch)) return@execute
                main.post {
                    if (!isCurrent(epoch)) return@post
                    if (large) {
                        importLargeSubscriptionDocument(file, name, checked)
                        return@post
                    }
                    dismissImportLoading()
                    if (list != null && importSubscriptionEntries(list, checked, name, Subscription.ORIGIN_LOCAL) ||
                        root != null && importJsonEntries(root, checked, "文件", Subscription.ORIGIN_LOCAL)) {
                        SubscriptionConfig.setLastImportDir(file.parent)
                    } else if (bookSource) {
                        sniffBookSourceSite(text!!, checked)
                    } else if (text != null && resolved == null) {
                        rejectSubscriptionContent(text.trim(), name)
                    } else if (resolved?.rewritten == true) {
                        saveJsonSubscription(resolved.content, checked)
                    } else {
                        addLocalFileSubscription(file, name, checked)
                    }
                }
            } catch (failure: Throwable) {
                main.post {
                    if (!isCurrent(epoch)) return@post
                    dismissImportLoading()
                    LogStore.fail(Category.SUBSCRIPTION, "订阅: 本地导入读取文件失败 (${exceptionType(failure)})")
                    host.showToast("读取所选文件失败")
                }
            }
        }
    }

    /**
     * JSON 粘贴导入:先按已知订阅格式识别;单条地址复用自定义导入的在线校验,
     * 多条清单逐项校验远端地址,内嵌配置可离线恢复,整份配置按本地文件导入的内容规则判定后落盘。
     * <pre>
     * 1) 清单数组          [{name,url}...] / [{sourceName,sourceUrl}...]
     * 2) 多线路            {"urls":[{name,url}...]}
     * 3) 多仓              {"storeHouse":[{sourceName,sourceUrl}...]} → 弹窗选仓
     * 4) 单条订阅          {"name":..,"url":..} / {"sourceName":..,"sourceUrl":..}
     * 5) 其它(单源规则配置、整份配置等)→ 存为本地 json 并以 clan:// 订阅加入(与本地导入一致)
     * </pre>
     * 「阅读」视频书源先按 sortUrl / 规则字段接入 JSON 接口,其余回落站点识别;
     * 其它内容落盘前过 [resolveSubscriptionContent],
     * 无效内容不存成 clan:// 订阅,避免启动时"解析配置失败"并挤掉当前可用订阅。
     * @param checked 是否在导入成功后默认启用(导入菜单勾选状态)
     */
    fun importJsonText(json: String, checked: Boolean) {
        beginImport(Subscription.ORIGIN_JSON)
        val text = json.trim()
        if (text.isEmpty()) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: JSON 导入内容为空")
            host.showToast("请输入 JSON 内容")
            return
        }
        val root = try {
            JsonParser.parseString(text)
        } catch (t: Throwable) {
            null
        }
        if (root == null) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: JSON 格式不正确 字符=${text.length}")
            host.showToast("JSON 格式不正确")
            return
        }
        // 1) 清单数组 / 多线路 / 多仓 / 单条订阅
        if (importJsonEntries(root, checked, "JSON")) return
        if (CmsApiRules.looksLikeBookSource(text)) {
            sniffBookSourceSite(text, checked)
            return
        }
        // 2) 兜底:存为本地 json 文件,以 clan:// 订阅加入
        val resolved = resolveSubscriptionContent(text, "JSON导入")
        if (resolved == null) {
            return
        }
        saveJsonSubscription(resolved.content, checked)
    }

    // ------------------------------------------------------------------
    // 压缩包 / 大 JSON / 清单文本导入
    // ------------------------------------------------------------------

    /**
     * 压缩包导入(一份副本、两条识别路径):
     * ① 本应用导出的<b>订阅清单 ZIP</b>(带内嵌本地订阅内容,可离线还原);
     * ② TVBox 生态的<b>本地包</b>(视频源/解析规则/JAR/直播源打成 ZIP):解压到应用专属目录
     *    local_packages/ 后,把包内清单指向的配置逐条按 clan:// 地址加入,没有清单时扫描包内完整配置兜底。
     * 判定只看归档清单(不解压);复制/解压/识别都在共享执行器,完成后仍逐条验证订阅。
     */
    private fun importArchive(uri: Uri, name: String, checked: Boolean) {
        val app = context
        val epoch = importEpoch
        showCancelableLoading("正在读取压缩包…") { cancelImport(epoch) }
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            if (!session.isCurrent(epoch)) return@execute
            var temporary: File? = null
            var entries: JsonArray? = null
            var origin = Subscription.ORIGIN_LOCAL
            var error: String? = null
            try {
                val archive = LocalPackageImporter.copyToTemp(app, uri)
                temporary = archive
                if (!session.isCurrent(epoch)) return@execute
                if (isSubscriptionBundle(archive)) {
                    if (archive.length() > ConfigBundle.MAX_ARCHIVE_BYTES)
                        throw java.io.IOException("订阅包超过 64 MB")
                    val parsed = ConfigBundle(app).readSubscriptionSelection(archive)
                    if (parsed.size() == 0 || parsed.size() > 500)
                        throw java.io.IOException("订阅清单条数无效")
                    entries = prepareEmbeddedEntries(parsed, epoch)
                } else {
                    val result = LocalPackageImporter.importPackageFile(app, archive, uri.toString(), name) {
                        !session.isCurrent(epoch)
                    }
                    if (result.error != null) throw java.io.IOException(result.error)
                    if (result.entries.size() == 0) throw java.io.IOException("本地包中没有可用的订阅")
                    entries = result.entries
                    origin = Subscription.ORIGIN_PACKAGE
                }
            } catch (failure: Exception) {
                error = failure.message ?: "压缩包读取失败"
            } finally {
                temporary?.delete()
            }
            val imported = entries
            val importedOrigin = origin
            val failure = error
            main.post {
                if (!isCurrent(epoch)) return@post
                dismissImportLoading()
                if (failure != null) {
                    host.showToast(failure)
                } else if (imported != null) {
                    addOrigin = importedOrigin
                    if (!importSubscriptionEntries(imported, checked, name, importedOrigin, embeddedPrepared = true))
                        host.showToast("压缩包中无有效订阅")
                }
            }
        }
    }

    /** 是不是本应用导出的订阅清单 ZIP(只读归档清单判定,不解压) */
    private fun isSubscriptionBundle(archive: File): Boolean {
        val domains = ShareArchives.manifest(archive)?.domains() ?: return false
        return domains.size == 1 && "subscriptions" == domains[0]
    }

    /** Large single JSON exports may embed an 8 MB local subscription; parse off the UI thread. */
    private fun importLargeSubscriptionDocument(file: File, name: String, checked: Boolean) {
        val epoch = importEpoch
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            if (!session.isCurrent(epoch)) return@execute
            var entries: JsonArray? = null
            var error: String? = null
            try {
                if (file.length() <= 32L * 1024 * 1024) {
                    val parsed = JsonParser.parseString(file.readText(Charsets.UTF_8))
                    if (parsed.isJsonObject) {
                        val obj = parsed.asJsonObject
                        val schema = runCatching { obj.get("schema")?.asInt }.getOrNull()
                        val category = runCatching { obj.get("category")?.asString }.getOrNull()
                        if (schema == 1 && category == "subscriptions") {
                            val items = obj.get("items")
                            if (items == null || !items.isJsonArray || items.asJsonArray.size() !in 1..500)
                                throw java.io.IOException("订阅清单格式无效")
                            entries = prepareEmbeddedEntries(items.asJsonArray, epoch)
                        }
                    }
                }
            } catch (failure: java.io.IOException) {
                error = failure.message ?: "订阅清单读取失败"
            } catch (_: Exception) {
                // 其它大 JSON 仍按原有本地配置文件入口处理。
            }
            val imported = entries
            val failure = error
            main.post {
                if (!isCurrent(epoch)) return@post
                dismissImportLoading()
                when {
                    failure != null -> host.showToast(failure)
                    imported != null -> {
                        addOrigin = Subscription.ORIGIN_LOCAL
                        if (!importSubscriptionEntries(imported, checked, name, Subscription.ORIGIN_LOCAL, embeddedPrepared = true))
                            host.showToast("清单中无有效订阅")
                        SubscriptionConfig.setLastImportDir(file.parent)
                    }
                    else -> addLocalFileSubscription(file, name, checked)
                }
            }
        }
    }

    /** ZIP/大 JSON 在共享执行器先还原内嵌文件，UI 线程只负责逐条加入订阅。 */
    private fun prepareEmbeddedEntries(entries: JsonArray, epoch: Long): JsonArray {
        for (element in entries) {
            if (!session.isCurrent(epoch)) throw java.io.IOException("导入已取消")
            if (!element.isJsonObject) continue
            val item = element.asJsonObject
            if (!SubscriptionImportRules.mayBeListEntry(item)) continue
            val raw = item.stringValue("content")
            if (raw.isBlank()) continue
            val name = item.stringValue("name").trim().ifEmpty { item.stringValue("sourceName").trim() }
            val content = SubscriptionImportRules.normalizedContent(raw)
            val file = content?.let {
                SubscriptionImportFiles.runLocked {
                    if (!session.isCurrent(epoch)) throw java.io.IOException("导入已取消")
                    SubscriptionLibrary.writeLibraryFile(context, item.stringValue("file"), name, it)
                }
            }
            if (!session.isCurrent(epoch)) throw java.io.IOException("导入已取消")
            if (file == null) {
                // 保留失败内容供 UI 计数，不重新尝试写入，也不回退成原来的远端地址。
                item.addProperty("url", "")
                item.addProperty("sourceUrl", "")
            } else {
                item.remove("content")
                item.addProperty("url", SubscriptionLibrary.clanPathOf(file))
                item.addProperty("name", name.ifEmpty { file.nameWithoutExtension })
            }
        }
        return entries
    }

    /** 共享执行器读取裸清单数组；识别失败返回 null，UI 仍按既有内容规则继续判定。 */
    private fun readSubscriptionList(file: File): JsonArray? {
        val text = try {
            file.readText(Charsets.UTF_8).trim()
        } catch (t: Throwable) {
            return null
        }
        if (text.isEmpty() || text[0] != '[') return null
        val entries = try {
            JsonParser.parseString(text).asJsonArray
        } catch (t: Throwable) {
            return null // 非目标格式(如单源配置 JSON 对象),回落旧逻辑
        }
        if (CmsApiRules.looksLikeBookSource(text)) return null
        return entries
    }

    /**
     * 订阅清单逐条加入(本地清单导入与 JSON 粘贴导入共用):
     * 条目兼容三种写法 —— ①**导出清单** `{name,type,origin,url[,file,content]}` ②`{name,url}` ③`{sourceName,sourceUrl}`;
     * 带 `content` 的条目(导出清单里"本地文件"那类订阅)先把原文落成内部库文件、再按 clan:// 加入,
     * 换机导入、原文件已删都能还原;远端地址不论单条或多条都在线识别后再加入。
     * 与本机已有订阅按地址去重。
     * @param origin 条目自身没写 origin 时的兜底来源(本地文件导入=local / JSON 粘贴=json)
     * @return true=已按清单处理(可能 0 条新增);false=无有效条目(调用方回落)
     */
    private fun importSubscriptionEntries(entries: JsonArray, checked: Boolean, label: String,
                                          origin: String, onComplete: (() -> Unit)? = null,
                                          embeddedPrepared: Boolean = false): Boolean {
        val epoch = importEpoch
        if (!isCurrent(epoch)) return true
        if (!embeddedPrepared && entries.any { it.isJsonObject && SubscriptionImportRules.mayBeListEntry(it.asJsonObject) &&
                it.asJsonObject.stringValue("content").isNotBlank() }) {
            val snapshot = entries.deepCopy()
            if (!batchImportInProgress) showCancelableLoading("正在还原订阅文件…") { cancelImport(epoch) }
            HeavyTaskUtil.getBigTaskExecutorService().execute {
                if (!session.isCurrent(epoch)) return@execute
                val prepared = runCatching { prepareEmbeddedEntries(snapshot, epoch) }
                main.post {
                    if (!isCurrent(epoch)) return@post
                    dismissImportLoading()
                    val restored = prepared.getOrNull()
                    if (restored == null || !importSubscriptionEntries(restored, checked, label, origin, onComplete, embeddedPrepared = true)) {
                        showImportToast("清单中无有效订阅")
                        onComplete?.invoke()
                    }
                }
            }
            return true
        }
        val parsed = ArrayList<Subscription>()
        var candidates = 0
        var invalid = 0
        for (el in entries) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            if (!SubscriptionImportRules.mayBeListEntry(obj)) continue
            val name = obj.stringValue("name").trim().ifEmpty { obj.stringValue("sourceName").trim() }
            val url = obj.stringValue("url").trim().ifEmpty { obj.stringValue("sourceUrl").trim() }
            val itemOrigin = obj.stringValue("origin").trim().ifEmpty { origin }
            val rawContent = obj.stringValue("content")
            if (url.isEmpty() && rawContent.isBlank()) continue
            candidates++
            if (rawContent.isNotBlank()) {
                invalid++
                LogStore.fail(Category.SUBSCRIPTION, "订阅: 清单条目无效，内部文件写入失败")
                continue
            }
            if (name.isNotEmpty() && SubscriptionImportRules.isSupportedAddress(url)) {
                parsed.add(Subscription(name, url, itemOrigin))
            } else {
                invalid++
                LogStore.fail(Category.SUBSCRIPTION, "订阅: 清单条目无效，名称或地址不符合格式")
            }
        }
        if (parsed.isEmpty()) {
            if (candidates == 0) return false
            host.showToast("清单中无有效订阅")
            onComplete?.invoke()
            return true
        }
        val wasEmpty = state.subscriptions.isEmpty()
        // 只有一条远端地址时按自定义导入拉取并识别内容，避免把无效配置地址直接收进列表。
        if (parsed.size == 1 && parsed[0].url.startsWith("http")) {
            val single = parsed[0]
            addOrigin = single.origin
            addSubscription(single.name, single.url, checked || wasEmpty, onComplete)
            return true
        }
        val pending = parsed.distinctBy { it.url }
        var added = 0
        var failed = invalid
        var duplicates = parsed.size - pending.size
        var index = 0
        var processed = 0
        val parentBatch = batchImportInProgress
        val parentProgress = batchProgress
        if (!parentBatch) {
            batchImportInProgress = true
            val initialSize = state.subscriptions.size
            val cancel: () -> Unit = {
                if (isCurrent(epoch)) {
                    // 子清单尚未结束时，已落表的条目也必须计入取消保留数量。
                    val retained = state.subscriptions.size - initialSize
                    invalidateImport()
                    host.dismissLoading()
                    host.persistSubscriptions()
                    LogStore.log(Category.SUBSCRIPTION,
                        "订阅: 清单校验取消 已处理=$processed/${pending.size} 成功=$retained 失败=$failed 重复=$duplicates")
                    host.showToast("已取消导入，已保留 $retained 条")
                }
            }
            batchCancelAction = cancel
            showCancelableLoading("正在准备校验 ${pending.size} 条订阅…", cancel)
        }
        val completed = ImportOnce()
        fun next() {
            if (!isCurrent(epoch)) return
            if (index >= pending.size) {
                if (!completed.claim()) return
                if (parentBatch) {
                    batchProgress = parentProgress
                } else {
                    batchImportInProgress = false
                    batchCancelAction = null
                    host.dismissLoading()
                }
                host.onSubscriptionListChanged()
                if (!parentBatch) host.persistSubscriptions()
                LogStore.log(Category.SUBSCRIPTION,
                    "订阅: 清单校验完成 成功=" + added + " 失败=" + failed + " 重复=" + duplicates)
                if (!parentBatch) host.showToast("已导入 $added 条" +
                    (if (failed > 0) "，跳过 $failed 条无效或不可用地址" else "") +
                    (if (duplicates > 0) "，重复 $duplicates 条" else ""))
                onComplete?.invoke()
                return
            }
            val item = pending[index++]
            batchProgress = "正在校验订阅 $index/${pending.size}：${item.name}（已导入 $added，失败 $failed，重复 $duplicates）"
            updateImportHint(item.url)
            if (state.subscriptions.any { it.url == item.url }) {
                duplicates++
                processed++
                host.post { next() }
                return
            }
            val before = state.subscriptions.size
            val itemCompleted = ImportOnce()
            addOrigin = item.origin
            addSubscription(item.name, item.url, (checked || wasEmpty) && added == 0,
                onComplete = itemDone@{
                    if (!isCurrent(epoch) || !itemCompleted.claim()) return@itemDone
                    if (state.subscriptions.size > before) {
                        val count = state.subscriptions.size - before
                        added += count
                    } else {
                        failed++
                        LogStore.fail(Category.SUBSCRIPTION,
                            "订阅: 清单条目失败 $index/${pending.size} 未加入订阅列表")
                    }
                    processed++
                    host.persistSubscriptions()
                    host.post { next() }
                }, loadingHint = batchProgress)
        }
        next()
        return true
    }

    /**
     * JSON 文本按"清单数组 / 多线路 / 多仓 / 单条订阅"识别展开(JSON 粘贴导入与本地文件导入共用):
     * 命中即按对应逻辑加入并返回 true;识别不了返回 false,由调用方按"订阅配置/本地文件"兜底。
     * @param label 日志与提示里的来源说明(如 "JSON"/"文件")
     */
    private fun importJsonEntries(root: JsonElement, checked: Boolean, label: String,
                                  origin: String = Subscription.ORIGIN_JSON): Boolean {
        // 1) 清单数组 / 多仓数组
        if (root.isJsonArray &&
            importSubscriptionEntries(root.asJsonArray, checked, label + "清单", origin)
        ) return true
        if (!root.isJsonObject) return false
        val obj = root.asJsonObject
        // 新单条导出与批量 ZIP 内的 subscriptions.json 共用这一层描述格式。
        val schema = runCatching { obj.get("schema")?.asInt }.getOrNull()
        val category = runCatching { obj.get("category")?.asString }.getOrNull()
        val described = obj.get("items")
        if (schema == 1 && category == "subscriptions" && described != null && described.isJsonArray
            && importSubscriptionEntries(described.asJsonArray, checked, label + "订阅", origin)
        ) return true
        // 2) 多线路
        val urls = obj.get("urls")
        if (urls != null && urls.isJsonArray
            && importSubscriptionEntries(urls.asJsonArray, checked, label + "多线路", origin)
        ) return true
        // 3) 多仓(列出仓源让用户选择)
        val storeHouse = obj.get("storeHouse")
        if (storeHouse != null && storeHouse.isJsonArray && isStoreHouseList(storeHouse.asJsonArray)) {
            showStoreHouseChoose(storeHouse.asJsonArray, checked)
            return true
        }
        // 4) 单条订阅走自定义地址的同一条拉取/识别链路;清单则保留批量恢复语义。
        if (SubscriptionImportRules.mayBeListEntry(obj)) {
            val single = subscriptionOf(obj, "name", "url")
                ?: subscriptionOf(obj, "sourceName", "sourceUrl")
            if (single != null) {
                addOrigin = origin
                if (obj.stringValue("content").isNotBlank()) {
                    val one = JsonArray()
                    one.add(obj)
                    return importSubscriptionEntries(one, checked, label + "订阅", origin)
                }
                addSubscription(single.name, single.url, checked)
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------
    // 内容判定与落盘
    // ------------------------------------------------------------------

    /** 订阅导入判定结果:@param rewritten 内容被补过 sites 外壳(须另存副本,不能引用原文件) */
    private class SubscriptionContent(val content: String, val rewritten: Boolean)

    /**
     * 判定一段内容能不能当订阅配置(JSON 粘贴导入与本地文件导入共用),并按需补 {"sites":[…]"} 外壳。
     * 不能用的内容一律不落盘:存成 clan:// 订阅只会让应用每次启动都"解析配置失败",还会挤掉当前可用订阅。
     * @return null=不是 TVBox 订阅配置(已提示用户并记日志)
     */
    private fun resolveSubscriptionContent(text: String, label: String): SubscriptionContent? {
        return classifySubscriptionContent(text).also {
            if (it == null) rejectSubscriptionContent(text.trim(), label)
        }
    }

    /** 无 UI 副作用，供后台文件读取路径做内容判定。 */
    private fun classifySubscriptionContent(text: String): SubscriptionContent? {
        val trimmed = text.trim()
        // 裸站点条目/数组(用户从别处复制的单站配置):补外壳后当单源订阅用
        val wrapped = CmsApiRules.wrapSiteJson(trimmed)
        if (wrapped != null && wrapped != trimmed) {
            return SubscriptionContent(wrapped, true)
        }
        return when (CmsApiRules.subscriptionShape(trimmed)) {
            // 完整配置 / 加密套路(加载阶段解码):原样存
            CmsApiRules.SHAPE_CONFIG, CmsApiRules.SHAPE_ENCRYPTED -> SubscriptionContent(trimmed, false)
            // 裸站点类内容在上一段就该被补壳改写(判定与补壳用的是同一套规则),走到这说明补壳失败,不落盘
            else -> null
        }
    }

    /** 拒绝落盘时按内容形态给用户能懂的原因(「阅读」书源 / 只有直播源 / 单站条目 / 缺 sites),并记日志 */
    private fun rejectSubscriptionContent(text: String, label: String) {
        val reason = when (CmsApiRules.subscriptionShape(text)) {
            CmsApiRules.SHAPE_BOOK_SOURCE -> "这是阅读书源，无法作为订阅"
            CmsApiRules.SHAPE_LIVES -> "仅含直播源，无法作为订阅"
            CmsApiRules.SHAPE_SITE -> "单站条目，请用 JSON 导入"
            else -> "缺少站点列表，无法导入"
        }
        LogStore.fail(Category.SUBSCRIPTION, "订阅: 拒绝导入非订阅内容 (" + reason + ")")
        showImportToast(reason)
    }

    /**
     * 远端响应能否直接存成订阅地址:完整配置({@code sites} 数组)与加密套路可用;
     * 裸站点条目/清单不行——那要就地补壳另存,只能走 JSON 粘贴或本地文件导入。
     */
    private fun isLoadableSubscription(text: String): Boolean {
        return when (CmsApiRules.subscriptionShape(text.trim())) {
            CmsApiRules.SHAPE_CONFIG, CmsApiRules.SHAPE_ENCRYPTED -> true
            else -> false
        }
    }

    /**
     * 未识别的 JSON(单源规则配置、整份配置等):按内容摘要存成应用专属目录下的 json 文件,
     * 再以 clan:// 本地订阅加入;启用时按普通订阅配置拉取解析。
     */
    private fun saveJsonSubscription(text: String, checked: Boolean) {
        val epoch = importEpoch
        showCancelableLoading("正在保存订阅…") { cancelImport(epoch) }
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            if (!session.isCurrent(epoch)) return@execute
            val name = guessJsonName(text) ?: "本地JSON导入"
            val file = SubscriptionImportFiles.runLocked {
                if (session.isCurrent(epoch)) SubscriptionLibrary.saveJsonFile(context, name, text) else null
            }
            main.post {
                if (!isCurrent(epoch)) return@post
                dismissImportLoading()
                if (file == null) host.showToast("JSON 导入失败")
                else addLocalFileSubscription(file, name, checked)
            }
        }
    }

    /** 从 JSON 里猜一个订阅名:sourceName / name / title / sites[0].name,取不到返回 null */
    private fun guessJsonName(text: String): String? {
        val root = try {
            JsonParser.parseString(text)
        } catch (t: Throwable) {
            return null
        }
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        for (key in arrayOf("sourceName", "name", "title")) {
            val v = obj.stringValue(key).trim()
            if (v.isNotEmpty()) return v.take(20)
        }
        val sites = obj.get("sites")
        if (sites != null && sites.isJsonArray && sites.asJsonArray.size() > 0 &&
            sites.asJsonArray[0].isJsonObject
        ) {
            val v = sites.asJsonArray[0].asJsonObject.stringValue("name").trim()
            if (v.isNotEmpty()) return v.take(20)
        }
        return null
    }

    /**
     * 以 clan:// 本地文件订阅加入列表(本地导入与 JSON 导入共用):
     * 校验文件位于主存储、记忆导入目录,按 clan:// 地址去重后加入。
     */
    private fun addLocalFileSubscription(file: File, name: String, checked: Boolean,
                                         onComplete: (() -> Unit)? = null) {
        val epoch = importEpoch
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            if (!session.isCurrent(epoch)) return@execute
            val underPrimary = SubscriptionLibrary.isUnderPrimaryStorage(file)
            val clanPath = SubscriptionLibrary.clanPathOf(file)
            main.post {
                if (!isCurrent(epoch)) return@post
                if (!underPrimary) {
                    showImportToast("请选本机存储中的订阅文件")
                    onComplete?.invoke()
                    return@post
                }
                SubscriptionConfig.setLastImportDir(file.parent)
                addSubscription(name, clanPath, checked, onComplete)
            }
        }
    }

    // ------------------------------------------------------------------
    // 阅读书源 / 资源站识别
    // ------------------------------------------------------------------

    /** 阅读视频书源优先按静态 JSON 接口规则验证；不匹配时再探测 CMS 接口与页面。 */
    private fun sniffBookSourceSite(text: String, checked: Boolean) {
        if (!CmsApiRules.looksLikeBookSource(text)) return
        val videoSpec = LegadoVideoRules.parse(text)
        if (videoSpec != null) {
            val epoch = importEpoch
            val completed = ImportOnce()
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            legadoImportCancelled?.set(true)
            legadoImportCancelled = cancelled
            showCancelableLoading("正在验证阅读视频源…") {
                cancelImport(epoch)
            }
            LegadoVideoImporter.probe(
                videoSpec, SubscriptionLibrary.importDir(context), cancelled,
                object : LegadoVideoImporter.Callback {
                    override fun onFound(name: String, configFile: File) {
                        if (!isCurrent(epoch) || !completed.claim()) return
                        if (legadoImportCancelled === cancelled) legadoImportCancelled = null
                        dismissImportLoading()
                        val before = state.subscriptions.size
                        addLocalFileSubscription(configFile, name, checked) {
                            if (state.subscriptions.size > before) showImportToast("阅读视频源已接入")
                        }
                    }

                    override fun onNotFound(reason: String) {
                        if (!isCurrent(epoch) || !completed.claim()) return
                        if (legadoImportCancelled === cancelled) legadoImportCancelled = null
                        dismissImportLoading()
                        showImportToast("书源验证失败：$reason")
                    }
                }, LegadoVideoImporter.Progress { hint ->
                    if (isCurrent(epoch)) {
                        updateImportHint(hint)
                    }
                })
            return
        }
        val siteUrl = CmsApiRules.bookSourceSiteUrl(text)
        if (siteUrl == null || !CmsApiRules.looksLikeSiteUrl(siteUrl)) {
            host.showToast("书源缺少可用站点地址")
            return
        }
        val epoch = importEpoch
        showCancelableLoading("正在读取地址…") { cancelImport(epoch) }
        // 带上完整书源文本:抓页面时用 sortUrl 的分类、搜索路径及子目录。
        sniffSource(guessJsonName(text) ?: "", siteUrl, null, CmsSiteImporter.InputKind.SITE, checked, text)
    }

    /**
     * 采集接口走不通时的兜底:按苹果CMS 站点页面结构抓(首页分类 → 分类页 → 详情页 → 播放页),
     * 探通后生成指向 {@code assets://js/lib/maccms.js} 的单源配置加入订阅。
     * 探不通不加(宁缺毋滥:打不开的订阅会让应用每次启动都"解析配置失败")。
     */
    private fun scrapeSite(name: String, siteUrl: String, hintText: String?, checked: Boolean,
                           onComplete: (() -> Unit)? = null) {
        val epoch = importEpoch
        val completed = ImportOnce()
        updateImportHint("正在按站点页面识别…\n" + CmsApiRules.displayHost(siteUrl))
        HtmlSiteImporter.probe(
            siteUrl, hintText, SubscriptionLibrary.importDir(context),
            object : HtmlSiteImporter.Callback {
                override fun onFound(siteName: String, file: File, samplePlayUrl: String) {
                    if (!isCurrent(epoch) || !completed.claim()) return
                    dismissImportLoading()
                    val display = if (isDefaultSubName(name)) siteName else name.trim()
                    addLocalFileSubscription(file, display, checked) {
                        showImportToast("站点已接入")
                        onComplete?.invoke()
                    }
                }

                override fun onNotFound() {
                    if (!isCurrent(epoch) || !completed.claim()) return
                    dismissImportLoading()
                    LogStore.fail(Category.SUBSCRIPTION,
                        "订阅: 采集接口与站点页面均不可用 (" + logSource(siteUrl) + ")")
                    showImportToast("未找到可用站点接口")
                    onComplete?.invoke()
                }
            }, object : HtmlSiteImporter.Progress {
                override fun onStep(hint: String) {
                    if (!isCurrent(epoch)) return
                    updateImportHint(hint)
                }
            })
    }

    // ------------------------------------------------------------------
    // 单条订阅加入(HTTP 拉取/识别链路)
    // ------------------------------------------------------------------

    /** 支持更多存储提供方转真实路径;主要支持 primary/home 等可被 clan 服务器按路径读取的存储 */
    private fun addSubscription(name: String, url: String, checked: Boolean,
                                onComplete: (() -> Unit)? = null,
                                loadingHint: String = "正在读取地址…") {
        val epoch = importEpoch
        if (!isCurrent(epoch)) return
        if (!SubscriptionImportRules.isSupportedAddress(url)) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 地址格式不正确 (" + logSource(url) + ")")
            showImportToast("订阅地址格式不正确")
            onComplete?.invoke()
            return
        }
        val duplicate = state.subscriptions.firstOrNull { it.url == url }
        if (duplicate != null) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 地址已存在 (" + logSource(url) + ")")
            showImportToast("订阅地址已存在")
            onComplete?.invoke()
            return
        }
        if (url.startsWith("clan://")) {
            importClanSubscription(name, url, checked, epoch, onComplete)
        } else if (url.startsWith("http")) {
            val finish = enterImportAddress(ImportExpansionGuard.httpKey(url), epoch, onComplete) ?: return
            // 从点击这一刻就把加载态亮出来:拉页面 → 嗅探接口可能要十几秒
            // (以前这里拉完页面先 dismiss、再由嗅探重新 show,中间那段就是"点了没反应")。
            // 可取消:点"取消"把这次添加作废,后面回来的结果一律不再采用
            if (batchImportInProgress) {
                // 批量流程持有同一个加载框；逐条识别时只更新状态，不清掉整批的取消标志。
                updateImportHint("正在读取地址…")
            } else {
                showCancelableLoading(loadingHint) { cancelImport(epoch) }
            }
            val responded = ImportOnce()
            HttpClient.get(url, null, "get_subscription", object : HCallBack {
                override fun onSuccess(response: String) {
                    if (!isCurrent(epoch) || !responded.claim()) return
                    // 「图片裹配置」套路(8 位字母/0 + ** + base64,http://www.饭太硬.net/tv 这类分享地址就是):
                    // 响应本身是**二进制/乱码**(图片 + 尾部 base64),直接按 JSON 解析必然失败,
                    // 接着会被当成资源站去嗅探采集接口 —— 用户口径"接口明明可以用啊,为啥弹未识别到采集接口"。
                    // 解出来确认能当配置用,就按 JSON 响应的同一条流程判定;地址仍原样存成订阅
                    // (加载阶段 ApiConfig.FindResult 用的是同一套解包规则,配置更新时能跟着变)。
                    val packed = CmsApiRules.unwrapPackedConfig(response)
                    handleSubscriptionBody(name, url, packed ?: response, checked, finish)
                    host.onSubscriptionListChanged()
                }

                override fun onError(e: Throwable) {
                    if (!isCurrent(epoch) || !responded.claim()) return
                    // 拉取这一步失败:只记异常类型;"新增算不算失败"的结论交给下面两个分支
                    // 各自落(站点形态转嗅探,它自己记成功/失败;非站点形态由紧随的 LogStore.fail 记)——
                    // 这里不再下"新增订阅失败"的结论,否则站点形态后续接入成功时日志里会留一条误导
                    LogStore.fail(Category.SUBSCRIPTION,
                        "订阅: 新增订阅拉取失败 (" + logSource(url) + ", " + exceptionType(e) + ")")
                    if (hasUnknownHost(e)) {
                        dismissImportLoading()
                        showImportToast("域名无法解析，请检查地址或 DNS")
                        finish()
                        return
                    }
                    // 通用兜底:地址像资源站就说"拉不到页面"太苛刻(站点常拦非浏览器 UA/首页超时),
                    // 仍按站点候选路径实探一次,能探到接口就正常接入
                    if (CmsApiRules.looksLikeSiteUrl(url)) {
                        sniffSource(name, url, null, CmsSiteImporter.InputKind.SITE, checked,
                            onComplete = finish)
                    } else {
                        dismissImportLoading()
                        LogStore.fail(Category.SUBSCRIPTION, "订阅: 新增订阅失败 网络错误/地址无效")
                        showImportToast("订阅失败，请检查地址或网络")
                        finish()
                    }
                }
            })
        } else {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 格式不正确 (" + logSource(url) + ")")
            showImportToast("订阅格式不正确")
            onComplete?.invoke()
        }
    }

    private fun enterImportAddress(key: String, epoch: Long, onComplete: (() -> Unit)?): (() -> Unit)? {
        if (!isCurrent(epoch)) return null
        val guard = expansion
        if (!guard.enter(key)) {
            dismissImportLoading()
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 清单包含循环地址或超过展开限制")
            showImportToast("订阅清单包含循环地址或超过展开限制")
            onComplete?.invoke()
            return null
        }
        val completed = ImportOnce()
        return {
            if (isCurrent(epoch) && completed.claim()) {
                guard.leave(key)
                onComplete?.invoke()
            }
        }
    }

    private fun importClanSubscription(name: String, url: String, checked: Boolean, epoch: Long,
                                       onComplete: (() -> Unit)?) {
        val origin = addOrigin
        if (!batchImportInProgress) showCancelableLoading("正在读取本地清单…") { cancelImport(epoch) }
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            if (!session.isCurrent(epoch)) return@execute
            val key = runCatching { ImportExpansionGuard.key(url, Environment.getExternalStorageDirectory()) }.getOrNull()
            main.post keyReady@{
                if (!isCurrent(epoch)) return@keyReady
                if (key == null) {
                    dismissImportLoading()
                    showImportToast("本地订阅路径无效")
                    onComplete?.invoke()
                    return@keyReady
                }
                val finish = enterImportAddress(key, epoch, onComplete) ?: return@keyReady
                HeavyTaskUtil.getBigTaskExecutorService().execute readManifest@{
                    if (!session.isCurrent(epoch)) return@readManifest
                    val expanded = runCatching { LocalPackageImporter.expandManifest(context, url) }
                    main.post manifestReady@{
                        if (!isCurrent(epoch)) return@manifestReady
                        dismissImportLoading()
                        if (expanded.isFailure) {
                            showImportToast("本地订阅读取失败")
                            finish()
                            return@manifestReady
                        }
                        val entries = expanded.getOrNull()
                        if (entries != null) {
                            if (entries.size() == 0 || !importSubscriptionEntries(entries, checked, name, origin, finish)) {
                                showImportToast("清单中没有可用的订阅地址")
                                finish()
                            }
                        } else {
                            addOrigin = origin
                            addSub2List(name, url, checked)
                            host.onSubscriptionListChanged()
                            // 在可能继续处理下一条之前保存；取消批量时已经成功的条目不丢失。
                            host.persistSubscriptions()
                            finish()
                        }
                    }
                }
            }
        }
    }

    private fun hasUnknownHost(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is UnknownHostException) return true
            cause = cause.cause
        }
        return false
    }

    /** 订阅地址可能带鉴权信息；业务日志只保留可安全区分的来源类型。 */
    private fun logSource(url: String): String = when {
        url.startsWith("clan://") -> "本地订阅"
        url.startsWith("http://") || url.startsWith("https://") -> "网络订阅"
        else -> "其他来源"
    }

    private fun exceptionType(error: Throwable?): String =
        error?.javaClass?.simpleName ?: "未知错误"

    /**
     * 订阅地址的响应按"多线路 / 多仓 / 单线路配置 / 资源站页面"判定并落地。
     *
     * 新增订阅(HTTP)两条来源共用:响应本来就是 JSON,或响应是「图片裹配置」
     * ({@link CmsApiRules#unwrapPackedConfig})解出来的配置正文。地址始终用用户填的那个 ——
     * 完整配置存 URL(配置更新能跟着变),多线路/多仓则展开成各自的地址。
     *
     * @param body 判定用的正文(图片裹配置时是解包结果,其余情况就是响应原文)
     */
    private fun handleSubscriptionBody(name: String, url: String, body: String, checked: Boolean,
                                       onComplete: (() -> Unit)? = null) {
        if (SubscriptionImportRules.isAccessDeniedResponse(body)) {
            dismissImportLoading()
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 地址拒绝访问 (" + logSource(url) + ")")
            showImportToast("订阅地址拒绝访问，请检查站点权限或更换地址")
            onComplete?.invoke()
            return
        }
        try {
            val json = JsonParser.parseString(body).asJsonObject
            // 多线路?
            val urls = json["urls"]
            // 多仓?
            val storeHouse = json["storeHouse"]
            if (urls != null && urls.isJsonArray) { // 多线路
                dismissImportLoading()
                if (!importSubscriptionEntries(urls.asJsonArray, checked, "多线路", addOrigin,
                        onComplete)) onComplete?.invoke()
            } else if (storeHouse != null && storeHouse.isJsonArray
                && isStoreHouseList(storeHouse.asJsonArray)
            ) { // 多仓
                host.dismissLoading()   // 要让位给选仓弹窗
                showStoreHouseChoose(storeHouse.asJsonArray, checked, onComplete)
            } else if (looksLikeConfigJson(json) || CmsApiRules.detectKind(body) < 0) {
                // 单线路订阅配置(含只有 flags/ads 之类键的最小配置)→ 原样加入;
                // 响应确实是 CMS 采集数据时,若粘的就是采集接口则直接落成单源配置,否则按站点嗅探
                if (looksLikeConfigJson(json)) {
                    // 配置类 JSON 不代表能当订阅加载(缺 sites 的存进去必然"解析配置失败"、
                    // 还会把当前可用订阅挤掉):先判定,不能用的直接说明原因,不再原样加入
                    if (isLoadableSubscription(body)) {
                        dismissImportLoading()
                        addSub2List(name, url, checked)
                        onComplete?.invoke()
                    } else {
                        dismissImportLoading()
                        rejectSubscriptionContent(body.trim(), "订阅地址 " + url)
                        onComplete?.invoke()
                    }
                } else {
                    sniffSource(
                        name, url, body,
                        if (CmsApiRules.looksLikeApi(url)) CmsSiteImporter.InputKind.API
                        else CmsSiteImporter.InputKind.SITE,
                        checked,
                        onComplete = onComplete
                    )
                }
            } else { // 填的是资源站采集接口/首页(典型 MacCMS 站):嗅探并生成单源订阅
                sniffSource(name, url, body, CmsSiteImporter.InputKind.SITE, checked,
                    onComplete = onComplete)
            }
        } catch (th: Throwable) {
            // 不是 JSON 配置:多半是资源站网页,按站点嗅探采集接口
            sniffSource(name, url, body, CmsSiteImporter.InputKind.SITE, checked,
                onComplete = onComplete)
        }
    }

    /**
     * 填入的是资源站地址(首页/栏目页/说明页)或采集接口,而不是订阅配置:
     * 嗅探采集接口并生成单源订阅配置后以 clan:// 加入。
     * <p>
     * 通用接入:只看"这份响应能不能当订阅用"——
     * 能当订阅(多线路/多仓/完整配置)的原样加入;不能当订阅的一律嗅探;响应根本读不到但地址像站点时也嗅探。
     * 嗅探不到接口时不再把站点地址原样存成订阅(那会在加载配置阶段报"解析配置失败"),直接提示用户。
     * <p>
     * 站点类输入(kind=SITE)在找不到采集接口时还会继续走 [scrapeSite]:直接抓站点页面
     * (苹果CMS 站页面结构固定,接口关闭也常能抓),这是"接口不可用"站点的最后一档兜底。
     * <p>
     * 加载态:调用方已经亮起加载框(带"正在读取地址…"),这里接管并显示探测进度;
     * 探测要逐个试候选地址、可能十几秒,结束(成功/失败)才关闭,中途不关。
     *
     * @param hintText 已知的线索文本(书源 JSON / 站点页面),用于推断站点子目录;可为 null
     */
    private fun sniffSource(name: String, inputUrl: String, probedContent: String?,
                            kind: CmsSiteImporter.InputKind, checked: Boolean,
                            hintText: String? = null, onComplete: (() -> Unit)? = null) {
        val epoch = importEpoch
        val completed = ImportOnce()
        updateImportHint("正在识别资源站…\n$inputUrl")
        CmsSiteImporter.probeInput(
            inputUrl, probedContent, kind, SubscriptionLibrary.importDir(context),
            object : CmsSiteImporter.Callback {
                override fun onFound(siteName: String, file: File, api: String) {
                    if (!isCurrent(epoch) || !completed.claim()) return
                    dismissImportLoading()
                    val display = if (isDefaultSubName(name)) siteName else name.trim()
                    addLocalFileSubscription(file, display, checked) {
                        showImportToast("资源站已接入")
                        onComplete?.invoke()
                    }
                }

                override fun onNotFound() {
                    if (!isCurrent(epoch) || !completed.claim()) return
                    // 站点类输入:采集接口不可用(如接口关闭)时继续按页面结构抓一次,别让用户只得到一句"没找到"
                    if (kind == CmsSiteImporter.InputKind.SITE && CmsApiRules.looksLikeSiteUrl(inputUrl)) {
                        scrapeSite(name, inputUrl, hintText ?: probedContent, checked, onComplete)
                        return
                    }
                    dismissImportLoading()
                    LogStore.fail(Category.SUBSCRIPTION,
                        "订阅: 资源站未识别到采集接口 (" + logSource(inputUrl) + ")")
                    showImportToast("未找到接口，请换个地址")
                    onComplete?.invoke()
                }
            },
            object : CmsSiteImporter.Progress {
                override fun onProbe(done: Int, total: Int, url: String) {
                    if (!isCurrent(epoch)) return
                    // 显示"第几个候选 + 该候选主机名":用户能看到在动,而不是以为点了没反应
                    val shown = if (done <= 0) "正在查说明页…" else "正在探测接口 $done/$total"
                    updateImportHint("识别资源站：$shown\n${CmsApiRules.displayHost(url)}")
                }
            })
    }

    /** 用户没在弹窗里改名(默认"订阅: N")时,用站点自己的名字 */
    private fun isDefaultSubName(name: String): Boolean {
        val n = name.trim()
        return n.isEmpty() || n.startsWith("订阅:") || n.startsWith("订阅：")
    }

    // ------------------------------------------------------------------
    // 多仓选择
    // ------------------------------------------------------------------

    /** 多仓 {"storeHouse":[{sourceName,sourceUrl},...]}:列出仓源供用户选择后加入(订阅地址返回多仓时同一套逻辑) */
    private fun showStoreHouseChoose(storeHouseList: JsonArray, checked: Boolean,
                                     onComplete: (() -> Unit)? = null) {
        val epoch = importEpoch
        val decision = ImportOnce()
        val sources = ArrayList<Source>()
        for (el in storeHouseList) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val name = obj.stringValue("sourceName").trim().replace("<|>|《|》|-".toRegex(), "")
            val url = obj.stringValue("sourceUrl").trim()
            if (name.isNotEmpty() && url.isNotEmpty()) sources.add(Source(name, url))
        }
        if (sources.isEmpty()) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 多仓配置中没有可用的源")
            showImportToast("多仓配置中没有可用的源")
            onComplete?.invoke()
            return
        }
        host.showStoreHouseChoose(
            sources,
            onPick = pick@{ name, url ->
                if (!isCurrent(epoch) || !decision.claim()) return@pick
                // 选择与 dismiss 最多处理一次；所选仓仍在原会话的递归路径内。
                resumeBatchLoading()
                addSubscription(name, url, checked, onComplete)
            },
            onDismiss = dismiss@{
                if (!isCurrent(epoch) || !decision.claim()) return@dismiss
                if (batchImportInProgress) {
                    resumeBatchLoading()
                    onComplete?.invoke()
                } else {
                    invalidateImport()
                }
            })
    }

    // ------------------------------------------------------------------
    // JSON 小工具
    // ------------------------------------------------------------------

    /** 从 JSON 对象取订阅条目(名称与地址都非空才算有效) */
    private fun subscriptionOf(obj: JsonObject, nameKey: String, urlKey: String): Subscription? {
        val name = obj.stringValue(nameKey).trim()
        val url = obj.stringValue(urlKey).trim()
        return if (name.isNotEmpty() && url.isNotEmpty()) Subscription(name, url) else null
    }

    /** 安全取 JSON 字符串字段(缺失/非字符串返回空串) */
    private fun JsonObject.stringValue(key: String): String {
        val el = get(key) ?: return ""
        return if (el.isJsonPrimitive) el.asString else ""
    }

    /** 是否多仓格式:首条为对象且带 sourceName/sourceUrl */
    private fun isStoreHouseList(list: JsonArray): Boolean {
        return list.size() > 0 && list[0].isJsonObject
            && list[0].asJsonObject.has("sourceName") && list[0].asJsonObject.has("sourceUrl")
    }

    /**
     * 是否"配置类"JSON(整份 TVBox 配置或带规则字段的单源配置):
     * 这类内容不能当订阅条目解析,只能存成本地文件按订阅配置加载。
     */
    private fun looksLikeConfigJson(obj: JsonObject): Boolean {
        for (key in obj.keySet()) {
            if (key.startsWith("rule") || key == "sites" || key == "lives" || key == "spider"
                || key == "parses" || key == "wallpaper" || key == "rules" || key == "storeHouse"
            ) return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // 落表
    // ------------------------------------------------------------------

    /**
     * 仅当选中本地文件和添加的为单线路时,使用此订阅生效。多线路会直接解析全部并添加,多仓会展开并选择,最后也按多线路处理,直接添加
     */
    private fun addSub2List(name: String, url: String, checkNewest: Boolean) {
        if (checkNewest) { //选中最新的,清除以前的选中订阅
            for (subscription in state.subscriptions) {
                if (subscription.isChecked) {
                    subscription.setChecked(false)
                }
            }
            state.selectedUrl = url
            state.subscriptions.add(Subscription(name, url, addOrigin).setChecked(true))
        } else {
            state.subscriptions.add(Subscription(name, url, addOrigin).setChecked(false))
        }
        LogStore.success(Category.SUBSCRIPTION, "订阅: 导入成功 (" + logSource(url) + ")")
        if (checkNewest && state.liveTabActive) host.refreshLiveSources()
    }
}

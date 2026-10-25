package com.github.tvbox.osc.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.spiderapi.CmsApiRules
import com.github.tvbox.osc.transfer.SubscriptionImportFiles
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 本地包导入(TVBox 生态的 ZIP 打包视频源/解析规则/JAR/直播源):
 * 解压到应用专属目录 local_packages/ 下,把包内根目录清单(形如 {"urls":[{name,url},...]} 的 txt)
 * 指向的配置逐条改写成解压目录下的 clan:// 地址;没有清单时扫描包内完整配置兜底。
 * 用户**自己解压好的包**同样支持:直接选包里的清单 txt/json,条目指向原位置(见 [expandExtractedManifest])。
 * 也负责手动粘贴 clan:// 清单地址时的展开,以及删除订阅后对包目录的清理。
 * 路径一律防目录穿越(Zip Slip),压缩包/单条目/总量带上限。
 */
object LocalPackageImporter {

    class ImportResult(val entries: JsonArray, val error: String?)

    /** 压缩包整体上限(解压目录按包内容 SHA-256 摘要命名,同内容重复导入复用已解压目录) */
    private const val MAX_ZIP_BYTES = 256L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRIES = 2000
    /** 清单/配置内容判定上限:订阅配置不会这么大 */
    private const val MAX_JUDGE_BYTES = 2L * 1024 * 1024
    private const val MAX_FALLBACK_CONFIGS = 50
    /** 清单可能埋在包里几层(实际样本有 TVBoxOSC/本地仓/本地仓.txt 这种三层结构) */
    private const val MAX_MANIFEST_DEPTH = 4
    /** 一轮最多解析多少个清单候选(大包也不至于把导入拖死) */
    private const val MAX_MANIFEST_CANDIDATES = 40
    /** 兜底扫描最多读多少个文件 */
    private const val MAX_CONFIG_READS = 200
    /** 判"是否依赖同目录文件"时最多收集多少个相对引用(够用了,别为判定翻遍整份配置) */
    private const val MAX_SIBLING_REFS = 200
    /**
     * 不是订阅配置的资源子目录(接口模板/js 片段/直播清单/多 jar/爬虫脚本/规则模板),扫描时跳过。
     * 跳过它们既省 I/O(道长包光 drpy_js 就四百多个文件),也避免把规则模板当成一条条订阅收进列表。
     */
    private val ASSET_SUBDIRS = setOf(
        "json", "js", "api", "lives", "jars", "py", "lib", "libs",
        "drpy_js", "xbpq", "xyqhiker", "img", "images", "assets"
    )
    /** 配置值里"写死到存储根"的本地服务地址(含 clan:// 形式);改写时逐个试着在包内定位 */
    private val LOCAL_SERVER_REF = Regex(
        "(?:https?://(?:127\\.0\\.0\\.1|localhost)(?::\\d+)?/file/.+|clan://localhost/.+)"
    )

    // ------------------------------------------------------------------
    // 解压 + 扫描(共享执行器上调用,耗时)
    // ------------------------------------------------------------------

    /** 把 SAF 选中的压缩包复制成临时文件(带上限);清单 ZIP 与本地包两条识别路径共用同一份副本 */
    fun copyToTemp(context: Context, uri: Uri): File =
        ImportFileStaging.copyToTemp(context.cacheDir) { target ->
            copyUriToFile(context, uri, target)
        }

    /** 解压 ZIP 并把可用的订阅条目(名称 + 改写后的 clan:// 地址)整理出来;失败返回 error 说明 */
    fun importPackage(context: Context, uri: Uri, displayName: String): ImportResult {
        val zipFile = try {
            copyToTemp(context, uri)
        } catch (t: Throwable) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 本地包读取失败 (${exceptionType(t)})")
            val message = if (t is IOException && !t.message.isNullOrBlank()) t.message else "本地包读取失败"
            return ImportResult(JsonArray(), message)
        }
        try {
            return importPackageFile(context, zipFile, uri.toString(), displayName)
        } finally {
            zipFile.delete()
        }
    }

    /**
     * 已落盘的压缩包导入(本地导入先把 SAF 文件复制成临时文件,清单 ZIP 与本地包共用那一份副本)。
     *
     * 解压目录名 = 包名 + **包内容摘要**:同一份包重复导入复用已解压目录;
     * 包更新(内容变了)会换新目录重新解压 —— 旧目录由订阅删除/整批更新的清理通道回收
     * (见 [cleanupAfterDelete]),这样「更新此批」才拿得到新内容。
     *
     * @param digestSeed 内容摘要算不出时的兜底种子(调用方传 URI 字符串)
     */
    @JvmOverloads
    fun importPackageFile(context: Context, zipFile: File, digestSeed: String,
                          displayName: String, cancelled: () -> Boolean = { false }): ImportResult {
        val root = packageRoot(context) ?: return ImportResult(JsonArray(), "应用存储目录不可用")
        val digest = runCatching { fileDigest(zipFile) }.getOrNull() ?: digestOf(digestSeed)
        val target = File(root, sanitizeBaseName(displayName) + "_" + digest)
        try {
            val existing = SubscriptionImportFiles.runLocked {
                ImportFileStaging.checkActive(cancelled)
                if (target.exists()) scanPackage(target).also {
                    ImportFileStaging.checkActive(cancelled)
                } else null
            }
            if (existing != null) return existing
            return ImportFileStaging.withDirectory(root, target.name) { staging ->
                ImportFileStaging.checkActive(cancelled)
                if (extractZip(zipFile, staging, cancelled) == null)
                    throw IOException("本地包内容无效或超过限制")
                SubscriptionImportFiles.runLocked {
                    // 解压期间另一请求可能已提交，锁内再次判定；不覆盖/清理其它请求的包。
                    // scan 会改写配置，必须先 rename 到最终位置再验证，失败仍在锁内回滚。
                    ImportFileStaging.publishValidated(staging, target, cancelled, ::scanPackage) {
                        it.error == null && it.entries.size() > 0
                    }
                }
            }
        } catch (t: Throwable) {
            if (!cancelled()) LogStore.fail(Category.SUBSCRIPTION, "订阅: 本地包读取失败 (${exceptionType(t)})")
            val message = if (t is IOException && !t.message.isNullOrBlank()) t.message
            else "本地包读取失败"
            return ImportResult(JsonArray(), message)
        }
    }

    /** 解压并返回内容根目录(所有文件条目唯一的公共顶层目录;不唯一时返回解压根) */
    private fun extractZip(zipFile: File, staging: File, cancelled: () -> Boolean): File? {
        val stagingCanonical = staging.canonicalPath
        ZipFile(zipFile).use { zip ->
            var count = 0
            var total = 0L
            val firstDirs = HashSet<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                ImportFileStaging.checkActive(cancelled)
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                count++
                if (count > MAX_ENTRIES) return null
                // Windows 打包工具可能用反斜杠;统一后再做段校验
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("/")) return null
                val segments = name.split('/').filter { it.isNotEmpty() }
                if (segments.any { it == ".." || it == "." }) return null
                if (segments.isNotEmpty()) firstDirs.add(segments[0])
                val target = File(staging, name).canonicalFile
                if (!target.path.startsWith(stagingCanonical + File.separator)) return null
                val parent = target.parentFile ?: return null
                if (!parent.isDirectory && !parent.mkdirs()) return null
                var written = 0L
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            ImportFileStaging.checkActive(cancelled)
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > MAX_ENTRY_BYTES) throw IOException("单文件超过 128 MB")
                            total += read
                            if (total > MAX_TOTAL_BYTES) throw IOException("解压总量超过 512 MB")
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (count == 0) return null
            return if (firstDirs.size == 1) File(staging, firstDirs.first()).canonicalFile else staging
        }
    }

    /** 包内容扫描:优先根目录清单,没有可用的清单条目时扫描包内完整配置兜底 */
    private fun scanPackage(contentRoot: File): ImportResult {
        val entries = scanManifests(contentRoot)
        if (entries.size() > 0) return ImportResult(entries, null)
        val fallback = scanConfigs(contentRoot)
        if (fallback.size() > 0) return ImportResult(fallback, null)
        return ImportResult(JsonArray(), "本地包中未找到可用的订阅配置")
    }

    /**
     * 包内清单({"urls":[...]} 或裸数组):条目地址改写为解压目录下的 clan:// 地址。
     *
     * 清单不一定躺在包根 —— 实际样本里有 {@code TVBoxOSC/本地仓/本地仓.txt} 这种埋在两三层的,
     * 所以按层找(最多 [MAX_MANIFEST_DEPTH] 层、跳过资源目录),取**能解出条目的最浅一层**;
     * 同一层可能有多份(clan:// 与 file:// 各写一份,tvboxqq 就是),clan 形式优先、按改写后的地址去重。
     */
    private fun scanManifests(contentRoot: File): JsonArray {
        val candidates = ArrayList<Pair<Int, File>>()
        collectManifestCandidates(contentRoot, candidates)
        if (candidates.isEmpty()) return JsonArray()
        val byDepth = java.util.TreeMap<Int, MutableList<File>>()
        for ((depth, file) in candidates) byDepth.getOrPut(depth) { ArrayList() }.add(file)
        for (files in byDepth.values) {
            val entries = mergeManifests(contentRoot, files)
            if (entries.size() > 0) return entries
        }
        return JsonArray()
    }

    /** 广度优先收集可能是清单的 txt/json 文件(浅层在前),跳过资源目录,数量封顶 */
    private fun collectManifestCandidates(dir: File, out: MutableList<Pair<Int, File>>) {
        val pending = ArrayDeque<Pair<File, Int>>()
        pending.add(dir to 1)
        while (pending.isNotEmpty() && out.size < MAX_MANIFEST_CANDIDATES) {
            val (current, depth) = pending.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (out.size >= MAX_MANIFEST_CANDIDATES) return
                if (child.isDirectory) {
                    if (depth < MAX_MANIFEST_DEPTH &&
                        child.name.lowercase(Locale.ROOT) !in ASSET_SUBDIRS
                    ) pending.add(child to depth + 1)
                    continue
                }
                if (!child.isFile || child.length() > MAX_JUDGE_BYTES) continue
                val ext = child.extension.lowercase(Locale.ROOT)
                if (ext == "txt" || ext == "json") out.add(depth to child)
            }
        }
    }

    /** 把同一层的若干清单合并成条目表(clan 形式优先,按改写后的地址去重) */
    private fun mergeManifests(contentRoot: File, files: List<File>): JsonArray {
        val clanForm = ArrayList<JsonArray>()
        val otherForm = ArrayList<JsonArray>()
        for (file in files) {
            val root = parseJsonFile(file) ?: continue
            val urls = manifestUrls(root) ?: continue
            if (urls.size() == 0) continue
            (if (usesClanForm(urls)) clanForm else otherForm).add(urls)
        }
        val collected = LinkedHashMap<String, String>() // 改写后的地址 -> 名称
        for (list in clanForm + otherForm) {
            for (el in list) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val name = stringValue(obj, "name").trim()
                    .ifEmpty { stringValue(obj, "sourceName").trim() }
                val url = stringValue(obj, "url").trim()
                    .ifEmpty { stringValue(obj, "sourceUrl").trim() }
                if (url.isEmpty()) continue
                val targetFile = resolvePackageEntry(contentRoot, url) ?: continue
                val text = readText(targetFile) ?: continue
                val shape = CmsApiRules.subscriptionShape(text.trim())
                if (shape != CmsApiRules.SHAPE_CONFIG && shape != CmsApiRules.SHAPE_ENCRYPTED) continue
                rewriteLocalServerRefs(targetFile, contentRoot, text)
                val clanUrl = SubscriptionLibrary.clanPathOf(targetFile)
                if (collected.containsKey(clanUrl)) continue
                val display = name.ifEmpty { targetFile.parentFile?.name ?: targetFile.nameWithoutExtension }
                collected[clanUrl] = display
            }
        }
        val result = JsonArray()
        for ((clanUrl, name) in collected) {
            result.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("url", clanUrl)
            })
        }
        return result
    }

    /** 包内没有清单时兜底:遍历完整配置(跳过资源子目录),按所在目录命名 */
    private fun scanConfigs(contentRoot: File): JsonArray {
        val result = JsonArray()
        collectConfigs(contentRoot, contentRoot, 1, result, intArrayOf(MAX_CONFIG_READS), HashSet())
        return result
    }

    private fun collectConfigs(contentRoot: File, dir: File, depth: Int, out: JsonArray,
                               budget: IntArray, usedNames: MutableSet<String>) {
        if (out.size() >= MAX_FALLBACK_CONFIGS || budget[0] <= 0) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (out.size() >= MAX_FALLBACK_CONFIGS || budget[0] <= 0) return
            if (child.isDirectory) {
                // 资源子目录只在包根以下的层级跳过:根下直接叫 api 的目录仍视为一个仓
                if (depth > 1 && child.name.lowercase(Locale.ROOT) in ASSET_SUBDIRS) continue
                collectConfigs(contentRoot, child, depth + 1, out, budget, usedNames)
                continue
            }
            if (!child.isFile || child.length() > MAX_JUDGE_BYTES) continue
            // 只看可能是配置的文件:逐个读 js/jar/图片纯属浪费(道长包光 drpy_js 就四百多个)
            val ext = child.extension.lowercase(Locale.ROOT)
            if (ext != "json" && ext != "txt") continue
            budget[0]--
            val text = readText(child) ?: continue
            val shape = CmsApiRules.subscriptionShape(text.trim())
            if (shape != CmsApiRules.SHAPE_CONFIG && shape != CmsApiRules.SHAPE_ENCRYPTED) continue
            rewriteLocalServerRefs(child, contentRoot, text)
            // 嵌套在仓目录下的配置按仓名命名;直接放在包根下的按文件名。
            // 同一目录里放了多份配置时(道长包的 custom.json + index.json)补上文件名区分,
            // 免得列表里出现两行一模一样的名字,用户分不清选哪个
            val base = if (dir != contentRoot) dir.name else child.nameWithoutExtension
            val name = if (usedNames.add(base)) base else "$base(${child.nameWithoutExtension})"
            out.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("url", SubscriptionLibrary.clanPathOf(child))
            })
        }
    }

    /**
     * 把配置里写死的"本地服务绝对路径"改写成解压后的实际位置。
     *
     * <p>有的包按"解压到存储根"的前提写死了地址,例如潇洒包的
     * {@code "spider":"http://127.0.0.1:9978/file/TVBoxOSC/本地仓/spider.jar"};
     * 我们解压到应用专属目录,这类引用会 404、整包站点全废。凡是能在包内定位到的引用一律换成
     * clan:// 绝对地址(加载阶段 {@code clanContentFix} 会按真实端口还原成 http),
     * 包外的引用(如 {@code TVBox/bili_cookie.txt} 这类可选令牌文件)原样保留 —— 它们本来就得用户自己放。
     *
     * <p>按 JSON 结构改写而不是正则扫原文:实际样本里大量配置把斜杠转义成 {@code http:\/\/},
     * 原文匹配会整片漏掉。代价是改写过的文件会丢掉 {@code //} 注释行与原有排版
     * (加载阶段本来就会剥注释,不影响使用),没东西可改时一个字都不动。
     *
     * <p>只改**我们自己解压出来的副本**,不动用户的文件;改写是幂等的
     * (改写后的 clan:// 地址再走一次同样的后缀匹配,结果不变)。
     */
    private fun rewriteLocalServerRefs(file: File, contentRoot: File, text: String? = null) {
        try {
            val source = text ?: readText(file) ?: return
            // 廉价预筛:压根没有本地服务地址就别解析了(加密套路的配置也在这一步之后自然跳过)
            if (!source.contains("127.0.0.1") && !source.contains("localhost") &&
                !source.contains("clan://")
            ) return
            val root = parseJsonText(source) ?: return
            var changed = false
            fun walk(element: JsonElement?) {
                when {
                    element == null -> return
                    element.isJsonObject -> {
                        val obj = element.asJsonObject
                        for (key in obj.keySet().toList()) {
                            val value = obj.get(key)
                            if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                                val fixed = rewriteRef(value.asString, contentRoot)
                                if (fixed != null) {
                                    obj.addProperty(key, fixed)
                                    changed = true
                                }
                            } else walk(value)
                        }
                    }
                    element.isJsonArray -> element.asJsonArray.forEach { walk(it) }
                }
            }
            walk(root)
            if (!changed) return
            file.writeText(root.toString(), Charsets.UTF_8)
            LogStore.log(Category.SUBSCRIPTION, "订阅: 本地包配置内的固定地址已改写为解压位置 ${file.name}")
        } catch (t: Throwable) {
            // 改写失败不阻断导入:相对引用("./")那一类仍然可用
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 本地包配置地址改写失败 (${exceptionType(t)})")
        }
    }

    /**
     * 一条字符串值:是"写死到存储根"的本地服务地址、且能在包内定位到文件时,换成解压后的 clan:// 地址。
     * 地址后面挂着的 {@code ;md5;xxx} 校验串先摘掉再定位,改写后原样挂回去。
     */
    private fun rewriteRef(value: String, contentRoot: File): String? {
        val trimmed = value.trim()
        if (!LOCAL_SERVER_REF.matches(trimmed)) return null
        val semi = trimmed.indexOf(';')
        val address = if (semi >= 0) trimmed.substring(0, semi) else trimmed
        val suffix = if (semi >= 0) trimmed.substring(semi) else ""
        val target = resolvePackageEntry(contentRoot, address) ?: return null
        val rewritten = SubscriptionLibrary.clanPathOf(target) + suffix
        return if (rewritten == trimmed) null else rewritten
    }

    // ------------------------------------------------------------------
    // 目录访问(备份/恢复等外部用途)
    // ------------------------------------------------------------------

    /** 本地包根目录;目录不存在时返回 null(备份侧据此决定要不要带本地包) */
    @JvmStatic
    fun packageRootDir(context: Context): File? {
        val root = packageRoot(context) ?: return null
        return if (root.isDirectory) root else null
    }

    /** 确保本地包根目录存在(恢复等写入路径用);创建失败返回 null */
    @JvmStatic
    fun ensurePackageRootDir(context: Context): File? {
        val root = packageRoot(context) ?: return null
        return if (root.isDirectory || root.mkdirs()) root else null
    }

    // ------------------------------------------------------------------
    // 手动粘贴 clan:// 清单地址时的展开(导入协调器的 clan:// 分支调用)
    // ------------------------------------------------------------------

    /**
     * 读本地文件判定是否订阅清单:是则把条目整理出来(file:// 换成 clan://,文件不存在的条目跳过,
     * http 条目原样保留),不是清单(或读不到/超出上限)返回 null,由调用方按单个文件订阅处理。
     */
    fun expandManifest(context: Context, clanUrl: String): JsonArray? {
        val file = primaryFileOf(clanUrl) ?: return null
        if (!file.isFile || file.length() > MAX_JUDGE_BYTES) return null
        val text = readText(file) ?: return null
        val root = parseJsonText(text) ?: return null
        val urls = manifestUrls(root) ?: return null
        val result = JsonArray()
        for (el in urls) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val name = stringValue(obj, "name").trim()
                .ifEmpty { stringValue(obj, "sourceName").trim() }
            val url = stringValue(obj, "url").trim()
                .ifEmpty { stringValue(obj, "sourceUrl").trim() }
            val rewritten = rewriteStandaloneEntry(url) ?: continue
            result.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("url", rewritten)
            })
        }
        return result
    }

    // ------------------------------------------------------------------
    // 已解压到本机存储的本地包(用户直接选包里的清单 txt/json,不是 ZIP)
    // ------------------------------------------------------------------

    /**
     * 读用户选中的清单文件,把条目整理成本地订阅条目。
     *
     * <p>与 ZIP 导入的区别:包已经在盘上(用户自己解压的),不需要复制到应用目录,
     * 条目直接指向原位置。地址按<b>主存储根 → 清单自己所在目录</b>两级解析:
     * 清单里写的 {@code clan://localhost/tvboxqq/x/api.json} 是按"解压到存储根"的惯例写的,
     * 用户把包解压到别处(如 Download/tvboxqq)时按后缀在清单同目录里再找一次。
     *
     * @return null=不是本地包清单(读不到/不是清单/一个本地文件都没命中),调用方按普通订阅文件继续处理
     */
    fun expandExtractedManifest(context: Context, uri: Uri): JsonArray? {
        val text = readUriText(context, uri) ?: return null
        val root = parseJsonText(text) ?: return null
        val urls = manifestUrls(root) ?: return null
        if (urls.size() == 0) return null
        val manifestDir = primaryFileOfUri(uri)?.parentFile
        val collected = LinkedHashMap<String, String>()
        var localHits = 0
        for (el in urls) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val name = stringValue(obj, "name").trim()
                .ifEmpty { stringValue(obj, "sourceName").trim() }
            val url = stringValue(obj, "url").trim()
                .ifEmpty { stringValue(obj, "sourceUrl").trim() }
            if (url.isEmpty()) continue
            val resolved = resolveExtractedEntry(url, manifestDir)
            if (resolved == null) {
                // 网络条目原样保留(与手动粘贴清单同语义),但它不算"本地包命中"
                if (url.startsWith("http://") || url.startsWith("https://")) collected.putIfAbsent(url, name)
                continue
            }
            localHits++
            val clanUrl = SubscriptionLibrary.clanPathOf(resolved)
            if (collected.containsKey(clanUrl)) continue
            collected[clanUrl] = name.ifEmpty {
                resolved.parentFile?.name ?: resolved.nameWithoutExtension
            }
        }
        // 一个本地文件都没命中 = 这就是份普通的网络多线路清单,交给订阅导入的既有流程(别误标成本地包)
        if (localHits == 0) return null
        val result = JsonArray()
        for ((entryUrl, entryName) in collected) {
            result.add(JsonObject().apply {
                addProperty("name", entryName)
                addProperty("url", entryUrl)
            })
        }
        return result
    }

    /** 清单条目地址 → 本机真实文件:先按主存储根,再按清单所在目录做路径段后缀匹配 */
    private fun resolveExtractedEntry(url: String, manifestDir: File?): File? {
        val rel = when {
            url.startsWith("clan://localhost/") -> url.substring("clan://localhost/".length)
            url.startsWith("file://") -> url.substring("file://".length).trimStart('/')
            else -> return null
        }
        if (!isSafeRelPath(rel)) return null
        val fromRoot = File(Environment.getExternalStorageDirectory(), rel)
        if (fromRoot.isFile) return fromRoot
        val segments = rel.split('/').filter { it.isNotEmpty() }
        if (manifestDir != null && segments.size > 1) {
            // 丢掉开头的包目录名(用户解压到哪儿、那层叫什么都不一定),从里层逐级试
            for (i in 1 until segments.size) {
                val candidate = File(manifestDir, segments.drop(i).joinToString("/"))
                // 只认主存储里的命中:清单来自应用 cache 的暂存副本时,同目录命中会把 clan://
                // 指到一个 clan 服务取不到的路径(导入后必然"解析配置失败")
                if (candidate.isFile && SubscriptionLibrary.isUnderPrimaryStorage(candidate)) return candidate
            }
        }
        return null
    }

    /**
     * 用户选中的是"包里的配置文件"(带 {@code ./BXS.jar}、{@code ./json/x.json} 这类同目录依赖)时,
     * 直接以 clan:// 引用**原位置**导入,不复制进应用目录。
     *
     * <p>为什么不能沿用"本地导入一律复制一份"的做法:只把那一个 json 复制走,
     * 它旁边的 jar/js/json 依赖会全部失效(小生包的 {@code "spider":"./BXS.jar"}、
     * 真心包的 {@code ./custom_spider.jar;md5;…}、道长包的 {@code "ext":"./json/tokenm.json"} 都是这种),
     * 导进去就是一份"解析配置失败"的坏订阅。自包含的单文件订阅不受影响,仍走复制流程
     * (原文件删了/改了都不影响订阅)。
     *
     * <p>就地引用不会误删用户文件:删除订阅时的两条清理通道都只认应用自己的目录
     * ({@code subscription_import/} 与 {@code local_packages/})。
     *
     * @return null = 不是这种情况(读不到/不在主存储/不是完整配置/不依赖同目录文件),调用方走原本的复制流程
     */
    fun extractedConfigEntry(uri: Uri): JsonArray? {
        val file = try {
            primaryFileOfUri(uri)?.canonicalFile
        } catch (t: Throwable) {
            null
        } ?: return null
        if (!file.isFile || file.length() > MAX_JUDGE_BYTES) return null
        // 只有主存储里的文件才能就地引用:clan:// 是按 /storage/emulated/0 取文件的,
        // 系统"打开方式"进来的是应用 cache 下的暂存副本,引用过去等于指向一个取不到的路径。
        if (!SubscriptionLibrary.isUnderPrimaryStorage(file)) return null
        val text = readText(file) ?: return null
        val shape = CmsApiRules.subscriptionShape(text.trim())
        if (shape != CmsApiRules.SHAPE_CONFIG && shape != CmsApiRules.SHAPE_ENCRYPTED) return null
        if (!dependsOnSiblings(text, file)) return null
        val result = JsonArray()
        result.add(JsonObject().apply {
            addProperty("name", packageDisplayNameOf(file))
            addProperty("url", SubscriptionLibrary.clanPathOf(file))
        })
        return result
    }

    /**
     * 配置有没有"同目录依赖":配置里写的相对引用({@code ./BXS.jar}、{@code ./lib/x.js})或 spider 文件名,
     * 必须在配置所在目录里能解析到<b>真实文件</b>才算。
     *
     * <p>为什么不能只扫文本里有没有 {@code "./"}:网络订阅里 {@code "spider":"./CFlLnSbJ"}、
     * {@code "api":"./lib/x.js"} 这种相对写法很常见(依赖挂在作者自己的站点/仓库上),
     * 用户手里只有这一个 json 时旁边根本没有那些文件。按字符串命中就会把它误判成"包里的配置",
     * 于是跳过复制、就地 {@code clan://} 引用原位置:轻则订阅名变成父目录名,
     * 重则引用到应用 cache 这类非主存储路径(clan 服务取不到)让应用每次启动都"解析配置失败"。
     */
    private fun dependsOnSiblings(text: String, file: File): Boolean {
        val dir = file.parentFile ?: return false
        val root = parseJsonText(text) ?: return false
        val refs = LinkedHashSet<String>()
        collectSiblingRefs(root, refs)
        for (ref in refs) {
            if (resolveSibling(dir, ref) != null) return true
        }
        return false
    }

    /** 收集配置里可能指向同目录文件的引用:相对路径写法,以及不带协议的 spider 文件名 */
    private fun collectSiblingRefs(element: JsonElement, out: MutableSet<String>) {
        if (out.size >= MAX_SIBLING_REFS) return
        when {
            element.isJsonPrimitive -> {
                if (!element.asJsonPrimitive.isString) return
                val value = element.asString.trim()
                if (value.startsWith("./") || value.startsWith("../")) out.add(value)
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                // "spider":"xxx.jar" / "spider":"xxx.jar;md5;…" 这类不带 ./ 的包内写法同样算
                val spider = stringValue(obj, "spider").trim()
                if (spider.isNotEmpty() && !spider.contains("://")) out.add(spider)
                for (entry in obj.entrySet()) collectSiblingRefs(entry.value, out)
            }
            element.isJsonArray -> {
                for (item in element.asJsonArray) collectSiblingRefs(item, out)
            }
        }
    }

    /** 同目录里真能找到这个文件才算依赖;解析后必须还在配置所在目录内(防目录穿越) */
    private fun resolveSibling(dir: File, ref: String): File? {
        val raw = ref.substringBefore(';').trim() // "spider":"a.jar;md5;…" 取文件名那一段
        if (raw.isEmpty() || raw.contains("://")) return null
        return try {
            val base = dir.canonicalFile
            val candidate = File(base, raw).canonicalFile
            when {
                !candidate.path.startsWith(base.path + File.separator) -> null
                candidate.isFile -> candidate
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 展示名:取配置所在的<b>目录名</b>。包里的配置一般叫 api.json / custom.json / FongMi.json 这种通用名,
     * 目录名(BXSTVBox、drpy_dzlive、真心20260330增量包)才认得出是哪个包;
     * 配置直接放在存储根下时才退回文件名。
     */
    private fun packageDisplayNameOf(file: File): String {
        val parent = file.parentFile ?: return file.nameWithoutExtension
        val root = try {
            Environment.getExternalStorageDirectory().canonicalFile
        } catch (t: Throwable) {
            null
        }
        return if (root != null && parent == root) file.nameWithoutExtension else parent.name
    }

    /** SAF 文档 URI → 主存储里的真实文件(拿不到/不在主存储返回 null;只用于定位清单同目录) */
    private fun primaryFileOfUri(uri: Uri): File? {
        try {
            if ("file".equals(uri.scheme, true)) return uri.path?.let { File(it) }
            val docId = android.provider.DocumentsContract.getDocumentId(uri)
            if (docId.startsWith("raw:")) return File(docId.substring("raw:".length))
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2 || !"primary".equals(parts[0], true)) return null
            val root = Environment.getExternalStorageDirectory().canonicalFile
            val file = File(root, parts[1]).canonicalFile
            return if (file.path.startsWith(root.path + File.separator)) file else null
        } catch (t: Throwable) {
            return null
        }
    }

    // ------------------------------------------------------------------
    // 删除订阅后的包目录清理
    // ------------------------------------------------------------------

    /**
     * 删除订阅时调用:订阅地址指向本地包文件,且没有其它订阅引用同一包目录时,整个包目录一并清掉;
     * 还有引用时不动文件(保留在盘上,重新导入同一包可恢复已删条目)。
     */
    fun cleanupAfterDelete(context: Context, deletedUrls: Collection<String>, remainingUrls: Collection<String>): Boolean {
        return try {
            SubscriptionImportFiles.runLocked {
                val referenced = remainingUrls.mapNotNull { packageRefOf(context, it)?.dir?.path }.toSet()
                val targets = deletedUrls.mapNotNull { packageRefOf(context, it)?.dir }.distinctBy { it.path }
                var complete = true
                for (dir in targets) {
                    if (dir.path in referenced || !dir.exists()) continue
                    if (dir.deleteRecursively()) {
                        LogStore.log(Category.SUBSCRIPTION, "订阅: 删除订阅后清理本地包目录 ${dir.name}")
                    } else {
                        complete = false
                        LogStore.fail(Category.SUBSCRIPTION, "订阅: 本地包目录未能完整清理 ${dir.name}")
                    }
                }
                complete
            }
        } catch (t: Throwable) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 清理本地包文件失败 (${exceptionType(t)})")
            false
        }
    }

    /** 删除确认框的补充说明:该订阅是包目录的最后一个引用时提示文件将一并删除,否则无需提示 */
    fun cleanupNote(context: Context, url: String, remainingUrls: Collection<String>): String? {
        val ref = packageRefOf(context, url) ?: return null
        val stillReferenced = remainingUrls.any { other ->
            packageRefOf(context, other)?.dir?.path == ref.dir.path
        }
        return if (stillReferenced) null else "该订阅的本地包文件将一并删除"
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 本地包根目录:应用专属目录下的 local_packages/ */
    private fun packageRoot(context: Context): File? {
        val external = context.getExternalFilesDir(null) ?: return null
        return File(external, "local_packages")
    }

    private class PackageRef(val root: File, val file: File, val dir: File)

    /** 订阅地址是否指向本地包目录里的文件(只认应用内部 local_packages 目录,防误删别处文件) */
    private fun packageRefOf(context: Context, url: String): PackageRef? {
        if (url == null || !url.startsWith("clan://localhost/")) return null
        try {
            val root = packageRoot(context)?.canonicalFile ?: return null
            val file = primaryFileOf(url)?.canonicalFile ?: return null
            if (!file.path.startsWith(root.path + File.separator)) return null
            val rel = file.relativeTo(root).path.replace('\\', '/')
            val first = rel.split('/').firstOrNull { it.isNotEmpty() } ?: return null
            return PackageRef(root, file, File(root, first))
        } catch (t: Throwable) {
            return null
        }
    }

    /** clan://localhost/ 地址 → 主存储里的真实文件 */
    private fun primaryFileOf(url: String): File? {
        val prefix = "clan://localhost/"
        if (url == null || !url.startsWith(prefix)) return null
        val rel = url.substring(prefix.length)
        if (!isSafeRelPath(rel)) return null
        return File(Environment.getExternalStorageDirectory(), rel)
    }

    private fun isSafeRelPath(rel: String): Boolean {
        if (rel.isEmpty() || rel.startsWith("/")) return false
        return rel.split('/').none { it == ".." || it == "." }
    }

    /** 清单条目地址 → 解压目录下的真实文件(按路径段从长到短匹配,命中第一个存在的文件) */
    private fun resolvePackageEntry(contentRoot: File, url: String): File? {
        val path = when {
            url.startsWith("clan://") -> url.substringAfter("://").substringAfter("/")
            url.startsWith("file://") -> url.substring("file://".length).trimStart('/')
            url.startsWith("http://") || url.startsWith("https://") -> {
                val marker = "/file/"
                val at = url.indexOf(marker)
                if (at >= 0) url.substring(at + marker.length) else return null
            }
            else -> return null
        }
        if (path.isEmpty() || !isSafeRelPath(path)) return null
        val segments = path.split('/')
        for (i in segments.indices) {
            val candidate = File(contentRoot, segments.drop(i).joinToString("/"))
            if (candidate.isFile) return candidate
        }
        return null
    }

    /** 手动粘贴的清单地址里的条目:clan:// 与 file:// 只在目标文件确实存在时保留/改写,http 原样 */
    private fun rewriteStandaloneEntry(url: String): String? {
        val root = Environment.getExternalStorageDirectory()
        return when {
            url.startsWith("clan://localhost/") -> {
                val rel = url.substring("clan://localhost/".length)
                if (isSafeRelPath(rel) && File(root, rel).isFile) url else null
            }
            url.startsWith("file://") -> {
                val rel = url.substring("file://".length).trimStart('/')
                if (isSafeRelPath(rel) && File(root, rel).isFile) "clan://localhost/$rel" else null
            }
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> null
        }
    }

    /** JSON 里的清单条目数组:对象取 "urls",裸数组直接用;带 sites 的是配置本身,不算清单 */
    private fun manifestUrls(root: JsonElement): JsonArray? {
        if (root.isJsonArray) return root.asJsonArray
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        if (obj.has("sites")) return null
        val urls = obj.get("urls") ?: return null
        return if (urls.isJsonArray) urls.asJsonArray else null
    }

    private fun usesClanForm(list: JsonArray): Boolean {
        for (el in list) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val url = stringValue(obj, "url").ifEmpty { stringValue(obj, "sourceUrl") }
            if (url.startsWith("clan://")) return true
        }
        return false
    }

    private fun stringValue(obj: JsonObject, key: String): String {
        val el = obj.get(key) ?: return ""
        return if (el.isJsonPrimitive) el.asString else ""
    }

    private fun parseJsonFile(file: File): JsonElement? {
        val text = readText(file) ?: return null
        return parseJsonText(text)
    }

    /** 清单/配置一律先剥注释与 BOM(与加载阶段同一套容忍度),解析失败返回 null */
    private fun parseJsonText(text: String): JsonElement? {
        return try {
            JsonParser.parseString(CmsApiRules.stripJsonNoise(text))
        } catch (t: Throwable) {
            null
        }
    }

    /** UTF-8 优先(去 BOM);解码出替换符时按 GBK 重读(本地包说明文档常为 GBK) */
    private fun readText(file: File): String? {
        if (file.length() > MAX_JUDGE_BYTES) return null
        return try {
            decodeBytes(file.readBytes())
        } catch (t: Throwable) {
            null
        }
    }

    /** 读 SAF 选中的文本内容(带上限);读不出/超限返回 null */
    private fun readUriText(context: Context, uri: Uri): String? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val read = source.read(chunk)
                    if (read < 0) break
                    if (buffer.size() + read > MAX_JUDGE_BYTES) return null
                    buffer.write(chunk, 0, read)
                }
                decodeBytes(buffer.toByteArray())
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun decodeBytes(bytes: ByteArray): String {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) 3 else 0
        val utf8 = String(bytes, start, bytes.size - start, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) String(bytes, start, bytes.size - start, Charset.forName("GBK"))
        else utf8
    }

    private fun copyUriToFile(context: Context, uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("文件打不开")
        input.use { source ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_ZIP_BYTES) throw IOException("本地包 ZIP 超过 256 MB")
                    out.write(buffer, 0, read)
                }
            }
        }
    }

    private fun sanitizeBaseName(displayName: String): String {
        var n = displayName.replace(Regex("[/\\\\:*?\"<>|\\u0000\\s]"), "_").trim()
        if (n.lowercase(Locale.ROOT).endsWith(".zip")) n = n.substring(0, n.length - 4)
        if (n.isEmpty()) n = "本地包"
        if (n.length > 40) n = n.substring(0, 40)
        return n
    }

    private fun digestOf(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format(Locale.ROOT, "%02x", b))
        return sb.substring(0, 8)
    }

    /** 文件内容摘要(解压目录名用:同内容复用目录,内容更新换目录,与 [digestOf] 同格式) */
    private fun fileDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val bytes = digest.digest()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format(Locale.ROOT, "%02x", b))
        return sb.substring(0, 8)
    }

    private fun exceptionType(error: Throwable?): String =
        error?.javaClass?.simpleName ?: "未知错误"
}

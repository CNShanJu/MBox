package com.github.catvod.crawler

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.util.HttpClient
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * py 源(内嵌 CPython)加载器:与 [JsLoader] / [JarLoader] 同层,由 `ApiConfig.getCSP` 分流进入。
 *
 * <p>职责:
 *  - 懒启动 Python 运行时(只有真的用到 py 源才付出这份启动开销,不影响开屏);
 *  - 把订阅里 `sites[].api` 指向的 .py 文件准备成本地可执行文件:
 *    `clan://`(应用专属目录/本地包)、`http://127.0.0.1:<port>/file/...`(本地服务地址)、
 *    `file://` 一律按本机路径读;远端 `http(s)://` 下载到 `filesDir/py/` 并缓存;
 *  - 按站点 key 缓存 [SpiderPY] 实例,创建失败进冷却(与 JS/JAR 源同一套语义)。
 *
 * <p>为什么先落一份副本再执行:源文件可能被用户删掉/改名(导入目录里的文件)或被系统挂载点挡住,
 * 副本在私有目录里始终可读;文件名带源内容摘要,源更新后自然换新文件,旧字节码不会命中。
 */
class PyLoader {

    companion object {

        private const val TAG = "PySpider"

        /** 远端 .py 的缓存有效期:过期就重新下载(本地文件不受影响) */
        private const val NET_CACHE_MS = 6 * 60 * 60 * 1000L

        /** 创建失败后的冷却期:坏源不再每次调用都白跑一次初始化 */
        private const val FAIL_COOLDOWN_MS = 5 * 60 * 1000L

        /** 只保留同一源最近几份副本(源频繁更新时避免私有目录越来越胖) */
        private const val KEEP_COPIES = 3

        @Volatile
        private var context: Context? = null

        @Volatile
        private var pythonStarted = false

        private val spiders = ConcurrentHashMap<String, Spider>()
        private val failedAt = ConcurrentHashMap<String, Long>()
        private val createLocks = ConcurrentHashMap<String, Any>()

        /** 注入 application context(与 JsLoader/JarLoader 一致,由 ApiConfig.setAppContext 同步) */
        @JvmStatic
        fun setContext(c: Context?) {
            context = c?.applicationContext
        }

        /** 本机 HTTP 服务基址(含动态端口):py 源的 getProxyUrl 拼在这里 */
        @JvmStatic
        fun serverBase(): String = ApiConfig.localBase()

        /**
         * 懒启动 Python:Chaquopy 首次调用要加载 libpython 与标准库(百毫秒级),
         * 因此只在真有 py 源时启动;启动失败按"本次不可用"返回 false,不抛给上层。
         */
        @JvmStatic
        fun ensurePython(ctx: Context): Boolean {
            if (pythonStarted) return true
            synchronized(this) {
                if (pythonStarted) return true
                return try {
                    if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
                    pythonStarted = true
                    LogStore.log(Category.SUBSCRIPTION, "订阅: Python 运行时已就绪(py 源)")
                    true
                } catch (error: Throwable) {
                    LOG.e(TAG, "Python 运行时启动失败 (" + error + ")")
                    LogStore.fail(Category.SUBSCRIPTION, "订阅: Python 运行时启动失败 (" + error.javaClass.simpleName + ")")
                    false
                }
            }
        }

        /** 换订阅/重载配置:整体退役现有实例(下次调用按新配置重建) */
        @JvmStatic
        fun load() {
            for (spider in spiders.values) runCatching { spider.destroy() }
            spiders.clear()
            failedAt.clear()
            createLocks.clear()
            SpiderFaults.get().clear()
        }

        /**
         * 取消在跑任务。Python 侧没有可安全中断的取消点(源是同步代码),
         * 这里保持空实现:调用方拿到的是"本次结果不再采用"的语义,由上层 epoch 兜底。
         */
        @JvmStatic
        fun stopAll() {
        }

        /**
         * 取站点实例。api 以 `.py` 结尾(或带 `?`/`;md5;` 后缀)的站点才会走到这里,
         * 由 `ApiConfig.getCSP` 分流;返回 [SpiderNull] 表示"这个源当前不可用"。
         */
        @JvmStatic
        fun getSpider(key: String, api: String, ext: String, jar: String): Spider {
            // 快路径:实例还能用就直接给。卡死过/已销毁的实例必须换掉 ——
            // 它的单线程道被上一次没跑完的 Python 调用占着,继续用只会让这个源永远超时
            val cached = spiders[key]
            if (cached != null) {
                if (cached !is SpiderPY || cached.isUsable()) return cached
                spiders.remove(key)
                runCatching { cached.destroy() }
                LogStore.fail(Category.SUBSCRIPTION, "订阅: py 源实例已作废,重建 key=" + key)
            }
            val ctx = context
            if (ctx == null) {
                LOG.e(TAG, "py 源加载失败:尚未注入 context")
                return SpiderNull()
            }
            val lastFail = failedAt[key]
            if (lastFail != null && System.currentTimeMillis() - lastFail < FAIL_COOLDOWN_MS) {
                SpiderFaults.get().markUnavailable(key, "py 源加载失败(稍后自动重试)")
                return SpiderNull()
            }
            synchronized(createLocks.computeIfAbsent(key) { Any() }) {
                spiders[key]?.let { return it }
                if (!ensurePython(ctx)) {
                    SpiderFaults.get().markUnavailable(key, "py 运行时不可用")
                    return SpiderNull()
                }
                return try {
                    val source = prepareSource(ctx, api)
                        ?: throw IllegalStateException("py 源文件不可用: " + api)
                    val spider = SpiderPY(key, api, source, ext)
                    spiders[key] = spider
                    failedAt.remove(key)
                    SpiderFaults.get().markAvailable(key)
                    LogStore.success(Category.SUBSCRIPTION, "订阅: py 源已加载 " + source.name)
                    spider
                } catch (error: Throwable) {
                    failedAt[key] = System.currentTimeMillis()
                    SpiderFaults.get().markUnavailable(key, "py 源加载失败")
                    LogStore.fail(Category.SUBSCRIPTION,
                        "订阅: py 源加载失败 (" + error.javaClass.simpleName + ")")
                    LOG.e(TAG, "py 源加载失败: $key (" + error + ")")
                    SpiderNull()
                }
            }
        }

        /** 本机代理转发(`/proxy?do=py&siteKey=...`) */
        @JvmStatic
        fun proxyInvoke(params: Map<String, String>?): Array<Any>? {
            return try {
                val key = params?.get("siteKey")
                val target = (if (key.isNullOrEmpty()) null else spiders[key]) as? SpiderPY
                    ?: spiders.values.filterIsInstance<SpiderPY>().firstOrNull()
                    ?: return null
                target.proxyLocal(params)
            } catch (error: Throwable) {
                LOG.e(TAG, "py 代理调用失败 (" + error + ")")
                null
            }
        }

        // ------------------------------------------------------------------
        // 源文件准备
        // ------------------------------------------------------------------

        /**
         * api 地址 → 私有目录里的可执行 .py 文件。
         *
         * 本机路径(clan:// / 127.0.0.1:port/file/ / file:// / 绝对路径)复制一份带内容摘要的副本;
         * 远端地址下载后缓存 [NET_CACHE_MS]。都失败返回 null(由调用方按"源不可用"处理)。
         */
        private fun prepareSource(ctx: Context, api: String): File? {
            val clean = api.substringBefore(";md5;").substringBefore(";").trim()
            if (clean.isEmpty()) return null
            val dir = File(ctx.filesDir, "py")
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val tag = MD5.encode(clean).lowercase(Locale.ROOT)
            // 先按地址解析;解析不到再按"文件名"在应用自己的订阅导入目录里找一次 ——
            // 地址被 clan/http 改写、URL 转义、异常斜杠都兜得住(只认导入目录 + 纯文件名,不越界)
            val local = localFileOf(clean)?.takeIf { it.isFile && it.length() > 0L }
                ?: importedFileOf(ctx, clean)
            if (local != null) {
                val digest = MD5.getFileMd5(local)
                if (digest.isNullOrEmpty()) return null
                val target = File(dir, "src_" + tag + "_" + digest.lowercase(Locale.ROOT) + ".py")
                if (!target.isFile || target.length() != local.length()) {
                    runCatching { local.copyTo(target, overwrite = true) }.getOrElse { return null }
                }
                trimCopies(dir, "src_" + tag + "_")
                return target
            }
            if (!clean.startsWith("http://") && !clean.startsWith("https://")) return null
            val target = File(dir, "net_" + tag + ".py")
            val fresh = target.isFile && target.length() > 0L &&
                System.currentTimeMillis() - target.lastModified() < NET_CACHE_MS
            if (fresh) return target
            return try {
                HttpClient.downloadSync(clean, target)
                if (target.isFile && target.length() > 0L) target
                else target.takeIf { it.isFile && it.length() > 0L }
            } catch (error: Throwable) {
                // 下载失败但有旧副本:旧副本仍可用(订阅地址临时不可达时不至于整源瘫掉)
                LOG.e(TAG, "py 源下载失败: $clean (" + error + ")")
                target.takeIf { it.isFile && it.length() > 0L }
            }
        }

        /**
         * 兜底定位:按地址末段的文件名,在应用订阅导入目录里找同名 .py。
         * 只用文件名(不含任何目录段),因此不存在目录穿越;找不到返回 null。
         */
        private fun importedFileOf(ctx: Context, api: String): File? {
            return try {
                val name = Uri.decode(api.substringAfterLast('/').substringBefore('?').substringBefore(';'))
                if (name.isEmpty() || !name.endsWith(".py", ignoreCase = true)) return null
                val importDir = ctx.getExternalFilesDir(null)?.let { File(it, "subscription_import") } ?: return null
                val candidate = File(importDir, name)
                if (candidate.isFile && candidate.length() > 0L) {
                    LOG.i(TAG, "py 源按文件名兜底命中: $name")
                    candidate
                } else {
                    null
                }
            } catch (error: Throwable) {
                null
            }
        }

        /** 地址像本机文件时给出真实路径;网络地址返回 null */
        private fun localFileOf(url: String): File? {
            return try {
                when {
                    url.startsWith("clan://localhost/") ->
                        File(Environment.getExternalStorageDirectory(), url.removePrefix("clan://localhost/"))
                    url.startsWith("clan://") -> {
                        // clan://<host>/<相对路径>:host 不是本机时同样按"主存储相对路径"兜一次
                        val rest = url.removePrefix("clan://")
                        val slash = rest.indexOf('/')
                        if (slash < 0) null
                        else File(Environment.getExternalStorageDirectory(), rest.substring(slash + 1))
                    }
                    url.startsWith("file://") -> Uri.parse(url).path?.let { File(it) }
                    url.startsWith("/") -> File(url)
                    isLoopbackFileUrl(url) -> loopbackFileOf(url)
                    else -> null
                }
            } catch (error: Throwable) {
                LOG.e(TAG, "py 源路径解析失败: $url (" + error + ")")
                null
            }
        }

        /**
         * 本机 HTTP 服务的 {@code /file/<rel>} 地址 → 主存储里的真实文件。
         *
         * <p>注意 {@code localhost} 这一段:订阅配置里的 {@code clan://localhost/xxx} 在加载时会被
         * `ApiConfig.clanContentFix` 把 {@code clan://} 整段换成 {@code http://127.0.0.1:端口/file/},
         * 于是原地址的 host 变成路径开头的 {@code localhost/}(本机服务的 /file 路由并不认这一段,
         * 直接请求也是 404)。这里把它剥掉,否则 py/JAR 这类"api 直接指向文件"的源永远找不到文件。
         */
        private fun loopbackFileOf(url: String): File? {
            var relative = Uri.decode(url.substringAfter("/file/"))
            if (relative.startsWith("localhost/")) relative = relative.removePrefix("localhost/")
            relative = relative.trimStart('/')
            if (relative.isEmpty()) return null
            return File(Environment.getExternalStorageDirectory(), relative)
        }

        /** 本机 HTTP 服务给出的文件地址:http://127.0.0.1:<port>/file/<主存储相对路径> */
        private fun isLoopbackFileUrl(url: String): Boolean {
            if (!url.contains("/file/")) return false
            return url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")
        }

        /** 同一源只留最近 [KEEP_COPIES] 份(文件名里带内容摘要,越新的修改时间越大) */
        private fun trimCopies(dir: File, prefix: String) {
            val copies = dir.listFiles { file -> file.isFile && file.name.startsWith(prefix) } ?: return
            if (copies.size <= KEEP_COPIES) return
            copies.sortedByDescending { it.lastModified() }
                .drop(KEEP_COPIES)
                .forEach { runCatching { it.delete() } }
        }
    }
}

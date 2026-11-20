package com.github.catvod.crawler

import android.util.Base64
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.util.LOG
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 单个 py 源实例:把 catvod 的 [Spider] 契约转发给 Python 里的 `Spider` 子类。
 *
 * <p>约定与 drpy(以及 FongMi 系)一致:源文件里 `from base.spider import Spider`,
 * 定义 `class Spider(Spider)` 并实现 init/homeContent/categoryContent/detailContent/
 * searchContent/playerContent(可按需覆盖 localProxy/action/isVideoFormat)。
 * Python 侧基类见 `spider/src/main/python/base/spider.py`。
 *
 * <p>两个实现细节:
 *  - 参数与返回值一律走 JSON(`box.bridge.loads/dump`):Java 的 List/Map 直接传进 Python 会变成
 *    Java 代理对象,源里的 `ids[0]`、`extend.get('x')` 行为不一致;JSON 中转后就是原生 list/dict。
 *  - 每个实例一条单线程 executor:Python 有 GIL,同源并发调用串行化反而更稳,超时也能靠 Future 兜住
 *    (源里的同步死循环无法中断,超时后只保证本次调用不再被采用,实例照旧复用)。
 */
class SpiderPY(
    private val key: String,
    private val api: String,
    private val sourceFile: File,
    private val extend: String
) : Spider() {

    companion object {
        private const val TAG = "PySpider"
        /**
         * 超时上限比"源自己的一次请求"更宽:py 源普遍在内部带重试(七五影视是 15s × 2 次),
         * 上限卡太紧只会把正常但慢的请求判死(实测 20s 上限 → 详情直接空白)。
         */
        private const val INIT_TIMEOUT_MS = 30_000L
        private const val HOME_TIMEOUT_MS = 30_000L
        private const val LIST_TIMEOUT_MS = 45_000L
        private const val SEARCH_TIMEOUT_MS = 45_000L
        private const val PLAY_TIMEOUT_MS = 60_000L
        private const val PROXY_TIMEOUT_MS = 60_000L
        private const val ACTION_TIMEOUT_MS = 15_000L

        /** 每源两条道:一个慢请求(等 socket)不至于把同源的详情/播放全堵住(Python 侧 GIL 自会串行) */
        private const val CALL_THREADS = 2

        private val GSON = Gson()
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(CALL_THREADS) { runnable ->
        Thread(runnable, "py-$key").also { it.isDaemon = true }
    }

    private val instance: PyObject

    /**
     * 调用超时过 = 实例不可再用。
     *
     * Python 是同步执行,`Future.cancel(true)` 只能打断等待、打断不了正在跑的 Python 字节码;
     * 一旦某个源卡死,这条单线程道就会一直被它占住,后续调用全部排队超时(现象:该源永远转圈)。
     * 标记后由 [PyLoader] 摘掉缓存、换新实例(重新执一次模块),源才有机会自愈。
     */
    @Volatile
    var wedged: Boolean = false
        private set

    /** 还能不能继续用:没被销毁过、也没卡死 */
    fun isUsable(): Boolean = !wedged && !executor.isShutdown

    init {
        siteKey = key
        instance = createInstance()
    }

    // ------------------------------------------------------------------
    // 创建
    // ------------------------------------------------------------------

    private fun createInstance(): PyObject {
        val python = Python.getInstance()
        val util = python.getModule("importlib.util")
        // 模块名带上"站点 key + 地址"的摘要:同一份文件被不同站点引用时不会互相污染 sys.modules
        val moduleName = "mbox_py_" + com.github.tvbox.osc.util.MD5.encode(key + "|" + api)
        val spec = util.callAttr("spec_from_file_location", moduleName, sourceFile.absolutePath)
            ?: throw IllegalStateException("py 源文件无法装载: ${sourceFile.name}")
        val module = util.callAttr("module_from_spec", spec)
            ?: throw IllegalStateException("py 源模块无法创建: ${sourceFile.name}")
        // 注意用 get("loader") 取属性再调 exec_module:callAttr 是"取属性并调用",
        // 对 loader 这种对象属性会变成 loader() → TypeError: 'SourceFileLoader' object is not callable
        val loader = spec.get("loader")
            ?: throw IllegalStateException("py 源无法装载: ${sourceFile.name}")
        loader.callAttr("exec_module", module)
        val clazz = module.get("Spider")
            ?: throw IllegalStateException("py 源缺少 Spider 类: ${sourceFile.name}")
        val obj = clazz.call()
        // 宿主注入:站点 key(拼本机代理)与本机服务地址;源自己没写这两个属性也能用
        runCatching { obj.put("skey", key) }
        runCatching { obj.put("server", PyLoader.serverBase()) }
        val initResult = bridge().callAttr("call", obj, "init", argsOf(listOf(extend)))
        LOG.i(TAG, "py 源已创建 key=$key 模块=$moduleName init=" + (initResult != null))
        return obj
    }

    // ------------------------------------------------------------------
    // 契约转发
    // ------------------------------------------------------------------

    override fun homeContent(filter: Boolean): String =
        result(invoke("homeContent", listOf(filter), HOME_TIMEOUT_MS))

    override fun homeVideoContent(): String =
        result(invoke("homeVideoContent", emptyList(), HOME_TIMEOUT_MS))

    override fun categoryContent(tid: String, pg: String, filter: Boolean, extend: HashMap<String, String>): String =
        result(invoke("categoryContent", listOf(tid, pg, filter, extend), LIST_TIMEOUT_MS))

    override fun detailContent(ids: List<String>): String {
        val text = result(invoke("detailContent", listOf(ids), LIST_TIMEOUT_MS))
        if (text.isEmpty()) return text
        return normalizeVodId(text, ids.firstOrNull())
    }

    /**
     * 详情返回的 {@code vod_id} 统一回填成"请求时用的 id"。
     *
     * <p>为什么必须归一:页面用 {@code isCurrentDetail()} 判"这条详情是不是当前这条"
     * (id 与 sourceKey 都要相等),不相等就直接丢弃 → 详情页永远转圈(实测:七五影视把
     * {@code vod_id} 改写成 {@code https://站点/mv/xxx.html} 绝对地址,与请求的 {@code /mv/xxx.html}
     * 不等,数据被静默丢掉)。jar/js 源习惯原样回传 id,所以这条约束平时看不出来。
     */
    private fun normalizeVodId(text: String, requested: String?): String {
        if (requested.isNullOrEmpty()) return text
        return try {
            val root = com.google.gson.JsonParser.parseString(text)
            val list = root.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonArray("list") ?: return text
            var changed = false
            for (element in list) {
                if (!element.isJsonObject) continue
                val obj = element.asJsonObject
                if (obj.get("vod_id")?.isJsonPrimitive != true) continue
                if (obj.get("vod_id").asString == requested) continue
                obj.addProperty("vod_id", requested)
                changed = true
            }
            if (changed) root.toString() else text
        } catch (error: Throwable) {
            LOG.e(TAG, "详情 vod_id 归一失败(" + error + ")")
            text
        }
    }

    override fun searchContent(key: String, quick: Boolean): String =
        result(invoke("searchContent", listOf(key, quick), SEARCH_TIMEOUT_MS))

    override fun searchContent(key: String, quick: Boolean, pg: String): String =
        result(invoke("searchContent", listOf(key, quick, pg), SEARCH_TIMEOUT_MS))

    override fun playerContent(flag: String, id: String, vipFlags: List<String>): String {
        // header 按应用侧约定统一成 JSON 字符串(dict / 单引号字典两种写法都在这里抹平)
        val raw = invoke("playerContent", listOf(flag, id, vipFlags), PLAY_TIMEOUT_MS) ?: return ""
        val normalized = runCatching { bridge().callAttr("normalize_player", raw) }.getOrNull() ?: raw
        return result(normalized)
    }

    override fun isVideoFormat(url: String): Boolean =
        invoke("isVideoFormat", listOf(url), ACTION_TIMEOUT_MS)?.toBoolean() ?: false

    override fun manualVideoCheck(): Boolean =
        invoke("manualVideoCheck", emptyList(), ACTION_TIMEOUT_MS)?.toBoolean() ?: false

    override fun action(action: String): String =
        result(invoke("action", listOf(action), ACTION_TIMEOUT_MS))

    /**
     * 本机代理:`/proxy?do=py&siteKey=...&url=...` 转成 Python 的 `localProxy(params)`。
     * 返回值按本机服务的约定给 [状态码, Content-Type, 字节内容]。
     */
    override fun proxyLocal(params: Map<String, String>?): Array<Any>? {
        val bridge = bridge()
        val payload = bridge.callAttr("loads", GSON.toJson(listOf(params ?: emptyMap<String, String>())))
        val encoded = submit({ bridge.callAttr("call", instance, "localProxy", payload) }, PROXY_TIMEOUT_MS)
            ?: return null
        val tuple = runCatching { bridge.callAttr("to_response", encoded) }.getOrNull() ?: return null
        val parts = runCatching { tuple.asList() }.getOrNull() ?: return null
        if (parts.size < 3) return null
        val code = runCatching { parts[0].toInt() }.getOrDefault(200)
        val contentType = runCatching { parts[1].toString() }.getOrDefault("text/plain; charset=utf-8")
        val body = runCatching { Base64.decode(parts[2].toString(), Base64.DEFAULT) }.getOrNull() ?: return null
        return arrayOf<Any>(code, contentType, body)
    }

    override fun destroy() {
        // 不阻塞:换订阅时 PyLoader.load() 在主线程调用,这里只排队交给源自己的线程,
        // 已排队的调用照旧跑完(源里的 destroy 一般是关本地代理/落缓存)。
        runCatching { executor.submit { bridge().callAttr("call", instance, "destroy") } }
        executor.shutdown()
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun bridge(): PyObject = Python.getInstance().getModule("box.bridge")

    /** 参数列表 → Python list(JSON 中转,避免 Java 集合代理对象) */
    private fun argsOf(values: List<Any?>): PyObject =
        bridge().callAttr("loads", GSON.toJson(values))

    /** 调用源方法;方法不存在时返回 null(基类兜底实现本来就返回空结构) */
    private fun invoke(name: String, values: List<Any?>, timeoutMs: Long): PyObject? {
        val bridge = bridge()
        val payload = argsOf(values)
        return submit({ bridge.callAttr("call", instance, name, payload) }, timeoutMs)
    }

    private fun submit(block: () -> PyObject?, timeoutMs: Long): PyObject? {
        val future = executor.submit(Callable { block() })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            wedged = true
            LOG.e(TAG, "py 源调用超时(${timeoutMs}ms) key=$key(实例作废,下次重建)")
            LogStore.fail(Category.SUBSCRIPTION, "订阅: py 源调用超时,已作废该实例 key=" + key)
            null
        } catch (error: Throwable) {
            LOG.e(TAG, "py 源调用失败 key=$key (" + rootCause(error) + ")")
            null
        }
    }

    /** 返回值 → 文本(dict/list 转 JSON,str 原样,None 给空串) */
    private fun result(value: PyObject?): String {
        if (value == null) return ""
        return runCatching { bridge().callAttr("dump", value).toString() }.getOrElse { "" }
    }

    private fun rootCause(error: Throwable): Throwable {
        var cause = error
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        return cause
    }
}

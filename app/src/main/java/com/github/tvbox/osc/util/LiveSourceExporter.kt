package com.github.tvbox.osc.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.github.tvbox.osc.R
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 直播源导出:把用户自建的直播源(名称 + 地址)写成一份 JSON,经 FileProvider 分享出去。
 *
 * <p>订阅自带的直播源不导:它们跟着订阅走,导出去会在别的机器上变成一份重复的"自建"清单。
 * 名称优先用用户起的,没起过的按地址推导(见 [LiveSourceEntries.displayName]),导出的文件人也能读。
 *
 * <p>落盘在共享执行器(不在主线程碰文件),回调切回主线程;导出目录只保留最近几份。
 * 换机整机迁移不靠它:直播源存在设置里,本地备份/还原的设置域本来就带着。
 */
object LiveSourceExporter {

    enum class Origin { USER, DEFAULT, SUBSCRIPTION, UNKNOWN }

    data class CurrentSource(val url: String, val origin: Origin)

    class Snapshot(history: List<String>, names: Map<String, String>, val current: CurrentSource?) {
        internal val history = history.toList()
        internal val names = names.toMap()
    }

    private const val KEEP_EXPORT_FILES = 4
    private val MAIN by lazy { Handler(Looper.getMainLooper()) }

    /** @param callback 主线程回调;file 为 null 时 error 给原因 */
    fun export(context: Context, snapshot: Snapshot, callback: (file: File?, error: String?) -> Unit) {
        val app = context.applicationContext
        HeavyTaskUtil.getSerialExecutorService().execute {
            var file: File? = null
            var error: String? = null
            try {
                val payload = payload(snapshot, System.currentTimeMillis())
                val count = payload.getAsJsonArray("items").size()
                if (count == 0) {
                    error = "暂无自建直播源可导出"
                } else {
                    file = write(app, payload)
                    LogStore.success(Category.SUBSCRIPTION, "订阅: 导出直播源 " + count + " 条")
                }
            } catch (t: Throwable) {
                LogStore.fail(Category.SUBSCRIPTION, "订阅: 导出直播源失败 (${t.javaClass.simpleName})")
                error = "导出失败：" + (t.message ?: t.javaClass.simpleName)
            }
            val exported = file
            val failure = error
            MAIN.post { callback(exported, failure) }
        }
    }

    // 历史本身就是自建来源凭据；当前地址必须另有来源证明，未知来源不补入。
    internal fun payload(snapshot: Snapshot, createdAt: Long): JsonObject {
        val items = JsonArray()
        val seen = LinkedHashSet<String>()
        fun add(raw: String?) {
            val url = raw?.trim().orEmpty()
            if (url.isEmpty() || !seen.add(url)) return
            val named = snapshot.names[url]?.trim().orEmpty()
                .ifEmpty { LiveSourceEntries.displayName(url) }
            items.add(JsonObject().apply {
                addProperty("name", named)
                addProperty("url", url)
            })
        }
        for (url in snapshot.history) add(url)
        val current = snapshot.current?.takeIf { it.origin == Origin.USER || it.origin == Origin.DEFAULT }
            ?.url?.trim()
        add(current)
        return JsonObject().apply {
            addProperty("schema", 1)
            addProperty("category", "live_sources")
            addProperty("createdAt", createdAt)
            if (current != null && current in seen) addProperty("current", current)
            add("items", items)
        }
    }

    private fun write(context: Context, payload: JsonObject): File {
        val root = context.getExternalCacheDir() ?: throw IOException("导出目录不可用")
        val dir = File(root, "live_source_export")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("导出目录不可用")
        prune(dir)
        val prefix = context.getString(R.string.app_name) + "直播源_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_"
        val out = File.createTempFile(prefix, ".json", dir)
        try {
            out.writeText(payload.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
        return out
    }

    /** 只留最近 [KEEP_EXPORT_FILES] 份导出文件(缓存目录也做有界清理) */
    private fun prune(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size < KEEP_EXPORT_FILES) return
        files.sortByDescending { it.lastModified() }
        for (index in KEEP_EXPORT_FILES until files.size) files[index].delete()
    }
}

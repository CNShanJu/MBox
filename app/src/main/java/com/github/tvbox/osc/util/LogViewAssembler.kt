package com.github.tvbox.osc.util

import com.github.tvbox.osc.log.LogFilter
import com.github.tvbox.osc.log.LogStore
import java.io.File

/**
 * 日志页数据组装（helper）：把 LogActivity 里的查询/格式化/文件读取/导出/清空
 * 下沉到这里，页面只负责交互与展示。
 * <ul>
 *   <li>Tab1 业务日志：经 LogStore(Room) 结构化查询 + formatEntry 组装文本（分类 + 仅失败筛选）；</li>
 *   <li>Tab2 错误日志：经 LogStore 门面读取 logcat 本应用 ERROR 级文件（过渡期含旧 AppLog 同目录文件），
 *       日期标签、尾部读取、清空、导出。</li>
 * </ul>
 * 阻塞方法（bizText/rawText/export*）需在后台线程调用（Room 禁止主线程查询）。
 */
object LogViewAssembler {

    const val SHOW_MAX_LINES = 1000
    const val BIZ_MAX_LINES = 300
    private const val BIZ_EXPORT_LIMIT = 2000

    /** 日志文件名 → 展示名:app-2026-06-01.log / logcat-2026-06-01.log → 2026-06-01;
     *  logcat 单日超大时按 8MB 滚动的分段文件 logcat-2026-06-01.2.log → 2026-06-01(分段2) */
    fun dayLabel(name: String): String {
        val m = Regex("""^(?:app|logcat)-(\d{4}-\d{2}-\d{2})(?:\.(\d+))?\.log$""").find(name)
            ?: return name
        return if (m.groupValues[2].isEmpty()) m.groupValues[1]
        else m.groupValues[1] + "(分段" + m.groupValues[2] + ")"
    }

    /** Tab1 组装：查询结构化日志并格式化成文本。无数据/降级返回 null（页面展示空态文案）。后台线程调用。 */
    fun bizText(store: LogStore, category: String?, errorOnly: Boolean): String? {
        val entries = try {
            val filter = LogFilter().apply {
                this.category = category
                minLevel = if (errorOnly) LogStore.LEVEL_ERROR else LogStore.LEVEL_INFO
                limit = BIZ_MAX_LINES
            }
            store.query(filter)
        } catch (th: Throwable) {
            null
        }
        if (entries == null || entries.isEmpty()) return null
        val sb = StringBuilder(entries.size * 96)
        for (e in entries) sb.append(store.formatEntry(e)).append("\n")
        return sb.toString()
    }

    /** Tab2 文件列表（新在前）。 */
    fun rawFiles(store: LogStore): List<File> = store.listRawLogFiles()

    /** Tab2 组装：读文件尾部文本并追加"仅显示最近 N 行"提示。文件为空/不存在返回 null。后台线程调用。 */
    fun rawText(store: LogStore, file: File?): String? {
        if (file == null) return null
        val lines = store.readRawLogTail(file, SHOW_MAX_LINES)
        if (lines.isEmpty()) return null
        val sb = StringBuilder(lines.size * 64)
        for (line in lines) sb.append(line).append("\n")
        sb.append("\n—— 仅显示最近 ").append(lines.size).append(" 行 ——")
        return sb.toString()
    }

    /** Tab1 导出：按当前筛选导出业务日志 txt（cacheDir）。无结果返回 null。 */
    fun exportBiz(store: LogStore, category: String?, errorOnly: Boolean): File? {
        val filter = LogFilter().apply {
            this.category = category
            minLevel = if (errorOnly) LogStore.LEVEL_ERROR else LogStore.LEVEL_INFO
            limit = BIZ_EXPORT_LIMIT
        }
        return store.export(filter)
    }

    /** Tab2 导出：导出错误日志文件 txt（cacheDir）。无文件返回 null。 */
    fun exportRaw(store: LogStore): File? = store.exportRawLogFiles()

    fun clearBiz(store: LogStore) = store.clearAll()

    fun clearRaw(store: LogStore) = store.clearRawLogFiles()
}

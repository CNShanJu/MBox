package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.DownloadTask
import com.github.tvbox.osc.bean.VideoInfo
import com.github.tvbox.osc.download.ArchiveItem
import com.github.tvbox.osc.download.DownloadFacade
import java.io.File

/**
 * 下载完成列表展示的纯静态工具(自 DownloadFragment 抽取,等价搬移;Java→Kotlin 化,改进.txt §八):
 * 剧集名/清晰度解析、尺寸/速度/进度文本、列表指纹、递归删除。
 * 无 View/Context/Android 依赖,可 JVM 单测。
 */
object DownloadDisplay {

    private val RESOLUTION = Regex("(?i)(\\d{3,4}p|4k|2k|8k|sd|hd|fhd|uhd)")

    private fun isEmpty(s: String?): Boolean = s == null || s.isEmpty()

    /** episodeId 最后一段(playIndex) */
    @JvmStatic
    fun lastSegment(episodeId: String?): String? {
        if (episodeId == null) return null
        val i = episodeId.lastIndexOf('|')
        return if (i >= 0) episodeId.substring(i + 1) else null
    }

    /** 集数名:优先档案 episodeName(去清晰度后缀),空则按索引推导 第N集,再空用文件名(去扩展名) */
    @JvmStatic
    fun episodeTitleOf(it: ArchiveItem, fileName: String?): String? {
        var label = it.episodeName
        if (isEmpty(label) && it.episodeId != null) {
            val idx = lastSegment(it.episodeId)
            if (idx != null && idx.matches(Regex("\\d+"))) label = "第" + idx + "集"
        }
        if (isEmpty(label)) {
            if (fileName != null) {
                var fromFile = fileName
                val dot = fromFile.lastIndexOf('.')
                if (dot > 0) fromFile = fromFile.substring(0, dot)
                label = fromFile
            }
        }
        if (label == null) return null
        return label.replace(Regex("(?i)_?(\\d{3,4}p|4k|2k|8k|sd|hd|fhd|uhd)$"), "").trim()
    }

    /** 清晰度:从集数名/文件名解析最后一个 480P/720P/1080P/4K... 段;无则 null */
    @JvmStatic
    fun resolutionOf(it: ArchiveItem, fileName: String?): String? {
        val raw = if (isEmpty(it.episodeName)) fileName else it.episodeName
        val text = raw ?: return null
        if (text.isEmpty()) return null
        var last: String? = null
        for (m in RESOLUTION.findAll(text)) last = m.groupValues[1]
        return last?.uppercase()
    }

    /** 下载完成列表指纹:文件路径 + 大小 */
    @JvmStatic
    fun doneSignature(files: List<VideoInfo>): String {
        val sb = StringBuilder()
        for (v in files) {
            sb.append(v.path).append('|').append(v.size).append(';')
        }
        return sb.toString()
    }

    /** 任务行"大小 · 进度"文本:分段 N/M + 已下/总量(有字节)+ 百分比;失败附原因。
     * 直链预检后 totalBytes 为精确大小直接展示;m3u8 只有估算时以"约X"展示(入队即预检写入)。 */
    @JvmStatic
    fun buildPercentText(t: DownloadTask): String {
        val sb = StringBuilder()
        if (t.isHls) {
            sb.append("分段 ").append(t.doneSegments).append("/").append(t.totalSegments)
        }
        if (t.totalBytes > 0) {
            if (sb.isNotEmpty()) sb.append("  ")
            sb.append(formatSize(t.downloadedBytes)).append("/").append(formatSize(t.totalBytes))
        } else if (t.estimatedBytes > 0) {
            // 只有 m3u8 估算总量(直链精确大小在 totalBytes):下载前即可看到"约多大"
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append("约").append(formatSize(t.estimatedBytes))
        }
        sb.append(" (").append(t.progressPercent).append("%)")
        if (t.state == DownloadTask.STATE_FAILED && t.message != null && t.message.isNotEmpty()) {
            sb.append(" 失败:").append(t.message)
        }
        return sb.toString()
    }

    /** 状态行色调:错误(红)/次要(灰)/下载中高亮 */
    enum class StatusTone { ERROR, MUTED, ACTIVE }

    private fun msgIs(msg: String?, stage: String): Boolean =
        msg != null && msg.startsWith(stage)

    /** 收尾阶段(合并x%/剩K片)message 自带进度,不再追加整体百分比 */
    @JvmStatic
    fun stageMessageOwnsProgress(msg: String?): Boolean =
        msgIs(msg, DownloadFacade.MSG_MERGING) || msgIs(msg, DownloadFacade.MSG_REPAIRING)

    /** 状态行文本:失败/已暂停/网络中断/排队中/等待中/已取消/收尾 message/下载中 */
    @JvmStatic
    fun statusTextOf(t: DownloadTask): String {
        when (t.state) {
            DownloadTask.STATE_FAILED -> return "失败"
            DownloadTask.STATE_PAUSED -> return "已暂停"
            DownloadTask.STATE_NETWORK_PAUSED -> return "网络中断"
            DownloadTask.STATE_SYSTEM_PAUSED -> return "排队中"
            DownloadTask.STATE_WAITING -> {
                // 排队/等待:被"仅WiFi"闸门拦住的任务带"等待Wi-Fi"说明,避免用户不知为何等待
                val msg = t.message
                return if (msg != null && msg.isNotEmpty()) msg else "等待中"
            }
            DownloadTask.STATE_CANCELLED -> return "已取消"
        }
        val msg = t.message
        if (msgIs(msg, DownloadFacade.MSG_REPAIRING)
            || msgIs(msg, DownloadFacade.MSG_VERIFYING)
            || msgIs(msg, DownloadFacade.MSG_MERGING)
            || msgIs(msg, DownloadFacade.MSG_REMUX)
        ) {
            return msg!!
        }
        return "下载中"
    }

    /** 状态行色调键:失败=ERROR;各暂停/取消=次要;下载中/收尾=ACTIVE */
    @JvmStatic
    fun statusToneOf(t: DownloadTask): StatusTone {
        if (t.state == DownloadTask.STATE_FAILED) return StatusTone.ERROR
        return when (t.state) {
            DownloadTask.STATE_PAUSED,
            DownloadTask.STATE_NETWORK_PAUSED,
            DownloadTask.STATE_SYSTEM_PAUSED,
            DownloadTask.STATE_WAITING,
            DownloadTask.STATE_CANCELLED -> StatusTone.MUTED
            else -> StatusTone.ACTIVE
        }
    }

    /** 是否显示实时网速:真正下载中且非收尾阶段(message 非 校验/合并/封装/补片) */
    @JvmStatic
    fun shouldShowSpeed(t: DownloadTask): Boolean {
        if (t.state != DownloadTask.STATE_DOWNLOADING || t.message == null) return false
        val msg = t.message
        return !stageMessageOwnsProgress(msg)
            && !msgIs(msg, DownloadFacade.MSG_VERIFYING)
            && !msgIs(msg, DownloadFacade.MSG_REMUX)
    }

    /** 实时网速文本 */
    @JvmStatic
    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec >= 1024 * 1024) {
            return String.format("%.1fMB/s", bytesPerSec / 1024.0 / 1024.0)
        }
        if (bytesPerSec >= 1024) {
            return String.format("%.0fKB/s", bytesPerSec / 1024.0)
        }
        return "$bytesPerSec" + "B/s"
    }

    /** 行1:剧名 · 集名(集名与剧名相同或为空时不追加) */
    @JvmStatic
    fun titleText(t: DownloadTask): String {
        val vodName = t.vodName ?: ""
        val ep = t.episodeName
        return if (ep != null && ep.isNotEmpty() && ep != t.vodName) {
            "$vodName · $ep"
        } else {
            vodName
        }
    }

    /** 状态行完整文本:状态(+整体百分比,收尾阶段省略)+ 实时网速(仅下载中非收尾) */
    @JvmStatic
    fun statusLine(t: DownloadTask): String {
        val status = statusTextOf(t)
        val stageOwns = stageMessageOwnsProgress(t.message)
        var line = if (stageOwns) status else "$status · ${t.progressPercent}%"
        if (shouldShowSpeed(t) && t.speed > 0) {
            line += " · " + formatSpeed(t.speed)
        }
        return line
    }

    /** 行4:来源文本(空来源显示"未知") */
    @JvmStatic
    fun sourceText(sourceName: String?): String {
        val src = sourceName ?: ""
        return "来源 " + (if (src.isEmpty()) "未知" else src)
    }

    /** 左滑暂停/继续按钮文案:暂停中=继续,失败=重试,其余=暂停 */
    @JvmStatic
    fun swipeActionText(t: DownloadTask): String {
        if (t.state == DownloadTask.STATE_PAUSED) return "继续"
        if (t.state == DownloadTask.STATE_FAILED) return "重试"
        return "暂停"
    }

    /** 文件大小文本 */
    @JvmStatic
    fun formatSize(bytes: Long): String {
        if (bytes < 1024 * 1024) return (bytes / 1024).toString() + "KB"
        if (bytes < 1024L * 1024 * 1024) return (bytes / 1024 / 1024).toString() + "MB"
        return String.format("%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    /** 递归删除文件/目录 */
    @JvmStatic
    fun deleteRecursive(f: File?) {
        if (f == null || !f.exists()) return
        if (f.isDirectory) {
            val fs = f.listFiles()
            if (fs != null) {
                for (c in fs) deleteRecursive(c)
            }
        }
        f.delete()
    }
}

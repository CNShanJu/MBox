package com.github.tvbox.osc.util.player

import org.apache.commons.lang3.StringUtils

/**
 * m3u8 播放地址净化(自 PlayFragment 抽取的纯逻辑,无 UI/播放器依赖;Java→Kotlin 化,改进.txt §八):
 * 识别并剔除少数派(minority)分片(广告/占位),必要时补齐相对路径与 EXT-X-KEY。
 */
object M3u8Cleaner {

    /**
     * 移除 m3u8 中的少数派分片(广告):同前缀出现次数最多的为正常分片,其余剔除。
     *
     * @param tsUrlPre   该 m3u8 所在目录(用于拼接相对路径)
     * @param m3u8content m3u8 文本
     * @return 净化后的 m3u8;无法识别(前缀过多/结构异常)返回 null,由调用方回退直接播放
     */
    @JvmStatic
    fun removeMinorityUrl(tsUrlPre: String, m3u8content: String): String? {
        if (!m3u8content.startsWith("#EXTM3U")) return null
        var linesplit = "\n"
        if (m3u8content.contains("\r\n")) linesplit = "\r\n"
        val lines = m3u8content.split(linesplit).toTypedArray()

        val preUrlMap = HashMap<String, Int>()
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val ilast = line.lastIndexOf('.')
            if (ilast <= 4) continue
            val preUrl = line.substring(0, ilast - 4)
            preUrlMap[preUrl] = (preUrlMap[preUrl] ?: 0) + 1
        }
        if (preUrlMap.size <= 1) return null
        if (preUrlMap.size > 5) return null // too many different url, can not identify ads url
        var maxTimes = 0
        var maxTimesPreUrl = ""
        for ((preUrl, times) in preUrlMap) {
            if (times > maxTimes) {
                maxTimesPreUrl = preUrl
                maxTimes = times
            }
        }
        if (maxTimes == 0) return null

        for (i in lines.indices) {
            // 每一条 #EXT-X-KEY 都要补成绝对地址:多密钥轮换(key rotation)很常见,
            // 原来只处理第一条(dealedExtXKey 用完就置 true),后面的 key 仍是相对地址 → 取不到 key → 黑屏
            if (lines[i].startsWith("#EXT-X-KEY")) {
                val keyUrl = StringUtils.substringBetween(lines[i], "URI=\"", "\"")
                if (keyUrl != null) {
                    val newKeyUrl = absoluteUrl(tsUrlPre, keyUrl)
                    if (newKeyUrl != keyUrl) {
                        lines[i] = lines[i].replace("URI=\"" + keyUrl + "\"", "URI=\"" + newKeyUrl + "\"")
                    }
                }
            }
            if (lines[i].isEmpty() || lines[i][0] == '#') continue
            if (lines[i].startsWith(maxTimesPreUrl)) {
                lines[i] = absoluteUrl(tsUrlPre, lines[i])
            } else {
                if (i > 0 && lines[i - 1].isNotEmpty() && lines[i - 1][0] == '#') {
                    lines[i - 1] = ""
                }
                lines[i] = ""
            }
        }
        return StringUtils.join(lines, linesplit)
    }

    /**
     * 把 m3u8 里的相对地址补成绝对地址(纯字符串处理,便于单测)。
     *
     * @param tsUrlPre m3u8 所在目录(如 `https://cdn/a/b/`)
     * @param url      待补的地址:已是绝对地址原样返回;以 `/` 开头按站点根拼;其余按目录拼
     */
    @JvmStatic
    fun absoluteUrl(tsUrlPre: String, url: String): String {
        if (url.isEmpty() || url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("/")) {
            // 取站点根(scheme://host[:port]):跳过 https:// 的两个斜杠后再找第一个斜杠。
            // 找不到(如 base 就是 https://host,没有路径)时退化为去掉结尾斜杠 —— 原来直接
            // substring(0, -1) 会抛 StringIndexOutOfBoundsException,整条播放链路异常
            val idx = tsUrlPre.indexOf('/', 9)
            val origin = if (idx > 0) tsUrlPre.substring(0, idx) else tsUrlPre.trimEnd('/')
            return origin + url
        }
        val dir = if (tsUrlPre.endsWith("/")) tsUrlPre else tsUrlPre + "/"
        return dir + url
    }
}

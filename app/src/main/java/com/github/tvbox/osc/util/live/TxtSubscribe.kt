package com.github.tvbox.osc.util.live

import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.LOG
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.StringReader
import java.util.LinkedHashMap
import java.util.regex.Pattern

/**
 * 直播源解析:兼容 txt(频道名,url[#url2...] / 分组名,#genre#)、M3U(#EXTM3U + #EXTINF 带 group-title 分组)
 * 与 JSON(订阅源内嵌 lives 数组)三种格式。
 *
 * <p>与旧 Java 版相比补齐了两处能力(对齐 AVBox):
 * <ul>
 *   <li>M3U 的 {@code group-title="分组名"} 分组——旧版只取频道名、丢掉了分组,导致所有 M3U 频道落到"未分组";</li>
 *   <li>JSON 数组格式的直播源(内嵌 group/channels 结构)。</li>
 * </ul>
 * 公共契约 {@link #parse(LinkedHashMap, String)} / {@link #live2JsonArray(LinkedHashMap)} 与旧版签名一致,
 * 调用方(LiveActivity)无需改动。
 */
object TxtSubscribe {

    /** 无分组时的默认分组名(对齐旧版"未分组";历史值 "Ungrouped" 归一化到它)。 */
    private const val DEFAULT_GROUP_NAME = "未分组"
    private const val LEGACY_DEFAULT_GROUP_NAME = "Ungrouped"

    private val NAME_PATTERN: Pattern = Pattern.compile(".*,(.+?)$")
    private val GROUP_PATTERN: Pattern = Pattern.compile("group-title=\"(.*?)\"")
    private val TVG_CHNO_PATTERN: Pattern = Pattern.compile("tvg-chno=\"(.*?)\"")
    private val TVG_LOGO_PATTERN: Pattern = Pattern.compile("tvg-logo=\"(.*?)\"")
    private val TVG_NAME_PATTERN: Pattern = Pattern.compile("tvg-name=\"(.*?)\"")
    private val TVG_URL_PATTERN: Pattern = Pattern.compile("tvg-url=\"(.*?)\"")
    private val TVG_ID_PATTERN: Pattern = Pattern.compile("tvg-id=\"(.*?)\"")
    private val HTTP_USER_AGENT_PATTERN: Pattern = Pattern.compile("http-user-agent=\"(.*?)\"")
    private val CATCHUP_PATTERN: Pattern = Pattern.compile("catchup=\"(.*?)\"")
    private val CATCHUP_SOURCE_PATTERN: Pattern = Pattern.compile("catchup-source=\"(.*?)\"")
    private val CATCHUP_REPLACE_PATTERN: Pattern = Pattern.compile("catchup-replace=\"(.*?)\"")

    @JvmStatic
    fun parse(linkedHashMap: LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>>, str: String) {
        linkedHashMap.clear()
        val array = parseToJsonArray(str) ?: return
        for (groupElement in array) {
            val groupObj = groupElement.asJsonObject
            val groupName = normalizeGroupName(DefaultConfig.safeJsonString(groupObj, "group", DEFAULT_GROUP_NAME))
            val channelMap = LinkedHashMap<String, ArrayList<String>>()
            if (groupObj.has("channels")) {
                for (channelElement in groupObj.getAsJsonArray("channels")) {
                    val channelObj = channelElement.asJsonObject
                    val channelName = DefaultConfig.safeJsonString(channelObj, "name", "Unnamed")
                    val urls = ArrayList<String>()
                    if (channelObj.has("urls")) {
                        for (urlElement in channelObj.getAsJsonArray("urls")) {
                            val url = urlElement.asString.trim { it <= ' ' }
                            if (isUrl(url) && !urls.contains(url)) urls.add(url)
                        }
                    }
                    if (urls.isNotEmpty()) channelMap[channelName] = urls
                }
            }
            if (channelMap.isNotEmpty()) linkedHashMap[groupName] = channelMap
        }
    }

    @JvmStatic
    fun parseToJsonArray(str: String?): JsonArray? {
        if (str == null) return null
        val trimmed = str.trim { it <= ' ' }
        if (trimmed.isEmpty()) return null
        try {
            val element = JsonParser.parseString(trimmed)
            if (element.isJsonArray) return normalizeJsonArray(element.asJsonArray)
        } catch (ignored: Throwable) {
            LOG.i("TxtSubscribe", "not json content, try m3u/txt parse")
        }
        if (trimmed.startsWith("#EXTM3U")) return parseM3uToJsonArray(trimmed)
        return parseTxtToJsonArray(trimmed)
    }

    private fun normalizeJsonArray(groups: JsonArray): JsonArray {
        val result = JsonArray()
        for (groupElement in groups) {
            val groupObj = groupElement.asJsonObject
            var groupName = DefaultConfig.safeJsonString(groupObj, "group", "")
            if (groupName.isEmpty()) groupName = DefaultConfig.safeJsonString(groupObj, "name", DEFAULT_GROUP_NAME)
            val outGroup = findOrCreateGroup(result, groupName)
            var channels: JsonArray? = null
            if (groupObj.has("channels") && groupObj.get("channels").isJsonArray) {
                channels = groupObj.getAsJsonArray("channels")
            } else if (groupObj.has("channel") && groupObj.get("channel").isJsonArray) {
                channels = groupObj.getAsJsonArray("channel")
            }
            if (channels != null) {
                for (channelElement in channels) {
                    val channelObj = channelElement.asJsonObject
                    val outChannel = JsonObject()
                    copyIfExists(channelObj, outChannel, "name")
                    copyIfExists(channelObj, outChannel, "urls")
                    copyIfExists(channelObj, outChannel, "logo")
                    copyIfExists(channelObj, outChannel, "epg")
                    copyIfExists(channelObj, outChannel, "ua")
                    copyIfExists(channelObj, outChannel, "click")
                    copyIfExists(channelObj, outChannel, "format")
                    copyIfExists(channelObj, outChannel, "origin")
                    copyIfExists(channelObj, outChannel, "referer")
                    copyIfExists(channelObj, outChannel, "tvg-id")
                    copyIfExists(channelObj, outChannel, "tvg-name")
                    copyIfExists(channelObj, outChannel, "tvg-chno")
                    copyIfExists(channelObj, outChannel, "parse")
                    copyIfExists(channelObj, outChannel, "header")
                    copyIfExists(channelObj, outChannel, "catchup")
                    copyIfExists(channelObj, outChannel, "catchup-source")
                    copyIfExists(channelObj, outChannel, "catchup-replace")
                    addChannel(outGroup, outChannel)
                }
            }
            if (!outGroup.has("channels")) outGroup.add("channels", JsonArray())
        }
        return result
    }

    private fun copyIfExists(src: JsonObject, dst: JsonObject, key: String) {
        if (src.has(key)) dst.add(key, src.get(key))
    }

    private fun parseM3uToJsonArray(str: String): JsonArray {
        val result = JsonArray()
        try {
            val reader = BufferedReader(StringReader(str.replace("\r\n", "\n").replace("\r", "")))
            var currentGroup: JsonObject? = null
            var pendingChannel: JsonObject? = null
            var pendingMeta = JsonObject()
            while (true) {
                var line = reader.readLine() ?: break
                line = line.trim { it <= ' ' }
                if (line.isEmpty()) continue
                if (line.startsWith("#EXTM3U")) {
                    mergeMeta(pendingMeta, buildMeta(line))
                    continue
                }
                if (isSetting(line)) {
                    mergeMeta(pendingMeta, buildSetting(line))
                    continue
                }
                if (line.startsWith("#EXTINF") || line.contains("#EXTINF")) {
                    var groupName = get(line, GROUP_PATTERN)
                    groupName = normalizeGroupName(groupName)
                    currentGroup = findOrCreateGroup(result, groupName)
                    pendingChannel = JsonObject()
                    pendingChannel.addProperty("name", get(line, NAME_PATTERN))
                    mergeMeta(pendingChannel, buildMeta(line))
                    mergeMeta(pendingChannel, pendingMeta)
                    pendingMeta = JsonObject()
                    continue
                }
                if (line.startsWith("#")) continue
                if (currentGroup == null) currentGroup = findOrCreateGroup(result, DEFAULT_GROUP_NAME)
                if (pendingChannel == null) pendingChannel = JsonObject()
                val parts = splitPattern("\\|").split(line, 2)
                val url = parts[0].trim { it <= ' ' }
                if (!isUrl(url)) continue
                if (parts.size > 1) mergeMeta(pendingMeta, parseHeaderString(parts[1]))
                mergeMeta(pendingChannel, pendingMeta)
                val urls = if (pendingChannel.has("urls")) pendingChannel.getAsJsonArray("urls") else JsonArray()
                if (!containsUrl(urls, url)) urls.add(url)
                pendingChannel.add("urls", urls)
                addChannel(currentGroup!!, pendingChannel)
                pendingMeta = JsonObject()
            }
            reader.close()
        } catch (ignored: Throwable) {
            LOG.i("TxtSubscribe", "m3u parse failed, keep parsed channels")
        }
        return result
    }

    private fun parseTxtToJsonArray(str: String): JsonArray {
        val result = JsonArray()
        try {
            val reader = BufferedReader(StringReader(str.replace("\r\n", "\n").replace("\r", "")))
            var currentGroup: JsonObject? = null
            var pendingMeta = JsonObject()
            while (true) {
                var line = reader.readLine() ?: break
                line = line.trim { it <= ' ' }
                if (line.isEmpty()) continue
                if (line.startsWith("#")) {
                    if (isSetting(line)) mergeMeta(pendingMeta, buildSetting(line))
                    continue
                }
                if (line.contains("#genre#")) {
                    val groupName = splitPattern(",").split(line, 2)[0].trim { it <= ' ' }
                    currentGroup = findOrCreateGroup(result, groupName)
                    pendingMeta = JsonObject()
                    continue
                }
                val split = splitPattern(",").split(line, 2)
                if (split.size < 2) continue
                if (currentGroup == null) currentGroup = findOrCreateGroup(result, DEFAULT_GROUP_NAME)
                val channel = JsonObject()
                channel.addProperty("name", split[0].trim { it <= ' ' })
                mergeMeta(channel, pendingMeta)
                val urls = ArrayList<String>()
                for (part in splitPattern("#").split(split[1].trim { it <= ' ' })) {
                    val url = part.trim { it <= ' ' }
                    if (isUrl(url) && !urls.contains(url)) urls.add(url)
                }
                if (urls.isEmpty()) continue
                val urlArray = JsonArray()
                for (url in urls) urlArray.add(url)
                channel.add("urls", urlArray)
                addChannel(currentGroup!!, channel)
                pendingMeta = JsonObject()
            }
            reader.close()
        } catch (ignored: Throwable) {
            LOG.i("TxtSubscribe", "txt parse failed, keep parsed channels")
        }
        return result
    }

    private fun parseHeaderString(text: String): JsonObject {
        val wrapper = JsonObject()
        val obj = JsonObject()
        val params = splitPattern("&").split(text)
        for (param in params) {
            if (!param.contains("=")) continue
            val a = splitPattern("=").split(param, 2)
            obj.addProperty(a[0].trim { it <= ' ' }.replace("\"", ""), a[1].trim { it <= ' ' }.replace("\"", ""))
        }
        if (obj.entrySet().size > 0) wrapper.add("header", obj)
        return wrapper
    }

    private fun findOrCreateGroup(result: JsonArray, name: String?): JsonObject {
        val groupName = normalizeGroupName(name)
        for (element in result) {
            val group = element.asJsonObject
            if (groupName == group.get("group").asString) return group
        }
        val group = JsonObject()
        group.addProperty("group", groupName)
        group.add("channels", JsonArray())
        result.add(group)
        return group
    }

    @JvmStatic
    fun normalizeGroupName(name: String?): String {
        if (name == null) return DEFAULT_GROUP_NAME
        val trimmed = name.trim { it <= ' ' }
        if (trimmed.isEmpty() || LEGACY_DEFAULT_GROUP_NAME.equals(trimmed, ignoreCase = true)) return DEFAULT_GROUP_NAME
        return trimmed
    }

    private fun addChannel(group: JsonObject, channel: JsonObject) {
        val channels = if (group.has("channels")) group.getAsJsonArray("channels") else JsonArray()
        val name = DefaultConfig.safeJsonString(channel, "name", "")
        val exists = if (name.isEmpty()) null else findChannel(channels, name)
        if (exists == null) {
            channels.add(channel)
        } else {
            mergeChannel(exists, channel)
        }
        group.add("channels", channels)
    }

    private fun findChannel(channels: JsonArray, name: String): JsonObject? {
        for (element in channels) {
            if (!element.isJsonObject) continue
            val channel = element.asJsonObject
            if (name == DefaultConfig.safeJsonString(channel, "name", "")) return channel
        }
        return null
    }

    private fun mergeChannel(dst: JsonObject, src: JsonObject) {
        mergeUrls(dst, src)
        for (entry in src.entrySet()) {
            val key = entry.key
            if ("urls" == key) continue
            if (!dst.has(key) || isEmptyValue(dst.get(key))) dst.add(key, entry.value)
        }
    }

    private fun mergeUrls(dst: JsonObject, src: JsonObject) {
        if (!src.has("urls") || !src.get("urls").isJsonArray) return
        val dstUrls = if (dst.has("urls") && dst.get("urls").isJsonArray) dst.getAsJsonArray("urls") else JsonArray()
        for (element in src.getAsJsonArray("urls")) {
            if (!element.isJsonPrimitive) continue
            val url = element.asString.trim { it <= ' ' }
            if (isUrl(url) && !containsUrl(dstUrls, url)) dstUrls.add(url)
        }
        dst.add("urls", dstUrls)
    }

    private fun isEmptyValue(element: JsonElement?): Boolean {
        if (element == null || element.isJsonNull) return true
        if (element.isJsonPrimitive) return element.asString.trim { it <= ' ' }.isEmpty()
        if (element.isJsonArray) return element.asJsonArray.size() == 0
        return element.isJsonObject && element.asJsonObject.entrySet().size == 0
    }

    private fun containsUrl(urls: JsonArray, url: String): Boolean {
        for (element in urls) {
            if (url == element.asString) return true
        }
        return false
    }

    private fun mergeMeta(dst: JsonObject, src: JsonObject) {
        for (entry in src.entrySet()) {
            dst.add(entry.key, entry.value)
        }
    }

    private fun buildMeta(line: String): JsonObject {
        val obj = JsonObject()
        put(obj, "logo", get(line, TVG_LOGO_PATTERN))
        put(obj, "epg", get(line, TVG_URL_PATTERN))
        put(obj, "tvg-id", get(line, TVG_ID_PATTERN))
        put(obj, "tvg-name", get(line, TVG_NAME_PATTERN))
        put(obj, "tvg-chno", get(line, TVG_CHNO_PATTERN))
        put(obj, "ua", get(line, HTTP_USER_AGENT_PATTERN))
        val catchup = get(line, CATCHUP_PATTERN)
        val source = get(line, CATCHUP_SOURCE_PATTERN)
        val replace = get(line, CATCHUP_REPLACE_PATTERN)
        if (catchup.isNotEmpty() || source.isNotEmpty() || replace.isNotEmpty()) {
            val catchupObj = JsonObject()
            put(catchupObj, "type", catchup)
            put(catchupObj, "source", source)
            put(catchupObj, "replace", replace)
            obj.add("catchup", catchupObj)
        }
        return obj
    }

    private fun buildSetting(line: String): JsonObject {
        val obj = JsonObject()
        if (line.startsWith("ua")) put(obj, "ua", getValue(line, "ua"))
        if (line.startsWith("parse")) put(obj, "parse", getValue(line, "parse"))
        if (line.startsWith("click")) put(obj, "click", getValue(line, "click"))
        if (line.startsWith("header")) {
            val value = getValue(line, "header")
            if (value.isNotEmpty()) {
                try {
                    obj.add("header", JsonParser.parseString(value).asJsonObject)
                } catch (ignored: Throwable) {
                    LOG.i("TxtSubscribe", "header setting json invalid, skipped")
                }
            }
        }
        if (line.startsWith("format")) put(obj, "format", getValue(line, "format"))
        if (line.startsWith("origin")) put(obj, "origin", getValue(line, "origin"))
        if (line.startsWith("referer")) put(obj, "referer", getValue(line, "referer"))
        if (line.startsWith("#EXTHTTP:")) {
            try {
                obj.add("header", JsonParser.parseString(splitPattern("#EXTHTTP:").split(line)[1].trim { it <= ' ' }).asJsonObject)
            } catch (ignored: Throwable) {
                LOG.i("TxtSubscribe", "EXTHTTP header json invalid, skipped")
            }
        }
        if (line.startsWith("#EXTVLCOPT:")) {
            if (line.contains("http-user-agent")) put(obj, "ua", getValue(line, "http-user-agent"))
            if (line.contains("http-origin")) put(obj, "origin", getValue(line, "http-origin"))
            if (line.contains("http-referrer")) put(obj, "referer", getValue(line, "http-referrer"))
        }
        if (line.startsWith("#KODIPROP:") && line.contains("manifest_type=")) {
            put(obj, "format", getValue(line, "manifest_type"))
        }
        return obj
    }

    private fun isSetting(line: String): Boolean {
        return line.startsWith("ua") || line.startsWith("parse") || line.startsWith("click") ||
            line.startsWith("player") || line.startsWith("header") || line.startsWith("format") ||
            line.startsWith("origin") || line.startsWith("referer") || line.startsWith("#EXTHTTP:") ||
            line.startsWith("#EXTVLCOPT:") || line.startsWith("#KODIPROP:")
    }

    private fun isUrl(url: String): Boolean {
        return url.isNotEmpty() && (url.startsWith("http") || url.startsWith("rtp") ||
            url.startsWith("rtsp") || url.startsWith("rtmp") || url.startsWith("udp"))
    }

    private fun get(line: String, pattern: Pattern): String {
        val matcher = pattern.matcher(line)
        if (matcher.find()) return matcher.group(1).trim { it <= ' ' }
        return ""
    }

    private fun getValue(line: String, key: String): String {
        val index = line.indexOf(key + "=")
        if (index == -1) return ""
        return line.substring(index + key.length + 1).trim { it <= ' ' }.replace("\"", "")
    }

    private fun put(obj: JsonObject, key: String, value: String?) {
        if (value != null && value.isNotEmpty()) obj.addProperty(key, value)
    }

    // 简单正则拆分缓存,等价于 AVBox 的 RegexUtils.getPattern,但保持本文件自包含、不引入新工具类。
    private fun splitPattern(regex: String): Pattern = Pattern.compile(regex)

    /**
     * 把分组结构序列化为 JSON:与旧 Java 版输出结构一致
     * {@code [{"group": "分组名", "channels": [{"name": "频道名", "urls": ["url1", ...]}]}, ...]}。
     */
    @JvmStatic
    fun live2JsonArray(linkedHashMap: LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>>): JsonArray {
        val jsonarr = JsonArray()
        for (str in linkedHashMap.keys) {
            val jsonarr2 = JsonArray()
            val linkedHashMap2 = linkedHashMap[str] ?: continue
            if (!linkedHashMap2.isEmpty()) {
                for (str2 in linkedHashMap2.keys) {
                    val arrayList = linkedHashMap2[str2] ?: continue
                    if (!arrayList.isEmpty()) {
                        val jsonarr3 = JsonArray()
                        for (i in 0 until arrayList.size) {
                            jsonarr3.add(arrayList[i])
                        }
                        val jsonobj = JsonObject()
                        jsonobj.addProperty("name", str2)
                        jsonobj.add("urls", jsonarr3)
                        jsonarr2.add(jsonobj)
                    }
                }
                val jsonobj2 = JsonObject()
                jsonobj2.addProperty("group", str)
                jsonobj2.add("channels", jsonarr2)
                jsonarr.add(jsonobj2)
            }
        }
        return jsonarr
    }
}

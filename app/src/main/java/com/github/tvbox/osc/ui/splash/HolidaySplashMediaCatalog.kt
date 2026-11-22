package com.github.tvbox.osc.ui.splash

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 开屏海报素材表（`assets/splash/holiday_media.json`）的纯解析实现，不依赖 Android，便于 JVM 单测。
 *
 * 设计取舍：
 * - 整份结构损坏（版本不符、items 不是数组）直接抛错，调用方置空后回落默认开屏，避免带病运行；
 * - 单个条目损坏（id 为空/重复、URL 不是 https）只跳过该条，一条写错不应废掉整套节气素材；
 * - 解析结果保持 JSON 顺序，同日多节日时按顺序取第一个已缓存的海报。
 */
internal object HolidaySplashMediaCatalog {
    /** 配置格式版本，只接受这个值。 */
    private const val VERSION = 1

    /** assets 读取上限，防止异常大文件拖慢启动。 */
    const val MAX_CHARS = 64 * 1024

    /** 缓存文件最小可接受体积，低于此值视为下载失败或占位文件。 */
    const val MIN_IMAGE_BYTES = 1024L

    /** 未配置时按居中裁切。 */
    const val DEFAULT_ANCHOR_X = 0.5f

    /** 未配置时贴顶：横屏铺满只保留图片顶部，标题才不会先被裁掉。 */
    const val DEFAULT_ANCHOR_Y = 0f

    /** 素材版本缺省值。 */
    const val DEFAULT_VERSION = 1

    /**
     * 一条开屏素材。
     *
     * [anchorX]/[anchorY] 是"铺满屏幕时保留图片哪一段"的锚点（0=起始边，1=结束边）：
     * 海报标题并不固定在左侧或右侧，竖屏铺满只裁左右、横屏铺满只裁上下，
     * 所以每张图要按自己的标题位置单独配，否则标题会被裁掉。
     */
    data class Item(
        val id: String,
        val name: String,
        val url: String,
        val anchorX: Float = DEFAULT_ANCHOR_X,
        val anchorY: Float = DEFAULT_ANCHOR_Y,
        /**
         * 素材版本：缓存文件名会带上它。
         * 换图时改 url 会自动重新下载；若图床是"同一链接覆盖内容"，把 version 加 1 即可强制刷新。
         */
        val version: Int = DEFAULT_VERSION
    )

    fun parse(json: String): List<Item> {
        if (json.isBlank()) throw IllegalArgumentException("holiday media JSON is empty")
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject) throw IllegalArgumentException("holiday media root must be an object")
        val rootObject = root.asJsonObject
        if (int(rootObject, "version") != VERSION) {
            throw IllegalArgumentException("Unsupported holiday media JSON version")
        }
        val itemsElement = rootObject.get("items")
        if (itemsElement == null || !itemsElement.isJsonArray) {
            throw IllegalArgumentException("holiday media items must be an array")
        }
        val seen = HashSet<String>()
        val items = ArrayList<Item>(itemsElement.asJsonArray.size())
        for (element in itemsElement.asJsonArray) {
            val item = parseItem(element) ?: continue
            if (!seen.add(item.id)) continue
            items.add(item)
        }
        return items
    }

    private fun parseItem(element: JsonElement): Item? {
        if (!element.isJsonObject) return null
        val item = element.asJsonObject
        val id = string(item, "id")?.trim().orEmpty()
        if (id.isEmpty()) return null
        val url = string(item, "url")?.trim().orEmpty()
        if (!isSupportedUrl(url)) return null
        return Item(
            id = id,
            name = string(item, "name")?.trim().orEmpty(),
            url = url,
            anchorX = number(item, "anchorX")?.toFloat()?.coerceIn(0f, 1f)
                ?: DEFAULT_ANCHOR_X,
            anchorY = number(item, "anchorY")?.toFloat()?.coerceIn(0f, 1f)
                ?: DEFAULT_ANCHOR_Y,
            version = int(item, "version")?.coerceAtLeast(1) ?: DEFAULT_VERSION
        )
    }

    /** 只接受 https 直链：明文 http 在 Android 9+ 默认被拦截，写进配置即等于必然加载失败。 */
    fun isSupportedUrl(url: String): Boolean =
        url.startsWith("https://") && url.length > "https://".length + 3 &&
            url.none { it.isWhitespace() }

    /**
     * 按文件头判断图片类型；不是图片（例如图床返回的 HTML 错误页）返回 null。
     * 只认 Android 能稳定解码的 jpg/png/webp，避免把错误页当成图片缓存下来。
     */
    fun sniffImageExtension(header: ByteArray, length: Int): String? {
        if (length < 12 || header.size < 12) return null
        if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
            return "jpg"
        }
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        if (png.indices.all { header[it] == png[it] }) return "png"
        val webp = length >= 12 && header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() && header[8] == 'W'.code.toByte() &&
            header[9] == 'E'.code.toByte() && header[10] == 'B'.code.toByte() &&
            header[11] == 'P'.code.toByte()
        return if (webp) "webp" else null
    }

    private fun string(owner: JsonObject, key: String): String? {
        val value = owner.get(key) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString
    }

    private fun int(owner: JsonObject, key: String): Int? {
        val value = owner.get(key) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return runCatching { value.asInt }.getOrNull()
    }

    private fun number(owner: JsonObject, key: String): Double? {
        val value = owner.get(key) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        val number = runCatching { value.asDouble }.getOrNull() ?: return null
        return if (number.isNaN()) null else number
    }
}

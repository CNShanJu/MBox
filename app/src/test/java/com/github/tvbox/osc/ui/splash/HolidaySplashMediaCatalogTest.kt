package com.github.tvbox.osc.ui.splash

import com.github.tvbox.osc.calendar.HolidayCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/** 校验随包发布的开屏素材表：既不能写错 id，也不能漏掉任何一个节气。 */
class HolidaySplashMediaCatalogTest {

    @Test
    fun packagedMediaCoversEverySolarTerm() {
        val items = HolidaySplashMediaCatalog.parse(mediaJson())
        assertTrue("素材条目不应少于节气数量", items.size >= 24)
        assertEquals("id 不允许重复", items.size, items.map { it.id }.toSet().size)

        val catalog = catalog()
        val solarTerms = catalog.holidays.filter { it.dateRule == "solar_term" }
        assertEquals("节日目录应包含 24 个节气", 24, solarTerms.size)

        for (item in items) {
            val entry = catalog.holidays.firstOrNull { it.id == item.id }
            assertNotNull("素材 id ${item.id} 不在假日目录中", entry)
            // 名称与节日目录一致，防止 id 映射错位（例如把中秋图挂到七夕上）。
            assertEquals("素材 ${item.id} 的名称与节日目录不一致", entry!!.name, item.name)
            assertTrue("素材 ${item.id} 必须使用 https", item.url.startsWith("https://"))
        }
        for (entry in solarTerms) {
            assertNotNull("节气 ${entry.name}(${entry.id}) 缺少开屏素材", items.firstOrNull { it.id == entry.id })
        }
    }

    @Test
    fun brokenEntryIsSkippedWithoutDroppingTheRest() {
        val json = """
            {
              "version": 1,
              "items": [
                {"id": "solar_term_yushui", "name": "雨水", "url": "https://example.com/a.jpg"},
                {"id": "", "name": "空 id", "url": "https://example.com/b.jpg"},
                {"id": "solar_term_lichun", "name": "立春", "url": "http://example.com/c.jpg"},
                {"id": "solar_term_yushui", "name": "重复", "url": "https://example.com/d.jpg"},
                {"id": "solar_term_bailu", "name": "白露", "url": "https://example.com/e.jpg"}
              ]
            }
        """.trimIndent()
        val items = HolidaySplashMediaCatalog.parse(json)
        assertEquals(2, items.size)
        assertEquals("solar_term_yushui", items[0].id)
        assertEquals("solar_term_bailu", items[1].id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedVersionFailsClosed() {
        HolidaySplashMediaCatalog.parse("""{"version": 2, "items": []}""")
    }

    @Test
    fun imageHeaderSniffingRejectsHtml() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(9)
        assertEquals("jpg", HolidaySplashMediaCatalog.sniffImageExtension(jpeg, jpeg.size))

        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        assertEquals("png", HolidaySplashMediaCatalog.sniffImageExtension(png, png.size))

        val html = "<!DOCTYPE html>".toByteArray(StandardCharsets.UTF_8)
        assertNull(HolidaySplashMediaCatalog.sniffImageExtension(html, html.size))
    }

    private fun mediaJson(): String = readAsset("src/main/assets/splash/holiday_media.json")

    private fun catalog(): HolidayCatalog =
        HolidayCatalog.fromJson(readAsset("src/main/assets/calendar/holidays.json"))

    private fun readAsset(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val asset = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到素材文件：$relative（尝试过 $candidates）")
        return asset.readText(StandardCharsets.UTF_8)
    }
}

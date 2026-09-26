package com.github.tvbox.osc.util.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * m3u8 净化回归(纯 JVM)。覆盖过的缺陷:
 *  - 只给第一条 `#EXT-X-KEY` 补绝对地址(多密钥轮换取不到 key → 黑屏);
 *  - `#EXT-X-MAP`(fMP4 init 段)未被补成绝对地址 → 净化后整条 fMP4 放不出来;
 *  - base 没有路径时 `substring(0, -1)` 抛 StringIndexOutOfBoundsException;
 *  - 带 BOM 的清单被 `startsWith("#EXTM3U")` 判否 → 广告过滤静默失效。
 *
 * 注:本类曾以 Java/Kotlin 两份同 FQN 共存(`M3u8CleanerTest.java` + `.kt`),测试运行时只加载其中一个,
 * Java 那份的 6 个用例从未真正执行 —— 已合并到本文件,勿再拆回两份。
 */
class M3u8CleanerTest {

    private val baseDir = "https://cdn.example.com/hls/video/"
    private val header = "#EXTM3U\n"
    private val headerCrlf = "#EXTM3U\r\n"

    @Test
    fun absolutizesEveryKeyLine() {
        val content = listOf(
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key1.key\"",
            "#EXTINF:4,",
            "seg_00001.ts",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"/keys/key2.key\"",
            "#EXTINF:4,",
            "seg_00002.ts",
            "#EXTINF:4,",
            "other_00003.ts",
        ).joinToString("\n")

        val out = M3u8Cleaner.removeMinorityUrl(baseDir, content)
        assertNotNull(out)
        // 两条 KEY 都要补成绝对地址(相对 → 拼目录;以 / 开头 → 拼站点根)
        assertTrue(out!!.contains("URI=\"https://cdn.example.com/hls/video/key1.key\""))
        assertTrue(out.contains("URI=\"https://cdn.example.com/keys/key2.key\""))
        // 少数派分片(不同前缀)被剔除,正常分片保留
        assertTrue(out.contains("seg_00001.ts"))
        assertTrue(out.contains("seg_00002.ts"))
        assertTrue(!out.contains("other_00003.ts"))
    }

    @Test
    fun absolutizesExtXMapForFmp4() {
        // fMP4:HLS 清单里的 init 段由 #EXT-X-MAP 指定;净化后的清单走本机回环提供,
        // 相对 URI 不补绝对地址就会被播放器按 127.0.0.1 解析 → 取不到 init 段
        val content = listOf(
            "#EXTM3U",
            "#EXT-X-MAP:URI=\"init.mp4\"",
            "#EXTINF:4,",
            "seg_00001.m4s",
            "#EXTINF:4,",
            "seg_00002.m4s",
            "#EXTINF:4,",
            "adblock0000.m4s",
        ).joinToString("\n")

        val out = M3u8Cleaner.removeMinorityUrl(baseDir, content)
        assertNotNull(out)
        assertTrue(out!!.contains("URI=\"https://cdn.example.com/hls/video/init.mp4\""))
        assertTrue(!out.contains("adblock0000.m4s"))
    }

    @Test
    fun absolutizesExtXMediaUri() {
        // 备用音轨(#EXT-X-MEDIA)的 URI 同样是清单内相对地址
        val content = listOf(
            "#EXTM3U",
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\",URI=\"/audio/track1.m3u8\"",
            "#EXTINF:4,",
            "seg_00001.ts",
            "#EXTINF:4,",
            "seg_00002.ts",
            "#EXTINF:4,",
            "adblock0000.ts",
        ).joinToString("\n")

        val out = M3u8Cleaner.removeMinorityUrl(baseDir, content)
        assertNotNull(out)
        assertTrue(out!!.contains("URI=\"https://cdn.example.com/audio/track1.m3u8\""))
    }

    @Test
    fun stripBomRemovesBomAndLeadingBlank() {
        assertEquals("#EXTM3U", M3u8Cleaner.stripBom("\uFEFF#EXTM3U"))
        assertEquals("#EXTM3U", M3u8Cleaner.stripBom("\n#EXTM3U"))
        assertEquals("#EXTM3U", M3u8Cleaner.stripBom("#EXTM3U"))
        // 中间/末尾不动
        assertEquals("#EXTM3U\nseg.ts\n", M3u8Cleaner.stripBom("#EXTM3U\nseg.ts\n"))
    }

    @Test
    fun bomPlaylistIsStillPurified() {
        // 带 BOM 的清单原来会被 startsWith 判否 → 返回 null(过滤静默失效)
        val content = "\uFEFF" + header +
            "#EXTINF:1,\nsegment0000.ts\n" +
            "#EXTINF:1,\nsegment0001.ts\n" +
            "#EXTINF:1,\nadblock0000.ts\n"
        val out = M3u8Cleaner.removeMinorityUrl("http://cdn/x/", content)
        assertNotNull(out)
        assertTrue(out!!.contains("http://cdn/x/segment0000.ts"))
        assertTrue(!out.contains("adblock0000.ts"))
    }

    @Test
    fun absoluteUrlHandlesBaseWithoutPath() {
        // base 是纯站点根(没有路径段):原来 indexOf('/', 9) 为 -1 → substring(0, -1) 抛
        // StringIndexOutOfBoundsException
        assertEquals("https://cdn.example.com/k.key",
            M3u8Cleaner.absoluteUrl("https://cdn.example.com", "/k.key"))
        // 目录不带结尾斜杠时按目录语义补上,不要拼成 hostkey.key
        assertEquals("https://cdn.example.com/hls/key.key",
            M3u8Cleaner.absoluteUrl("https://cdn.example.com/hls", "key.key"))
        // 已是绝对地址原样返回
        assertEquals("https://other/key.key", M3u8Cleaner.absoluteUrl(baseDir, "https://other/key.key"))
    }

    @Test
    fun returnsNullWhenNoAdsIdentified() {
        // 只有一个前缀分组 → 无法判定广告,返回 null 由调用方回退直接播放
        val content = listOf(
            "#EXTM3U",
            "#EXTINF:4,",
            "seg_00001.ts",
            "#EXTINF:4,",
            "seg_00002.ts",
        ).joinToString("\n")
        assertNull(M3u8Cleaner.removeMinorityUrl(baseDir, content))
    }

    @Test
    fun nonM3u8AndEmptyReturnNull() {
        assertNull(M3u8Cleaner.removeMinorityUrl("http://a/", "not a playlist"))
        assertNull(M3u8Cleaner.removeMinorityUrl("http://a/", header))
        assertNull(M3u8Cleaner.removeMinorityUrl("http://a/", header + "#EXTINF:1,\nhttp://a/segment0000.ts\n"))
    }

    @Test
    fun crlfPlaylistProcessed() {
        val content = headerCrlf +
            "#EXTINF:1,\r\nsegment0000.ts\r\n" +
            "#EXTINF:1,\r\nsegment0001.ts\r\n" +
            "#EXTINF:1,\r\nadblock0000.ts\r\n"
        val out = M3u8Cleaner.removeMinorityUrl("http://cdn/x/", content)
        assertNotNull(out)
        assertTrue(out!!.contains("segment0001.ts"))
        assertTrue(!out.contains("adblock0000.ts"))
    }

    @Test
    fun tooManyDistinctUrlsReturnsNull() {
        // 前缀种类 > 5(如按 hash 命名的分片目录):无法判定广告,整体不净化
        val sb = StringBuilder(header)
        for (i in 0 until 8) {
            sb.append("#EXTINF:1,\n").append("http://host").append(i).append("/segment0000.ts\n")
        }
        assertNull(M3u8Cleaner.removeMinorityUrl("http://cdn/", sb.toString()))
    }
}

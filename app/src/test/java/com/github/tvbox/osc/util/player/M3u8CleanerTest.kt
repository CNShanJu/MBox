package com.github.tvbox.osc.util.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * m3u8 净化回归(纯 JVM):
 * 覆盖"只给第一条 #EXT-X-KEY 补绝对地址(多密钥轮换取不到 key → 黑屏)"
 * 与"base 没有路径时 substring(0, -1) 抛异常"两个曾经的缺陷。
 */
class M3u8CleanerTest {

    private val baseDir = "https://cdn.example.com/hls/video/"

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
}

package com.github.tvbox.osc.download.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 分片失败日志里的 URL 摘要（{@code DownloadExecutor.segUrlForLog}）回归。
 *
 * <p>为什么值得钉住：用户报"下载一直 404"时，业务日志里那行 {@code url=…} 是判断
 * "404 来自源站（CDN 上就是没这个文件，换哪个下载器都一样）"还是"来自本机回源代理
 * {@code 127.0.0.1:9978/proxy?do=…}（源 JS/代理的问题，能修）"的唯一线索 ——
 * 那条 {@code Log.i("TVBox-Download", "分片 HTTP 404 url=…")} 是 I 级，logcat 捕获默认只收 E 级，
 * 用户在"运行日志"里根本看不到。所以它既不能丢 host，也不能把带签名的超长地址整条塞进日志行。
 */
public class DownloadSegmentUrlLogTest {

    @Test
    public void keepsNormalUrlIntact() {
        String url = "https://vip.example.cn/202510/series/EP001_0237.ts";
        assertEquals(url, DownloadExecutor.segUrlForLog(url));
    }

    @Test
    public void keepsLoopbackProxyHostVisible() {
        // 本机回源代理形态：host 就在开头，截断也必须留着它 —— 认源全靠这个
        String url = "http://127.0.0.1:9978/proxy?do=seg&url=https%3A%2F%2Fvip.example.cn%2Fa%2FEP001_0237.ts";
        String out = DownloadExecutor.segUrlForLog(url);
        assertTrue(out.startsWith("http://127.0.0.1:9978/proxy?do=seg&url=https%3A%2F%2F"));
    }

    @Test
    public void truncatesOverlongUrlWithEllipsis() {
        StringBuilder sb = new StringBuilder("https://cdn.example.cn/a/");
        for (int i = 0; i < 60; i++) {
            sb.append("0123456789");
        }
        sb.append(".ts?sign=abcdef");
        String out = DownloadExecutor.segUrlForLog(sb.toString());
        assertTrue("超长 URL 必须夹住，不能把整行日志撑爆", out.length() == DownloadExecutor.SEG_URL_LOG_MAX + 1);
        assertTrue(out.endsWith("…"));
        assertEquals(sb.substring(0, DownloadExecutor.SEG_URL_LOG_MAX), out.substring(0, DownloadExecutor.SEG_URL_LOG_MAX));
    }

    @Test
    public void trimsBlankAndToleratesNull() {
        assertEquals("", DownloadExecutor.segUrlForLog(null));
        assertEquals("https://a/b.ts", DownloadExecutor.segUrlForLog("  https://a/b.ts  "));
    }
}

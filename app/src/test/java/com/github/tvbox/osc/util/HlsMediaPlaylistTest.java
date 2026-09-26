package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * HLS 媒体播放列表解析单测(纯 JVM,不依赖 Android)。
 *
 * <p>被钉住的是本次新增的能力:<b>fMP4({@code #EXT-X-MAP})</b> 与 <b>字节范围({@code #EXT-X-BYTERANGE})</b>。
 * 这两类清单以前是"直接抛异常、整集下不了",现在解析必须精确:
 * <ul>
 *   <li>显式 offset({@code len@off})照单取值;</li>
 *   <li>隐式 offset({@code len})接"上一条<b>同资源</b>分片的末尾"—— 写错就会把别的字节当分片存下来,
 *       产物静默损坏(不是失败,是坏文件),所以按资源分别验;</li>
 *   <li>MAP 带/不带 BYTERANGE;</li>
 *   <li>CRLF/BOM/空行/注释/HTML 包裹等真实清单的脏数据;</li>
 *   <li>异常输入必须给出<b>明确原因</b>({@link HlsMediaPlaylist.Result#error}),而不是抛异常或静默取默认值。</li>
 * </ul>
 */
public class HlsMediaPlaylistTest {

    private static final String BASE = "https://cdn.example.com/hls/movie/index.m3u8";
    private static final String DIR = "https://cdn.example.com/hls/movie/";

    private static HlsMediaPlaylist.Result parse(String body) {
        return HlsMediaPlaylist.parse(BASE, body);
    }

    private static boolean warned(List<String> warnings, String needle) {
        for (String w : warnings) {
            if (w.contains(needle)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 字节范围
    // ------------------------------------------------------------------

    @Test
    public void explicitByteRange_isParsedAsIs() {
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n"
                + "#EXTINF:6.0,\n#EXT-X-BYTERANGE:1000@0\nvideo.mp4\n"
                + "#EXTINF:6.0,\n#EXT-X-BYTERANGE:2000@1000\nvideo.mp4\n"
                + "#EXT-X-ENDLIST\n");
        assertTrue(r.error, r.ok());
        assertEquals(2, r.segments.size());
        assertEquals(DIR + "video.mp4", r.segments.get(0).url);
        assertEquals(0L, r.segments.get(0).range.offset);
        assertEquals(1000L, r.segments.get(0).range.length);
        assertEquals(1000L, r.segments.get(1).range.offset);
        assertEquals(2000L, r.segments.get(1).range.length);
        // 固定区间请求头:bytes=<off>-<off+len-1>(不是续传用的 open-ended "bytes=N-")
        assertEquals("bytes=1000-2999", r.segments.get(1).range.headerValue());
        assertNull(r.init);
        assertFalse(r.isFmp4());
    }

    @Test
    public void implicitByteRange_followsPreviousSegmentEnd() {
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n"
                + "#EXT-X-BYTERANGE:1000@100\nall.ts\n"
                + "#EXT-X-BYTERANGE:500\nall.ts\n"
                + "#EXT-X-BYTERANGE:200@5000\nall.ts\n"
                + "#EXT-X-BYTERANGE:300\nall.ts\n");
        assertTrue(r.error, r.ok());
        assertEquals(100L, r.segments.get(0).range.offset);
        // 省略 @off:接上一条同资源分片的结束位置(100+1000)
        assertEquals(1100L, r.segments.get(1).range.offset);
        assertEquals(500L, r.segments.get(1).range.length);
        assertEquals(5000L, r.segments.get(2).range.offset);
        assertEquals(5200L, r.segments.get(3).range.offset); // 5000+200
    }

    @Test
    public void implicitByteRange_isTrackedPerResource() {
        // 两个资源交替出现:HLS 说"上一条同资源分片",按全局"上一条"算就会取错字节
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n"
                + "#EXT-X-BYTERANGE:100@0\na.ts\n"
                + "#EXT-X-BYTERANGE:200@0\nb.ts\n"
                + "#EXT-X-BYTERANGE:50\na.ts\n"
                + "#EXT-X-BYTERANGE:50\nb.ts\n");
        assertTrue(r.error, r.ok());
        assertEquals(100L, r.segments.get(2).range.offset); // a.ts 接自己上一条的末尾
        assertEquals(200L, r.segments.get(3).range.offset); // b.ts 同理
    }

    @Test
    public void implicitOffsetWithoutPreviousSameResource_warnsAndUsesZero() {
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n#EXT-X-BYTERANGE:500\nonly.ts\n");
        assertTrue(r.error, r.ok());
        assertEquals(0L, r.segments.get(0).range.offset);
        // 不静默:日志里必须能看出"清单不守规范,按资源起点解释"
        assertTrue(warned(r.warnings, "省略 offset"));
    }

    @Test
    public void byteRangeWithoutMap_isTsSlicesOfOneFile() {
        // 单文件 TS 切片:没有 init 段,只是一整集被切成若干区间
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n"
                + "#EXTINF:6.0,\n#EXT-X-BYTERANGE:1880@0\nts.mp4\n"
                + "#EXTINF:6.0,\n#EXT-X-BYTERANGE:1880\nts.mp4\n"
                + "#EXTINF:6.0,\n#EXT-X-BYTERANGE:1880\nts.mp4\n");
        assertTrue(r.error, r.ok());
        assertEquals(3, r.segments.size());
        assertNull(r.init);
        assertFalse(r.isFmp4()); // 没有 MAP 就不是 fMP4:产物是 TS,重封装失败要回退 .ts
        assertEquals(0L, r.segments.get(0).range.offset);
        assertEquals(1880L, r.segments.get(1).range.offset);
        assertEquals(3760L, r.segments.get(2).range.offset);
    }

    // ------------------------------------------------------------------
    // fMP4(init 段)
    // ------------------------------------------------------------------

    @Test
    public void mapWithByteRange_isInitSegment() {
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"720@0\"\n"
                + "#EXTINF:6.0,\n#EXT-X-BYTERANGE:4096@720\nvideo.mp4\n"
                + "#EXTINF:6.0,\n#EXT-X-BYTERANGE:4096\nvideo.mp4\n");
        assertTrue(r.error, r.ok());
        assertNotNull(r.init);
        assertTrue(r.isFmp4());
        assertEquals(DIR + "init.mp4", r.init.url);
        assertEquals(0L, r.init.range.offset);
        assertEquals(720L, r.init.range.length);
        // 分片紧随 init 段之后:分片的隐式起点不看 init 段,而是看"上一条同资源分片"
        assertEquals(720L, r.segments.get(0).range.offset);
        assertEquals(4816L, r.segments.get(1).range.offset);
    }

    @Test
    public void mapWithoutByteRange_hasNullRange() {
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"../init/init.mp4\"\n"
                + "#EXTINF:6.0,\nseg1.m4s\n#EXTINF:6.0,\nseg2.m4s\n");
        assertTrue(r.error, r.ok());
        assertNotNull(r.init);
        assertNull(r.init.range);
        // 相对地址按清单目录拼接(与播放/下载链路的既有口径一致;".." 由 HTTP 客户端归一,这里不自己解析)
        assertEquals(DIR + "../init/init.mp4", r.init.url);
        assertEquals(2, r.segments.size());
        assertNull(r.segments.get(0).range);
        assertEquals(DIR + "seg1.m4s", r.segments.get(0).url);
    }

    @Test
    public void sameMapRepeated_isAccepted() {
        // 规范允许多次声明同一 init 段(常用于分片之间重复声明),不能因此判错
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\na.m4s\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\nb.m4s\n");
        assertTrue(r.error, r.ok());
        assertNotNull(r.init);
        assertEquals(2, r.segments.size());
    }

    @Test
    public void differentMapInMiddle_isRejectedWithReason() {
        // 中途换 init 段 = "整集一个 init"不成立,拼出来必是坏产物:必须明确拒绝并说明
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"init1.mp4\"\n#EXTINF:6,\na.m4s\n"
                + "#EXT-X-MAP:URI=\"init2.mp4\"\n#EXTINF:6,\nb.m4s\n");
        assertFalse(r.ok());
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("EXT-X-MAP"));
        assertTrue(r.error, r.error.contains("中途"));
    }

    // ------------------------------------------------------------------
    // 脏数据兼容(CRLF/BOM/空行/注释/HTML 包裹/相对地址)
    // ------------------------------------------------------------------

    @Test
    public void crlfAndBomAndBlankLines_areTolerated() {
        HlsMediaPlaylist.Result r = parse("\uFEFF#EXTM3U\r\n"
                + "#EXT-X-VERSION:7\r\n"
                + "\r\n"
                + "# 注释行\r\n"
                + "#EXTINF:6.0,\r\n"
                + "#EXT-X-BYTERANGE:1000@0\r\n"
                + "video.mp4\r\n"
                + "\r\n"
                + "#EXT-X-ENDLIST\r\n");
        assertTrue(r.error, r.ok());
        assertEquals(1, r.segments.size());
        assertEquals(DIR + "video.mp4", r.segments.get(0).url);
        assertEquals(1000L, r.segments.get(0).range.length);
    }

    @Test
    public void htmlWrappedLines_areSkipped() {
        // 代理把 m3u8 包在 <pre> 里返回:含尖括号的行不是分片
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n<pre>\n#EXTINF:6,\nseg1.ts\n</pre>\n#EXTINF:6,\nseg2.ts\n");
        assertTrue(r.error, r.ok());
        assertEquals(2, r.segments.size());
        assertEquals(DIR + "seg1.ts", r.segments.get(0).url);
    }

    @Test
    public void resolveUrl_handlesAllReferenceForms() {
        assertEquals("https://other/x.ts", HlsMediaPlaylist.resolveUrl(BASE, "https://other/x.ts"));
        assertEquals("https://cdn.example.com/x.ts", HlsMediaPlaylist.resolveUrl(BASE, "//cdn.example.com/x.ts"));
        assertEquals("https://cdn.example.com/root/x.ts", HlsMediaPlaylist.resolveUrl(BASE, "/root/x.ts"));
        assertEquals(DIR + "x.ts", HlsMediaPlaylist.resolveUrl(BASE, "x.ts"));
        assertEquals(DIR + "sub/x.ts", HlsMediaPlaylist.resolveUrl(BASE, "sub/x.ts"));
        // 站内绝对路径必须带端口,否则带端口的源会全部 404
        assertEquals("http://1.2.3.4:8080/c.ts",
                HlsMediaPlaylist.resolveUrl("http://1.2.3.4:8080/a/b/index.m3u8", "/c.ts"));
        assertEquals("http://1.2.3.4:8080/a/b/c.ts",
                HlsMediaPlaylist.resolveUrl("http://1.2.3.4:8080/a/b/index.m3u8", "c.ts"));
    }

    @Test
    public void mediaSequenceAndDiscontinuity_areExposed() {
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:42\n"
                + "#EXT-X-DISCONTINUITY\n#EXTINF:6,\nseg1.ts\n");
        assertTrue(r.error, r.ok());
        assertEquals(42L, r.mediaSequence);
        assertTrue("不连续点必须留痕(重封装按轨钳制时间戳)", warned(r.warnings, "EXT-X-DISCONTINUITY"));
    }

    // ------------------------------------------------------------------
    // 加密(#EXT-X-KEY):结构与策略分离 —— 解析器只透传,下载侧决定支持与否
    // ------------------------------------------------------------------

    @Test
    public void keyAttributes_arePassedThroughAndNoneClears() {
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x1\n"
                + "#EXTINF:6,\nseg1.ts\n"
                + "#EXT-X-KEY:METHOD=NONE\n"
                + "#EXTINF:6,\nseg2.ts\n");
        assertTrue(r.error, r.ok());
        assertNotNull(r.segments.get(0).key);
        assertEquals("AES-128", r.segments.get(0).key.method);
        assertEquals(DIR + "key.bin", r.segments.get(0).key.uri);
        assertEquals("0x1", r.segments.get(0).key.iv);
        assertNull(r.segments.get(1).key);
    }

    @Test
    public void unsupportedKeyMethod_isLeftToCaller() {
        // SAMPLE-AES 解不了:解析器照结构透传,由下载侧明确报"暂不支持",而不是在这里假装能解
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:6,\nseg1.ts\n");
        assertTrue(r.error, r.ok());
        assertEquals("SAMPLE-AES", r.segments.get(0).key.method);
    }

    @Test
    public void aesKeyWithoutUri_isRejectedWithReason() {
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128\n#EXTINF:6,\nseg1.ts\n");
        assertFalse(r.ok());
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("EXT-X-KEY"));
    }

    // ------------------------------------------------------------------
    // 异常输入:必须给出明确原因(不抛异常、不静默取默认值)
    // ------------------------------------------------------------------

    @Test
    public void malformedByteRange_isRejectedWithReason() {
        String[] bad = { "abc", "1000@", "@5", "0@0", "-1@0" };
        for (String v : bad) {
            HlsMediaPlaylist.Result r = parse("#EXTM3U\n#EXT-X-BYTERANGE:" + v + "\nseg.ts\n");
            assertFalse("应拒绝: " + v, r.ok());
            assertNotNull(r.error);
            assertTrue(r.error, r.error.contains("EXT-X-BYTERANGE"));
            assertTrue(r.segments.isEmpty());
        }
    }

    @Test
    public void mapWithoutUri_isRejectedWithReason() {
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n#EXT-X-MAP:BYTERANGE=\"100@0\"\n#EXTINF:6,\nseg.m4s\n");
        assertFalse(r.ok());
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("EXT-X-MAP"));
    }

    @Test
    public void malformedMapByteRange_isRejectedWithReason() {
        HlsMediaPlaylist.Result r = parse(
                "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"x\"\n#EXTINF:6,\nseg.m4s\n");
        assertFalse(r.ok());
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("BYTERANGE"));
    }

    @Test
    public void emptyInput_isRejectedWithReason() {
        assertFalse(HlsMediaPlaylist.parse(BASE, null).ok());
        assertFalse(HlsMediaPlaylist.parse(BASE, "   ").ok());
        HlsMediaPlaylist.Result r = HlsMediaPlaylist.parse(BASE, "");
        assertNotNull(r.error);
        assertTrue(r.segments.isEmpty());
    }

    @Test
    public void danglingByteRangeAtEnd_isWarnedNotFatal() {
        // 清单尾部多出一条 BYTERANGE(没有对应分片):不致命(它本来就不作用于任何分片),但必须留痕
        HlsMediaPlaylist.Result r = parse("#EXTM3U\n#EXTINF:6,\nseg1.ts\n#EXT-X-BYTERANGE:100\n");
        assertTrue(r.error, r.ok());
        assertEquals(1, r.segments.size());
        assertTrue(warned(r.warnings, "没有对应分片"));
    }
}

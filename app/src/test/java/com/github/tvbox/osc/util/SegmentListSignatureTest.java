package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 分片列表指纹单测(纯 JVM)。
 * <p>
 * 这个指纹是"换线路(重新解析地址)后不把两份视频的碎片拼在一起"的唯一依据,所以判等必须严格:
 * 顺序、数量、任一 URL 变化都要判为"播放列表已变"。
 */
public class SegmentListSignatureTest {

    @Test
    public void sameList_sameSignature() {
        List<String> a = Arrays.asList("http://cdn/1.ts", "http://cdn/2.ts", "http://cdn/3.ts");
        List<String> b = new ArrayList<>(a);
        assertEquals(SegmentListSignature.of(a), SegmentListSignature.of(b));
        assertEquals(SegmentListSignature.of(a), SegmentListSignature.of(a)); // 稳定可重复
    }

    @Test
    public void orderMatters() {
        List<String> a = Arrays.asList("http://cdn/1.ts", "http://cdn/2.ts");
        List<String> b = Arrays.asList("http://cdn/2.ts", "http://cdn/1.ts");
        assertNotEquals(SegmentListSignature.of(a), SegmentListSignature.of(b));
    }

    @Test
    public void countMatters() {
        assertNotEquals(
                SegmentListSignature.of(Arrays.asList("http://cdn/1.ts")),
                SegmentListSignature.of(Arrays.asList("http://cdn/1.ts", "http://cdn/2.ts")));
    }

    @Test
    public void oneUrlChanged_isDifferentPlaylist() {
        List<String> a = Arrays.asList("http://cdn/1.ts", "http://cdn/2.ts");
        // 地址续期/换 CDN:分片内容可能是另一份编码,必须判为不同列表(丢弃旧碎片重下)
        List<String> b = Arrays.asList("http://cdn/1.ts?sign=NEW", "http://cdn/2.ts?sign=NEW");
        assertNotEquals(SegmentListSignature.of(a), SegmentListSignature.of(b));
    }

    @Test
    public void emptyOrNull_meansUnknown() {
        assertEquals("", SegmentListSignature.of(null));
        assertEquals("", SegmentListSignature.of(Collections.<String>emptyList()));
        assertTrue(SegmentListSignature.of(Arrays.asList("http://cdn/1.ts")).length() > 0);
    }

    @Test
    public void byteRanges_matterAsMuchAsUrls() {
        // 同一个大文件的不同区间是完全不同的片(切片型清单):只看 URL 会把"另一份清单的区间"当同一片复用
        List<String> urls = Arrays.asList("http://cdn/all.ts", "http://cdn/all.ts");
        List<HlsMediaPlaylist.ByteRange> a = Arrays.asList(
                new HlsMediaPlaylist.ByteRange(0, 1000), new HlsMediaPlaylist.ByteRange(1000, 1000));
        List<HlsMediaPlaylist.ByteRange> b = Arrays.asList(
                new HlsMediaPlaylist.ByteRange(0, 1000), new HlsMediaPlaylist.ByteRange(2000, 1000));
        assertNotEquals(SegmentListSignature.of(urls, a), SegmentListSignature.of(urls, b));
        assertEquals(SegmentListSignature.of(urls, a),
                SegmentListSignature.of(urls, Arrays.asList(
                        new HlsMediaPlaylist.ByteRange(0, 1000), new HlsMediaPlaylist.ByteRange(1000, 1000))));
    }

    @Test
    public void noRanges_keepsOldSignatureFormat() {
        // 升级前已经在下的普通(整文件分片)任务不能因为指纹算法变化被清空重下:
        // 整表无范围时必须与旧格式(单参数版)得到同一枚指纹
        List<String> urls = Arrays.asList("http://cdn/1.ts", "http://cdn/2.ts");
        List<HlsMediaPlaylist.ByteRange> nulls = Arrays.<HlsMediaPlaylist.ByteRange>asList(null, null);
        assertEquals(SegmentListSignature.of(urls), SegmentListSignature.of(urls, nulls));
        assertEquals(SegmentListSignature.of(urls), SegmentListSignature.of(urls, null));
    }
}

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
}

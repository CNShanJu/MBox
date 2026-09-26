package com.github.tvbox.osc.util.player;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** M3u8Cleaner(去广告少数派分片/补相对路径)的 JVM 单测 */
public class M3u8CleanerTest {

    private static final String HEADER = "#EXTM3U\n";
    private static final String HEADER_CRLF = "#EXTM3U\r\n";

    @Test
    public void nonM3u8_returnsNull() {
        assertNull(M3u8Cleaner.removeMinorityUrl("http://a/", "not a playlist"));
    }

    @Test
    public void empty_returnsNull() {
        assertNull(M3u8Cleaner.removeMinorityUrl("http://a/", HEADER));
    }

    @Test
    public void singleUrl_returnsNull() {
        assertNull(M3u8Cleaner.removeMinorityUrl("http://a/",
                HEADER + "#EXTINF:1,\nhttp://a/segment0000.ts\n"));
    }

    @Test
    public void removesMinorityAdsAndKeepsRelativeBase() {
        // 多数派前缀 segment 出现 2 次,少数派 adblock 1 次 → 广告行被剔除、多数派相对地址补全
        String m3u8 = HEADER
                + "#EXTINF:1,\nsegment0000.ts\n"
                + "#EXTINF:1,\nsegment0001.ts\n"
                + "#EXTINF:1,\nadblock0000.ts\n";
        String out = M3u8Cleaner.removeMinorityUrl("http://cdn/x/", m3u8);
        assertNotNull(out);
        assertTrue(out.contains("http://cdn/x/segment0000.ts"));
        assertTrue(out.contains("http://cdn/x/segment0001.ts"));
        assertTrue(!out.contains("adblock0000.ts"));
    }

    @Test
    public void crlfPlaylist_processed() {
        String m3u8 = HEADER_CRLF
                + "#EXTINF:1,\r\nsegment0000.ts\r\n"
                + "#EXTINF:1,\r\nsegment0001.ts\r\n"
                + "#EXTINF:1,\r\nadblock0000.ts\r\n";
        String out = M3u8Cleaner.removeMinorityUrl("http://cdn/x/", m3u8);
        assertNotNull(out);
        assertTrue(out.contains("segment0001.ts"));
        assertTrue(!out.contains("adblock0000.ts"));
    }

    @Test
    public void tooManyDistinctUrls_returnsNull() {
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 0; i < 8; i++) {
            sb.append("#EXTINF:1,\n").append("http://host").append(i).append("/segment0000.ts\n");
        }
        assertNull(M3u8Cleaner.removeMinorityUrl("http://cdn/", sb.toString()));
    }
}

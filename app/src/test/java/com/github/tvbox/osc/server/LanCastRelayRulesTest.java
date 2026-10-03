package com.github.tvbox.osc.server;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LanCastRelayRulesTest {
    @Test public void rewritesNestedPlaylistSegmentsAndKeyAgainstSourceUrl() {
        String source = "#EXTM3U\r\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\r\n"
                + "#EXTINF:6,\r\nseg-01.ts\r\n#EXT-X-STREAM-INF:BANDWIDTH=1000\r\n"
                + "https://cdn.example/alt.m3u8\r\n";
        Map<String, String> resolved = new HashMap<>();
        String rewritten = LanCastRelayRules.rewrite(source, "https://video.example/show/part/index.m3u8",
                url -> {
                    String path = "/api/cast/media?id=" + resolved.size();
                    resolved.put(path, url);
                    return path;
                });
        assertTrue(rewritten.contains("URI=\"/api/cast/media?id=0\""));
        assertTrue(rewritten.contains("/api/cast/media?id=1\r\n"));
        assertEquals("https://video.example/show/key.bin", resolved.get("/api/cast/media?id=0"));
        assertEquals("https://video.example/show/part/seg-01.ts", resolved.get("/api/cast/media?id=1"));
        assertEquals("https://cdn.example/alt.m3u8", resolved.get("/api/cast/media?id=2"));
        assertFalse(rewritten.contains("seg-01.ts"));
    }

    @Test public void rejectsNonHttpReferences() {
        String rewritten = LanCastRelayRules.rewrite(
                "#EXTM3U\nfile:///secret\nhttp://127.0.0.1:9978/api/lan/data\n",
                "https://video.example/index.m3u8", ignored -> "/api/cast/media?id=1");
        assertEquals(2, rewritten.split("about:blank", -1).length - 1);
        assertFalse(LanCastRelayRules.allowedRedirect("https://video.example/index.m3u8",
                "http://192.168.1.1/private"));
        assertTrue(LanCastRelayRules.allowedRedirect("https://video.example/index.m3u8",
                "https://cdn.example/segment.ts"));
    }

    @Test public void ipv4MappedIpv6CannotReachPrivateChildren() {
        String publicManifest = "https://video.example/index.m3u8";
        assertFalse(LanCastRelayRules.allowedRedirect(publicManifest,
                "http://[::ffff:192.168.1.1]/admin"));
        assertFalse(LanCastRelayRules.allowedRedirect(publicManifest,
                "http://[0:0:0:0:0:ffff:c0a8:101]/admin"));
        assertFalse(LanCastRelayRules.allowedRedirect(publicManifest,
                "http://[::ffff:127.0.0.1]:9978/api/lan/data"));
        assertTrue(LanCastRelayRules.allowedRedirect(publicManifest,
                "https://[2001:4860:4860::8888]/segment.ts"));
    }
}

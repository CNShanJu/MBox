package com.github.tvbox.osc.util.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.net.URLEncoder;
import java.util.HashMap;

public class ProxyDirectFallbackTest {
    @Test
    public void localHlsProxyCanRetryOriginalWithSourceHeaders() throws Exception {
        String target = "https://cdn.example/show/index.m3u8?token=a%2Bb";
        String proxy = "http://127.0.0.1:9978/proxy?do=js&from=catvod&url="
                + URLEncoder.encode(target, "UTF-8")
                + "&header=" + URLEncoder.encode("{\"Referer\":\"https://site.example/\",\"user-agent\":\"source\"}", "UTF-8");
        HashMap<String, String> playbackHeaders = new HashMap<>();
        playbackHeaders.put("User-Agent", "player");
        playbackHeaders.put("Cookie", "session=1");

        ProxyDirectFallback.Candidate candidate = ProxyDirectFallback.from(proxy, playbackHeaders);

        assertNotNull(candidate);
        assertEquals(target, candidate.url);
        assertEquals("source", candidate.headers.get("user-agent"));
        assertEquals(3, candidate.headers.size());
        assertEquals("https://site.example/", candidate.headers.get("Referer"));
        assertEquals("session=1", candidate.headers.get("Cookie"));
    }

    @Test
    public void onlyExternalHlsTargetsBehindLoopbackProxyQualify() throws Exception {
        String encoded = URLEncoder.encode("https://cdn.example/video.mp4", "UTF-8");
        assertNull(ProxyDirectFallback.from("http://127.0.0.1:9978/proxy?url=" + encoded, null));
        assertNull(ProxyDirectFallback.from("http://example.com/proxy?url="
                + URLEncoder.encode("https://cdn.example/index.m3u8", "UTF-8"), null));
        assertNull(ProxyDirectFallback.from("http://127.0.0.1:9978/proxy?url="
                + URLEncoder.encode("http://127.0.0.1:9978/proxy.m3u8", "UTF-8"), null));
        assertNull(ProxyDirectFallback.from("http://127.0.0.1:9978/proxy?url="
                + URLEncoder.encode("https://cdn.example/index.m3u8", "UTF-8")
                + "&url=" + URLEncoder.encode("https://other.example/index.m3u8", "UTF-8"), null));
    }
}

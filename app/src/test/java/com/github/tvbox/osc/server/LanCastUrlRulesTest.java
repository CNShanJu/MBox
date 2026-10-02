package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LanCastUrlRulesTest {
    @Test public void onlyCurrentServerMediaPathsBecomeBrowserRelative() {
        assertEquals("/proxy?do=video&id=1", LanCastUrlRules.browserUrl(
                "http://127.0.0.1:9978/proxy?do=video&id=1", 9978));
        assertEquals("/purify.m3u8", LanCastUrlRules.browserUrl(
                "http://localhost:9978/purify.m3u8", 9978));
        assertEquals("https://video.example.org/movie.mp4", LanCastUrlRules.browserUrl(
                "https://video.example.org/movie.mp4", 9978));
        assertNull(LanCastUrlRules.browserUrl("http://127.0.0.1:9980/proxy?do=video", 9978));
        assertNull(LanCastUrlRules.browserUrl("http://127.0.0.1:9978/api/lan/data", 9978));
        assertNull(LanCastUrlRules.browserUrl("file:///sdcard/movie.mp4", 9978));
    }

    @Test public void playlistRewritesOnlyAllowedLocalMediaUrls() {
        String playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"http://127.0.0.1:9978/proxy?do=key\"\n"
                + "http://127.0.0.1:9978/proxy?do=ts&n=1\n"
                + "https://video.example.org/next.ts\n";
        assertEquals("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"/proxy?do=key\"\n"
                        + "/proxy?do=ts&n=1\nhttps://video.example.org/next.ts\n",
                LanCastUrlRules.rewritePlaylist(playlist, 9978));
    }
}

package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class LanCastUrlRulesTest {
    @Test public void mediaDirectoryOrProxyQueryDoesNotTurnSegmentsIntoPlaylists() {
        assertTrue(LanCastUrlRules.isPlaylistUrl("http://127.0.0.1:9978/purify.m3u8"));
        assertTrue(LanCastUrlRules.isPlaylistUrl("https://video.example/show.M3U8?token=1"));
        assertFalse(LanCastUrlRules.isPlaylistUrl("https://video.example/m3u8/part.ts"));
        assertFalse(LanCastUrlRules.isPlaylistUrl(
                "http://127.0.0.1:9978/proxy?url=https%3A%2F%2Fvideo.example%2Findex.m3u8&segment=1"));
    }

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

    @Test public void browserManifestRegistersFullHttpUrisAndPreservesResourceRoles() {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"http://127.0.0.1:9978/proxy?do=key\"\n"
                + "#EXT-X-MAP:URI=\"/file/init.mp4\"\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio?lang=en\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1000\nvariant?id=1\n"
                + "#EXTINF:4,\n/proxy?do=segment\n//cdn.example/next.ts\npart.ts\n";
        Map<String, LanCastRelayRules.ResourceKind> registered = new LinkedHashMap<>();
        String rewritten = LanCastUrlRules.rewriteForBrowser(playlist,
                "https://video.example/show/master.m3u8", 9978, (url, kind) -> {
                    String relay = "/api/cast/media?id=" + registered.size();
                    registered.put(url, kind);
                    return relay;
                });

        assertEquals(LanCastRelayRules.ResourceKind.KEY,
                registered.get("http://127.0.0.1:9978/proxy?do=key"));
        assertEquals(LanCastRelayRules.ResourceKind.INIT,
                registered.get("http://127.0.0.1:9978/file/init.mp4"));
        assertEquals(LanCastRelayRules.ResourceKind.PLAYLIST,
                registered.get("https://video.example/show/audio?lang=en"));
        assertEquals(LanCastRelayRules.ResourceKind.PLAYLIST,
                registered.get("https://video.example/show/variant?id=1"));
        assertEquals(LanCastRelayRules.ResourceKind.SEGMENT,
                registered.get("http://127.0.0.1:9978/proxy?do=segment"));
        assertEquals(LanCastRelayRules.ResourceKind.SEGMENT,
                registered.get("https://cdn.example/next.ts"));
        assertEquals(LanCastRelayRules.ResourceKind.SEGMENT,
                registered.get("https://video.example/show/part.ts"));
        assertTrue(rewritten.contains("URI=\"/api/cast/media?id=0\""));
        assertFalse(rewritten.contains("/proxy?do=segment"));
        assertEquals(7, registered.size());
    }

    @Test public void browserManifestRejectsUnsafeOrUnregisteredChildBeforeServing() {
        String base = "https://video.example/master.m3u8";
        String[] blocked = {
                "http://127.0.0.1:9980/proxy?do=segment",
                "http://127.0.0.1:9978/api/lan/data",
                "http://127.0.0.1:9978/file/../api/lan/data",
                "http://192.168.1.4/segment.ts",
                "file:///private/segment.ts"
        };
        for (String child : blocked) {
            assertThrows(child, IllegalStateException.class,
                    () -> LanCastUrlRules.rewriteForBrowser("#EXTM3U\n" + child + "\n",
                            base, 9978, (url, role) -> "/api/cast/media?id=1"));
        }
        assertThrows(IllegalStateException.class,
                () -> LanCastUrlRules.rewriteForBrowser("#EXTM3U\nseg.ts\n", base, 9978,
                        (url, role) -> null));
    }

    @Test public void queryOnlyHlsChildKeepsCurrentManifestFilename() {
        Map<String, LanCastRelayRules.ResourceKind> children = new LinkedHashMap<>();
        LanCastUrlRules.rewriteForBrowser("#EXTM3U\n?segment=1\n",
                "https://video.example/show/stream.m3u8?old=1", 9978,
                (url, kind) -> {
                    children.put(url, kind);
                    return "/api/cast/media?id=1";
                });
        assertEquals(LanCastRelayRules.ResourceKind.SEGMENT,
                children.get("https://video.example/show/stream.m3u8?segment=1"));
        assertEquals(1, children.size());
    }

    @Test public void childHeadersFollowOriginAndAreIndependentSnapshots() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("Cookie", "session=secret");
        source.put("authorization", "Bearer secret");
        source.put("Proxy-Authorization", "proxy-secret");
        source.put("User-Agent", "MBox");
        source.put("Referer", "https://video.example/");

        Map<String, String> same = LanCastUrlRules.headersForChild(
                "https://video.example/master.m3u8",
                "https://video.example/segment.ts", source, 9978);
        assertEquals("session=secret", same.get("Cookie"));
        assertNotSame(source, same);

        Map<String, String> cdn = LanCastUrlRules.headersForChild(
                "https://video.example/master.m3u8",
                "https://cdn.example/segment.ts", source, 9978);
        assertFalse(cdn.containsKey("Cookie"));
        assertFalse(cdn.containsKey("authorization"));
        assertFalse(cdn.containsKey("Proxy-Authorization"));
        assertEquals("MBox", cdn.get("User-Agent"));
        assertEquals("https://video.example/", cdn.get("Referer"));
        assertEquals("session=secret", source.get("Cookie"));

        Map<String, String> local = LanCastUrlRules.headersForChild(
                "https://video.example/master.m3u8",
                "http://127.0.0.1:9978/proxy?do=segment", source, 9978);
        assertEquals("session=secret", local.get("Cookie"));
        assertEquals("Bearer secret", local.get("authorization"));
        assertNotSame(source, local);
        assertTrue(LanCastUrlRules.headersForChild(null,
                "https://cdn.example/segment.ts", null, 9978).isEmpty());
    }
}

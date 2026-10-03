package com.github.tvbox.osc.cast;

import com.github.tvbox.osc.server.LanCastRelayRules;

import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CastMediaRulesTest {
    @Test public void onlyLocalOrHeaderBoundMediaNeedsRelay() {
        assertFalse(CastMediaRules.needsRelay("https://video.example/movie.mp4", null, 9978));
        assertTrue(CastMediaRules.needsRelay("http://127.0.0.1:9978/proxy?do=video", null, 9978));
        assertTrue(CastMediaRules.needsRelay("http://192.168.1.5:9978/proxy?do=video", null, 9978));
        assertTrue(CastMediaRules.needsRelay("content://media/external/video/media/1", null, 9978));
        assertTrue(CastMediaRules.needsRelay("https://video.example/movie.mp4",
                Collections.singletonMap("Referer", "https://example.org"), 9978));
    }

    @Test public void playlistCannotReachLocalManagementEndpoints() {
        String local = "http://127.0.0.1:9978/purify.m3u8";
        assertTrue(CastMediaRules.allowedChild(local, "http://127.0.0.1:9978/proxy?do=ts", 9978));
        assertFalse(CastMediaRules.allowedChild(local, "http://127.0.0.1:9978/api/lan/data", 9978));
        assertFalse(CastMediaRules.allowedChild(local, "http://127.0.0.1:9978/file/private", 9978));
        assertFalse(CastMediaRules.allowedChild(local, "http://127.0.0.1:9979/proxy?do=ts", 9978));
        assertFalse(CastMediaRules.allowedChild("https://video.example/index.m3u8",
                "http://127.0.0.1:9978/proxy?do=ts", 9978));
    }

    @Test public void crossOriginChildrenDoNotReceiveSecrets() {
        Map<String, String> supplied = new HashMap<>();
        supplied.put("Cookie", "session=secret");
        supplied.put("Authorization", "Bearer secret");
        supplied.put("Referer", "https://source.example/?token=secret");
        supplied.put("User-Agent", "MBox");
        supplied.put("Connection", "close");
        Map<String, String> snapshot = CastMediaRules.snapshotHeaders(supplied);
        assertFalse(snapshot.containsKey("Connection"));
        assertEquals("session=secret", CastMediaRules.headersForChild("https://source.example/a.m3u8",
                "https://source.example/b.ts", snapshot).get("Cookie"));
        Map<String, String> foreign = CastMediaRules.headersForChild("https://source.example/a.m3u8",
                "https://cdn.example/b.ts", snapshot);
        assertEquals(Collections.singletonMap("User-Agent", "MBox"), foreign);
    }

    @Test public void hlsRewritesNestedMediaToCapabilities() {
        String source = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\nseg.ts\n";
        String rewritten = LanCastRelayRules.rewrite(source, "https://video.example/live/master.m3u8",
                url -> CastMediaRules.allowedChild("https://video.example/live/master.m3u8", url, 9978)
                        ? "http://192.168.1.5:12345/media/capability/" + CastMediaRules.extension(url) : null);
        assertTrue(rewritten.contains("URI=\"http://192.168.1.5:12345/media/capability/bin\""));
        assertTrue(rewritten.contains("http://192.168.1.5:12345/media/capability/ts"));
        assertFalse(rewritten.contains("\nseg.ts\n"));
    }

    @Test public void localRangeSupportsSeekAndSuffix() {
        CastMediaRules.ByteRange range = CastMediaRules.range("bytes=10-19", 100);
        assertTrue(range.valid);
        assertTrue(range.partial);
        assertEquals(10, range.start);
        assertEquals(19, range.end);
        assertEquals(90, CastMediaRules.range("bytes=-10", 100).start);
        assertFalse(CastMediaRules.range("bytes=100-", 100).valid);
        assertFalse(CastMediaRules.range("bytes=0-1,2-3", 100).valid);
    }

    @Test public void fileUriWithSpacesKeepsTheSelectedFileAndExtension() {
        String normalized = CastMediaRules.normalizeFileUri("file:///storage/emulated/0/My Movies/part 1.mp4");
        assertEquals("file:///storage/emulated/0/My%20Movies/part%201.mp4", normalized);
        assertEquals("/storage/emulated/0/My Movies/part 1.mp4",
                new File(CastMediaRules.parse(normalized)).getPath().replace('\\', '/'));
        assertEquals("mp4", CastMediaRules.extension(normalized));
        assertTrue(CastMediaRules.needsRelay(normalized, null, 9978));
    }

    @Test public void extensionlessProxyPlaylistUsesHlsIdentityDespitePlainTextMime() {
        byte[] response = "\uFEFF#EXTM3U\n#EXT-X-VERSION:3\nsegment.ts\n"
                .getBytes(StandardCharsets.UTF_8);
        CastMediaRules.MediaKind kind = CastMediaRules.mediaKind(
                "http://127.0.0.1:9978/proxy?do=js", "text/plain", response, response.length);
        assertEquals("m3u8", kind.extension);
        assertEquals("application/vnd.apple.mpegurl", kind.mime);
        assertTrue(kind.playlist);
    }

    @Test public void extensionlessMp4AndErrorPageAreNotAdvertisedAsHls() {
        byte[] mp4 = new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        CastMediaRules.MediaKind movie = CastMediaRules.mediaKind(
                "https://video.example/play?id=1", "application/octet-stream", mp4, mp4.length);
        assertEquals("mp4", movie.extension);
        assertFalse(movie.playlist);
        byte[] html = "<html>login required</html>".getBytes(StandardCharsets.UTF_8);
        assertTrue(CastMediaRules.mediaKind("https://video.example/play.php", "text/html", html,
                html.length) == CastMediaRules.MediaKind.INVALID);
    }

    @Test public void extensionlessHlsVariantKeepsPlaylistSuffixButKeyDoesNot() {
        String manifest = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvariant?id=1\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key?id=1\"\n";
        String rewritten = LanCastRelayRules.rewriteWithHint(manifest,
                "https://video.example/master", (url, playlistChild) ->
                        "https://phone.example/stream." + (playlistChild ? "m3u8" : "bin"));
        assertTrue(rewritten.contains("\nhttps://phone.example/stream.m3u8\n"));
        assertTrue(rewritten.contains("URI=\"https://phone.example/stream.bin\""));
    }

    @Test public void extensionlessHlsSegmentsUseDecoderAcceptedSuffixes() {
        String transportStream = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key?id=1\"\n"
                + "#EXTINF:10,\nsegment?id=1\n";
        String ts = LanCastRelayRules.rewriteWithResourceHint(transportStream,
                "https://video.example/media/list.m3u8", (url, role) ->
                        "https://phone.example/stream." + CastMediaRules.hlsChildKind(url, role, false).extension);
        assertTrue(ts.contains("URI=\"https://phone.example/stream.key\""));
        assertTrue(ts.contains("\nhttps://phone.example/stream.ts\n"));

        String fragmentedMp4 = "#EXTM3U\n#EXT-X-MAP:URI=\"init?id=1\"\n"
                + "#EXTINF:10,\nsegment?id=1\n";
        String mp4 = LanCastRelayRules.rewriteWithResourceHint(fragmentedMp4,
                "https://video.example/media/list.m3u8", (url, role) ->
                        "https://phone.example/stream." + CastMediaRules.hlsChildKind(url, role, true).extension);
        assertTrue(mp4.contains("URI=\"https://phone.example/stream.mp4\""));
        assertTrue(mp4.contains("\nhttps://phone.example/stream.m4s\n"));

        String preload = "#EXTM3U\n#EXT-X-PRELOAD-HINT:URI=\"init?id=2\",TYPE=MAP\n"
                + "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"part?id=2\"\n";
        String hinted = LanCastRelayRules.rewriteWithResourceHint(preload,
                "https://video.example/media/list.m3u8", (url, role) ->
                        "https://phone.example/stream." + CastMediaRules.hlsChildKind(url, role, true).extension);
        assertTrue(hinted.contains("URI=\"https://phone.example/stream.mp4\",TYPE=MAP"));
        assertTrue(hinted.contains("TYPE=PART,URI=\"https://phone.example/stream.m4s\""));
    }
}

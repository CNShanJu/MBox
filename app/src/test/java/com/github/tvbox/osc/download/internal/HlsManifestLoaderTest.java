package com.github.tvbox.osc.download.internal;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class HlsManifestLoaderTest {
    @Test public void highestQualityIsPreferredAndUnavailableVariantFallsBack() throws Exception {
        List<String> requests = new ArrayList<>();
        HlsManifestLoader.Manifest manifest = HlsManifestLoader.load("https://cdn.example/master.m3u8", url -> {
            requests.add(url);
            if (url.endsWith("master.m3u8")) return "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100,RESOLUTION=640x360\nlow.m3u8\n"
                    + "#EXT-X-STREAM-INF:BANDWIDTH=900,RESOLUTION=1920x1080\nhigh.m3u8";
            if (url.endsWith("high.m3u8")) throw new DownloadErrors.HttpFailure(404, "变体");
            return "#EXTM3U\n#EXTINF:10,\na.ts";
        });
        assertTrue(manifest.url.endsWith("low.m3u8"));
        assertTrue(requests.get(1).endsWith("high.m3u8")); assertEquals(3, requests.size());
    }
    @Test public void recursiveMasterAndLoginHtmlAreRejected() throws Exception {
        try { HlsManifestLoader.load("https://cdn.example/loop.m3u8", url -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nloop.m3u8"); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("循环")); }
        try { HlsManifestLoader.load("https://cdn.example/login", url -> "<html>login</html>"); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("登录页")); }
    }
    @Test public void unavailableSegmentFallsBackAndRequestedQualityIsHonored() throws Exception {
        List<String> checked = new ArrayList<>();
        HlsManifestLoader.Manifest manifest = HlsManifestLoader.load("https://cdn.example/master", url ->
                url.endsWith("master") ? "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900,RESOLUTION=1920x1080\nhigh\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=500,RESOLUTION=1280x720\nmedium"
                        : "#EXTM3U\na.ts", candidate -> {
            checked.add(candidate.url);
            if (candidate.url.endsWith("medium")) throw new DownloadErrors.HttpFailure(403, "变体首片");
        }, 720, null);
        assertTrue(checked.get(0).endsWith("medium")); assertTrue(manifest.url.endsWith("high"));
    }
}

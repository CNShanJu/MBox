package com.github.tvbox.osc.cast;

import com.github.tvbox.osc.server.LanCastRelayRules;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CastMediaRelayCapacityTest {
    @Test public void longVodRewritesEverySegmentAndCapacityFailsAsAWhole() {
        CastMediaRelay.Session session = new CastMediaRelay.Session(null,
                "http://192.168.1.5:12345", "capability", 9978, 1);
        session.register("https://video.example/movie.m3u8", Collections.emptyMap(), null,
                "https://video.example/movie.m3u8", CastMediaRules.MediaKind.HLS, null);

        StringBuilder manifest = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:2\n");
        for (int i = 0; i < 5400; i++)
            manifest.append("#EXTINF:2,\nsegment-").append(i).append(".ts\n");
        manifest.append("#EXT-X-ENDLIST\n");
        String rewritten = LanCastRelayRules.rewriteWithResourceHint(manifest.toString(),
                "https://video.example/movie.m3u8", (url, role) -> session.baseUrl
                        + session.register(url, Collections.emptyMap(), null, url,
                        CastMediaRules.hlsChildKind(url, role, false), role));
        assertEquals(5400, rewritten.split("/media/", -1).length - 1);
        assertFalse(rewritten.contains("about:blank"));

        int next = 5400;
        while (session.targets.size() < CastMediaRelay.MAX_TARGETS) {
            String url = "https://video.example/extra-" + next++ + ".ts";
            session.register(url, Collections.emptyMap(), null, url,
                    CastMediaRules.hlsChildKind(url,
                            LanCastRelayRules.ResourceKind.SEGMENT, false),
                    LanCastRelayRules.ResourceKind.SEGMENT);
        }
        assertThrows(CastMediaRelay.TargetLimitException.class,
                () -> LanCastRelayRules.rewriteWithResourceHint(
                        "#EXTM3U\n#EXTINF:2,\noverflow.ts\n",
                        "https://video.example/movie.m3u8", (url, role) ->
                                session.baseUrl + session.register(url, Collections.emptyMap(),
                                        null, url, CastMediaRules.hlsChildKind(url, role, false),
                                        role)));
        assertEquals(CastMediaRelay.MAX_TARGETS, session.targets.size());
        session.close();
    }

    @Test public void manyLongUrlsCannotExhaustMemoryBeforeTheCountLimit() {
        CastMediaRelay.Session session = new CastMediaRelay.Session(null,
                "http://192.168.1.5:12345", "capability", 9978, 2);
        StringBuilder padding = new StringBuilder(8000);
        for (int i = 0; i < 8000; i++) padding.append('a');
        boolean limited = false;
        for (int i = 0; i < CastMediaRelay.MAX_TARGETS; i++) {
            String url = "https://video.example/" + padding + i;
            try {
                session.register(url, Collections.emptyMap(), null, url,
                        CastMediaRules.MediaKind.HLS, LanCastRelayRules.ResourceKind.PLAYLIST);
            } catch (CastMediaRelay.TargetLimitException expected) {
                limited = true;
                break;
            }
        }
        assertTrue(limited);
        assertTrue(session.targets.size() < CastMediaRelay.MAX_TARGETS);
        assertTrue(session.targetUrlChars <= CastMediaRelay.MAX_TARGET_URL_CHARS);
        session.close();
    }
}

package com.github.tvbox.osc.util.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HlsPlaybackPolicyTest {
    @Test
    public void rollingMediaWindowMustNotBeServedAsFixedSnapshot() {
        String live = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:91\n"
                + "#EXTINF:6,\n91.ts\n#EXTINF:6,\n92.ts\n";
        assertTrue(HlsPlaybackPolicy.isRefreshingMedia(live));
        assertFalse(HlsPlaybackPolicy.isMaster(live));
    }

    @Test
    public void completedVodAndMasterAreNotMistakenForLiveMedia() {
        String vod = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST\n";
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nlow.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhigh.m3u8\n";
        assertFalse(HlsPlaybackPolicy.isRefreshingMedia(vod));
        assertFalse(HlsPlaybackPolicy.isRefreshingMedia(master));
        assertTrue(HlsPlaybackPolicy.isMaster(master));
    }

    @Test
    public void emptyLiveWindowStillNeedsRefreshing() {
        assertTrue(HlsPlaybackPolicy.isRefreshingMedia(
                "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:100\n"));
    }
}

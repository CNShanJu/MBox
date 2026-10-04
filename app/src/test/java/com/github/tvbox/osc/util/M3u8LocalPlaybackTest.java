package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class M3u8LocalPlaybackTest {
    @Test
    public void bomPlaylistCanBeServedLocallyWithoutLosingRelativeResources() {
        String input = "\uFEFF#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\nseg1.ts\n#EXT-X-ENDLIST\n";
        assertEquals("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://cdn.example/key.bin\"\n"
                        + "#EXT-X-MAP:URI=\"https://cdn.example/show/init.mp4\"\n"
                        + "#EXTINF:6,\nhttps://cdn.example/show/seg1.ts\n#EXT-X-ENDLIST\n",
                M3u8Purifier.normalizeForLocalPlayback("https://cdn.example/show/index.m3u8?token=1", input));
    }

    @Test
    public void masterVariantsKeepTheirOrderAndAllVariants() {
        String input = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nlow.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=200\nhigh.m3u8\n";
        assertEquals("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nhttps://cdn.example/low.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=200\nhttps://cdn.example/high.m3u8\n",
                M3u8Purifier.normalizeForLocalPlayback("https://cdn.example/index.m3u8", input));
        assertNull(M3u8Purifier.normalizeForLocalPlayback("https://cdn.example/index.m3u8",
                "#EXTM3U\n#EXTINF:4,\ninvalid uri [\n"));
    }
}

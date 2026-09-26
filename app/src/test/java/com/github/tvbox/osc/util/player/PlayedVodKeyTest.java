package com.github.tvbox.osc.util.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** PlayedVodKey(已播放剧集键:sourceKey|vodId)单测 */
public class PlayedVodKeyTest {

    @Test
    public void of_joinsSourceAndVod() {
        assertEquals("src|vod1", PlayedVodKey.of("src", "vod1"));
        assertEquals("|vod1", PlayedVodKey.of(null, "vod1"));
        assertEquals("src|", PlayedVodKey.of("src", null));
        assertEquals("|", PlayedVodKey.of(null, null));
    }

    @Test
    public void fromEpisodeId_takesFirstTwoSegments() {
        assertEquals("src|vod1", PlayedVodKey.fromEpisodeId("src|vod1|f1|3"));
        assertEquals("|vod1", PlayedVodKey.fromEpisodeId("|vod1|f1|3"));
    }

    @Test
    public void fromEpisodeId_malformedReturnsNull() {
        assertNull(PlayedVodKey.fromEpisodeId(null));
        assertNull(PlayedVodKey.fromEpisodeId("novod"));
        assertNull(PlayedVodKey.fromEpisodeId("src|vod1")); // 缺第三段(此处取前两段需第二个分隔符,故也判非法)
    }
}

package com.github.tvbox.osc.util.player;

import static org.junit.Assert.assertEquals;

import com.github.tvbox.osc.bean.VodInfo;

import org.junit.Test;

/** PlaySessionKeys(播放会话键构造)单测:与 PlayFragment 历史拼接一致 */
public class PlaySessionKeysTest {

    private static VodInfo vod(int playIndex) {
        VodInfo v = new VodInfo();
        v.sourceKey = "src";
        v.id = "vod1";
        v.playFlag = "f1";
        v.playIndex = playIndex;
        return v;
    }

    @Test
    public void progressKey_joinsWithoutSeparatorAsHistorically() {
        // 历史实现:sourceKey+id+playFlag+playIndex+seriesName(无分隔)
        assertEquals("srcvod1f13第4集", PlaySessionKeys.progressKey(vod(3), "第4集"));
    }

    @Test
    public void subtitleCacheKey_joinsWithDashesAndSubtSuffix() {
        assertEquals("src-vod1-f1-3-第4集-subt",
                PlaySessionKeys.subtitleCacheKey(vod(3), "第4集"));
    }

    @Test
    public void keysAreDeterministicAndIndexSensitive() {
        String a = PlaySessionKeys.subtitleCacheKey(vod(2), "第3集");
        String b = PlaySessionKeys.subtitleCacheKey(vod(2), "第3集");
        assertEquals(a, b); // 相同剧/集稳定
        String c = PlaySessionKeys.subtitleCacheKey(vod(3), "第3集");
        assertFalse(a.equals(c)); // 索引不同键不同(进度不串集)
    }

    @Test
    public void playbackSessionKey_vodPrefixed() {
        assertEquals("vod|src|vod1|f1|3", PlaySessionKeys.playbackSessionKey("src", vod(3)));
        assertEquals("vod|null|vod1|f1|0", PlaySessionKeys.playbackSessionKey(null, vod(0)));
    }

    private static void assertFalse(boolean b) {
        org.junit.Assert.assertFalse(b);
    }
}

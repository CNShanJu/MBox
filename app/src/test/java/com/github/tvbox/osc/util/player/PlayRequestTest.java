package com.github.tvbox.osc.util.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.github.tvbox.osc.bean.VodInfo;

import org.junit.Test;

/** PlayRequest(播放请求上下文)单测:身份与键与历史一致 */
public class PlayRequestTest {

    private static VodInfo vod() {
        VodInfo v = new VodInfo();
        v.sourceKey = "src";
        v.id = "vod1";
        v.playFlag = "f1";
        v.playIndex = 3;
        return v;
    }

    private static VodInfo.VodSeries series() {
        VodInfo.VodSeries s = new VodInfo.VodSeries();
        s.name = "第4集";
        s.url = "http://e/4.m3u8";
        return s;
    }

    @Test
    public void carriesIdentityAndKeys() {
        PlayRequest r = PlayRequest.of(vod(), series());
        assertEquals("src", r.sourceKey());
        assertEquals("第4集", r.seriesName());
        assertEquals("http://e/4.m3u8", r.url());
        assertEquals(vod().playFlag, r.vodInfo().playFlag);
        // 键与历史拼接一致
        assertEquals("srcvod1f13第4集", r.progressKey());
        assertEquals("src-vod1-f1-3-第4集-subt", r.subtitleCacheKey());
        assertNotNull(r.vodInfo());
    }
}

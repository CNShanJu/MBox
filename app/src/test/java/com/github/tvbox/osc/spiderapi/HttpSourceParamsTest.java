package com.github.tvbox.osc.spiderapi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Map;

/** HttpSourceParams(type0/1/4 HTTP 源请求参数构造)等值单测:VM 旧路径与 typed 共用同一语义 */
public class HttpSourceParamsTest {

    @Test
    public void detail_type0_usesVideolistAc() {
        Map<String, String> p = HttpSourceParams.detail(0, "1001");
        assertEquals("videolist", p.get("ac"));
        assertEquals("1001", p.get("ids"));
        assertEquals(2, p.size());
    }

    @Test
    public void detail_type1_and_type4_useDetailAc() {
        assertEquals("detail", HttpSourceParams.detail(1, "1001").get("ac"));
        assertEquals("detail", HttpSourceParams.detail(4, "1001").get("ac"));
    }

    @Test
    public void detail_unsupportedTypeOrNullIdReturnsNull() {
        assertNull(HttpSourceParams.detail(3, "1001"));
        assertNull(HttpSourceParams.detail(2, "1001"));
        assertNull(HttpSourceParams.detail(0, null));
    }

    @Test
    public void search_type0_onlyHasWd() {
        Map<String, String> p = HttpSourceParams.search(0, "测试", false);
        assertEquals("测试", p.get("wd"));
        assertEquals(1, p.size());
        assertNull(p.get("ac"));
    }

    @Test
    public void search_type1_addsDetailAc() {
        Map<String, String> p = HttpSourceParams.search(1, "测试", false);
        assertEquals("测试", p.get("wd"));
        assertEquals("detail", p.get("ac"));
        assertEquals(2, p.size());
        assertNull(p.get("quick"));
    }

    @Test
    public void search_type4_quickFlagDistinguishesSearchModes() {
        Map<String, String> agg = HttpSourceParams.search(4, "测试", false);
        assertEquals("detail", agg.get("ac"));
        assertEquals("false", agg.get("quick"));
        Map<String, String> quick = HttpSourceParams.search(4, "测试", true);
        assertEquals("true", quick.get("quick"));
    }

    @Test
    public void search_unsupportedTypeOrNullWordReturnsNull() {
        assertNull(HttpSourceParams.search(3, "测试", false));
        assertNull(HttpSourceParams.search(2, "测试", false));
        assertNull(HttpSourceParams.search(0, null, false));
    }

    @Test
    public void play_type4_hasPlayAndFlag() {
        Map<String, String> p = HttpSourceParams.play(4, "http://x/a.m3u8", "线路1");
        assertEquals("http://x/a.m3u8", p.get("play"));
        assertEquals("线路1", p.get("flag"));
        assertEquals(2, p.size());
    }

    @Test
    public void play_nonType4OrNullUrlReturnsNull() {
        assertNull(HttpSourceParams.play(0, "http://x/a.m3u8", "l"));
        assertNull(HttpSourceParams.play(1, "http://x/a.m3u8", "l"));
        assertNull(HttpSourceParams.play(4, null, "l"));
    }
}

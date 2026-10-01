package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;

import org.junit.Test;

public class LegadoVideoRulesTest {
    private static final String SOURCE = "{\"sourceName\":\"示例\","
            + "\"sourceUrl\":\"https://video.example\","
            + "\"header\":\"{\\\"User-Agent\\\":\\\"Browser\\\",\\\"referer\\\":\\\"{{baseUrl}}\\\"}\","
            + "\"sortUrl\":\"随机::/api/videosort/0?page={{ Math.ceil(Math.random()*1500) }}\\n"
            + "最新::/api/videosort/0?page={{page}}\\n"
            + "搜索::/api/videosort/0?serach={{source.getVariable()}}&page={{page}}\\n"
            + "电影::/api/videosort/25?page={{page}}\","
            + "\"ruleArticles\":\"$.rescont.data[*]\","
            + "\"ruleTitle\":\"$.title##.*忽略.*\","
            + "\"ruleImage\":\"{{$.coverbase64.url}}\","
            + "\"ruleLink\":\"/api/videoplay/{{$.id}}?uuid=1\","
            + "\"ruleContent\":\"<video src='{{$.rescont.videopath}}'></video>\","
            + "\"ruleNextPage\":\"$.rescont.next_page_url\"}";

    @Test
    public void customJsonApiSourceUsesDeclaredRoutesAndRules() {
        LegadoVideoRules.Spec spec = LegadoVideoRules.parse(SOURCE);
        assertNotNull(spec);
        assertEquals("示例", spec.name);
        assertEquals(3, spec.routes.size());
        assertEquals("https://video.example/api/videosort/0?page=1", spec.firstCategoryUrl());
        assertEquals("https://video.example/api/videosort/0?page={random:1500}", spec.routes.get("0"));
        assertEquals("https://video.example", spec.headers.get("referer"));
        assertTrue(spec.extJson.contains("serach={key}"));
        assertTrue(spec.extJson.contains("\"detailImagePath\":\"$.rescont.coverbase64.url\""));
        assertTrue(spec.extJson.contains("\"lastPagePath\":\"$.rescont.last_page\""));
        assertEquals(1, spec.listItems("{\"rescont\":{\"data\":[{\"id\":12,\"title\":\"影片\"}]}}").size());
        assertEquals("https://video.example/api/videoplay/12?uuid=1",
                spec.detailUrl(JsonParser.parseString("{\"id\":12}").getAsJsonObject()));
        assertTrue(spec.hasMedia("{\"rescont\":{\"videopath\":\"https://cdn.example/a.m3u8\"}}"));
        assertFalse(spec.hasMedia("{\"rescont\":{\"title\":\"影片\"}}"));
        String scriptedSearch = SOURCE.replace("{{source.getVariable()}}",
                "{{v=source.getVariable();if(/^\\\\s*$/.test(v)||v==null)"
                        + "source.setVariable('测试');source.getVariable()}}");
        assertNotNull(LegadoVideoRules.parse(scriptedSearch));
    }

    @Test
    public void unsupportedRulesAreNotSilentlyImported() {
        assertNull(LegadoVideoRules.parse(SOURCE.replace("$.rescont.data[*]", "@js:run()")));
        assertNull(LegadoVideoRules.parse(SOURCE.replace("/api/videoplay/{{$.id}}", "https://elsewhere.example/{{$.id}}")));
    }

}

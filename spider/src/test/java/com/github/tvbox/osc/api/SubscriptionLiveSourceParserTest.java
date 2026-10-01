package com.github.tvbox.osc.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.github.tvbox.osc.spiderapi.LiveChannelConfigApi;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.List;

public class SubscriptionLiveSourceParserTest {

    @Test
    public void keepsEveryNamedSourceFromOneSubscription() {
        JsonArray lives = JsonParser.parseString("["
                + "{\"name\":\"Kimentanm\",\"type\":0,\"url\":\"https://live.example/1.m3u\"},"
                + "{\"name\":\"综合直播\",\"type\":0,\"url\":\"http://live.example/2.m3u\"},"
                + "{\"name\":\"范明明\",\"type\":0,\"url\":\"https://live.example/3.m3u\"},"
                + "{\"name\":\"虎牙一起看\",\"type\":0,\"url\":\"https://live.example/4.m3u\"},"
                + "{\"name\":\"斗鱼一起看\",\"type\":0,\"url\":\"https://live.example/5.m3u\"},"
                + "{\"name\":\"YY轮播\",\"type\":0,\"url\":\"https://live.example/6.m3u\"}]"
        ).getAsJsonArray();

        List<LiveChannelConfigApi.SubscribeLiveSource> sources = SubscriptionLiveSourceParser.parseTypedSources(lives);

        assertEquals(6, sources.size());
        assertEquals("Kimentanm", sources.get(0).name);
        assertEquals("https://live.example/1.m3u", sources.get(0).url);
        assertEquals("YY轮播", sources.get(5).name);
        assertEquals("https://live.example/6.m3u", sources.get(5).url);
    }

    @Test
    public void invalidEntryDoesNotHideLaterSourcesAndEmbeddedGroupsStayUnselectable() {
        JsonArray lives = JsonParser.parseString("["
                + "{\"type\":0},"
                + "{\"name\":\"内嵌频道\",\"type\":1},"
                + "{\"name\":\"备用\",\"type\":0,\"url\":\"https://live.example/backup.m3u\"}]"
        ).getAsJsonArray();

        List<LiveChannelConfigApi.SubscribeLiveSource> sources = SubscriptionLiveSourceParser.parseTypedSources(lives);

        assertEquals(2, sources.size());
        assertEquals("内嵌频道", sources.get(0).name);
        assertFalse(sources.get(0).hasUrl());
        assertEquals("备用", sources.get(1).name);
    }
}

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

    @Test
    public void typeThreePlaylistRemainsSelectable() {
        JsonArray lives = JsonParser.parseString("["
                + "{\"name\":\"Gather蕉\",\"type\":3,\"url\":\"https://example.org/live.txt\"},"
                + "{\"name\":\"内嵌分组\",\"type\":3}]"
        ).getAsJsonArray();

        List<LiveChannelConfigApi.SubscribeLiveSource> sources = SubscriptionLiveSourceParser.parseTypedSources(lives);

        assertEquals(2, sources.size());
        assertEquals("https://example.org/live.txt", sources.get(0).url);
        assertFalse(sources.get(1).hasUrl());
    }

    @Test
    public void previewOfEmbeddedGroupsMatchesSelectableSingleChannelRule() {
        JsonArray lives = JsonParser.parseString("["
                + "{\"group\":\"单频道\",\"channels\":[{\"name\":\"电视\","
                + "\"urls\":[\"https://live.example/a.m3u8$主线\"]}]},"
                + "{\"group\":\"多频道\",\"channels\":["
                + "{\"name\":\"甲\",\"urls\":[\"https://live.example/a\"]},"
                + "{\"name\":\"乙\",\"urls\":[\"https://live.example/b\"]}]}]"
        ).getAsJsonArray();

        List<LiveChannelConfigApi.SubscribeLiveSource> sources =
                SubscriptionLiveSourceParser.parseEmbeddedSources(lives);

        assertEquals(2, sources.size());
        assertEquals("单频道", sources.get(0).name);
        assertEquals("https://live.example/a.m3u8", sources.get(0).url);
        assertEquals("多频道", sources.get(1).name);
        assertFalse(sources.get(1).hasUrl());
    }
}

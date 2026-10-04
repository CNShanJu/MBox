package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;

import com.github.tvbox.osc.util.live.TxtSubscribe;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public class TxtSubscribeTest {
    @Test
    public void duplicateUrlsAreDeduplicatedWithinChannelOnly() {
        LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> groups = new LinkedHashMap<>();
        TxtSubscribe.parse(groups, "新闻,#genre#\nA,https://example.com/live#https://example.com/live\n"
                + "A,https://example.com/live\nB,https://example.com/live\n");
        LinkedHashMap<String, ArrayList<String>> channels = groups.get("新闻");
        assertEquals(1, channels.get("A").size());
        assertEquals(1, channels.get("B").size());
    }
}

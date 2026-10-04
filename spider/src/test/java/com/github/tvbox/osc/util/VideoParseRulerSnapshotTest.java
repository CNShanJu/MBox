package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

public class VideoParseRulerSnapshotTest {
    @After
    public void reset() {
        VideoParseRuler.clearRule();
    }

    @Test
    public void preparedRulesStayInactiveUntilPublishedAndCannotBeMutatedThroughInputs() {
        ArrayList<String> input = new ArrayList<>(Arrays.asList("video", "m3u8"));
        VideoParseRuler.RuleSet prepared = new VideoParseRuler.Builder()
                .addHostRule("example.com", input)
                .addHostFilter("example.com", Arrays.asList("ad"))
                .build();
        input.clear();

        assertNull(VideoParseRuler.getHostRules("example.com"));
        assertNull(VideoParseRuler.getHostFilters("example.com"));

        VideoParseRuler.replaceRules(prepared);
        assertEquals(Arrays.asList("video", "m3u8"),
                VideoParseRuler.getHostRules("example.com").get(0));
        assertEquals(Arrays.asList("ad"), VideoParseRuler.getHostFilters("example.com").get(0));

        VideoParseRuler.getHostRules("example.com").get(0).clear();
        assertEquals(Arrays.asList("video", "m3u8"),
                VideoParseRuler.getHostRules("example.com").get(0));
    }

    @Test
    public void replacingRulesDropsOldHostsAndLegacyAddsPreserveTheOtherMap() {
        VideoParseRuler.replaceRules(new VideoParseRuler.Builder()
                .addHostRule("old.example", Arrays.asList("old"))
                .addHostFilter("old.example", Arrays.asList("block"))
                .build());

        VideoParseRuler.replaceRules(new VideoParseRuler.Builder()
                .addHostRule("new.example", Arrays.asList("new"))
                .build());
        assertNull(VideoParseRuler.getHostRules("old.example"));
        assertNull(VideoParseRuler.getHostFilters("old.example"));

        VideoParseRuler.addHostFilter("new.example", new ArrayList<>(Arrays.asList("filter")));
        assertEquals(Arrays.asList("new"), VideoParseRuler.getHostRules("new.example").get(0));
        assertEquals(Arrays.asList("filter"), VideoParseRuler.getHostFilters("new.example").get(0));
    }
}

package com.github.catvod.crawler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.github.tvbox.osc.bean.ParseBean;

import org.junit.Test;

import java.util.Arrays;

public class SniffPagePlannerTest {
    private static final String INTERNAL_ID = "NBY-XMYAES095124|84f621bd";

    @Test public void opaqueEpisodeIdNeedsARealParserPage() {
        assertNull(SniffPagePlanner.pageUrl("", INTERNAL_ID));
        assertNull(SniffPagePlanner.pageUrl("http://", INTERNAL_ID));
        assertEquals("https://parse.example/watch?url=" + INTERNAL_ID,
                SniffPagePlanner.pageUrl("https://parse.example/watch?url=", INTERNAL_ID));
        assertEquals("https://source.example/episode/2",
                SniffPagePlanner.pageUrl("", "https://source.example/episode/2"));
    }

    @Test public void parserSelectionMatchesPlaybackPath() {
        ParseBean defaultParser = parser("default", 0, "https://default.example/?url=");
        ParseBean named = parser("named", 0, "https://named.example/?url=");
        assertSame(defaultParser, SniffPagePlanner.webParser("", true, defaultParser, Arrays.asList(named)));
        assertSame(named, SniffPagePlanner.webParser("parse:named", false, defaultParser, Arrays.asList(named)));
        assertNull(SniffPagePlanner.webParser("json:https://api.example/?url=", false,
                defaultParser, Arrays.asList(named)));
        assertNull(SniffPagePlanner.webParser("parse:missing", false,
                defaultParser, Arrays.asList(named)));
    }

    private static ParseBean parser(String name, int type, String url) {
        ParseBean parser = new ParseBean();
        parser.setName(name);
        parser.setType(type);
        parser.setUrl(url);
        return parser;
    }
}

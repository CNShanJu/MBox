package xyz.doikki.videoplayer.exo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** 保护 Media3 取流时源站 UA、重播鉴权头及媒体源之间的隔离。 */
public class PlaybackHttpHeadersTest {
    @Test
    public void sourceUserAgentSurvivesReplayWithoutMutatingCaller() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("user-agent", "  source-bound-agent  ");
        headers.put("Authorization", "  Bearer token  ");
        headers.put("ignored", null);
        Map<String, String> first = ExoMediaSourceHelper.copyHeaders(headers);
        Map<String, String> replay = ExoMediaSourceHelper.copyHeaders(headers);
        assertEquals("source-bound-agent", first.get("user-agent"));
        assertEquals("Bearer token", first.get("Authorization"));
        assertFalse(first.containsKey("ignored"));
        assertEquals(first, replay);
        assertEquals("  source-bound-agent  ", headers.get("user-agent"));
        assertTrue(headers.containsKey("ignored"));
    }

    @Test
    public void switchingSourceDoesNotShareOrCarryHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "first-source");
        Map<String, String> first = ExoMediaSourceHelper.copyHeaders(headers);
        headers.put("User-Agent", "second-source");
        Map<String, String> second = ExoMediaSourceHelper.copyHeaders(headers);
        second.clear();
        assertEquals("first-source", first.get("User-Agent"));
        assertEquals("second-source", headers.get("User-Agent"));
        assertTrue(ExoMediaSourceHelper.copyHeaders(null).isEmpty());
        headers.put("User-Agent", "  ");
        assertTrue(ExoMediaSourceHelper.copyHeaders(headers).isEmpty());
    }
}

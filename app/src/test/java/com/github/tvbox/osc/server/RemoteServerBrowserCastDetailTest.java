package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.UUID;

public class RemoteServerBrowserCastDetailTest {
    @Test public void sameVideoKeepsSessionAcrossEpisodeLineAndSortChanges() {
        RemoteServer.BrowserCastDetail first = RemoteServer.nextBrowserCastDetail(null,
                "source-a", "vod-1", "剧名", "线路一", 0, false,
                "第1集", "https://source.example/episode-1");
        RemoteServer.BrowserCastDetail selected = first.withSelectedIndex(4);
        RemoteServer.BrowserCastDetail changed = RemoteServer.nextBrowserCastDetail(selected,
                "source-a", "vod-1", "剧名", "线路二", 4, true,
                "第5集", "https://source.example/episode-5");

        assertEquals(first.sessionId, UUID.fromString(first.sessionId).toString());
        assertEquals(first.sessionId, changed.sessionId);
        assertEquals(first.sessionId, selected.sessionId);
        assertEquals("第1集", selected.episodeName);
        assertEquals("https://source.example/episode-1", selected.episodeUrl);
        assertEquals("线路二", changed.playFlag);
        assertEquals(4, changed.selectedIndex);
        assertTrue(changed.reverseSort);
        assertEquals("第5集", changed.episodeName);
        assertEquals("https://source.example/episode-5", changed.episodeUrl);
        assertEquals("线路一", first.playFlag);
        assertEquals(0, first.selectedIndex);
        assertFalse(first.reverseSort);
        assertEquals("第1集", first.episodeName);
        assertEquals("https://source.example/episode-1", first.episodeUrl);
        assertTrue(RemoteServer.browserCastSessionMatches(changed, first.sessionId));
    }

    @Test public void differentVideoOrSourceCannotRebindPreviousSession() {
        RemoteServer.BrowserCastDetail first = RemoteServer.nextBrowserCastDetail(null,
                "source-a", "vod-1", "剧名", "线路一", 0, false,
                "第1集", "https://source.example/episode-1");
        RemoteServer.BrowserCastDetail differentVod = RemoteServer.nextBrowserCastDetail(first,
                "source-a", "vod-2", "另一部", "线路一", 0, false,
                "第1集", "https://source.example/other-episode-1");
        RemoteServer.BrowserCastDetail differentSource = RemoteServer.nextBrowserCastDetail(first,
                "source-b", "vod-1", "剧名", "线路一", 0, false,
                "第1集", "https://other.example/episode-1");

        assertNotEquals(first.sessionId, differentVod.sessionId);
        assertNotEquals(first.sessionId, differentSource.sessionId);
        assertFalse(RemoteServer.browserCastSessionMatches(differentVod, first.sessionId));
        assertFalse(RemoteServer.browserCastSessionMatches(differentSource, first.sessionId));
        assertFalse(RemoteServer.browserCastSessionMatches(null, first.sessionId));
    }
}

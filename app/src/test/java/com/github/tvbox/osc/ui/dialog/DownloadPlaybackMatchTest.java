package com.github.tvbox.osc.ui.dialog;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.VodInfo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;

public class DownloadPlaybackMatchTest {
    @Test
    public void onlyExactPlayingEpisodeMayReuseItsResolvedUrl() {
        VodInfo displayed = vod("source", "vod", "A", 1);
        VodInfo playing = vod("source", "vod", "A", 1);
        assertTrue(DownloadPlaybackMatch.matches(displayed, playing));

        displayed.playFlag = "B";
        displayed.playIndex = -1;
        assertFalse(DownloadPlaybackMatch.matches(displayed, playing));

        displayed.playFlag = "A";
        displayed.playIndex = 1;
        displayed.seriesMap.get("A").get(1).url = "new-episode-url";
        assertFalse(DownloadPlaybackMatch.matches(displayed, playing));
    }

    @Test
    public void staleDetailOrIndexCannotReusePlayback() {
        VodInfo displayed = vod("source", "vod", "A", 1);
        VodInfo playing = vod("source", "vod", "A", 1);
        playing.sourceKey = "other-source";
        assertFalse(DownloadPlaybackMatch.matches(displayed, playing));
        playing.sourceKey = "source";
        playing.id = "other-vod";
        assertFalse(DownloadPlaybackMatch.matches(displayed, playing));
        playing.id = "vod";
        playing.playIndex = 0;
        assertFalse(DownloadPlaybackMatch.matches(displayed, playing));
        playing.playIndex = 9;
        displayed.playIndex = 9;
        assertFalse(DownloadPlaybackMatch.matches(displayed, playing));
    }

    private static VodInfo vod(String source, String id, String flag, int index) {
        VodInfo info = new VodInfo();
        info.sourceKey = source;
        info.id = id;
        info.playFlag = flag;
        info.playIndex = index;
        info.seriesMap = new LinkedHashMap<>();
        info.seriesMap.put("A", new ArrayList<>(Arrays.asList(
                new VodInfo.VodSeries("Episode 1", "url-a-1"),
                new VodInfo.VodSeries("Episode 2", "url-a-2"))));
        info.seriesMap.put("B", new ArrayList<>(Arrays.asList(
                new VodInfo.VodSeries("Episode 1", "url-b-1"),
                new VodInfo.VodSeries("Episode 2", "url-b-2"))));
        return info;
    }
}

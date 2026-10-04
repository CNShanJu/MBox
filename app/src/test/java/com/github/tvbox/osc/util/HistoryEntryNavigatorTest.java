package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.VodInfo;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;

public class HistoryEntryNavigatorTest {
    @Test
    public void savedEpisodeCanStartBeforeDetailRefresh() {
        VodInfo info = new VodInfo();
        info.playFlag = "line";
        info.playIndex = 1;
        info.seriesMap = new LinkedHashMap<>();
        info.seriesMap.put("line", new ArrayList<>(Arrays.asList(
                new VodInfo.VodSeries("第一集", "https://example.com/1"),
                new VodInfo.VodSeries("第二集", "https://example.com/2"))));

        assertTrue(HistoryEntryNavigator.hasPlayableSnapshot(info));
        info.playIndex = 2;
        assertFalse(HistoryEntryNavigator.hasPlayableSnapshot(info));
    }

    @Test
    public void oldHistoryWithoutEpisodeListUsesDetailFallback() {
        VodInfo info = new VodInfo();
        info.playFlag = "line";
        info.playIndex = 0;

        assertFalse(HistoryEntryNavigator.hasPlayableSnapshot(info));
    }

    @Test
    public void episodeSnapshotSurvivesIntentSerialization() throws Exception {
        VodInfo info = new VodInfo();
        info.playFlag = "line";
        info.seriesMap = new LinkedHashMap<>();
        info.seriesMap.put("line", new ArrayList<>(Arrays.asList(
                new VodInfo.VodSeries("第一集", "https://example.com/1"))));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(info);
        }
        VodInfo restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (VodInfo) input.readObject();
        }
        assertTrue(HistoryEntryNavigator.hasPlayableSnapshot(restored));
    }

    @Test
    public void refreshedUrlMatchesEpisodeNameAfterPlaylistShift() {
        VodInfo playing = playlist("line", 1,
                new VodInfo.VodSeries("第一集", "https://old/1"),
                new VodInfo.VodSeries("第二集", "https://old/2"));
        VodInfo fresh = playlist("other", 0,
                new VodInfo.VodSeries("预告", "https://new/preview"),
                new VodInfo.VodSeries("第一集", "https://new/1"),
                new VodInfo.VodSeries("第二集", "https://new/2"));
        fresh.seriesMap.put("line", fresh.seriesMap.remove("other"));

        assertTrue(HistoryEntryNavigator.matchCurrentEpisode(fresh, playing));
        assertEquals("line", fresh.playFlag);
        assertEquals(2, fresh.playIndex);
    }

    @Test
    public void repeatedNamesPreferSavedIndexWhenUrlsRefresh() {
        VodInfo playing = playlist("line", 1,
                new VodInfo.VodSeries("正片", "https://old/1"),
                new VodInfo.VodSeries("正片", "https://old/2"));
        VodInfo fresh = playlist("line", 0,
                new VodInfo.VodSeries("正片", "https://new/1"),
                new VodInfo.VodSeries("正片", "https://new/2"));

        assertTrue(HistoryEntryNavigator.matchCurrentEpisode(fresh, playing));
        assertEquals(1, fresh.playIndex);
    }

    @Test
    public void ambiguousNameWithoutMatchingIndexDoesNotSelectWrongEpisode() {
        VodInfo playing = playlist("line", 1,
                new VodInfo.VodSeries("第一集", "https://old/1"),
                new VodInfo.VodSeries("正片", "https://old/2"));
        VodInfo fresh = playlist("line", 0,
                new VodInfo.VodSeries("正片", "https://new/1"),
                new VodInfo.VodSeries("预告", "https://new/preview"),
                new VodInfo.VodSeries("正片", "https://new/2"));

        assertFalse(HistoryEntryNavigator.matchCurrentEpisode(fresh, playing));
        assertEquals(0, fresh.playIndex);
    }

    private static VodInfo playlist(String flag, int index, VodInfo.VodSeries... episodes) {
        VodInfo info = new VodInfo();
        info.playFlag = flag;
        info.playIndex = index;
        info.seriesMap = new LinkedHashMap<>();
        info.seriesMap.put(flag, new ArrayList<>(Arrays.asList(episodes)));
        return info;
    }
}

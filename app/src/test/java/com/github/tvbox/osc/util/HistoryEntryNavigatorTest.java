package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
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
}

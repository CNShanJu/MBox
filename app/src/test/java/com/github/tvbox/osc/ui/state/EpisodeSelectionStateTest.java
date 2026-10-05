package com.github.tvbox.osc.ui.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import com.github.tvbox.osc.bean.VodInfo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class EpisodeSelectionStateTest {
    @Test
    public void browsingAnotherLineDoesNotChangePlaybackOrHighlightItsOldIndex() {
        VodInfo info = vod(lines(3, 2));
        info.playFlag = "A";
        info.playIndex = 1;
        info.seriesMap.get("B").get(1).selected = true;
        EpisodeSelectionState state = new EpisodeSelectionState();
        state.bind(info, false);

        assertTrue(state.browse(info, "B"));
        assertEquals("B", state.displayedFlag(info));
        assertSame(info.seriesMap.get("B"), state.displayedEpisodes(info));
        assertEquals("A", info.playFlag);
        assertEquals(1, info.playIndex);
        assertTrue(info.seriesMap.get("A").get(1).selected);
        assertNoSelected(info.seriesMap.get("B"));
        assertFalse(info.seriesFlags.get(0).selected);
        assertTrue(info.seriesFlags.get(1).selected);

        // Playback's next-episode callback still advances on A while B remains visible.
        info.playIndex = 2;
        state.sync(info);
        assertEquals("A", info.playFlag);
        assertTrue(info.seriesMap.get("A").get(2).selected);
        assertFalse(info.seriesMap.get("A").get(1).selected);
        assertNoSelected(info.seriesMap.get("B"));
    }

    @Test
    public void syncClearsAllStaleSelectionsAcrossLines() {
        VodInfo info = vod(lines(3, 2));
        info.playFlag = "A";
        info.playIndex = 2;
        for (VodInfo.VodSeriesFlag flag : info.seriesFlags) flag.selected = true;
        for (List<VodInfo.VodSeries> line : info.seriesMap.values()) {
            for (VodInfo.VodSeries episode : line) episode.selected = true;
        }

        EpisodeSelectionState state = new EpisodeSelectionState();
        state.bind(info, false);
        assertTrue(info.seriesFlags.get(0).selected);
        assertFalse(info.seriesFlags.get(1).selected);
        assertFalse(info.seriesMap.get("A").get(0).selected);
        assertFalse(info.seriesMap.get("A").get(1).selected);
        assertTrue(info.seriesMap.get("A").get(2).selected);
        assertNoSelected(info.seriesMap.get("B"));
    }

    @Test
    public void invalidOrEmptyPlaybackLineFallsBackAndClampsIndex() {
        LinkedHashMap<String, List<VodInfo.VodSeries>> map = new LinkedHashMap<>();
        map.put("empty", new ArrayList<>());
        map.put("short", episodes("S1", "S2"));
        VodInfo info = vod(map);
        info.playFlag = "empty";
        info.playIndex = 99;
        EpisodeSelectionState state = new EpisodeSelectionState();

        state.bind(info, false);
        assertEquals("short", info.playFlag);
        assertEquals(1, info.playIndex);
        assertEquals("short", state.displayedFlag(info));
        assertTrue(info.seriesMap.get("short").get(1).selected);

        assertTrue(state.browse(info, "empty"));
        assertTrue(state.displayedEpisodes(info).isEmpty());
        assertFalse(state.select(info, 0));
        assertEquals("short", info.playFlag);
        assertEquals(1, info.playIndex);
    }

    @Test
    public void allEmptyLinesHaveNoPlaybackSelectionAndRejectClicks() {
        LinkedHashMap<String, List<VodInfo.VodSeries>> map = new LinkedHashMap<>();
        map.put("empty", new ArrayList<>());
        map.put("missing", null);
        VodInfo info = vod(map);
        info.playFlag = "missing";
        info.playIndex = 5;
        EpisodeSelectionState state = new EpisodeSelectionState();

        state.bind(info, false);
        assertNull(info.playFlag);
        assertEquals(-1, info.playIndex);
        assertEquals("empty", state.displayedFlag(info));
        assertTrue(state.displayedEpisodes(info).isEmpty());
        assertFalse(state.select(info, -1));
        assertFalse(state.select(info, 0));
        assertFalse(state.browse(info, "unknown"));
    }

    @Test
    public void selectingValidEpisodeCommitsBrowsedLineOnlyAtClick() {
        VodInfo info = vod(lines(2, 1));
        info.playFlag = "A";
        info.playIndex = 1;
        EpisodeSelectionState state = new EpisodeSelectionState();
        state.bind(info, false);
        state.browse(info, "B");

        assertFalse(state.select(info, -1));
        assertFalse(state.select(info, 1));
        assertEquals("A", info.playFlag);
        assertEquals(1, info.playIndex);
        assertTrue(state.select(info, 0));
        assertEquals("B", info.playFlag);
        assertEquals(0, info.playIndex);
        assertNoSelected(info.seriesMap.get("A"));
        assertTrue(info.seriesMap.get("B").get(0).selected);
    }

    @Test
    public void reversingPreservesActualEpisodeWhileBrowsingAnotherLine() {
        VodInfo info = vod(lines(3, 2));
        info.playFlag = "A";
        info.playIndex = 0;
        VodInfo.VodSeries playing = info.seriesMap.get("A").get(0);
        EpisodeSelectionState state = new EpisodeSelectionState();
        state.bind(info, false);
        state.browse(info, "B");

        assertTrue(state.reverse(info));
        assertEquals("B", state.displayedFlag(info));
        assertEquals("A", info.playFlag);
        assertEquals(2, info.playIndex);
        assertSame(playing, info.seriesMap.get("A").get(info.playIndex));
        assertTrue(playing.selected);
        assertNoSelected(info.seriesMap.get("B"));

        assertFalse(state.reverse(info));
        assertEquals(0, info.playIndex);
        assertSame(playing, info.seriesMap.get("A").get(info.playIndex));
    }

    @Test
    public void refreshedPlaylistKeepsValidBrowsedLineAndFallsBackWhenRemoved() {
        EpisodeSelectionState state = new EpisodeSelectionState();
        VodInfo original = vod(lines(2, 2));
        original.playFlag = "A";
        state.bind(original, false);
        state.browse(original, "B");

        VodInfo refreshed = vod(lines(3, 1));
        refreshed.playFlag = "A";
        state.bind(refreshed, true);
        assertEquals("B", state.displayedFlag(refreshed));
        assertEquals("A", refreshed.playFlag);
        assertNoSelected(refreshed.seriesMap.get("B"));

        LinkedHashMap<String, List<VodInfo.VodSeries>> onlyA = new LinkedHashMap<>();
        onlyA.put("A", episodes("A1"));
        VodInfo removed = vod(onlyA);
        removed.playFlag = "A";
        state.bind(removed, true);
        assertEquals("A", state.displayedFlag(removed));
        assertTrue(removed.seriesFlags.get(0).selected);
    }

    private static VodInfo vod(LinkedHashMap<String, List<VodInfo.VodSeries>> map) {
        VodInfo info = new VodInfo();
        info.seriesMap = map;
        info.seriesFlags = new ArrayList<>();
        for (String flag : map.keySet()) info.seriesFlags.add(new VodInfo.VodSeriesFlag(flag));
        return info;
    }

    private static LinkedHashMap<String, List<VodInfo.VodSeries>> lines(int a, int b) {
        LinkedHashMap<String, List<VodInfo.VodSeries>> map = new LinkedHashMap<>();
        List<VodInfo.VodSeries> first = new ArrayList<>();
        List<VodInfo.VodSeries> second = new ArrayList<>();
        for (int index = 1; index <= a; index++) first.add(new VodInfo.VodSeries("A" + index, "a" + index));
        for (int index = 1; index <= b; index++) second.add(new VodInfo.VodSeries("B" + index, "b" + index));
        map.put("A", first);
        map.put("B", second);
        return map;
    }

    private static List<VodInfo.VodSeries> episodes(String... names) {
        List<VodInfo.VodSeries> episodes = new ArrayList<>();
        for (String name : names) episodes.add(new VodInfo.VodSeries(name, name));
        return episodes;
    }

    private static void assertNoSelected(List<VodInfo.VodSeries> episodes) {
        for (VodInfo.VodSeries episode : episodes) assertFalse(episode.selected);
    }
}

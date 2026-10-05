package com.github.tvbox.osc.ui.state;

import com.github.tvbox.osc.bean.VodInfo;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Detail-page episode state. The displayed line is a browsing choice; VodInfo.playFlag and
 * VodInfo.playIndex always identify the selected playback episode.
 */
public final class EpisodeSelectionState {
    private String displayedFlag;

    /** Validate playback against a refreshed playlist, then choose or retain the browsed line. */
    public void bind(VodInfo info, boolean keepDisplayedFlag) {
        if (info == null) {
            displayedFlag = null;
            return;
        }
        if (!hasPlayableLine(info, info.playFlag)) {
            info.playFlag = firstPlayableFlag(info);
        }
        List<VodInfo.VodSeries> playing = episodesFor(info, info.playFlag);
        info.playIndex = playing.isEmpty() ? -1 : Math.max(0, Math.min(info.playIndex, playing.size() - 1));
        if (!keepDisplayedFlag || !hasLine(info, displayedFlag)) {
            displayedFlag = hasLine(info, info.playFlag) ? info.playFlag : firstExistingFlag(info);
        }
        sync(info);
    }

    /** Switch the visible line without changing the current playback selection. */
    public boolean browse(VodInfo info, String flag) {
        if (!hasLine(info, flag)) return false;
        displayedFlag = flag;
        sync(info);
        return true;
    }

    public String displayedFlag(VodInfo info) {
        if (hasLine(info, displayedFlag)) return displayedFlag;
        if (hasLine(info, info == null ? null : info.playFlag)) return info.playFlag;
        return firstExistingFlag(info);
    }

    /** Returns the model list for the visible line, or an empty list when none is available. */
    public List<VodInfo.VodSeries> displayedEpisodes(VodInfo info) {
        return episodesFor(info, displayedFlag(info));
    }

    /** Commit a clicked episode to playback; invalid positions leave both selections untouched. */
    public boolean select(VodInfo info, int index) {
        String flag = displayedFlag(info);
        List<VodInfo.VodSeries> episodes = episodesFor(info, flag);
        if (flag == null || index < 0 || index >= episodes.size()) return false;
        info.playFlag = flag;
        info.playIndex = index;
        sync(info);
        return true;
    }

    /** Derive all highlights from the two state fields, clearing stale or duplicate selections. */
    public void sync(VodInfo info) {
        if (info == null) return;
        displayedFlag = displayedFlag(info);
        if (info.seriesFlags != null) {
            for (VodInfo.VodSeriesFlag flag : info.seriesFlags) {
                if (flag != null) flag.selected = displayedFlag != null
                        && Objects.equals(flag.name, displayedFlag);
            }
        }
        if (info.seriesMap == null) return;
        for (Map.Entry<String, List<VodInfo.VodSeries>> line : info.seriesMap.entrySet()) {
            List<VodInfo.VodSeries> episodes = line.getValue();
            if (episodes == null) continue;
            boolean playingLine = info.playFlag != null && info.playFlag.equals(line.getKey());
            for (int index = 0; index < episodes.size(); index++) {
                VodInfo.VodSeries episode = episodes.get(index);
                if (episode != null) episode.selected = playingLine && index == info.playIndex;
            }
        }
    }

    /** Reverse every line while preserving the actual playing episode and visible line. */
    public boolean reverse(VodInfo info) {
        if (info == null) return false;
        bind(info, true);
        List<VodInfo.VodSeries> playing = episodesFor(info, info.playFlag);
        if (!playing.isEmpty()) info.playIndex = playing.size() - 1 - info.playIndex;
        if (info.seriesMap != null) {
            for (List<VodInfo.VodSeries> episodes : info.seriesMap.values()) {
                if (episodes != null) Collections.reverse(episodes);
            }
        }
        info.reverseSort = !info.reverseSort;
        sync(info);
        return info.reverseSort;
    }

    private static boolean hasLine(VodInfo info, String flag) {
        return info != null && info.seriesMap != null && flag != null
                && info.seriesMap.containsKey(flag);
    }

    private static boolean hasPlayableLine(VodInfo info, String flag) {
        return hasLine(info, flag) && !episodesFor(info, flag).isEmpty();
    }

    private static List<VodInfo.VodSeries> episodesFor(VodInfo info, String flag) {
        if (!hasLine(info, flag)) return Collections.emptyList();
        List<VodInfo.VodSeries> episodes = info.seriesMap.get(flag);
        return episodes == null ? Collections.emptyList() : episodes;
    }

    private static String firstPlayableFlag(VodInfo info) {
        if (info == null || info.seriesMap == null) return null;
        for (Map.Entry<String, List<VodInfo.VodSeries>> line : info.seriesMap.entrySet()) {
            if (line.getKey() != null && line.getValue() != null && !line.getValue().isEmpty()) {
                return line.getKey();
            }
        }
        return null;
    }

    private static String firstExistingFlag(VodInfo info) {
        if (info == null || info.seriesMap == null) return null;
        for (String flag : info.seriesMap.keySet()) {
            if (flag != null) return flag;
        }
        return null;
    }
}

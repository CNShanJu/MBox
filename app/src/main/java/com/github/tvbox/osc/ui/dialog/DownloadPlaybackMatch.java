package com.github.tvbox.osc.ui.dialog;

import com.github.tvbox.osc.bean.VodInfo;

import java.util.List;
import java.util.Objects;

/** 下载时复用播放器地址的精确条件；浏览线路不能冒用仍在播放的旧线路地址。 */
final class DownloadPlaybackMatch {
    private DownloadPlaybackMatch() {}

    static boolean matches(VodInfo displayed, VodInfo playing) {
        if (displayed == null || playing == null
                || empty(displayed.sourceKey) || empty(displayed.id) || empty(displayed.playFlag)
                || !Objects.equals(displayed.sourceKey, playing.sourceKey)
                || !Objects.equals(displayed.id, playing.id)
                || !Objects.equals(displayed.playFlag, playing.playFlag)
                || displayed.playIndex < 0 || displayed.playIndex != playing.playIndex
                || displayed.seriesMap == null || playing.seriesMap == null) return false;
        List<VodInfo.VodSeries> shown = displayed.seriesMap.get(displayed.playFlag);
        List<VodInfo.VodSeries> active = playing.seriesMap.get(playing.playFlag);
        if (shown == null || active == null || displayed.playIndex >= shown.size()
                || playing.playIndex >= active.size()) return false;
        VodInfo.VodSeries episode = shown.get(displayed.playIndex);
        VodInfo.VodSeries playingEpisode = active.get(playing.playIndex);
        return episode != null && playingEpisode != null && !empty(episode.url)
                && Objects.equals(episode.url, playingEpisode.url)
                && Objects.equals(episode.name, playingEpisode.name);
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }
}

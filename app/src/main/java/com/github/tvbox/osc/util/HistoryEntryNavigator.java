package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;

import java.util.List;
import java.util.Objects;

/** 历史卡片与首页“上次看到”共用的打开路径。 */
public final class HistoryEntryNavigator {
    private HistoryEntryNavigator() { }

    public static void open(Context context, VodInfo info) {
        if (context == null || info == null) return;
        if (HistorySourceBinding.isAvailable(info)) {
            Intent intent = new Intent(context, DetailActivity.class)
                    .putExtra("id", info.id)
                    .putExtra("sourceKey", info.sourceKey)
                    .putExtra("vodName", info.name);
            if (hasPlayableSnapshot(info)) intent.putExtra("historySnapshot", info);
            context.startActivity(intent);
        } else if (!TextUtils.isEmpty(info.name)) {
            AppBubble.toast("原来源未启用，正在当前订阅中搜索同名影片");
            context.startActivity(new Intent(context, FastSearchActivity.class)
                    .putExtra("title", info.name));
        } else {
            AppBubble.toast("原来源未启用，且历史记录缺少片名");
        }
    }

    /** 旧历史没有剧集快照，仍走详情请求；只把可起播的快照交给详情页。 */
    public static boolean hasPlayableSnapshot(VodInfo info) {
        if (info == null || info.seriesMap == null || info.playFlag == null) return false;
        List<VodInfo.VodSeries> episodes = info.seriesMap.get(info.playFlag);
        return episodes != null && info.playIndex >= 0 && info.playIndex < episodes.size()
                && episodes.get(info.playIndex) != null
                && episodes.get(info.playIndex).url != null
                && !episodes.get(info.playIndex).url.isEmpty();
    }

    /** Locate the saved episode in a refreshed playlist without selecting an ambiguous namesake. */
    public static boolean matchCurrentEpisode(VodInfo fresh, VodInfo playing) {
        if (!hasPlayableSnapshot(playing) || fresh == null || fresh.seriesMap == null) return false;
        List<VodInfo.VodSeries> episodes = fresh.seriesMap.get(playing.playFlag);
        if (episodes == null) return false;
        VodInfo.VodSeries current = playing.seriesMap.get(playing.playFlag).get(playing.playIndex);

        int byUrl = -1;
        int urlMatches = 0;
        for (int i = 0; i < episodes.size(); i++) {
            VodInfo.VodSeries episode = episodes.get(i);
            if (episode == null || !Objects.equals(episode.url, current.url)) continue;
            if (i == playing.playIndex) return selectEpisode(fresh, playing.playFlag, i);
            byUrl = i;
            urlMatches++;
        }
        if (urlMatches == 1) return selectEpisode(fresh, playing.playFlag, byUrl);
        if (urlMatches > 1 || current.name == null || current.name.isEmpty()) return false;

        if (playing.playIndex < episodes.size()) {
            VodInfo.VodSeries sameIndex = episodes.get(playing.playIndex);
            if (sameIndex != null && Objects.equals(sameIndex.name, current.name)) {
                return selectEpisode(fresh, playing.playFlag, playing.playIndex);
            }
        }
        int byName = -1;
        for (int i = 0; i < episodes.size(); i++) {
            VodInfo.VodSeries episode = episodes.get(i);
            if (episode == null || !Objects.equals(episode.name, current.name)) continue;
            if (byName >= 0) return false;
            byName = i;
        }
        return byName >= 0 && selectEpisode(fresh, playing.playFlag, byName);
    }

    private static boolean selectEpisode(VodInfo fresh, String flag, int index) {
        fresh.playFlag = flag;
        fresh.playIndex = index;
        return true;
    }
}

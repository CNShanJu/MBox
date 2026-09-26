package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.DownloadRoute;
import com.github.tvbox.osc.bean.VodInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 换线路重下(4.8②)的候选挑选:从"同一集的多条线路"里挑出可用的备用线路(纯计算,可 JVM 单测)。
 *
 * <p>背景:一条线路整体失效时(整集缺片全是源站 404/410、或连续多片下载失败),原地重试与重新解析地址
 * 都是同一个结果 —— 真正能救回来的是"这集换一条线路下载"。候选在入队时就地取材(详情页的
 * {@code seriesMap} 已经包含全部线路),换线路时不需要再联网查一次剧集详情。
 *
 * <p>同集对齐规则(两条线路的集名/集数不一定一致,顺序也可能相反):
 * <ol>
 *   <li>先按<strong>集名精确匹配</strong>(最可靠);</li>
 *   <li>匹配不到再按<strong>集序号</strong>取同位置(集名风格不同但集数一致时用得上);</li>
 *   <li>都取不到就跳过这条线路,不猜。</li>
 * </ol>
 * 另外跳过:当前线路本身、与当前地址相同的线路(换了等于没换)、以及候选数超过 {@link #MAX_ALTERNATIVES} 的部分
 * —— 每换一条都要把已下碎片整份丢弃重下(另一份播放列表的分片拼不进去),不能无限试。
 */
public final class DownloadRoutePlan {

    /** 最多带几条备用线路(每条都要整集重下,代价大) */
    public static final int MAX_ALTERNATIVES = 3;

    private DownloadRoutePlan() {
    }

    /**
     * 挑选备用线路。
     *
     * @param seriesMap     详情页的多线路剧集表(线路名 → 该线路的剧集列表),可 null
     * @param currentFlag   当前线路(不入候选)
     * @param currentRawUrl 当前线路这一集的原始地址(与它相同的候选跳过)
     * @param episodeIndex  这一集在当前线路里的序号(集名匹配不到时按序号对齐)
     * @param episodeName   这一集的集名(优先按它匹配)
     * @return 备用线路列表(可能为空,永不为 null);最多 {@link #MAX_ALTERNATIVES} 条
     */
    public static List<DownloadRoute> alternatives(Map<String, List<VodInfo.VodSeries>> seriesMap,
            String currentFlag, String currentRawUrl, int episodeIndex, String episodeName) {
        List<DownloadRoute> out = new ArrayList<>();
        if (seriesMap == null || seriesMap.isEmpty()) return out;
        for (Map.Entry<String, List<VodInfo.VodSeries>> e : seriesMap.entrySet()) {
            if (out.size() >= MAX_ALTERNATIVES) break;
            String flag = e.getKey();
            if (flag == null || flag.isEmpty() || flag.equals(currentFlag)) continue;
            List<VodInfo.VodSeries> list = e.getValue();
            if (list == null || list.isEmpty()) continue;
            VodInfo.VodSeries picked = pickEpisode(list, episodeIndex, episodeName);
            if (picked == null || picked.url == null || picked.url.isEmpty()) continue;
            if (picked.url.equals(currentRawUrl)) continue; // 同一地址:换过去还是同一个文件,没意义
            DownloadRoute route = new DownloadRoute(flag, picked.url, picked.name);
            if (!route.isUsable()) continue;
            out.add(route);
        }
        return out;
    }

    /** 在一条线路里定位同一集:先按集名精确匹配,再按序号对齐;都取不到返回 null */
    private static VodInfo.VodSeries pickEpisode(List<VodInfo.VodSeries> list, int episodeIndex, String episodeName) {
        if (episodeName != null && !episodeName.isEmpty()) {
            for (VodInfo.VodSeries s : list) {
                if (s != null && episodeName.equals(s.name) && s.url != null && !s.url.isEmpty()) return s;
            }
        }
        if (episodeIndex >= 0 && episodeIndex < list.size()) {
            VodInfo.VodSeries s = list.get(episodeIndex);
            if (s != null && s.url != null && !s.url.isEmpty()) return s;
        }
        return null;
    }
}

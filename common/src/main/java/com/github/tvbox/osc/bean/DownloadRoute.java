package com.github.tvbox.osc.bean;

/**
 * 备用下载线路(换线路重下用):同一集在<strong>另一条 playFlag</strong> 下的原始集地址。
 *
 * <p>为什么记"原始集地址"而不是解析后的直链:换线路要的是"用新线路重新解析一次",旧线路的直链
 * 正是失效的那个,复用没有意义;而解析只需 (sourceKey, playFlag, episodeRawUrl) 三元组,
 * 所以候选里只要有后两者即可。
 *
 * <p>随任务持久化({@code DownloadStore} 用 Gson 存 {@code List<DownloadTask>}),进程重启后仍能换线路;
 * 由入队侧按"同集同序号"从详情页的其它线路收集(见 {@code util/DownloadRoutePlan#alternatives})。
 */
public class DownloadRoute {

    /** 线路名(对应详情页的 seriesMap key / VodInfo.playFlag) */
    public String playFlag;
    /** 该线路下这一集的原始地址(重解析入参) */
    public String episodeRawUrl;
    /** 该线路下这一集的集名(仅日志/提示用,可空) */
    public String episodeName;

    public DownloadRoute() {
    }

    public DownloadRoute(String playFlag, String episodeRawUrl, String episodeName) {
        this.playFlag = playFlag;
        this.episodeRawUrl = episodeRawUrl;
        this.episodeName = episodeName;
    }

    /** 解析入参是否齐全(null/空一律视为不可用候选) */
    public boolean isUsable() {
        return playFlag != null && !playFlag.isEmpty()
                && episodeRawUrl != null && !episodeRawUrl.isEmpty();
    }

    /** 日志用描述:线路名(集名) */
    public String describe() {
        if (episodeName == null || episodeName.isEmpty()) return String.valueOf(playFlag);
        return playFlag + "(" + episodeName + ")";
    }
}

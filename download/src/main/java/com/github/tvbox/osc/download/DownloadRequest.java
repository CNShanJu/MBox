package com.github.tvbox.osc.download;

import com.github.tvbox.osc.bean.DownloadRoute;

import java.util.List;
import java.util.Map;

/**
 * 下载入队请求(已准备齐全的业务数据,UI 只构造本对象;下载模块不读取 Activity/VM/Hawk)。
 */
public final class DownloadRequest {

    public final String url;              // 真实可下载地址(直链或 m3u8)
    public final String sourceKey;        // 源 key
    public final String playFlag;         // 线路/解析方式
    public final String episodeRawUrl;    // 剧集原始地址(重解析用)
    public final String episodeId;        // 统一剧集标识
    public final String pic;              // 海报地址
    public final Map<String, String> headers; // 防盗链请求头(可 null)
    public final String sourceName;       // 来源名
    public final String vodName;          // 剧名
    public final String episodeName;      // 集名(文件名用)
    /** 备用线路候选(换线路重下用,可 null):同一集在其它线路下的原始地址,见 DownloadRoutePlan */
    public final List<DownloadRoute> altRoutes;
    public final boolean requiresResolution;

    public DownloadRequest(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String pic, Map<String, String> headers,
                           String sourceName, String vodName, String episodeName) {
        this(url, sourceKey, playFlag, episodeRawUrl, episodeId, pic, headers,
                sourceName, vodName, episodeName, null);
    }

    public DownloadRequest(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String pic, Map<String, String> headers,
                           String sourceName, String vodName, String episodeName,
                           List<DownloadRoute> altRoutes) {
        this(url, sourceKey, playFlag, episodeRawUrl, episodeId, pic, headers, sourceName, vodName,
                episodeName, altRoutes, false);
    }

    public DownloadRequest(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String pic, Map<String, String> headers,
                           String sourceName, String vodName, String episodeName,
                           List<DownloadRoute> altRoutes, boolean requiresResolution) {
        this.requiresResolution = requiresResolution;
        this.url = url;
        this.sourceKey = sourceKey;
        this.playFlag = playFlag;
        this.episodeRawUrl = episodeRawUrl;
        this.episodeId = episodeId;
        this.pic = pic;
        this.headers = com.github.tvbox.osc.util.DownloadHeaders.merge(headers);
        this.sourceName = sourceName;
        this.vodName = vodName;
        this.episodeName = episodeName;
        if (altRoutes == null) this.altRoutes = null;
        else {
            java.util.ArrayList<DownloadRoute> copy = new java.util.ArrayList<>();
            for (DownloadRoute route : altRoutes) if (route != null)
                copy.add(new DownloadRoute(route.playFlag, route.episodeRawUrl, route.episodeName));
            this.altRoutes = java.util.Collections.unmodifiableList(copy);
        }
    }
}

package com.github.tvbox.osc.download;

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

    public DownloadRequest(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String pic, Map<String, String> headers,
                           String sourceName, String vodName, String episodeName) {
        this.url = url;
        this.sourceKey = sourceKey;
        this.playFlag = playFlag;
        this.episodeRawUrl = episodeRawUrl;
        this.episodeId = episodeId;
        this.pic = pic;
        this.headers = headers;
        this.sourceName = sourceName;
        this.vodName = vodName;
        this.episodeName = episodeName;
    }
}

package com.github.tvbox.osc.download;

import java.util.Map;

/**
 * 下载地址嗅探器（方案 A：单例无头 WebView，串行复用）。
 * <p>
 * 嗅探型源（type 0，播放靠 WebView 页面嗅探）的剧集地址是源站页面而非真实视频，
 * 批量下载非当前集时无法靠解析器直接拿地址，需在任务启动前经此接口嗅探出
 * 真实播放地址 + 请求头（UA/Referer/Cookie），防盗链源的分片才能下载。
 * <p>
 * 由 :app 模块的 {@code WebSniffResolver} 实现，App 启动时注册到
 * {@link com.github.tvbox.osc.download.internal.DownloadManager}；DownloadScheduler 在任务
 * 启动前/地址过期重解析时调用。实现须保证同一 WebView 串行复用（Cookie/登录会话不丢）。
 */
public interface DownloadUrlSniffer {

    /** 嗅探结果：真实播放地址 + 请求头（可为空） */
    class SniffResult {
        public final String url;
        public final Map<String, String> headers;

        public SniffResult(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    /**
     * 无头 WebView 加载剧集页，拦截真实视频地址（串行，单实例复用）。
     *
     * @param sourceKey     来源 key
     * @param playFlag      线路名（可空，当前未使用，预留）
     * @param episodeRawUrl 源站剧集页地址
     * @param timeoutMs     单集嗅探超时（毫秒）
     * @return 命中返回地址 + 请求头；超时/失败/无命中返回 null。任意线程可调（实现内部转主线程）。
     */
    SniffResult sniff(String sourceKey, String playFlag, String episodeRawUrl, long timeoutMs);
}

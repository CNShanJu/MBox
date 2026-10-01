package com.github.tvbox.osc.spiderapi;

import java.util.Map;

/** 播放地址解析契约:download 等消费方只依赖本接口,不依赖 :spider 实现 */
public interface PlayUrlResolverApi {

    /**
     * 解析真实播放地址与请求头(串行执行,避免 quickjs 并发卡死)。
     *
     * @param sourceKey   源 key
     * @param playFlag    线路/解析方式
     * @param episodeRawUrl 原始剧集页/地址
     * @return 解析结果;失败/不可解析返回 null
     */
    ResolveResult resolvePlayUrl(String sourceKey, String playFlag, String episodeRawUrl);

    /** 直链解析失败时，取得播放器实际使用的 WebView 解析页；内部集标识不能直接当网页加载。 */
    ResolveResult resolveSniffPage(String sourceKey, String playFlag, String episodeRawUrl);

    /**
     * 解析"当前播放集"的真实地址与请求头(下载防盗链特例,原 PlayUrlResolver.resolveCurrentWithPlaybackHeaders):
     * 解析成功但结果无请求头时补播放器请求头(UA/Referer);解析失败时回退播放器已嗅探到的地址 + 播放器请求头。
     *
     * @param playbackHeaders  播放器当前请求头(可 null)
     * @param playbackFinalUrl 播放器最终播放地址(解析失败时回退,可 null)
     * @return 解析/回退结果;两者皆无时 url 可能为 null,调用方按"解析失败"处理
     */
    ResolveResult resolveCurrentWithPlaybackHeaders(String sourceKey, String playFlag, String episodeRawUrl,
                                                    Map<String, String> playbackHeaders, String playbackFinalUrl);

    /** 未注入时的默认实现:一律按"解析失败"返回 null */
    PlayUrlResolverApi NONE = new PlayUrlResolverApi() {
        @Override
        public ResolveResult resolvePlayUrl(String sourceKey, String playFlag, String episodeRawUrl) {
            return null;
        }

        @Override
        public ResolveResult resolveSniffPage(String sourceKey, String playFlag, String episodeRawUrl) {
            return null;
        }

        @Override
        public ResolveResult resolveCurrentWithPlaybackHeaders(String sourceKey, String playFlag, String episodeRawUrl,
                                                               Map<String, String> playbackHeaders,
                                                               String playbackFinalUrl) {
            return null;
        }
    };
}

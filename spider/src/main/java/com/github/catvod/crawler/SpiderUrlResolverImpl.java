package com.github.catvod.crawler;

import com.github.tvbox.osc.spiderapi.PlayUrlResolverApi;
import com.github.tvbox.osc.spiderapi.ResolveResult;

import java.util.Map;

/**
 * :spider 侧对爬虫契约(spiderapi 包)的实现:桥接 ApiConfig/SpiderApi 的既有解析链路。
 * App 组合根在启动时把它注入 DownloadManager(下载侧不再直接依赖 :spider 实现)与
 * PlayUrlResolverProviders(app 侧批量下载经契约解析)。
 */
public final class SpiderUrlResolverImpl implements PlayUrlResolverApi {

    private static final SpiderUrlResolverImpl INSTANCE = new SpiderUrlResolverImpl();

    public static SpiderUrlResolverImpl get() {
        return INSTANCE;
    }

    @Override
    public ResolveResult resolvePlayUrl(String sourceKey, String playFlag, String episodeRawUrl) {
        if (sourceKey == null || playFlag == null || episodeRawUrl == null) {
            android.util.Log.w("SpiderBridge", "resolvePlayUrl: 入参缺失 sourceKey=" + sourceKey
                    + " playFlag=" + playFlag + " raw=" + episodeRawUrl);
            return null;
        }
        try {
            PlayUrlResolver.ResolveResult rr = SpiderApi.resolvePlayUrl(sourceKey, playFlag, episodeRawUrl);
            if (rr == null || rr.url == null || rr.url.isEmpty()) {
                android.util.Log.w("SpiderBridge", "resolvePlayUrl 解析无结果: " + sourceKey
                        + "/" + playFlag + " raw=" + episodeRawUrl);
                return null;
            }
            return new ResolveResult(rr.url, rr.headers);
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "resolvePlayUrl 异常: " + sourceKey + "/" + playFlag, th);
            return null;
        }
    }

    @Override
    public ResolveResult resolveSniffPage(String sourceKey, String playFlag, String episodeRawUrl) {
        if (sourceKey == null || playFlag == null || episodeRawUrl == null) return null;
        try {
            PlayUrlResolver.ResolveResult rr = SpiderApi.resolveSniffPage(sourceKey, playFlag, episodeRawUrl);
            return rr == null ? null : new ResolveResult(rr.url, rr.headers);
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "resolveSniffPage 异常: " + sourceKey + "/" + playFlag, th);
            return null;
        }
    }

    @Override
    public ResolveResult resolveCurrentWithPlaybackHeaders(String sourceKey, String playFlag, String episodeRawUrl,
                                                           Map<String, String> playbackHeaders,
                                                           String playbackFinalUrl) {
        if (sourceKey == null || playFlag == null || episodeRawUrl == null) {
            android.util.Log.w("SpiderBridge", "resolveCurrentWithPlaybackHeaders: 入参缺失 sourceKey=" + sourceKey
                    + " playFlag=" + playFlag + " raw=" + episodeRawUrl);
            return null;
        }
        try {
            // 复用既有实现(含"解析无头补播放器头""解析失败回退播放器地址"两条分支),行为与直调一致
            PlayUrlResolver.ResolveResult rr = PlayUrlResolver.resolveCurrentWithPlaybackHeaders(
                    sourceKey, playFlag, episodeRawUrl, playbackHeaders, playbackFinalUrl);
            if (rr == null) {
                return null;
            }
            return new ResolveResult(rr.url, rr.headers);
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "resolveCurrentWithPlaybackHeaders 异常: "
                    + sourceKey + "/" + playFlag, th);
            return null;
        }
    }
}

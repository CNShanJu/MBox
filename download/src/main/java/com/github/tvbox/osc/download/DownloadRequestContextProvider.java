package com.github.tvbox.osc.download;

import java.util.Map;

/** 宿主刷新登录 Cookie 与请求上下文；实现不暴露 WebView 或业务单例给下载模块。 */
public interface DownloadRequestContextProvider {
    Map<String, String> refresh(String sourceKey, String playFlag, String episodeRawUrl,
                               String resolvedUrl, Map<String, String> headers) throws Exception;
}

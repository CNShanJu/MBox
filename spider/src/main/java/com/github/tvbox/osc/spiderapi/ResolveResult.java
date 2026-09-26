package com.github.tvbox.osc.spiderapi;

import java.util.Map;

/** 播放地址解析结果(纯 DTO,跨模块共享):真实播放地址 + 防盗链请求头 */
public final class ResolveResult {

    public final String url;
    public final Map<String, String> headers;

    public ResolveResult(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers;
    }
}

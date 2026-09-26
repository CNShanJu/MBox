package com.github.tvbox.osc.spiderapi;

/**
 * 播放地址解析契约持有者(App 组合根注入 :spider 实现;未注入时为 {@link PlayUrlResolverApi#NONE},
 * 调用方按"解析失败"处理,不崩溃)。下载侧另有 DownloadFacade.setUrlResolverApi 注入同一实现。
 */
public final class PlayUrlResolverProviders {

    private static volatile PlayUrlResolverApi impl = PlayUrlResolverApi.NONE;

    private PlayUrlResolverProviders() {
    }

    public static void set(PlayUrlResolverApi api) {
        if (api != null) {
            impl = api;
        }
    }

    public static PlayUrlResolverApi get() {
        return impl;
    }
}

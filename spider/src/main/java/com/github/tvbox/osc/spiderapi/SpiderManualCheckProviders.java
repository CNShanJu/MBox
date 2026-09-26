package com.github.tvbox.osc.spiderapi;

/** 手动视频判定服务的持有者(App 组合根注入 :spider 实现;默认 null=回退通用规则) */
public final class SpiderManualCheckProviders {

    private static volatile SpiderManualCheckApi impl = (sourceKey, url) -> null;

    private SpiderManualCheckProviders() {
    }

    public static void set(SpiderManualCheckApi api) {
        if (api != null) {
            impl = api;
        }
    }

    public static SpiderManualCheckApi get() {
        return impl;
    }
}

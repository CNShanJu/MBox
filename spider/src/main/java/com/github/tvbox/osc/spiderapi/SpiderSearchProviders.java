package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.AbsXml;

/** 强类型搜索服务持有者(App 组合根注入;默认不可用返回 null,调用方回退字符串通道) */
public final class SpiderSearchProviders {

    private static volatile SpiderSearchApi impl = (sourceKey, word, quick) -> null;

    private SpiderSearchProviders() {
    }

    public static void set(SpiderSearchApi api) {
        if (api != null) {
            impl = api;
        }
    }

    public static SpiderSearchApi get() {
        return impl;
    }
}

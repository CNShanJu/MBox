package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.AbsXml;

/** 强类型详情服务持有者(App 组合根注入;默认不可用返回 null,调用方回退字符串通道) */
public final class SpiderDetailProviders {

    private static volatile SpiderDetailApi impl = (sourceKey, vodId) -> null;

    private SpiderDetailProviders() {
    }

    public static void set(SpiderDetailApi api) {
        if (api != null) {
            impl = api;
        }
    }

    public static SpiderDetailApi get() {
        return impl;
    }
}

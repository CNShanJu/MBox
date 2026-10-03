package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.AbsXml;

/** 强类型搜索服务持有者(App 组合根注入;未注入时调用方可回退字符串通道) */
public final class SpiderSearchProviders {

    private static volatile SpiderSearchApi impl = (sourceKey, word, quick) -> null;
    private static volatile boolean installed;

    private SpiderSearchProviders() {
    }

    public static void set(SpiderSearchApi api) {
        if (api != null) {
            impl = api;
            installed = true;
        }
    }

    /** 区分“尚未注入”与“已请求但结果为空”，避免同一来源重复联网。 */
    public static boolean isInstalled() {
        return installed;
    }

    public static SpiderSearchApi get() {
        return impl;
    }
}

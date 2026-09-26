package com.github.tvbox.osc.spiderapi;

/** 爬虫内容服务持有者(App 组合根注入 :spider 实现;默认全部返回 null 以安全降级) */
public final class SpiderContentProviders {

    private static volatile SpiderContentApi impl = new SpiderContentApi() {
        @Override
        public String homeContent(String sourceKey, boolean filter) {
            return null;
        }

        @Override
        public String homeVideoContent(String sourceKey) {
            return null;
        }

        @Override
        public String categoryContent(String sourceKey, String tid, String pg, boolean filter, java.util.Map<String, String> extend) {
            return null;
        }

        @Override
        public String detailContent(String sourceKey, java.util.List<String> ids) {
            return null;
        }

        @Override
        public String searchContent(String sourceKey, String word, boolean quick) {
            return null;
        }

        @Override
        public String playerContent(String sourceKey, String flag, String id, java.util.List<String> vipFlags) {
            return null;
        }
    };

    private SpiderContentProviders() {
    }

    public static void set(SpiderContentApi api) {
        if (api != null) {
            impl = api;
        }
    }

    public static SpiderContentApi get() {
        return impl;
    }
}

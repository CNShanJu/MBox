package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.google.gson.JsonArray;

import java.util.Collections;
import java.util.List;

/**
 * 直播频道配置服务持有者（App 组合根注入 :spider 实现；默认安全降级：空分组列表）。
 */
public final class LiveChannelConfigProviders {

    private static volatile LiveChannelConfigApi impl = new LiveChannelConfigApi() {
        @Override
        public List<LiveChannelGroup> getChannelGroupList() {
            return Collections.emptyList();
        }

        @Override
        public List<LiveChannelGroup> getFallbackChannelGroupList() {
            return Collections.emptyList();
        }

        @Override
        public List<LiveChannelConfigApi.SubscribeLiveSource> getSubscribeLiveSources() {
            return Collections.emptyList();
        }

        @Override
        public String getLoadedSubscriptionUrl() {
            return "";
        }

        @Override
        public void loadLives(JsonArray livesArray) {
        }
    };

    private LiveChannelConfigProviders() {
    }

    public static void set(LiveChannelConfigApi api) {
        if (api != null) {
            impl = api;
        }
    }

    public static LiveChannelConfigApi get() {
        return impl;
    }
}

package com.github.tvbox.osc.api;

import com.github.tvbox.osc.spiderapi.LiveChannelConfigApi;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** 解析订阅 {@code lives} 中带 type 的直播源，逐条保留名字和地址。 */
final class SubscriptionLiveSourceParser {

    private SubscriptionLiveSourceParser() {
    }

    static List<LiveChannelConfigApi.SubscribeLiveSource> parseTypedSources(JsonArray lives) {
        List<LiveChannelConfigApi.SubscribeLiveSource> sources = new ArrayList<>();
        if (lives == null) return sources;
        for (JsonElement element : lives) {
            if (!element.isJsonObject()) continue;
            try {
                JsonObject item = element.getAsJsonObject();
                if (!item.has("type") || item.get("type").isJsonNull()) continue;
                String name = item.has("name") && !item.get("name").isJsonNull()
                        ? item.get("name").getAsString().trim() : "";
                if (!"0".equals(item.get("type").getAsString())) {
                    sources.add(new LiveChannelConfigApi.SubscribeLiveSource(name, ""));
                    continue;
                }
                if (!item.has("url") || item.get("url").isJsonNull()) continue;
                String url = item.get("url").getAsString().trim();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    sources.add(new LiveChannelConfigApi.SubscribeLiveSource(name, url));
                }
            } catch (RuntimeException ignored) {
                // 一条格式异常不应吞掉同一订阅后面的直播源。
            }
        }
        return sources;
    }
}

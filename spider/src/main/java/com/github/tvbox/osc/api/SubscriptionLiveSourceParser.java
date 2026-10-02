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
                String type = item.get("type").getAsString();
                String url = item.has("url") && !item.get("url").isJsonNull()
                        ? item.get("url").getAsString().trim() : "";
                // type=3 也可以是独立的 TXT/M3U 地址（肥猫订阅即如此），不能把它当成内嵌分组。
                if (("0".equals(type) || "3".equals(type))
                        && (url.startsWith("http://") || url.startsWith("https://"))) {
                    sources.add(new LiveChannelConfigApi.SubscribeLiveSource(name, url));
                } else if (!"0".equals(type)) {
                    sources.add(new LiveChannelConfigApi.SubscribeLiveSource(name, ""));
                }
            } catch (RuntimeException ignored) {
                // 一条格式异常不应吞掉同一订阅后面的直播源。
            }
        }
        return sources;
    }

    /** 内嵌 lives 分组的只读预览；单频道分组与加载配置时一样可单独列出地址。 */
    static List<LiveChannelConfigApi.SubscribeLiveSource> parseEmbeddedSources(JsonArray lives) {
        List<LiveChannelConfigApi.SubscribeLiveSource> sources = new ArrayList<>();
        if (lives == null) return sources;
        for (JsonElement element : lives) {
            if (!element.isJsonObject()) continue;
            try {
                JsonObject group = element.getAsJsonObject();
                String name = group.has("group") && !group.get("group").isJsonNull()
                        ? group.get("group").getAsString().trim() : "";
                JsonArray channels = group.getAsJsonArray("channels");
                String url = "";
                if (channels != null && channels.size() == 1 && channels.get(0).isJsonObject()) {
                    JsonArray urls = channels.get(0).getAsJsonObject().getAsJsonArray("urls");
                    if (urls != null && !urls.isEmpty() && urls.get(0).isJsonPrimitive()) {
                        url = urls.get(0).getAsString().trim().split("\\$", 2)[0];
                    }
                }
                sources.add(new LiveChannelConfigApi.SubscribeLiveSource(name, url));
            } catch (RuntimeException ignored) {
                // 坏分组不影响后续分组的预览。
            }
        }
        return sources;
    }
}

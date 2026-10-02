package com.github.tvbox.osc.server;

import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.util.Utils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 与「我的 → 本地视频」共用 MediaStore 清单和文件夹分组口径。 */
final class LanVideoLibrary {
    private LanVideoLibrary() { }

    static JsonObject catalog() {
        List<VideoInfo> videos = Utils.getVideoList();
        Map<String, JsonArray> groups = new LinkedHashMap<>();
        for (VideoInfo video : videos) {
            if (video == null || video.getId() <= 0) continue;
            String folder = video.getBucketDisplayName();
            JsonArray items = groups.computeIfAbsent(folder, key -> new JsonArray());
            JsonObject item = new JsonObject();
            item.addProperty("id", video.getId());
            String name = video.getDisplayName();
            if (name == null || name.trim().isEmpty()) name = video.getTitle();
            item.addProperty("name", name == null || name.trim().isEmpty()
                    ? "视频 " + video.getId() : name);
            item.addProperty("size", video.getSize());
            item.addProperty("duration", video.getDuration());
            items.add(item);
        }
        JsonArray folders = new JsonArray();
        for (Map.Entry<String, JsonArray> entry : groups.entrySet()) {
            JsonObject folder = new JsonObject();
            folder.addProperty("name", entry.getKey() == null || entry.getKey().trim().isEmpty()
                    ? "未分类" : entry.getKey());
            folder.addProperty("count", entry.getValue().size());
            folder.add("videos", entry.getValue());
            folders.add(folder);
        }
        JsonObject result = new JsonObject();
        result.add("folders", folders);
        int total = 0;
        for (JsonArray items : groups.values()) total += items.size();
        result.addProperty("count", total);
        return result;
    }
}

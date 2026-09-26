package com.github.tvbox.osc.util.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.StringReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 直播源解析:兼容 txt(频道名,url[#url2...]/分组#genre#)与 M3U(#EXTINF 频道名 + 独立 URL 行)两种格式。
 * 所有以 # 开头的 M3U 指令行(如 #EXTM3U x-tvg-url=...#EXTINF、#EXTVLCOPT 等)都会被跳过,
 * 避免把文件头解析成频道。
 */
public class TxtSubscribe {
    public static void parse(LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> map, String str) {
        try {
            BufferedReader reader = new BufferedReader(new StringReader(str));
            LinkedHashMap<String, ArrayList<String>> ungrouped = new LinkedHashMap<>();
            LinkedHashMap<String, ArrayList<String>> currentGroup = ungrouped;
            String pendingName = null; // M3U #EXTINF 之后待使用的频道名
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty()) continue;

                // txt 分组行: 分组名,#genre#
                if (t.contains("#genre#")) {
                    String g = t.split(",")[0].trim();
                    if (!map.containsKey(g)) {
                        currentGroup = new LinkedHashMap<>();
                        map.put(g, currentGroup);
                    } else {
                        currentGroup = map.get(g);
                    }
                    pendingName = null;
                    continue;
                }

                // M3U 指令行:跳过,但 #EXTINF 提取频道名
                if (t.startsWith("#EXTINF")) {
                    int idx = t.indexOf(',');
                    pendingName = idx >= 0 ? t.substring(idx + 1).trim() : null;
                    continue;
                }
                if (t.startsWith("#")) {
                    pendingName = null;
                    continue;
                }

                // 尝试 txt 格式: 频道名,url[#url2...]
                int comma = t.indexOf(',');
                if (comma > 0) {
                    String name = t.substring(0, comma).trim();
                    String urlPart = t.substring(comma + 1).trim();
                    if (!name.isEmpty() && !urlPart.isEmpty()) {
                        boolean added = false;
                        for (String s : urlPart.split("#")) {
                            String url = s.trim();
                            if (isLiveUrl(url)) {
                                addChannel(currentGroup, name, url);
                                added = true;
                            }
                        }
                        if (added) pendingName = null;
                        continue;
                    }
                }

                // M3U 格式: 独立 URL 行,使用 #EXTINF 提供的频道名
                if (isLiveUrl(t)) {
                    addChannel(currentGroup, pendingName != null ? pendingName : "未命名", t);
                    pendingName = null;
                }
            }
            reader.close();
            if (!ungrouped.isEmpty()) map.put("未分组", ungrouped);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isLiveUrl(String u) {
        return u.startsWith("http") || u.startsWith("rtp://") || u.startsWith("rtsp://")
                || u.startsWith("rtmp://") || u.startsWith("udp://");
    }

    private static void addChannel(LinkedHashMap<String, ArrayList<String>> group, String name, String url) {
        ArrayList<String> urls = group.get(name);
        if (urls == null) {
            urls = new ArrayList<>();
            group.put(name, urls);
        }
        if (!urls.contains(url)) urls.add(url);
    }

    public static JsonArray live2JsonArray(LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> linkedHashMap) {
        JsonArray jsonarr = new JsonArray();
        for (String str : linkedHashMap.keySet()) {
            JsonArray jsonarr2 = new JsonArray();
            LinkedHashMap<String, ArrayList<String>> linkedHashMap2 = linkedHashMap.get(str);
            if (!linkedHashMap2.isEmpty()) {
                for (String str2 : linkedHashMap2.keySet()) {
                    ArrayList<String> arrayList = linkedHashMap2.get(str2);
                    if (!arrayList.isEmpty()) {
                        JsonArray jsonarr3 = new JsonArray();
                        for (int i = 0; i < arrayList.size(); i++) {
                            jsonarr3.add(arrayList.get(i));
                        }
                        JsonObject jsonobj = new JsonObject();
                        try {
                            jsonobj.addProperty("name", str2);
                            jsonobj.add("urls", jsonarr3);
                        } catch (Throwable e) {
                        }
                        jsonarr2.add(jsonobj);
                    }
                }
                JsonObject jsonobj2 = new JsonObject();
                try {
                    jsonobj2.addProperty("group", str);
                    jsonobj2.add("channels", jsonarr2);
                } catch (Throwable e) {
                }
                jsonarr.add(jsonobj2);
            }
        }
        return jsonarr;
    }
}

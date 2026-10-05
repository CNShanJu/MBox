package com.github.tvbox.osc.transfer;

import android.content.Context;
import android.os.Environment;

import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.bean.theme.ThemeDef;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.repo.HistoryRepositories;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.player.api.PlayConfig;
import com.github.tvbox.osc.storage.theme.ThemeArchive;
import com.github.tvbox.osc.storage.theme.ThemeStore;
import com.github.tvbox.osc.util.LiveConfig;
import com.github.tvbox.osc.util.SubscriptionConfig;
import com.github.tvbox.osc.util.SubscriptionExporter;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 配置数据格式与本机合并规则；LAN、在线配置和缓存分享共用，传输方式由调用方负责。 */
public final class ConfigDataExchange {
    private static final Gson GSON = new Gson();
    /** 传输历史沿用原有轻量格式；本机起播快照不随备份导出。 */
    private static final Gson HISTORY_EXPORT_GSON = new GsonBuilder()
            .addSerializationExclusionStrategy(new ExclusionStrategy() {
                @Override public boolean shouldSkipField(FieldAttributes field) {
                    return field.getDeclaringClass() == VodInfo.class
                            && ("seriesMap".equals(field.getName())
                            || "seriesFlags".equals(field.getName()));
                }
                @Override public boolean shouldSkipClass(Class<?> clazz) { return false; }
            }).create();
    private static final int MAX_HISTORY = 1000;

    private static final Type SETTINGS_TYPE = new TypeToken<LinkedHashMap<String, Object>>() { }.getType();
    private final Context context;

    public ConfigDataExchange(Context context) {
        this.context = context.getApplicationContext();
    }

    public static JsonObject catalog() {
        JsonObject result = new JsonObject();
        result.addProperty("schema", 1);
        result.addProperty("archiveSchema", 1);
        JsonArray categories = new JsonArray();
        categories.add(category("subscriptions", "订阅源", SubscriptionConfig.getSubscriptions().size()));
        int liveCount = LiveConfig.liveHistory().size();
        if (!SystemConfig.getLiveUrl().isEmpty() && !LiveConfig.liveHistory().contains(SystemConfig.getLiveUrl())) liveCount++;
        categories.add(category("live", "直播源", liveCount));
        List<ThemeDef> themes = ThemeStore.userThemes();
        categories.add(category("themes", "自定义主题", themes.size()));
        categories.add(category("settings", "我的设置", settings().size()));
        categories.add(category("history", "历史记录", RoomDataManger.getAllVodRecord(MAX_HISTORY).size()
                + SubscriptionConfig.getSearchHistory().size()
                + SubscriptionConfig.getSearchFavorites().size()));
        result.add("categories", categories);
        JsonArray themeList = new JsonArray();
        for (ThemeDef theme : themes) {
            JsonObject item = new JsonObject();
            item.addProperty("id", theme.getId());
            item.addProperty("name", theme.getName());
            item.addProperty("withImage", theme.hasBackgroundImage());
            themeList.add(item);
        }
        result.add("themes", themeList);
        return result;
    }

    private static JsonObject category(String id, String label, int count) {
        JsonObject item = new JsonObject();
        item.addProperty("id", id);
        item.addProperty("label", label);
        item.addProperty("count", count);
        return item;
    }

    public static JsonObject exportCategory(String category) {
        if (category == null) return null;
        JsonObject result = new JsonObject();
        result.addProperty("schema", 1);
        result.addProperty("category", category);
        result.addProperty("createdAt", System.currentTimeMillis());
        switch (category) {
            case "subscriptions": {
                JsonArray items = new JsonArray();
                for (Subscription sub : SubscriptionConfig.getSubscriptions()) {
                    if (sub != null) items.add(SubscriptionExporter.describeForTransfer(sub));
                }
                result.add("items", items);
                result.addProperty("activeUrl", SubscriptionConfig.getApiUrl());
                break;
            }
            case "live": {
                result.add("history", GSON.toJsonTree(LiveConfig.liveHistory()));
                result.addProperty("activeUrl", SystemConfig.getLiveUrl());
                break;
            }
            case "settings": {
                result.add("items", GSON.toJsonTree(settings()));
                break;
            }
            case "history": {
                List<VodInfo> history = RoomDataManger.getAllVodRecord(MAX_HISTORY);
                result.add("videos", HISTORY_EXPORT_GSON.toJsonTree(history));
                result.add("searches", GSON.toJsonTree(SubscriptionConfig.getSearchHistory()));
                result.add("searchFavorites", GSON.toJsonTree(SubscriptionConfig.getSearchFavorites()));
                break;
            }
            case "themes": {
                result.add("themes", catalog().get("themes"));
                break;
            }
            default: return null;
        }
        return result;
    }

    private static Map<String, Object> settings() {
        Map<String, Object> values = SystemConfig.exportConfig();
        values.remove("theme_tag");
        values.remove("theme_custom_id");
        values.remove("theme_default_bright");
        values.remove("theme_default_dark");
        values.remove("live_url");
        values.putAll(PlayConfig.exportConfig());
        return values;
    }

    public static File exportTheme(Context context, String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("\\") || id.contains("..")) return null;
        ThemeDef target = null;
        for (ThemeDef theme : ThemeStore.userThemes()) {
            if (id.equals(theme.getId())) { target = theme; break; }
        }
        if (target == null) return null;
        File dir = new File(context.getCacheDir(), "config_theme_exports");
        ThemeArchive.ExportResult result = ThemeArchive.exportTo(target, dir);
        return result.ok() ? result.file : null;
    }

    /** 对所有传输来源使用同一套格式校验与合并规则。调用方须在共享后台执行器运行。 */
    public int importCategory(String category, JsonObject data) throws Exception {
        if (category == null || data == null || !data.has("schema") || data.get("schema").getAsInt() != 1
                || !category.equals(string(data, "category"))) throw new IOException("数据类别不匹配");
        switch (category) {
            case "subscriptions": return importSubscriptions(data);
            case "live": return importLive(data);
            case "settings": return importSettings(data);
            case "history": return importHistory(data);
            default: throw new IOException("不支持的数据类别");
        }
    }

    /** 导入一个已取得的主题归档，来源可以是 LAN、在线链接或本地缓存。 */
    public boolean shouldImportTheme(String name) {
        return name != null && !name.isEmpty() && !ThemeStore.isNameTaken(name, "");
    }

    public int importTheme(File archive) throws Exception {
        if (archive == null || !archive.isFile() || archive.length() > 64L * 1024L * 1024L)
            throw new IOException("主题包无效或过大");
        ThemeArchive.ImportResult imported = ThemeArchive.importFrom(archive);
        if (!imported.ok()) throw new IOException(imported.error);
        if (!imported.warnings.isEmpty())
            throw new IOException("主题配置未能完整导入：" + android.text.TextUtils.join("、", imported.warnings));
        ThemeDef def = imported.def;
        if (ThemeStore.isNameTaken(def.getName(), "")) {
            ThemeStore.gc();
            return 0;
        }
        def.setId("");
        def.setCreatedAt(0L);
        ThemeStore.SaveResult saved = ThemeStore.save(def);
        if (!saved.ok()) throw new IOException(saved.error);
        return 1;
    }

    private int importSubscriptions(JsonObject data) throws Exception {
        JsonArray items = array(data, "items");
        List<Subscription> merged = new ArrayList<>(SubscriptionConfig.getSubscriptions());
        Set<String> urls = new HashSet<>();
        for (Subscription sub : merged) if (sub != null) urls.add(sub.getUrl());
        int added = 0;
        for (JsonElement element : items) {
            if (added >= 500) break;
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String name = string(item, "name").trim();
            if (name.isEmpty()) continue;
            String url = string(item, "url").trim();
            String content = string(item, "content");
            if (content.isEmpty() && url.startsWith("clan://")) continue;
            if (!content.isEmpty()) url = storeLocalSubscription(content, string(item, "file"));
            if (url.isEmpty() || !validSubscriptionUrl(url) || !urls.add(url)) continue;
            String origin = string(item, "origin");
            merged.add(new Subscription(name, url, origin.isEmpty() ? Subscription.ORIGIN_DIRECT : origin));
            added++;
        }
        if (added > 0) {
            SubscriptionConfig.setSubscriptions(merged);
            if (SubscriptionConfig.getApiUrl().isEmpty()) {
                String remoteActive = string(data, "activeUrl");
                for (Subscription sub : merged) if (remoteActive.equals(sub.getUrl())) {
                    SubscriptionConfig.setApiUrl(remoteActive); break;
                }
            }
        }
        return added;
    }

    private static boolean validSubscriptionUrl(String url) {
        return url.startsWith("https://") || url.startsWith("http://") || url.startsWith("clan://localhost/");
    }

    private String storeLocalSubscription(String content, String suggested) throws Exception {
        return SubscriptionImportFiles.runLocked(() -> storeLocalSubscriptionLocked(content, suggested));
    }

    private String storeLocalSubscriptionLocked(String content, String suggested) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 8 * 1024 * 1024) return "";
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("应用存储目录不可用");
        File dir = new File(external, "subscription_import");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建订阅目录");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 8; i++) key.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 0xff));
        String ext = suggested.toLowerCase(java.util.Locale.ROOT).endsWith(".txt") ? ".txt" : ".json";
        File file = new File(dir, "config_" + key + ext);
        File root = Environment.getExternalStorageDirectory().getCanonicalFile();
        File canonical = file.getCanonicalFile();
        if (!canonical.getPath().startsWith(root.getPath() + File.separator)) throw new IOException("订阅目录不在主存储内");
        android.util.AtomicFile atomic = new android.util.AtomicFile(canonical);
        FileOutputStream out = atomic.startWrite();
        try {
            out.write(bytes);
            atomic.finishWrite(out);
        } catch (Exception failure) {
            atomic.failWrite(out);
            throw failure;
        }
        return "clan://localhost/" + canonical.getPath().substring(root.getPath().length() + 1).replace(File.separatorChar, '/');
    }

    private int importLive(JsonObject data) {
        ArrayList<String> merged = LiveConfig.liveHistory();
        int added = 0;
        for (JsonElement element : array(data, "history")) {
            if (!element.isJsonPrimitive() || merged.size() >= 100) break;
            String url = element.getAsString();
            if (validLiveUrl(url) && !merged.contains(url)) {
                merged.add(url); added++;
            }
        }
        String active = string(data, "activeUrl");
        if (validLiveUrl(active) && !merged.contains(active)) {
            merged.add(0, active); added++;
        }
        LiveConfig.setLiveHistory(merged);
        if (SystemConfig.getLiveUrl().isEmpty() && validLiveUrl(active)) SystemConfig.setLiveUrl(active);
        return added;
    }

    private static boolean validLiveUrl(String url) {
        return (url.startsWith("http://") || url.startsWith("https://"))
                && okhttp3.HttpUrl.parse(url) != null;
    }


    private int importSettings(JsonObject data) {
        JsonObject items = data.has("items") && data.get("items").isJsonObject() ? data.getAsJsonObject("items") : new JsonObject();
        Map<String, Object> values = GSON.fromJson(items, SETTINGS_TYPE);
        if (values == null) return 0;
        // 外部数据只允许覆盖用户可见设置；局域网开关、主题选择和直播当前源由本机决定。
        Set<String> allowed = new HashSet<>(java.util.Arrays.asList("doh_url", "loading_anim", "home_rec",
                "history_num", "private_browsing", "auto_check_update"));
        Map<String, Object> system = new LinkedHashMap<>(values);
        system.keySet().retainAll(allowed);
        SystemConfig.importConfig(system);
        Set<String> playerKeys = new HashSet<>(java.util.Arrays.asList("play_type", "play_render", "play_scale",
                "play_time_step", "ijk_codec", "ijk_cache_play", "background_play_type", "video_purify",
                "video_purify_mode",
                "video_speed", "subtitle_open", "subtitle_text_size", "subtitle_time_delay"));
        Map<String, Object> player = new LinkedHashMap<>(values);
        player.keySet().retainAll(playerKeys);
        for (String key : new String[]{"play_type", "play_render", "play_scale", "play_time_step",
                "background_play_type", "subtitle_text_size", "subtitle_time_delay"}) {
            Object value = player.get(key);
            if (value instanceof Number) player.put(key, ((Number) value).intValue());
        }
        PlayConfig.importConfig(player);
        return system.size() + player.size();
    }

    private int importHistory(JsonObject data) {
        int added = 0;
        for (JsonElement element : array(data, "videos")) {
            if (added >= 1000) break;
            if (!element.isJsonObject()) continue;
            VodInfo video = GSON.fromJson(element, VodInfo.class);
            if (video == null || video.id == null || video.id.isEmpty()
                    || video.sourceKey == null || video.sourceKey.isEmpty()) continue;
            // 同一影片以本机观看进度为准，重复导入不能把它回退到旧备份位置。
            if (HistoryRepositories.history().get(video.sourceKey, video.id) != null) continue;
            // 解析或落盘失败必须交给外层事务回滚，不能吞掉错误后报告导入成功。
            HistoryRepositories.history().save(video.sourceKey, video);
            added++;
        }
        List<String> searches = new ArrayList<>(SubscriptionConfig.getSearchHistory());
        for (JsonElement element : array(data, "searches")) {
            if (!element.isJsonPrimitive() || searches.size() >= 100) break;
            String word = element.getAsString().trim();
            if (!word.isEmpty() && !searches.contains(word)) {
                searches.add(word);
                added++;
            }
        }
        SubscriptionConfig.setSearchHistory(searches);
        List<String> favorites = new ArrayList<>(SubscriptionConfig.getSearchFavorites());
        Set<String> favoriteSet = new HashSet<>(favorites);
        for (JsonElement element : array(data, "searchFavorites")) {
            if (!element.isJsonPrimitive()) continue;
            String word = element.getAsString().trim();
            if (!word.isEmpty() && favoriteSet.add(word)) {
                favorites.add(word);
                added++;
            }
        }
        SubscriptionConfig.setSearchFavorites(favorites);
        return added;
    }

    private static JsonArray array(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : new JsonArray();
    }
    private static String string(JsonObject object, String name) {
        try { return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : ""; }
        catch (Throwable ignored) { return ""; }
    }

}

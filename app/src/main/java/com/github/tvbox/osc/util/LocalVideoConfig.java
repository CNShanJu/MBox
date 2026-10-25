package com.github.tvbox.osc.util;

import com.github.tvbox.osc.config.PrefsDataStore;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 本地视频进度/播放配置的类型安全 Config 门面(AGENTS §五:UI 不得裸读写具体配置单例)。
 * <p>
 * 原实现用第三方 SPUtils 裸读写(com.blankj.utilcode.util.SPUtils + CacheConst 的 SP 文件名),
 * 现收口为 PrefsDataStore;键加 {@code _private_} 前缀(本地视频以绝对路径为键、设备相关,
 * 不随共享/备份导出)。
 */
public final class LocalVideoConfig {

    /** 本地视频数据是设备相关的(path 为键),用 _private_ 前缀从共享/备份中排除 */
    private static final String PREFIX = "_private_local_video_";

    private static final Type STRING_LIST_TYPE = new TypeToken<ArrayList<String>>() {
    }.getType();

    private LocalVideoConfig() {
    }

    private static String durationKey(String path) {
        return PREFIX + "duration:" + path;
    }

    private static String progressKey(String path) {
        return PREFIX + "progress:" + path;
    }

    private static String cfgKey(String path) {
        return PREFIX + "cfg:" + path;
    }

    private static String playedKey(String id) {
        return PREFIX + "played:" + id;
    }

    // ── 时长 ──

    public static void saveDuration(String path, long duration) {
        if (path == null || path.isEmpty()) return;
        PrefsDataStore.put(durationKey(path), duration);
    }

    public static long getDuration(String path, long defValue) {
        if (path == null || path.isEmpty()) return defValue;
        return PrefsDataStore.getLong(durationKey(path), defValue);
    }

    public static void removeDuration(String path) {
        if (path == null || path.isEmpty()) return;
        PrefsDataStore.delete(durationKey(path));
    }

    // ── 播放进度 ──

    public static void saveProgress(String path, long progress) {
        if (path == null || path.isEmpty()) return;
        PrefsDataStore.put(progressKey(path), progress);
    }

    public static long getProgress(String path, long defValue) {
        if (path == null || path.isEmpty()) return defValue;
        return PrefsDataStore.getLong(progressKey(path), defValue);
    }

    public static void removeProgress(String path) {
        if (path == null || path.isEmpty()) return;
        PrefsDataStore.delete(progressKey(path));
    }

    // ── 每文件播放配置(JSON 文本) ──

    public static String getPlayerCfg(String path) {
        if (path == null || path.isEmpty()) return null;
        return PrefsDataStore.getString(cfgKey(path), null);
    }

    public static void savePlayerCfg(String path, String json) {
        if (path == null || path.isEmpty() || json == null) return;
        PrefsDataStore.put(cfgKey(path), json);
    }

    // ── 已播放剧集(PlayedVodKey → 集索引集合) ──

    public static Set<String> getPlayed(String id) {
        if (id == null || id.isEmpty()) return null;
        ArrayList<String> list = PrefsDataStore.getJson(playedKey(id), STRING_LIST_TYPE, null);
        return list == null ? null : new LinkedHashSet<>(list);
    }

    public static void savePlayed(String id, Set<String> indexes) {
        if (id == null || id.isEmpty() || indexes == null) return;
        PrefsDataStore.putJson(playedKey(id), new ArrayList<>(indexes));
    }
}

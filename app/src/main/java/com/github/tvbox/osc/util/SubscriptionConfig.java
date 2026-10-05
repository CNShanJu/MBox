package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.config.HawkConfig;
import com.github.tvbox.osc.config.PrefsDataStore;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 订阅/搜索域配置门面(UI 摘 Hawk;DataStore 化:标量直存,对象/容器经 gson typed JSON)。
 * <p>
 * 键沿用 {@link HawkConfig} 旧应用 key(历史设置兼容);旧 Hawk 存量类加载时一次性导入并删旧键。
 * 订阅**加载/落库**(ApiConfig)仍在 :spider 侧处理,本门面只向 UI 提供类型安全的读写。
 */
public final class SubscriptionConfig {

    private static final Type SUBSCRIPTION_LIST_TYPE = new TypeToken<List<Subscription>>() {
    }.getType();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {
    }.getType();
    private static final Type SOURCES_MAP_TYPE =
            new TypeToken<HashMap<String, HashMap<String, String>>>() {
            }.getType();

    private static final String KEY_IMPORT_DIR = "before_selected_path";
    private static final String KEY_SEARCH_FAVORITES = "search_favorites";

    /**
     * 本地默认订阅文件**实际注入过**的订阅集(启动同步删除时的唯一依据)。
     * <p>与 {@link HawkConfig#DEFAULT_SUBS}(文件内容快照)语义不同:快照只能说明"文件里有什么",
     * 不能说明"列表里哪些条目是我们加的"。早期版本曾用快照当删除依据,升级后首次启动会把
     * 与该快照同名的**用户自建订阅**一并当成注入项删除(实证:整表清空 + api_url 置空)。
     */
    private static final String KEY_INJECTED = "default_subs_injected";

    private SubscriptionConfig() {
    }


    // ── 订阅地址(当前启用接口地址)──

    /** 当前启用订阅接口地址(未设置返回空串) */
    public static String getApiUrl() {
        return PrefsDataStore.getString(HawkConfig.API_URL, "");
    }

    public static void setApiUrl(String url) {
        PrefsDataStore.put(HawkConfig.API_URL, url == null ? "" : url);
    }

    // ── 订阅列表 ──

    /** 订阅列表(空表兜底) */
    public static List<Subscription> getSubscriptions() {
        return PrefsDataStore.getJson(HawkConfig.SUBSCRIPTIONS, SUBSCRIPTION_LIST_TYPE, new ArrayList<Subscription>());
    }

    public static void setSubscriptions(List<Subscription> subs) {
        PrefsDataStore.putJson(HawkConfig.SUBSCRIPTIONS, subs == null ? new ArrayList<>() : subs);
    }

    // ── 本地默认订阅(打包 assets 注入;App 启动与文件同步)──

    /** 上次记录的本地默认订阅集(未记录返回空表) */
    public static List<Subscription> getDefaultSubs() {
        return PrefsDataStore.getJson(HawkConfig.DEFAULT_SUBS, SUBSCRIPTION_LIST_TYPE, new ArrayList<Subscription>());
    }

    public static void setDefaultSubs(List<Subscription> subs) {
        PrefsDataStore.putJson(HawkConfig.DEFAULT_SUBS, subs == null ? new ArrayList<>() : subs);
    }

    /** 是否记录过本地默认订阅集(迁移兼容判定用) */
    public static boolean containsDefaultSubs() {
        return PrefsDataStore.contains(HawkConfig.DEFAULT_SUBS);
    }

    // ── 默认订阅**实际注入**记录(与文件内容快照分离;缺失=从未注入过)──

    /** 曾经由默认订阅文件注入(且尚未被用户删除)的条目标签;空标签列表返回 null 表示"无注入记录" */
    public static List<String> getInjectedTags() {
        if (!PrefsDataStore.contains(KEY_INJECTED)) return null;
        return PrefsDataStore.getJson(KEY_INJECTED, STRING_LIST_TYPE, new ArrayList<String>());
    }

    /** 记录默认订阅注入标签;传 null 表示清空记录(仍算"已记录过",与从未记录区分) */
    public static void setInjectedTags(List<String> tags) {
        PrefsDataStore.putJson(KEY_INJECTED, tags == null ? new ArrayList<String>() : tags);
    }

    /** 某条目是否由默认订阅注入过(标签=名称+地址,与列表条目一一对应) */
    public static boolean isTagInjected(List<String> tags, Subscription sub) {
        if (tags == null || sub == null) return false;
        String tag = injectedTag(sub);
        for (String t : tags) {
            if (TextUtils.equals(t, tag)) return true;
        }
        return false;
    }

    /** 注入标签:名称与地址都以长度前缀编码,避免"名称|地址"拼接歧义 */
    public static String injectedTag(Subscription sub) {
        if (sub == null) return "";
        return len(sub.getName()) + ":" + len(sub.getUrl());
    }

    private static String len(String s) {
        String v = s == null ? "" : s;
        return v.length() + "|" + v;
    }

    // ── 搜索历史(快速搜索页记录)──

    /** 搜索历史词(空表兜底) */
    public static List<String> getSearchHistory() {
        return PrefsDataStore.getJson(HawkConfig.HISTORY_SEARCH, STRING_LIST_TYPE, new ArrayList<String>());
    }

    public static void setSearchHistory(List<String> history) {
        PrefsDataStore.putJson(HawkConfig.HISTORY_SEARCH, history == null ? new ArrayList<>() : history);
    }

    public static void clearSearchHistory() {
        PrefsDataStore.putJson(HawkConfig.HISTORY_SEARCH, new ArrayList<String>());
    }

    // ── 常用搜索收藏(独立于搜索历史,清空历史不影响收藏)──

    /** 常用收藏词;过滤空词与重复词,保留首次收藏顺序 */
    public static List<String> getSearchFavorites() {
        List<String> stored = PrefsDataStore.getJson(KEY_SEARCH_FAVORITES, STRING_LIST_TYPE,
                new ArrayList<String>());
        return normalizeSearchFavorites(stored);
    }

    /** 批量替换常用收藏(用于备份导入),自动去重并忽略空词 */
    public static void setSearchFavorites(List<String> favorites) {
        PrefsDataStore.putJson(KEY_SEARCH_FAVORITES, normalizeSearchFavorites(favorites));
    }

    /** 收藏搜索词;已存在或词为空时返回 false */
    public static boolean addSearchFavorite(String word) {
        if (word == null || word.trim().isEmpty()) return false;
        String normalized = word.trim();
        List<String> favorites = getSearchFavorites();
        if (favorites.contains(normalized)) return false;
        favorites.add(normalized);
        PrefsDataStore.putJson(KEY_SEARCH_FAVORITES, favorites);
        return true;
    }

    /** 删除单条常用收藏;不存在或词为空时返回 false */
    public static boolean removeSearchFavorite(String word) {
        if (word == null || word.trim().isEmpty()) return false;
        List<String> favorites = getSearchFavorites();
        if (!favorites.remove(word.trim())) return false;
        PrefsDataStore.putJson(KEY_SEARCH_FAVORITES, favorites);
        return true;
    }

    private static List<String> normalizeSearchFavorites(List<String> favorites) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (favorites != null) {
            for (String word : favorites) {
                if (word == null) continue;
                String normalized = word.trim();
                if (!normalized.isEmpty()) unique.add(normalized);
            }
        }
        return new ArrayList<>(unique);
    }

    // ── 搜索源勾选记忆(按订阅接口地址分组)──

    /** 全部按源勾选记忆(key=接口地址;搜索页/搜索助手按 api 取用) */
    public static HashMap<String, HashMap<String, String>> getCheckedSources() {
        return PrefsDataStore.getJson(HawkConfig.SOURCES_FOR_SEARCH, SOURCES_MAP_TYPE,
                new HashMap<String, HashMap<String, String>>());
    }

    public static void setCheckedSources(HashMap<String, HashMap<String, String>> sources) {
        PrefsDataStore.putJson(HawkConfig.SOURCES_FOR_SEARCH,
                sources == null ? new HashMap<>() : sources);
    }

    // ── 订阅管理页导入目录记忆(文件选择器起始目录)──

    /** 上次导入选择的目录(未记忆返回空串,调用方用默认下载目录兜底) */
    public static String getLastImportDir() {
        return PrefsDataStore.getString(KEY_IMPORT_DIR, "");
    }

    public static void setLastImportDir(String dir) {
        PrefsDataStore.put(KEY_IMPORT_DIR, dir == null ? "" : dir);
    }
}

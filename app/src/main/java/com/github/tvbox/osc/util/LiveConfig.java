package com.github.tvbox.osc.util;

import com.github.tvbox.osc.config.HawkConfig;
import com.github.tvbox.osc.config.PrefsDataStore;
import com.google.gson.reflect.TypeToken;

import org.json.JSONObject;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * 直播偏好类型安全配置门面（改进.txt §五"配置：类型安全 Config"；DataStore 化）。
 * <p>
 * 收敛 LiveActivity/LiveSettingDialog/LiveSettingRightDialog/历史源弹窗对
 * {@link HawkConfig} 直播键的裸 Hawk 读写：键名沿用旧 key（兼容历史数据），
 * 读写封装为强类型方法并统一默认值。
 * <p>
 * 频道播放配置按"频道名"键存 JSON 文本；旧 Hawk 存量标量键类加载一次性迁移,
 * 频道键按访问惰性迁移(读取时迁一次并删旧键)。
 */
public final class LiveConfig {

    public static final String DEFAULT_LIVE_URL = "https://gh-proxy.com/raw.githubusercontent.com/vbskycn/iptv/refs/heads/master/tv/iptv4.txt";

    private static final Type STRING_LIST_TYPE = new TypeToken<ArrayList<String>>() {
    }.getType();

    private static final Type STRING_MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() {
    }.getType();

    /** 名称映射的条数上界(历史源本身最多 20 条,留出改名/残留余量) */
    private static final int MAX_LIVE_NAMES = 40;

    private LiveConfig() {
    }


    // ── 超时换源（0-5 档，对应 5s~30s；默认 1 = 10s）──

    /** 超时换源档位索引(0-5)，默认 1 */
    public static int connectTimeout() {
        return PrefsDataStore.getInt(HawkConfig.LIVE_CONNECT_TIMEOUT, 1);
    }

    public static void setConnectTimeout(int index) {
        int v = Math.max(0, Math.min(5, index));
        if (v == connectTimeout()) return;
        PrefsDataStore.put(HawkConfig.LIVE_CONNECT_TIMEOUT, v);
    }

    // ── 显示偏好 ──

    /** 显示时间(默认关) */
    public static boolean showTime() {
        return PrefsDataStore.getBoolean(HawkConfig.LIVE_SHOW_TIME, false);
    }

    public static void setShowTime(boolean on) {
        if (showTime() == on) return;
        PrefsDataStore.put(HawkConfig.LIVE_SHOW_TIME, on);
    }

    /** 显示网速(默认关) */
    public static boolean showNetSpeed() {
        return PrefsDataStore.getBoolean(HawkConfig.LIVE_SHOW_NET_SPEED, false);
    }

    public static void setShowNetSpeed(boolean on) {
        if (showNetSpeed() == on) return;
        PrefsDataStore.put(HawkConfig.LIVE_SHOW_NET_SPEED, on);
    }

    /** 换台方向反转(默认关:上=上一台) */
    public static boolean channelReverse() {
        return PrefsDataStore.getBoolean(HawkConfig.LIVE_CHANNEL_REVERSE, false);
    }

    public static void setChannelReverse(boolean on) {
        if (channelReverse() == on) return;
        PrefsDataStore.put(HawkConfig.LIVE_CHANNEL_REVERSE, on);
    }

    /** 上下键换台跨分组(默认关) */
    public static boolean crossGroup() {
        return PrefsDataStore.getBoolean(HawkConfig.LIVE_CROSS_GROUP, false);
    }

    public static void setCrossGroup(boolean on) {
        if (crossGroup() == on) return;
        PrefsDataStore.put(HawkConfig.LIVE_CROSS_GROUP, on);
    }

    // ── 频道记忆 / 历史源 ──

    /** 最后播放频道名(默认空串) */
    public static String lastChannel() {
        return PrefsDataStore.getString(HawkConfig.LIVE_CHANNEL, "");
    }

    public static void setLastChannel(String name) {
        if (name == null) return;
        PrefsDataStore.put(HawkConfig.LIVE_CHANNEL, name);
    }

    /** 历史直播源列表(默认空;最多 20 条在调用方维护) */
    public static ArrayList<String> liveHistory() {
        ArrayList<String> list = PrefsDataStore.getJson(HawkConfig.LIVE_HISTORY, STRING_LIST_TYPE, new ArrayList<String>());
        return list == null ? new ArrayList<String>() : list;
    }

    /** 整体写回历史源列表 */
    public static void setLiveHistory(ArrayList<String> history) {
        PrefsDataStore.putJson(HawkConfig.LIVE_HISTORY, history == null ? new ArrayList<String>() : history);
    }

    // ── 自建直播源的名称 ──

    /**
     * 用户给自建直播源起的名字(地址 → 名称)。
     * <p>与 {@link #liveHistory()} 分开存:历史列表是一串裸地址(旧数据、以及播放器/设置页都按地址用),
     * 名称只是展示与编辑用的附加信息,不改变既有读取方的语义。
     */
    public static LinkedHashMap<String, String> liveNames() {
        LinkedHashMap<String, String> map = PrefsDataStore.getJson(
                HawkConfig.LIVE_NAMES, STRING_MAP_TYPE, new LinkedHashMap<String, String>());
        return map == null ? new LinkedHashMap<String, String>() : map;
    }

    public static void setLiveNames(LinkedHashMap<String, String> names) {
        PrefsDataStore.putJson(HawkConfig.LIVE_NAMES,
                names == null ? new LinkedHashMap<String, String>() : names);
    }

    /** 某地址的用户命名(没起过返回空串) */
    public static String liveName(String url) {
        if (url == null || url.isEmpty()) return "";
        String name = liveNames().get(url);
        return name == null ? "" : name;
    }

    /** 起名/改名;名字为空即清除(回到按地址推导展示名)。地址为空不记。 */
    public static void setLiveName(String url, String name) {
        if (url == null || url.isEmpty()) return;
        LinkedHashMap<String, String> names = liveNames();
        if (name == null || name.trim().isEmpty()) {
            names.remove(url);
        } else {
            names.put(url, name.trim());
            // 名称只在用户主动起名时写入,但仍给它一个上界:删源没清干净时不会无界增长
            while (names.size() > MAX_LIVE_NAMES) {
                Iterator<String> it = names.keySet().iterator();
                if (!it.hasNext()) break;
                it.next();
                it.remove();
            }
        }
        setLiveNames(names);
    }

    /** 删除直播源时一并清掉它的名字 */
    public static void removeLiveName(String url) {
        if (url == null || url.isEmpty()) return;
        LinkedHashMap<String, String> names = liveNames();
        if (names.remove(url) != null) setLiveNames(names);
    }

    // ── EPG / 频道播放配置 ──

    /** EPG 地址(由 :spider ApiConfig 加载订阅时写入,此处只读;未配置返回空串,调用方给默认值) */
    public static String epgUrl() {
        return PrefsDataStore.getString(HawkConfig.EPG_URL, "");
    }

    /** 某频道的播放配置覆写(未覆写返回 null,走默认配置) */
    public static JSONObject channelPlayerConfig(String channelName) {
        if (channelName == null || channelName.isEmpty()) return null;
        String s = PrefsDataStore.getString(channelName, null);
        if (s == null || s.isEmpty()) return null;
        try {
            return new JSONObject(s);
        } catch (Throwable th) {
            return null;
        }
    }

    /** 覆写/更新某频道播放配置(存 JSON 文本) */
    public static void setChannelPlayerConfig(String channelName, JSONObject cfg) {
        if (channelName == null || channelName.isEmpty()) return;
        if (cfg == null) {
            PrefsDataStore.delete(channelName);
        } else {
            PrefsDataStore.put(channelName, cfg.toString());
        }
    }

    /** 清除某频道覆写(回到默认配置) */
    public static void deleteChannelPlayerConfig(String channelName) {
        if (channelName == null || channelName.isEmpty()) return;
        PrefsDataStore.delete(channelName);
    }
}

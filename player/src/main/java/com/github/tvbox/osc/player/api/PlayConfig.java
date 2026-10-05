package com.github.tvbox.osc.player.api;

import com.github.tvbox.osc.config.PrefsDataStore;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 播放配置门面（配置门面模式 3.6：数据自持 + 模块内持久化 + 变更订阅）。
 * <p>
 * 数据维护在播放模块内部（Hawk 键沿用旧应用 key，与历史设置兼容，独立模块不依赖 app 的 HawkConfig）；
 * 对外只暴露 查询 / 操作 / 订阅 / 备份：
 * <ul>
 *   <li>查询：getPlayType / getRenderType / getScaleType / getTimeStep / getIjkCodec / isIjkCachePlay /
 *       getBackgroundPlayType / getVideoPurifyMode / getVideoSpeed / 字幕三件套</li>
 *   <li>操作：对应 setXxx（内部校验 + 持久化 + 广播变更）</li>
 *   <li>订阅：{@link #subscribe(Listener)}——设置页等关注方刷新 UI</li>
 *   <li>备份：{@link #exportConfig()} / {@link #importConfig(Map)}（BackupDialog 聚合）</li>
 * </ul>
 * 展示名转换（getScaleName/getPlayerName/getRenderName）属 UI 层，留在 app 的 PlayerHelper。
 */
public final class PlayConfig {

    // 键沿用旧应用 key（兼容历史设置）
    private static final String KEY_PLAY_TYPE = "play_type";          // 0 系统 1 IJK 2 Exo 10 MX...
    private static final String KEY_PLAY_RENDER = "play_render";      // 0 texture 1 surface
    private static final String KEY_PLAY_SCALE = "play_scale";
    private static final String KEY_PLAY_TIME_STEP = "play_time_step";
    private static final String KEY_IJK_CODEC = "ijk_codec";
    private static final String KEY_IJK_CACHE_PLAY = "ijk_cache_play";
    private static final String KEY_BACKGROUND_PLAY_TYPE = "background_play_type";
    private static final String KEY_VIDEO_PURIFY = "video_purify";
    private static final String KEY_VIDEO_PURIFY_MODE = "video_purify_mode";
    private static final String KEY_VIDEO_SPEED = "video_speed";
    private static final String KEY_SUBTITLE_OPEN = "subtitle_open";
    private static final String KEY_SUBTITLE_TEXT_SIZE = "subtitle_text_size";
    private static final String KEY_SUBTITLE_TIME_DELAY = "subtitle_time_delay";

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public static final int VIDEO_PURIFY_OFF = 0;
    public static final int VIDEO_PURIFY_FILTER = 1;
    public static final int VIDEO_PURIFY_ENHANCED = 2;
    public static final float DEFAULT_VIDEO_SPEED = 3.0f;
    private static final List<Float> VIDEO_SPEED_OPTIONS = Collections.unmodifiableList(
            Arrays.asList(2.0f, 3.0f, 4.0f, 5.0f));

    private PlayConfig() {
    }

    public interface Listener {
        void onConfigChanged();
    }



    // ── 查询 ──

    /** 默认播放器：0 系统 1 IJK 2 Exo 10 MX...，默认 0 */
    public static int getPlayType() {
        return PrefsDataStore.getInt(KEY_PLAY_TYPE, 0);
    }

    /** 渲染方式：0 texture 1 surface，默认 1；已保存的用户选择保持不变 */
    public static int getRenderType() {
        return PrefsDataStore.getInt(KEY_PLAY_RENDER, 1);
    }

    /** 画面缩放（0-5），默认 0 */
    public static int getScaleType() {
        return PrefsDataStore.getInt(KEY_PLAY_SCALE, 0);
    }

    /** 长按倍速步进（秒），默认 1 */
    public static int getTimeStep() {
        return PrefsDataStore.getInt(KEY_PLAY_TIME_STEP, 1);
    }

    /** IJK 解码器名，默认 "软解码" */
    public static String getIjkCodec() {
        return PrefsDataStore.getString(KEY_IJK_CODEC, "软解码");
    }

    /** IJK 缓存播放，默认关 */
    public static boolean isIjkCachePlay() {
        return PrefsDataStore.getBoolean(KEY_IJK_CACHE_PLAY, false);
    }

    /** 后台播放：0 关闭 1 开启 2 画中画，默认 0 */
    public static int getBackgroundPlayType() {
        int mode = PrefsDataStore.getInt(KEY_BACKGROUND_PLAY_TYPE, 0);
        return mode >= 0 && mode <= 2 ? mode : 0;
    }

    /** 广告过滤模式。旧布尔设置映射到关闭/原有过滤，默认仍是原有过滤。 */
    public static int getVideoPurifyMode() {
        int mode = PrefsDataStore.getInt(KEY_VIDEO_PURIFY_MODE, -1);
        if (mode >= VIDEO_PURIFY_OFF && mode <= VIDEO_PURIFY_ENHANCED) return mode;
        return PrefsDataStore.getBoolean(KEY_VIDEO_PURIFY, true)
                ? VIDEO_PURIFY_FILTER : VIDEO_PURIFY_OFF;
    }

    /** 兼容旧调用方：增强过滤同样属于已开启。 */
    public static boolean isVideoPurify() {
        return getVideoPurifyMode() != VIDEO_PURIFY_OFF;
    }

    /** 长按倍速值，未设置时默认 3.0；已保存的选择保持不变。 */
    public static float getVideoSpeed() {
        return PrefsDataStore.getFloat(KEY_VIDEO_SPEED, DEFAULT_VIDEO_SPEED);
    }

    /** 设置页与播放页共用的长按倍速选项。 */
    public static List<Float> getVideoSpeedOptions() {
        return VIDEO_SPEED_OPTIONS;
    }

    /** 字幕开关，默认关 */
    public static boolean isSubtitleOpen() {
        return PrefsDataStore.getBoolean(KEY_SUBTITLE_OPEN, false);
    }

    /** 字幕字号，默认 -1（自动） */
    public static int getSubtitleTextSize() {
        return PrefsDataStore.getInt(KEY_SUBTITLE_TEXT_SIZE, -1);
    }

    /** 字幕时间延迟（毫秒），默认 0 */
    public static int getSubtitleTimeDelay() {
        return PrefsDataStore.getInt(KEY_SUBTITLE_TIME_DELAY, 0);
    }

    // ── 操作（内部校验 + 持久化 + 广播变更）──

    public static void setPlayType(int v) {
        if (getPlayType() == v) return;
        PrefsDataStore.put(KEY_PLAY_TYPE, v);
        fireChanged();
    }

    public static void setRenderType(int v) {
        if (getRenderType() == v) return;
        PrefsDataStore.put(KEY_PLAY_RENDER, v);
        fireChanged();
    }

    public static void setScaleType(int v) {
        if (getScaleType() == v) return;
        PrefsDataStore.put(KEY_PLAY_SCALE, v);
        fireChanged();
    }

    public static void setTimeStep(int v) {
        int val = Math.max(1, v);
        if (getTimeStep() == val) return;
        PrefsDataStore.put(KEY_PLAY_TIME_STEP, val);
        fireChanged();
    }

    public static void setIjkCodec(String name) {
        if (name == null) return;
        if (name.equals(getIjkCodec())) return;
        PrefsDataStore.put(KEY_IJK_CODEC, name);
        fireChanged();
    }

    public static void setIjkCachePlay(boolean on) {
        if (isIjkCachePlay() == on) return;
        PrefsDataStore.put(KEY_IJK_CACHE_PLAY, on);
        fireChanged();
    }

    public static void setBackgroundPlayType(int v) {
        int val = Math.max(0, Math.min(2, v));
        if (getBackgroundPlayType() == val) return;
        PrefsDataStore.put(KEY_BACKGROUND_PLAY_TYPE, val);
        fireChanged();
    }

    public static void setVideoPurifyMode(int mode) {
        if (mode < VIDEO_PURIFY_OFF || mode > VIDEO_PURIFY_ENHANCED) return;
        if (getVideoPurifyMode() == mode && PrefsDataStore.contains(KEY_VIDEO_PURIFY_MODE)) return;
        PrefsDataStore.put(KEY_VIDEO_PURIFY_MODE, mode);
        // Retain the old key for older backups/app versions; the new mode key is authoritative.
        PrefsDataStore.put(KEY_VIDEO_PURIFY, mode != VIDEO_PURIFY_OFF);
        fireChanged();
    }

    public static void setVideoPurify(boolean on) {
        setVideoPurifyMode(on ? VIDEO_PURIFY_FILTER : VIDEO_PURIFY_OFF);
    }

    public static void setVideoSpeed(float v) {
        if (Float.compare(getVideoSpeed(), v) == 0 && PrefsDataStore.contains(KEY_VIDEO_SPEED)) return;
        PrefsDataStore.put(KEY_VIDEO_SPEED, v);
        fireChanged();
    }

    public static void setSubtitleOpen(boolean on) {
        if (isSubtitleOpen() == on) return;
        PrefsDataStore.put(KEY_SUBTITLE_OPEN, on);
        fireChanged();
    }

    public static void setSubtitleTextSize(int size) {
        if (getSubtitleTextSize() == size) return;
        PrefsDataStore.put(KEY_SUBTITLE_TEXT_SIZE, size);
        fireChanged();
    }

    public static void setSubtitleTimeDelay(int ms) {
        if (getSubtitleTimeDelay() == ms) return;
        PrefsDataStore.put(KEY_SUBTITLE_TIME_DELAY, ms);
        fireChanged();
    }

    // ── 订阅 ──

    public static void subscribe(Listener l) {
        if (l != null) listeners.add(l);
    }

    public static void unsubscribe(Listener l) {
        listeners.remove(l);
    }

    private static void fireChanged() {
        for (Listener l : listeners) {
            try {
                l.onConfigChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    // ── 备份/恢复（BackupDialog 聚合各模块配置）──

    public static Map<String, Object> exportConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put(KEY_PLAY_TYPE, getPlayType());
        cfg.put(KEY_PLAY_RENDER, getRenderType());
        cfg.put(KEY_PLAY_SCALE, getScaleType());
        cfg.put(KEY_PLAY_TIME_STEP, getTimeStep());
        cfg.put(KEY_IJK_CODEC, getIjkCodec());
        cfg.put(KEY_IJK_CACHE_PLAY, isIjkCachePlay());
        cfg.put(KEY_BACKGROUND_PLAY_TYPE, getBackgroundPlayType());
        cfg.put(KEY_VIDEO_PURIFY, isVideoPurify());
        cfg.put(KEY_VIDEO_PURIFY_MODE, getVideoPurifyMode());
        cfg.put(KEY_VIDEO_SPEED, getVideoSpeed());
        cfg.put(KEY_SUBTITLE_OPEN, isSubtitleOpen());
        cfg.put(KEY_SUBTITLE_TEXT_SIZE, getSubtitleTextSize());
        cfg.put(KEY_SUBTITLE_TIME_DELAY, getSubtitleTimeDelay());
        return cfg;
    }

    public static void importConfig(Map<String, Object> cfg) {
        if (cfg == null) return;
        Object v;
        if ((v = cfg.get(KEY_PLAY_TYPE)) instanceof Integer) setPlayType((Integer) v);
        if ((v = cfg.get(KEY_PLAY_RENDER)) instanceof Integer) setRenderType((Integer) v);
        if ((v = cfg.get(KEY_PLAY_SCALE)) instanceof Integer) setScaleType((Integer) v);
        if ((v = cfg.get(KEY_PLAY_TIME_STEP)) instanceof Integer) setTimeStep((Integer) v);
        if ((v = cfg.get(KEY_IJK_CODEC)) instanceof String) setIjkCodec((String) v);
        if ((v = cfg.get(KEY_IJK_CACHE_PLAY)) instanceof Boolean) setIjkCachePlay((Boolean) v);
        if ((v = cfg.get(KEY_BACKGROUND_PLAY_TYPE)) instanceof Integer) setBackgroundPlayType((Integer) v);
        boolean importedPurifyMode = false;
        if ((v = cfg.get(KEY_VIDEO_PURIFY_MODE)) instanceof Number) {
            double value = ((Number) v).doubleValue();
            int mode = (int) value;
            if (value == mode && mode >= VIDEO_PURIFY_OFF && mode <= VIDEO_PURIFY_ENHANCED) {
                setVideoPurifyMode(mode);
                importedPurifyMode = true;
            }
        }
        if (!importedPurifyMode && (v = cfg.get(KEY_VIDEO_PURIFY)) instanceof Boolean) {
            setVideoPurify((Boolean) v);
        }
        if ((v = cfg.get(KEY_VIDEO_SPEED)) instanceof Number) setVideoSpeed(((Number) v).floatValue());
        if ((v = cfg.get(KEY_SUBTITLE_OPEN)) instanceof Boolean) setSubtitleOpen((Boolean) v);
        if ((v = cfg.get(KEY_SUBTITLE_TEXT_SIZE)) instanceof Integer) setSubtitleTextSize((Integer) v);
        if ((v = cfg.get(KEY_SUBTITLE_TIME_DELAY)) instanceof Integer) setSubtitleTimeDelay((Integer) v);
    }
}

package com.github.tvbox.osc.log;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 日志配置门面（配置门面模式：数据自持 + 模块内持久化 + 变更订阅）。
 * <p>
 * 数据维护在 log 模块内部；持久化经 {@link LogConfigStore}（依赖倒置，由宿主 App 启动时注入
 * core-storage 的实现），未注入时使用进程内存实现（仅进程内生效，正常装配总会在启动早期注入，
 * 不丢持久化）。
 * <ul>
 *   <li>查询：{@link #isEnabled()} / {@link #getLevel()} / {@link #getRetentionDays()}</li>
 *   <li>操作：{@link #setEnabled(boolean)}（业务日志开关；错误日志 logcat 常驻不受此开关影响）/
 *       {@link #setLevel(int)} / {@link #setRetentionDays(int)}</li>
 *   <li>订阅：{@link #subscribe(Listener)}——设置页等关注方刷新 UI</li>
 * </ul>
 * 持久化 key：开关沿用旧应用 key "app_log"（与历史设置兼容，独立模块不依赖 app 的 HawkConfig），
 * 级别/保留天数用独立 key。
 */
public final class LogConfig {

    /** 开关（沿用旧应用 key "app_log"，兼容历史设置；独立模块内联，不依赖 app 的 HawkConfig） */
    private static final String KEY_ENABLED = "app_log";
    private static final String KEY_LEVEL = "log_level";
    private static final String KEY_RETENTION = "log_retention";

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** 存储实现（宿主注入；缺省内存实现保证未注入时序不空转） */
    private static volatile LogConfigStore store = new MemoryStore();

    private LogConfig() {
    }

    public interface Listener {
        void onConfigChanged();
    }

    /** 注入持久化存储实现（App 组合根在启动早期调用一次；null 忽略） */
    public static void setStore(LogConfigStore impl) {
        if (impl != null) {
            store = impl;
        }
    }

    /** 进程内存实现：未注入持久化实现时的兜底（进程内一致,重启丢失;正常装配总会在启动早期注入） */
    private static final class MemoryStore implements LogConfigStore {
        private final ConcurrentHashMap<String, Object> mem = new ConcurrentHashMap<>();

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = mem.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = mem.get(key);
            return v instanceof Integer ? (Integer) v : defValue;
        }

        @Override
        public void putBoolean(String key, boolean value) {
            mem.put(key, value);
        }

        @Override
        public void putInt(String key, int value) {
            mem.put(key, value);
        }
    }

    // ── 查询 ──

    public static boolean isEnabled() {
        return store.getBoolean(KEY_ENABLED, false);
    }

    /** 0=DEBUG 1=INFO 2=WARN 3=ERROR，默认 INFO */
    public static int getLevel() {
        return Math.max(LogStore.LEVEL_DEBUG, Math.min(LogStore.LEVEL_ERROR, store.getInt(KEY_LEVEL, LogStore.LEVEL_INFO)));
    }

    /** 默认 7 天 */
    public static int getRetentionDays() {
        int v = store.getInt(KEY_RETENTION, 7);
        return Math.max(1, Math.min(30, v));
    }

    // ── 操作（内部校验 + 持久化 + 广播变更）──

    public static void setEnabled(boolean on) {
        if (isEnabled() == on) return;
        store.putBoolean(KEY_ENABLED, on);
        LogStore.get().setEnabled(on);
        fireChanged();
    }

    public static void setLevel(int level) {
        int v = Math.max(LogStore.LEVEL_DEBUG, Math.min(LogStore.LEVEL_ERROR, level));
        if (getLevel() == v) return;
        store.putInt(KEY_LEVEL, v);
        LogStore.get().setLevel(v);
        fireChanged();
    }

    public static void setRetentionDays(int days) {
        int v = Math.max(1, Math.min(30, days));
        if (getRetentionDays() == v) return;
        store.putInt(KEY_RETENTION, v);
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
}

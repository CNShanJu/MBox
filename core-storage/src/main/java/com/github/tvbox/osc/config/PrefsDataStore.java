package com.github.tvbox.osc.config;

import android.content.Context;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.rxjava3.RxDataStore;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;

import com.google.gson.Gson;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 现代化偏好存储(Preferences DataStore)的同步门面。
 * <p>
 * 语义:启动一次性把磁盘读入内存,get 内存直读;put 同步落盘(串行锁),与旧 Hawk 的
 * "写后立即可读/同步持久化"体验一致。标量(int/boolean/string/float/long)直存;
 * 对象/容器经 gson JSON 文本存(调用方提供 {@link Type});对象旧存量迁移见各配置门面。
 */
public final class PrefsDataStore {

    private static final Gson GSON = new Gson();

    private static final String FILE_NAME = "prefs.pb";
    private static final String PRIVATE_KEY_PREFIX = "_private_";
    private static final String LAN_PAIRING_CODE_KEY = "lan_pairing_code";

    private static volatile RxDataStore<Preferences> store;
    private static volatile ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();

    private static final Object WRITE_LOCK = new Object();

    private PrefsDataStore() {
    }

    /** App 启动调用一次(DataStore 为运行权威;DataStore 为运行权威;旧 Hawk 一次性迁移通道已下线退役) */
    public static void init(Context context) {
        if (store != null) return;
        synchronized (PrefsDataStore.class) {
            if (store != null) return;
            RxDataStore<Preferences> ds = new RxPreferenceDataStoreBuilder(
                    context == null ? null : context.getApplicationContext(), FILE_NAME).build();
            store = ds;
            try {
                Preferences prefs = ds.data().blockingFirst();
                if (prefs != null) {
                    ConcurrentHashMap<String, Object> map = new ConcurrentHashMap<>();
                    for (Preferences.Key<?> k : prefs.asMap().keySet()) {
                        Object v = prefs.asMap().get(k);
                        if (v != null) map.put(k.getName(), v);
                    }
                    cache = map;
                }
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    // ── 读(内存)──

    public static String getString(String key, String defValue) {
        Object v = cache.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    public static boolean getBoolean(String key, boolean defValue) {
        Object v = cache.get(key);
        return v instanceof Boolean ? (Boolean) v : defValue;
    }

    public static int getInt(String key, int defValue) {
        Object v = cache.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defValue;
    }

    public static float getFloat(String key, float defValue) {
        Object v = cache.get(key);
        return v instanceof Number ? ((Number) v).floatValue() : defValue;
    }

    public static long getLong(String key, long defValue) {
        Object v = cache.get(key);
        return v instanceof Number ? ((Number) v).longValue() : defValue;
    }

    public static boolean contains(String key) {
        return cache.containsKey(key);
    }

    // ── 写(同步落盘,串行)──

    public static void put(String key, Object value) {
        if (value instanceof String) {
            putString(key, (String) value);
        } else if (value instanceof Boolean) {
            putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            putInt(key, (Integer) value);
        } else if (value instanceof Float) {
            putFloat(key, (Float) value);
        } else if (value instanceof Long) {
            putLong(key, (Long) value);
        } else {
            throw new IllegalArgumentException("PrefsDataStore 仅支持标量:" + (value == null ? "null" : value.getClass().getName()));
        }
    }

    private static void putString(String key, String v) {
        cache.put(key, v);
        write(p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.stringKey(key), v);
            return m;
        });
    }

    private static void putBoolean(String key, boolean v) {
        cache.put(key, v);
        write(p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.booleanKey(key), v);
            return m;
        });
    }

    private static void putInt(String key, int v) {
        cache.put(key, v);
        write(p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.intKey(key), v);
            return m;
        });
    }

    private static void putFloat(String key, float v) {
        cache.put(key, v);
        write(p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.floatKey(key), v);
            return m;
        });
    }

    private static void putLong(String key, long v) {
        cache.put(key, v);
        write(p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.longKey(key), v);
            return m;
        });
    }

    /** 对象/容器存为 JSON 文本(调用方读取时提供相同 Type;null 忽略) */
    public static void putJson(String key, Object value) {
        if (value == null) return;
        String s = GSON.toJson(value);
        cache.put(key, s);
        RxDataStore<Preferences> st = store;
        if (st == null) return;
        synchronized (WRITE_LOCK) {
            try {
                st.updateDataAsync(prefs -> {
                    MutablePreferences m = prefs.toMutablePreferences();
                    m.set(PreferencesKeys.stringKey(key), s);
                    return io.reactivex.rxjava3.core.Single.just(m);
                }).blockingGet();
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    /** 读取 JSON 文本对象;缺失/解析失败返回 defValue */
    public static <T> T getJson(String key, Type typeOfT, T defValue) {
        Object c = cache.get(key);
        if (!(c instanceof String)) return defValue;
        try {
            T r = GSON.fromJson((String) c, typeOfT);
            return r != null ? r : defValue;
        } catch (Throwable th) {
            return defValue;
        }
    }

    /** 删除键(无论原存储类型;整表扫描移除同名校验值) */
    public static void delete(String key) {
        cache.remove(key);
        RxDataStore<Preferences> s = store;
        if (s == null) return;
        synchronized (WRITE_LOCK) {
            try {
                s.updateDataAsync(prefs -> {
                    MutablePreferences mutable = prefs.toMutablePreferences();
                    for (Preferences.Key<?> k : new ArrayList<>(prefs.asMap().keySet())) {
                        if (key.equals(k.getName())) {
                            mutable.remove(k);
                        }
                    }
                    return io.reactivex.rxjava3.core.Single.just(mutable);
                }).blockingGet();
            } catch (Throwable ignored) {
            }
        }
    }

    // ── 备份/恢复(BackupDialog 聚合;DataStore 为全部配置域的唯一权威)──

    /** 导出用户配置；配对令牌等应用私有键不进入备份或共享包。 */
    public static String exportJson() {
        try {
            java.util.Map<String, Object> exportable = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, Object> entry : cache.entrySet()) {
                if (!entry.getKey().startsWith(PRIVATE_KEY_PREFIX)
                        && !LAN_PAIRING_CODE_KEY.equals(entry.getKey())) {
                    exportable.put(entry.getKey(), entry.getValue());
                }
            }
            return GSON.toJson(exportable);
        } catch (Throwable th) {
            throw new IllegalStateException("设置数据导出失败", th);
        }
    }

    /** 从 JSON 文本恢复可导出的键值(与 {@link #exportJson()} 对称;写后内存/磁盘立即生效)。
     *  @return 实际恢复的键数量(-1 表示解析失败) */
    public static int importJson(String json) {
        if (json == null) return 0;
        try {
            return importAll(parseImportValues(json));
        } catch (Throwable th) {
            th.printStackTrace();
            return -1;
        }
    }

    /** 保留备份 JSON 的整数词法，避免 Gson Object 模式把长整数先转成 Double。 */
    static java.util.Map<String, Object> parseImportValues(String json) {
        com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(json);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("备份设置不是 JSON 对象");
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry
                : parsed.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) continue;
            com.google.gson.JsonPrimitive value = entry.getValue().getAsJsonPrimitive();
            if (value.isBoolean()) values.put(entry.getKey(), value.getAsBoolean());
            else if (value.isString()) values.put(entry.getKey(), value.getAsString());
            else if (value.isNumber()) {
                String raw = value.getAsString();
                if (!raw.contains(".") && !raw.contains("e") && !raw.contains("E")) {
                    try {
                        long integer = Long.parseLong(raw);
                        values.put(entry.getKey(), integer >= Integer.MIN_VALUE && integer <= Integer.MAX_VALUE
                                ? (Object) (int) integer : integer);
                        continue;
                    } catch (NumberFormatException ignored) { }
                }
                try {
                    float decimal = Float.parseFloat(raw);
                    if (!Float.isNaN(decimal) && !Float.isInfinite(decimal))
                        values.put(entry.getKey(), decimal);
                } catch (NumberFormatException ignored) { }
            }
        }
        return values;
    }

    /** 一次性写入可恢复键值；失败时不更新内存缓存，避免备份只恢复一部分设置。
     *  数值做整/浮点归一,避免 Gson Object 化后变 Double 而丢失类型。
     *  @return 实际写入的键数量，-1 表示落盘失败 */
    public static int importAll(java.util.Map<String, Object> cfg) {
        if (cfg == null) return 0;
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Object> e : cfg.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            if (key == null || v == null || key.startsWith(PRIVATE_KEY_PREFIX)
                    || LAN_PAIRING_CODE_KEY.equals(key)) continue;
            if (v instanceof Boolean || v instanceof String) {
                values.put(key, v);
            } else if (v instanceof Integer || v instanceof Long || v instanceof Float) {
                values.put(key, v);
            } else if (v instanceof Number) {
                double d = ((Number) v).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d) || Math.abs(d) > Float.MAX_VALUE) continue;
                values.put(key, d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE
                        ? (Object) (int) d : (float) d);
            }
        }
        if (values.isEmpty()) return 0;
        RxDataStore<Preferences> s = store;
        if (s == null) return -1;
        synchronized (WRITE_LOCK) {
            try {
                s.updateDataAsync(prefs -> {
                    MutablePreferences mutable = prefs.toMutablePreferences();
                    for (Preferences.Key<?> old : prefs.asMap().keySet()) {
                        if (values.containsKey(old.getName())) mutable.remove(old);
                    }
                    for (java.util.Map.Entry<String, Object> entry : values.entrySet()) {
                        String key = entry.getKey();
                        Object value = entry.getValue();
                        if (value instanceof String) mutable.set(PreferencesKeys.stringKey(key), (String) value);
                        else if (value instanceof Boolean) mutable.set(PreferencesKeys.booleanKey(key), (Boolean) value);
                        else if (value instanceof Integer) mutable.set(PreferencesKeys.intKey(key), (Integer) value);
                        else if (value instanceof Long) mutable.set(PreferencesKeys.longKey(key), (Long) value);
                        else if (value instanceof Float) mutable.set(PreferencesKeys.floatKey(key), (Float) value);
                    }
                    return io.reactivex.rxjava3.core.Single.just(mutable);
                }).blockingGet();
                cache.putAll(values);
                return values.size();
            } catch (Throwable error) {
                error.printStackTrace();
                return -1;
            }
        }
    }

    private static void write(java.util.function.Function<Preferences, MutablePreferences> fn) {
        RxDataStore<Preferences> s = store;
        if (s == null) return; // init 前 put 丢弃(装配先 init)
        synchronized (WRITE_LOCK) {
            try {
                s.updateDataAsync(prefs -> io.reactivex.rxjava3.core.Single.just(fn.apply(prefs)))
                        .blockingGet();
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }
}

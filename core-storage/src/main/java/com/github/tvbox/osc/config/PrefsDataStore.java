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

    /** 导出全部键值为 JSON 文本(标量 + putJson 的 JSON 文本原样往返;备份写盘用) */
    public static String exportJson() {
        try {
            return GSON.toJson(cache);
        } catch (Throwable th) {
            th.printStackTrace();
            return "{}";
        }
    }

    /** 从 JSON 文本恢复全部键值(与 {@link #exportJson()} 对称;写后内存/磁盘立即生效)。
     *  @return 实际恢复的键数量(-1 表示解析失败) */
    public static int importJson(String json) {
        if (json == null) return 0;
        try {
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.LinkedHashMap<String, Object>>() {
            }.getType();
            java.util.Map<String, Object> map = GSON.fromJson(json, type);
            return map == null ? 0 : importAll(map);
        } catch (Throwable th) {
            th.printStackTrace();
            return -1;
        }
    }

    /** 写入全部键值(未经 JSON 的类型原样写回);数值做整/浮点归一,避免 Gson Object 化后变 Double 而丢失类型。
     *  @return 实际写入的键数量 */
    public static int importAll(java.util.Map<String, Object> cfg) {
        if (cfg == null) return 0;
        int count = 0;
        for (java.util.Map.Entry<String, Object> e : cfg.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            if (key == null || v == null) continue;
            try {
                if (v instanceof Boolean || v instanceof String) {
                    put(key, v);
                    count++;
                } else if (v instanceof Number) {
                    double d = ((Number) v).doubleValue();
                    if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) <= Integer.MAX_VALUE) {
                        put(key, (int) d);
                    } else {
                        put(key, (float) d);
                    }
                    count++;
                } else {
                    // 不支持的运行时类型跳过(不影响其余键)
                }
            } catch (Throwable ignored) {
            }
        }
        return count;
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

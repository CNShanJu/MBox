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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
    /** 旧版指定域名设置已停用，仍从共享/备份数据中排除。 */
    private static final String LEGACY_SSL_EXCEPTION_HOST_KEY = "ssl_exception_host";
    /** 日志独立管理：本地/共享备份不得导出或覆盖本机日志设置。 */
    private static final String LOG_ENABLED_KEY = "app_log";
    private static final String LOG_LEVEL_KEY = "log_level";
    private static final String LOG_RETENTION_KEY = "log_retention";

    /** The edit boundary also lets JVM tests inject deterministic persistence failures. */
    interface EditStore { void update(Function<Preferences, MutablePreferences> edit); }

    private static volatile EditStore editor;
    private static volatile ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();

    private static final Object WRITE_LOCK = new Object();
    private static final ThreadLocal<RollbackScope> ROLLBACK_SCOPE = new ThreadLocal<>();

    private static final class RollbackScope {
        final Map<String, Object> before;
        final Set<String> touched = new LinkedHashSet<>();

        RollbackScope(Map<String, Object> before) { this.before = before; }
    }

    private PrefsDataStore() {
    }

    /** App 启动调用一次(DataStore 为运行权威;DataStore 为运行权威;旧 Hawk 一次性迁移通道已下线退役) */
    public static void init(Context context) {
        if (editor != null) return;
        synchronized (PrefsDataStore.class) {
            if (editor != null) return;
            RxDataStore<Preferences> ds = new RxPreferenceDataStoreBuilder(
                    context == null ? null : context.getApplicationContext(), FILE_NAME).build();
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
            // Do not expose a writable store until its initial disk snapshot is published.
            editor = edit -> ds.updateDataAsync(prefs ->
                    io.reactivex.rxjava3.core.Single.just(edit.apply(prefs))).blockingGet();
        }
    }

    /** Run synchronous preference writes as one rollback scope around the caller's full transaction. */
    public static <T> T runWithRollback(Callable<T> work) throws Exception {
        if (work == null) throw new IllegalArgumentException("回滚作用域任务不能为空");
        synchronized (WRITE_LOCK) {
            if (ROLLBACK_SCOPE.get() != null) throw new IllegalStateException("配置回滚作用域不能嵌套");
            if (editor == null) throw new IllegalStateException("PrefsDataStore 尚未初始化");
            RollbackScope scope = new RollbackScope(new LinkedHashMap<>(cache));
            ROLLBACK_SCOPE.set(scope);
            try {
                return work.call();
            } catch (Throwable failure) {
                try {
                    rollback(scope);
                } catch (Throwable rollbackFailure) {
                    failure.addSuppressed(new IllegalStateException("设置回滚失败，持久化状态可能已改变", rollbackFailure));
                }
                if (failure instanceof Exception) throw (Exception) failure;
                if (failure instanceof Error) throw (Error) failure;
                throw new IllegalStateException(failure);
            } finally {
                ROLLBACK_SCOPE.remove();
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
        write(key, v, p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.stringKey(key), v);
            return m;
        });
    }

    private static void putBoolean(String key, boolean v) {
        write(key, v, p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.booleanKey(key), v);
            return m;
        });
    }

    private static void putInt(String key, int v) {
        write(key, v, p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.intKey(key), v);
            return m;
        });
    }

    private static void putFloat(String key, float v) {
        write(key, v, p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.floatKey(key), v);
            return m;
        });
    }

    private static void putLong(String key, long v) {
        write(key, v, p -> {
            MutablePreferences m = p.toMutablePreferences();
            m.set(PreferencesKeys.longKey(key), v);
            return m;
        });
    }

    /** 对象/容器存为 JSON 文本(调用方读取时提供相同 Type;null 忽略) */
    public static void putJson(String key, Object value) {
        if (value == null) return;
        String s = GSON.toJson(value);
        write(key, s, prefs -> {
            MutablePreferences m = prefs.toMutablePreferences();
            m.set(PreferencesKeys.stringKey(key), s);
            return m;
        });
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
        synchronized (WRITE_LOCK) {
            EditStore s = editor;
            if (s == null) {
                if (ROLLBACK_SCOPE.get() != null) throw new IllegalStateException("PrefsDataStore 尚未初始化");
                return;
            }
            try {
                if (cache.containsKey(key)) recordTouched(key);
                s.update(prefs -> {
                    MutablePreferences mutable = prefs.toMutablePreferences();
                    removeNamedKey(prefs, mutable, key);
                    return mutable;
                });
                cache.remove(key);
            } catch (Throwable failure) {
                if (ROLLBACK_SCOPE.get() != null) throw persistenceFailure(failure);
            }
        }
    }

    // ── 备份/恢复(BackupDialog 聚合;DataStore 为全部配置域的唯一权威)──

    /** 导出用户配置；配对令牌等应用私有键不进入备份或共享包。 */
    public static String exportJson() {
        try {
            java.util.Map<String, Object> exportable = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, Object> entry : cache.entrySet()) {
                if (!isTransferExcludedKey(entry.getKey())) {
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
            if (ROLLBACK_SCOPE.get() != null) throw persistenceFailure(th);
            th.printStackTrace();
            return -1;
        }
    }

    /**
     * 精确恢复系统备份中的可转移设置。包中不存在的可转移键会被删除；本机私有键保留。
     * 一次 DataStore edit 成功后才发布新的内存快照，解析或落盘失败直接抛错。
     *
     * @return 备份中恢复的可转移键数量
     */
    public static int replaceTransferableJson(String json) {
        if (json == null) throw new IllegalArgumentException("备份设置不能为空");
        Map<String, Object> parsed;
        try {
            parsed = parseImportValues(json);
            com.google.gson.JsonObject source = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : source.entrySet()) {
                if (!isTransferExcludedKey(entry.getKey()) && !parsed.containsKey(entry.getKey()))
                    throw new IllegalArgumentException("备份设置包含不支持的值: " + entry.getKey());
            }
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("备份设置格式无效", error);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            if (!isTransferExcludedKey(entry.getKey())) values.put(entry.getKey(), entry.getValue());
        }

        synchronized (WRITE_LOCK) {
            EditStore s = editor;
            if (s == null) throw new IllegalStateException("PrefsDataStore 尚未初始化");
            ConcurrentHashMap<String, Object> restored = new ConcurrentHashMap<>(cache);
            for (String key : new ArrayList<>(restored.keySet())) {
                if (!isTransferExcludedKey(key)) {
                    recordTouched(key);
                    restored.remove(key);
                }
            }
            for (String key : values.keySet()) recordTouched(key);
            restored.putAll(values);
            try {
                s.update(prefs -> {
                    MutablePreferences mutable = prefs.toMutablePreferences();
                    for (Preferences.Key<?> old : prefs.asMap().keySet()) {
                        if (!isTransferExcludedKey(old.getName())) mutable.remove(old);
                    }
                    for (Map.Entry<String, Object> entry : values.entrySet()) {
                        setScalar(mutable, entry.getKey(), entry.getValue());
                    }
                    return mutable;
                });
            } catch (Throwable error) {
                throw persistenceFailure(error);
            }
            cache = restored;
            return values.size();
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
            if (key == null || v == null || isTransferExcludedKey(key)) continue;
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
        synchronized (WRITE_LOCK) {
            EditStore s = editor;
            if (s == null) {
                if (ROLLBACK_SCOPE.get() != null) throw new IllegalStateException("PrefsDataStore 尚未初始化");
                return -1;
            }
            try {
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    if (!entry.getValue().equals(cache.get(entry.getKey()))) recordTouched(entry.getKey());
                }
                s.update(prefs -> {
                    MutablePreferences mutable = prefs.toMutablePreferences();
                    for (Preferences.Key<?> old : prefs.asMap().keySet()) {
                        if (values.containsKey(old.getName())) mutable.remove(old);
                    }
                    for (java.util.Map.Entry<String, Object> entry : values.entrySet()) {
                        setScalar(mutable, entry.getKey(), entry.getValue());
                    }
                    return mutable;
                });
                cache.putAll(values);
                return values.size();
            } catch (Throwable error) {
                if (ROLLBACK_SCOPE.get() != null) throw persistenceFailure(error);
                error.printStackTrace();
                return -1;
            }
        }
    }

    private static void write(String key, Object value,
                              java.util.function.Function<Preferences, MutablePreferences> fn) {
        synchronized (WRITE_LOCK) {
            EditStore s = editor;
            if (s == null) {
                if (ROLLBACK_SCOPE.get() != null) throw new IllegalStateException("PrefsDataStore 尚未初始化");
                return; // init 前 put 丢弃(装配先 init)
            }
            try {
                if (!value.equals(cache.get(key))) recordTouched(key);
                s.update(fn);
                // Keep disk order and in-memory order identical for concurrent writers.
                cache.put(key, value);
            } catch (Throwable th) {
                if (ROLLBACK_SCOPE.get() != null) throw persistenceFailure(th);
                th.printStackTrace();
            }
        }
    }

    private static void recordTouched(String key) {
        RollbackScope scope = ROLLBACK_SCOPE.get();
        if (scope != null) scope.touched.add(key);
    }

    private static void rollback(RollbackScope scope) {
        if (scope.touched.isEmpty()) return;
        ConcurrentHashMap<String, Object> restored = new ConcurrentHashMap<>(cache);
        for (String key : scope.touched) {
            if (scope.before.containsKey(key)) restored.put(key, scope.before.get(key));
            else restored.remove(key);
        }
        EditStore s = editor;
        if (s == null) throw new IllegalStateException("PrefsDataStore 尚未初始化");
        // A single DataStore edit restores only keys this scope touched; unrelated changes survive.
        s.update(prefs -> {
            MutablePreferences mutable = prefs.toMutablePreferences();
            for (String key : scope.touched) removeNamedKey(prefs, mutable, key);
            for (String key : scope.touched) {
                if (scope.before.containsKey(key)) setScalar(mutable, key, scope.before.get(key));
            }
            return mutable;
        });
        cache = restored;
    }

    private static void removeNamedKey(Preferences prefs, MutablePreferences mutable, String key) {
        for (Preferences.Key<?> old : new ArrayList<>(prefs.asMap().keySet())) {
            if (key.equals(old.getName())) mutable.remove(old);
        }
    }

    private static void setScalar(MutablePreferences mutable, String key, Object value) {
        if (value instanceof String) mutable.set(PreferencesKeys.stringKey(key), (String) value);
        else if (value instanceof Boolean) mutable.set(PreferencesKeys.booleanKey(key), (Boolean) value);
        else if (value instanceof Integer) mutable.set(PreferencesKeys.intKey(key), (Integer) value);
        else if (value instanceof Long) mutable.set(PreferencesKeys.longKey(key), (Long) value);
        else if (value instanceof Float) mutable.set(PreferencesKeys.floatKey(key), (Float) value);
        else throw new IllegalStateException("无法恢复非标量设置: " + key);
    }

    private static RuntimeException persistenceFailure(Throwable failure) {
        if (failure instanceof Error) throw (Error) failure;
        return failure instanceof RuntimeException ? (RuntimeException) failure
                : new IllegalStateException("设置持久化失败", failure);
    }

    /** 敏感本机设置必须由用户在本机显式操作，不能从共享/备份数据启用。 */
    private static boolean isTransferExcludedKey(String key) {
        return key.startsWith(PRIVATE_KEY_PREFIX) || LAN_PAIRING_CODE_KEY.equals(key)
                || SystemConfig.KEY_IGNORE_SSL_ERROR.equals(key)
                || LEGACY_SSL_EXCEPTION_HOST_KEY.equals(key)
                || LOG_ENABLED_KEY.equals(key) || LOG_LEVEL_KEY.equals(key)
                || LOG_RETENTION_KEY.equals(key);
    }
}

package com.github.tvbox.osc.share;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 归档清单:导出包的自描述头部(随包一起走,导入侧先读它再决定怎么落)。
 *
 * <p>为什么必须随包带上这些:<b>导入发生在另一台设备、可能是更老或更新的版本上</b>。
 * 没有清单,导入侧只能猜"这堆字节里有没有 room.db、是不是本应用的东西";
 * 有了 {@code schema} 就能做前向兼容(老版本读到新 schema 直接拒绝,而不是写坏数据),
 * 有了 {@link #checksumSha256} 就能挡住传输途中的截断/损坏。
 *
 * <p>字段解析用显式 try/catch 且各字段独立兜底:清单里多一个未知字段、少一个可选字段
 * 都不该让整包作废(旧版本写的清单也要能读)。
 *
 * <p>与 app 侧 {@code BackupDialog.buildManifest()} 的关系:那份清单只覆盖"设置+Room",
 * 是本地备份目录用的;schema 语义与这里保持一致(见 {@link #SCHEMA_CURRENT}),
 * 后续统一到本类,避免两套清单各写各的。
 */
public final class ShareManifest {

    /** 当前归档格式版本。导入侧按"<= 本值 可读"处理;未知的更大值一律拒绝。 */
    public static final int SCHEMA_CURRENT = 1;

    /** 归档内配置文件(与 BackupDialog 的 prefs.json 同名,保证与新老备份目录互通) */
    public static final String ENTRY_PREFS = "prefs.json";
    /** 归档内 Room 数据库文件 */
    public static final String ENTRY_ROOM = "room.db";
    /** 归档内清单文件 */
    public static final String ENTRY_MANIFEST = "manifest.json";

    private static final Gson GSON = new Gson();

    private final int schema;
    private final String fileName;
    private final long sizeBytes;
    private final String checksumSha256;
    private final long createdAtMillis;
    private final String appVersionName;
    private final int appVersionCode;
    private final List<String> domains;

    public ShareManifest(int schema, @Nullable String fileName, long sizeBytes,
                         @Nullable String checksumSha256, long createdAtMillis,
                         @Nullable String appVersionName, int appVersionCode,
                         @Nullable List<String> domains) {
        this.schema = schema;
        this.fileName = fileName == null ? "" : fileName;
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256 == null ? "" : checksumSha256;
        this.createdAtMillis = createdAtMillis;
        this.appVersionName = appVersionName == null ? "" : appVersionName;
        this.appVersionCode = appVersionCode;
        this.domains = domains == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(domains));
    }

    /** 归档格式版本 */
    public int schema() {
        return schema;
    }

    /** 归档文件名(给落盘/展示用) */
    @NonNull
    public String fileName() {
        return fileName;
    }

    /** 归档字节数 */
    public long sizeBytes() {
        return sizeBytes;
    }

    /** 归档内容 SHA-256(小写十六进制);空串=导出侧没算,导入侧跳过校验 */
    @NonNull
    public String checksumSha256() {
        return checksumSha256;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    @NonNull
    public String appVersionName() {
        return appVersionName;
    }

    public int appVersionCode() {
        return appVersionCode;
    }

    /** 覆盖的数据域(如 prefs / room / themes),导入侧据此提示"会覆盖什么" */
    @NonNull
    public List<String> domains() {
        return domains;
    }

    /**
     * 本版本能否读这个清单。判据只有一条:schema 不超过当前值。
     * 更小的 schema 交给导入侧按缺失字段兜底(前向兼容),不做拒绝。
     */
    public boolean isReadableByCurrentVersion() {
        return schema > 0 && schema <= SCHEMA_CURRENT;
    }

    @NonNull
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("schema", schema);
        o.addProperty("fileName", fileName);
        o.addProperty("size", sizeBytes);
        o.addProperty("checksum", checksumSha256);
        o.addProperty("createdAt", createdAtMillis);
        o.addProperty("appVersion", appVersionName);
        o.addProperty("versionCode", appVersionCode);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (String d : domains) arr.add(d);
        o.add("domains", arr);
        return o.toString();
    }

    /** 解析清单;结构非法返回 {@code null}(调用方转成 {@link ShareErrorCode#PARSE}) */
    @Nullable
    public static ShareManifest fromJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            List<String> domains = new ArrayList<>();
            if (o.has("domains") && o.get("domains").isJsonArray()) {
                for (com.google.gson.JsonElement e : o.getAsJsonArray("domains")) {
                    if (e != null && e.isJsonPrimitive()) domains.add(e.getAsString());
                }
            }
            return new ShareManifest(
                    optInt(o, "schema", 0),
                    optString(o, "fileName", ""),
                    optLong(o, "size", 0L),
                    optString(o, "checksum", ""),
                    optLong(o, "createdAt", 0L),
                    optString(o, "appVersion", ""),
                    optInt(o, "versionCode", 0),
                    domains);
        } catch (Throwable t) {
            return null;
        }
    }

    // —— 容错取值:类型不对/字段缺失都退回默认值,不让一个坏字段废掉整份清单 ——

    private static int optInt(JsonObject o, String key, int def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static long optLong(JsonObject o, String key, long def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static String optString(JsonObject o, String key, String def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "ShareManifest{schema=" + schema + ", file=" + fileName + ", size=" + sizeBytes
                + ", domains=" + domains + "}";
    }
}

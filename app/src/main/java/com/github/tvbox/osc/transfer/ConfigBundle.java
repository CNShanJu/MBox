package com.github.tvbox.osc.transfer;

import android.content.Context;

import com.github.tvbox.osc.share.ShareArchives;
import com.github.tvbox.osc.share.ShareManifest;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned bulk configuration package shared by LAN now and online transports later. */
public final class ConfigBundle {
    public static final long MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_UNPACKED_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_JSON_BYTES = 32 * 1024 * 1024;
    private static final Set<String> CATEGORIES = new LinkedHashSet<>(Arrays.asList(
            "subscriptions", "live", "themes", "settings", "history"));
    private static final List<String> ORDER = Arrays.asList(
            "subscriptions", "live", "themes", "settings", "history");
    private final Context context;
    private final ConfigDataExchange exchange;

    public ConfigBundle(Context context) {
        this.context = context.getApplicationContext();
        this.exchange = new ConfigDataExchange(this.context);
    }

    /** Caller owns the returned ZIP and must delete it after transport finishes. */
    public File exportSelected(Set<String> selected) throws Exception {
        return exportSelected(selected, java.util.Collections.emptyMap());
    }

    /** Subscription management exports only the selected items, not every saved subscription. */
    public File exportSubscriptionSelection(JsonArray items) throws Exception {
        if (items == null || items.size() < 2) throw new IOException("请至少选择两条订阅");
        JsonObject data = new JsonObject();
        data.addProperty("schema", 1);
        data.addProperty("category", "subscriptions");
        data.addProperty("createdAt", System.currentTimeMillis());
        data.add("items", items);
        return exportSelected(java.util.Collections.singleton("subscriptions"),
                java.util.Collections.singletonMap("subscriptions", data));
    }

    private File exportSelected(Set<String> selected, Map<String, JsonObject> overrides) throws Exception {
        Set<String> requested = requireCategories(selected);
        File work = createWorkDirectory();
        File archive = null;
        try {
            Map<String, File> entries = new LinkedHashMap<>();
            for (String category : ORDER) {
                if (!requested.contains(category)) continue;
                JsonObject data = overrides.containsKey(category)
                        ? overrides.get(category) : ConfigDataExchange.exportCategory(category);
                if (data == null) throw new IOException("无法导出 " + category);
                entries.put(category + ".json", write(work, category + ".json", data.toString()));
                if ("themes".equals(category)) {
                    com.google.gson.JsonArray themes = data.getAsJsonArray("themes");
                    if (themes == null || themes.size() > 40) throw new IOException("主题数量超出批量导出上限");
                    for (int i = 0; i < themes.size(); i++) {
                        JsonObject theme = themes.get(i).getAsJsonObject();
                        String id = theme.get("id").getAsString();
                        File exported = ConfigDataExchange.exportTheme(context, id);
                        if (exported == null) throw new IOException("主题导出失败：" + theme.get("name").getAsString());
                        String name = themeName(i);
                        try { copy(exported, new File(work, name), MAX_ARCHIVE_BYTES); }
                        finally { exported.delete(); }
                        entries.put(name, new File(work, name));
                    }
                }
            }
            ShareManifest manifest = new ShareManifest(ShareManifest.SCHEMA_CURRENT,
                    "mbox-config.zip", 0, "", System.currentTimeMillis(),
                    com.blankj.utilcode.util.AppUtils.getAppVersionName(),
                    com.blankj.utilcode.util.AppUtils.getAppVersionCode(),
                    new ArrayList<>(requested));
            entries.put(ShareManifest.ENTRY_MANIFEST,
                    write(work, ShareManifest.ENTRY_MANIFEST, manifest.toJson()));
            archive = File.createTempFile("mbox_config_", ".zip", context.getCacheDir());
            ShareArchives.zip(entries, archive);
            if (archive.length() > MAX_ARCHIVE_BYTES) throw new IOException("配置包超过 64 MB");
            return archive;
        } catch (Exception error) {
            if (archive != null) archive.delete();
            throw error;
        } finally {
            clean(work);
        }
    }

    /** Read a subscriptions-only ZIP for the standalone subscription import flow. */
    public JsonArray readSubscriptionSelection(File archive) throws Exception {
        if (archive == null || !archive.isFile() || archive.length() > MAX_ARCHIVE_BYTES)
            throw new IOException("订阅包无效或超过 64 MB");
        ShareManifest manifest = ShareArchives.manifest(archive);
        if (manifest == null || !manifest.isReadableByCurrentVersion()
                || !"mbox-config.zip".equals(manifest.fileName())
                || manifest.domains().size() != 1
                || !"subscriptions".equals(manifest.domains().get(0)))
            throw new IOException("这不是订阅清单 ZIP");
        ShareArchives.check(archive, manifest);
        File work = createWorkDirectory();
        try {
            ShareArchives.extract(archive, work, MAX_ARCHIVE_BYTES, MAX_UNPACKED_BYTES);
            validateEntries(work, manifest.domains());
            JsonElement parsed = JsonParser.parseString(read(new File(work, "subscriptions.json"), MAX_JSON_BYTES));
            if (!parsed.isJsonObject()) throw new IOException("订阅数据格式无效");
            JsonObject data = parsed.getAsJsonObject();
            if (!data.has("schema") || data.get("schema").getAsInt() != 1
                    || !data.has("category") || !"subscriptions".equals(data.get("category").getAsString())
                    || !data.has("items") || !data.get("items").isJsonArray())
                throw new IOException("订阅数据格式无效");
            return data.getAsJsonArray("items");
        } finally {
            clean(work);
        }
    }

    /** Validate and unpack everything before touching local configuration. */
    public String importSelected(File archive, Set<String> selected) throws Exception {
        Set<String> requested = requireCategories(selected);
        if (archive == null || !archive.isFile() || archive.length() > MAX_ARCHIVE_BYTES)
            throw new IOException("配置包无效或超过 64 MB");
        ShareManifest manifest = ShareArchives.manifest(archive);
        if (manifest == null || !manifest.isReadableByCurrentVersion()
                || !"mbox-config.zip".equals(manifest.fileName())
                || manifest.domains().isEmpty() || manifest.domains().size() > CATEGORIES.size()
                || !CATEGORIES.containsAll(manifest.domains())
                || new LinkedHashSet<>(manifest.domains()).size() != manifest.domains().size()
                || !manifest.domains().containsAll(requested)) throw new IOException("配置包清单无效或缺少所选类别");
        ShareArchives.check(archive, manifest);
        File work = createWorkDirectory();
        try {
            ShareArchives.extract(archive, work, MAX_ARCHIVE_BYTES, MAX_UNPACKED_BYTES);
            validateEntries(work, manifest.domains());
            Map<String, JsonObject> staged = new LinkedHashMap<>();
            for (String category : ORDER) {
                if (!requested.contains(category)) continue;
                JsonElement parsed = JsonParser.parseString(read(new File(work, category + ".json"), MAX_JSON_BYTES));
                if (!parsed.isJsonObject()) throw new IOException("配置数据格式无效：" + category);
                JsonObject data = parsed.getAsJsonObject();
                if (!data.has("schema") || data.get("schema").getAsInt() != 1
                        || !data.has("category") || !category.equals(data.get("category").getAsString()))
                    throw new IOException("配置类别不匹配：" + category);
                staged.put(category, data);
            }
            List<String> results = new ArrayList<>();
            for (String category : ORDER) {
                if (!requested.contains(category)) continue;
                try {
                    int count;
                    if ("themes".equals(category)) {
                        int expected = staged.get("themes").getAsJsonArray("themes").size();
                        count = 0;
                        for (int i = 0; i < expected; i++) count += exchange.importTheme(new File(work, themeName(i)));
                    } else {
                        count = exchange.importCategory(category, staged.get(category));
                    }
                    results.add(label(category) + " " + count + " 项");
                } catch (Exception failure) {
                    throw new IOException((results.isEmpty() ? "" : "此前已导入 "
                            + android.text.TextUtils.join("、", results) + "；")
                            + label(category) + " 导入失败，当前类别也可能部分写入："
                            + failure.getMessage(), failure);
                }
            }
            return "已导入：" + android.text.TextUtils.join("、", results) + "。重启应用后全部生效。";
        } finally {
            clean(work);
        }
    }

    private static Set<String> requireCategories(Set<String> selected) throws IOException {
        if (selected == null || selected.isEmpty() || !CATEGORIES.containsAll(selected))
            throw new IOException("请选择有效的配置类别");
        return new LinkedHashSet<>(selected);
    }

    private static String label(String category) {
        switch (category) {
            case "subscriptions": return "订阅源";
            case "live": return "直播源";
            case "themes": return "主题";
            case "settings": return "设置";
            default: return "历史记录";
        }
    }

    private static String themeName(int index) { return String.format(java.util.Locale.ROOT, "theme-%03d.bin", index); }

    private static void validateEntries(File work, List<String> domains) throws IOException {
        if (domains.isEmpty() || !CATEGORIES.containsAll(domains)) throw new IOException("配置包包含未知类别");
        File[] files = work.listFiles();
        if (files == null) throw new IOException("配置包解压失败");
        Set<String> expected = new LinkedHashSet<>();
        expected.add(ShareManifest.ENTRY_MANIFEST);
        for (String domain : domains) expected.add(domain + ".json");
        if (domains.contains("themes")) {
            JsonElement parsed = JsonParser.parseString(read(new File(work, "themes.json"), MAX_JSON_BYTES));
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("themes")
                    || !parsed.getAsJsonObject().get("themes").isJsonArray())
                throw new IOException("主题清单无效");
            int count = parsed.getAsJsonObject().getAsJsonArray("themes").size();
            if (count > 40) throw new IOException("主题数量超出上限");
            for (int i = 0; i < count; i++) expected.add(themeName(i));
        }
        if (files.length != expected.size()) throw new IOException("配置包条目不完整或包含额外文件");
        for (File file : files) if (!file.isFile() || !expected.contains(file.getName()))
            throw new IOException("配置包包含未知条目");
    }

    private File createWorkDirectory() throws IOException {
        File dir = new File(context.getCacheDir(), "config_work_" + java.util.UUID.randomUUID());
        if (!dir.mkdir()) throw new IOException("无法建立临时目录");
        return dir;
    }

    private static File write(File dir, String name, String text) throws IOException {
        File file = new File(dir, name);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON_BYTES) throw new IOException("配置数据过大");
        try (OutputStream out = new FileOutputStream(file)) { out.write(bytes); }
        return file;
    }

    private static String read(File file, int limit) throws IOException {
        if (!file.isFile() || file.length() > limit) throw new IOException("配置条目缺失或过大");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) > 0) {
                if (out.size() + n > limit) throw new IOException("配置条目过大");
                out.write(buffer, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void copy(File from, File to, long limit) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192]; int n; long total = 0;
            while ((n = in.read(buffer)) > 0) {
                total += n; if (total > limit) throw new IOException("主题包过大");
                out.write(buffer, 0, n);
            }
        }
    }

    private void clean(File dir) {
        try {
            String cache = context.getCacheDir().getCanonicalPath() + File.separator;
            if (!dir.getCanonicalPath().startsWith(cache) || !dir.getName().startsWith("config_work_")) return;
            cleanChildren(dir);
        } catch (IOException ignored) { }
    }

    private static void cleanChildren(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) cleanChildren(child);
        file.delete();
    }
}

package com.github.tvbox.osc.transfer;

import android.content.Context;

import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.bean.theme.ThemeDef;
import com.github.tvbox.osc.config.PrefsDataStore;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.repo.HistoryRepositories;
import com.github.tvbox.osc.share.ShareArchives;
import com.github.tvbox.osc.share.domain.DataDomain;
import com.github.tvbox.osc.share.domain.DataTransferHub;
import com.github.tvbox.osc.storage.theme.ThemeStore;
import com.github.tvbox.osc.util.LiveConfig;
import com.github.tvbox.osc.util.SubscriptionConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 配置数据域（局域网配置导入导出用）的 {@link DataDomain} 实现与注册。
 *
 * <p>各域复用 {@link ConfigDataExchange} 的领域逻辑（序列化格式、去重规则），只做
 * File ↔ JsonObject 的适配；清空语义按"清空本机该域可转移数据"实现。领域逻辑仍由各
 * 配置门面（SubscriptionConfig / LiveConfig / ThemeStore / SystemConfig 等）自己管理，
 * 这里不另起炉灶 —— 某域格式变了，只需改那个域。
 *
 * <p>同一调度器上还注册着三个备份数据域（prefs / room / subs，见 BackupModules 的
 * DataDomain 适配）：本地备份的创建/还原与局域网配置导入因此走同一条 share 调度，
 * 只是选定的数据域与导入语义不同。
 */
public final class ConfigDomains {

    private static final long JSON_MAX_BYTES = 32L * 1024L * 1024L;
    private static final long THEMES_MAX_BYTES = 64L * 1024L * 1024L;

    private static volatile DataTransferHub hub;
    private static volatile Context appContext;

    private ConfigDomains() {
    }

    /** 已初始化的统一调度器（配置域 + 备份域）。 */
    public static DataTransferHub hub() {
        DataTransferHub h = hub;
        if (h == null) throw new IllegalStateException("ConfigDomains 未初始化");
        return h;
    }

    /** 在组合根（App 启动）注册配置与备份数据域。幂等。 */
    public static void init(Context context) {
        if (hub != null) return;
        Context app = context.getApplicationContext();
        appContext = app;
        DataTransferHub h = new DataTransferHub(app);
        h.register(new SubscriptionsDomain(app));
        h.register(new LiveDomain(app));
        h.register(new ThemesDomain(app));
        h.register(new SettingsDomain(app));
        h.register(new HistoryDomain(app));
        h.register(new PrefsBackupDomain(app));
        h.register(new RoomBackupDomain(app));
        h.register(new SubsBackupDomain(app));
        hub = h;
    }

    // ------------------------------------------------------------------
    // 订阅源
    // ------------------------------------------------------------------
    static final class SubscriptionsDomain implements DataDomain {
        private final ConfigDataExchange exchange;

        SubscriptionsDomain(Context context) {
            this.exchange = new ConfigDataExchange(context);
        }

        @Override public String id() { return "subscriptions"; }
        @Override public String label() { return "订阅源"; }
        @Override public String entryName() { return "subscriptions.json"; }
        @Override public long maxBytes() { return JSON_MAX_BYTES; }

        @Override public void exportTo(File target) throws IOException {
            writeJson(target, ConfigDataExchange.exportCategory("subscriptions"));
        }

        @Override public int mergeFrom(File source) throws IOException {
            return importCategory(exchange, "subscriptions", source);
        }

        @Override public void clearData() {
            SubscriptionConfig.setSubscriptions(new ArrayList<Subscription>());
            SubscriptionConfig.setApiUrl("");
        }
    }

    // ------------------------------------------------------------------
    // 直播源
    // ------------------------------------------------------------------
    static final class LiveDomain implements DataDomain {
        private final ConfigDataExchange exchange;

        LiveDomain(Context context) {
            this.exchange = new ConfigDataExchange(context);
        }

        @Override public String id() { return "live"; }
        @Override public String label() { return "直播源"; }
        @Override public String entryName() { return "live.json"; }
        @Override public long maxBytes() { return JSON_MAX_BYTES; }

        @Override public void exportTo(File target) throws IOException {
            writeJson(target, ConfigDataExchange.exportCategory("live"));
        }

        @Override public int mergeFrom(File source) throws IOException {
            return importCategory(exchange, "live", source);
        }

        @Override public void clearData() {
            LiveConfig.setLiveHistory(new ArrayList<String>());
            SystemConfig.setLiveUrl("");
        }
    }

    // ------------------------------------------------------------------
    // 主题（清单 + 多个主题包，域内打包为单个 themes.zip）
    // ------------------------------------------------------------------
    static final class ThemesDomain implements DataDomain {
        private final Context context;

        ThemesDomain(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override public String id() { return "themes"; }
        @Override public String label() { return "主题"; }
        @Override public String entryName() { return "themes.zip"; }
        @Override public long maxBytes() { return THEMES_MAX_BYTES; }

        @Override public boolean hasData() {
            return !ThemeStore.userThemes().isEmpty();
        }

        @Override public void exportTo(File target) throws IOException {
            JsonObject data = ConfigDataExchange.exportCategory("themes");
            JsonArray themes = data.getAsJsonArray("themes");
            if (themes == null || themes.size() > 40) throw new IOException("主题数量超出批量导出上限");
            File work = workDir();
            try {
                Map<String, File> entries = new LinkedHashMap<>();
                entries.put("themes.json", writeJsonFile(new File(work, "themes.json"), data));
                for (int i = 0; i < themes.size(); i++) {
                    String id = themes.get(i).getAsJsonObject().get("id").getAsString();
                    File exported = ConfigDataExchange.exportTheme(context, id);
                    if (exported == null) {
                        throw new IOException("主题导出失败：" + themes.get(i).getAsJsonObject().get("name").getAsString());
                    }
                    try {
                        copy(exported, new File(work, themeName(i)), THEMES_MAX_BYTES);
                    } finally {
                        exported.delete();
                    }
                    entries.put(themeName(i), new File(work, themeName(i)));
                }
                ShareArchives.zip(entries, target);
            } finally {
                clean(work);
            }
        }

        @Override public int mergeFrom(File source) throws IOException {
            File work = workDir();
            try {
                ShareArchives.extract(source, work, THEMES_MAX_BYTES, THEMES_MAX_BYTES * 2);
                JsonObject data = readJson(new File(work, "themes.json"));
                JsonArray themes = data.getAsJsonArray("themes");
                if (themes == null) return 0;
                int count = 0;
                ConfigDataExchange exchange = new ConfigDataExchange(context);
                for (int i = 0; i < themes.size(); i++) {
                    count += importTheme(exchange, new File(work, themeName(i)));
                }
                return count;
            } finally {
                clean(work);
            }
        }

        @Override public void clearData() {
            for (ThemeDef theme : new ArrayList<>(ThemeStore.userThemes())) {
                ThemeStore.delete(theme.getId());
            }
        }

        private static String themeName(int index) {
            return String.format(java.util.Locale.ROOT, "theme-%03d.bin", index);
        }
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------
    static final class SettingsDomain implements DataDomain {
        private final ConfigDataExchange exchange;

        SettingsDomain(Context context) {
            this.exchange = new ConfigDataExchange(context);
        }

        @Override public String id() { return "settings"; }
        @Override public String label() { return "设置"; }
        @Override public String entryName() { return "settings.json"; }
        @Override public long maxBytes() { return JSON_MAX_BYTES; }

        @Override public void exportTo(File target) throws IOException {
            writeJson(target, ConfigDataExchange.exportCategory("settings"));
        }

        @Override public int mergeFrom(File source) throws IOException {
            return importCategory(exchange, "settings", source);
        }

        @Override public void clearData() {
            // 与备份 prefs 域一致：清空可转移键，私有键/配对码/SSL/日志配置保留。
            PrefsDataStore.replaceTransferableJson("{}");
        }
    }

    // ------------------------------------------------------------------
    // 历史记录
    // ------------------------------------------------------------------
    static final class HistoryDomain implements DataDomain {
        private final ConfigDataExchange exchange;

        HistoryDomain(Context context) {
            this.exchange = new ConfigDataExchange(context);
        }

        @Override public String id() { return "history"; }
        @Override public String label() { return "历史记录"; }
        @Override public String entryName() { return "history.json"; }
        @Override public long maxBytes() { return JSON_MAX_BYTES; }

        @Override public void exportTo(File target) throws IOException {
            writeJson(target, ConfigDataExchange.exportCategory("history"));
        }

        @Override public int mergeFrom(File source) throws IOException {
            return importCategory(exchange, "history", source);
        }

        @Override public void clearData() {
            HistoryRepositories.history().clear();
            SubscriptionConfig.clearSearchHistory();
            SubscriptionConfig.setSearchFavorites(new ArrayList<String>());
        }
    }

    // ------------------------------------------------------------------
    // File <-> JsonObject 工具
    // ------------------------------------------------------------------
    private static void writeJson(File target, JsonObject data) throws IOException {
        if (data == null) throw new IOException("数据域导出失败");
        writeJsonFile(target, data);
    }

    private static File writeJsonFile(File file, JsonObject data) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data.toString().getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static JsonObject readJson(File file) throws IOException {
        if (!file.isFile() || file.length() > JSON_MAX_BYTES) throw new IOException("数据域条目缺失或过大");
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = readBounded(in, JSON_MAX_BYTES);
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IOException("数据域条目格式无效");
            return parsed.getAsJsonObject();
        }
    }

    private static int importCategory(ConfigDataExchange exchange, String category, File source) throws IOException {
        try {
            return exchange.importCategory(category, readJson(source));
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    private static int importTheme(ConfigDataExchange exchange, File archive) throws IOException {
        try {
            return exchange.importTheme(archive);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    private static byte[] readBounded(InputStream in, long limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            if (out.size() + n > limit) throw new IOException("数据域条目过大");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void copy(File from, File to, long limit) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > limit) throw new IOException("主题包过大");
                out.write(buffer, 0, n);
            }
        }
    }

    private static File workDir() throws IOException {
        File dir = new File(appContext.getCacheDir(), "config_domain_" + UUID.randomUUID());
        if (!dir.mkdir()) throw new IOException("无法建立临时目录");
        return dir;
    }

    private static void clean(File dir) {
        try {
            String cache = appContext.getCacheDir().getCanonicalPath() + File.separator;
            if (!dir.getCanonicalPath().startsWith(cache)
                    || !dir.getName().startsWith("config_domain_")) {
                return;
            }
            cleanChildren(dir);
        } catch (IOException ignored) {
        }
    }

    private static void cleanChildren(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) cleanChildren(child);
        file.delete();
    }
}

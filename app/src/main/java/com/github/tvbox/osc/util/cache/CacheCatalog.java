package com.github.tvbox.osc.util.cache;

import android.content.Context;
import android.os.Environment;

import com.github.catvod.utils.Path;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.util.OkGoHelper;

import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * App cache and log inventory with narrowly scoped cleanup. Both methods perform disk IO and
 * must run on a module-level background executor. Known source cache files in filesDir are
 * counted as protected data and are never deleted here.
 */
public final class CacheCatalog {
    public static final String IMAGE_HTTP = "image_http";
    public static final String LOGS = "logs";
    public static final String TEMP_FILES = "temp_files";
    public static final String PLAYBACK_CACHE = "playback_cache";
    /**
     * 网盘登录凭据：扫码登录后保存的 Cookie / Token（{@code /sdcard/tvfan/Cloud-drive.txt} 这类）。
     * 它既不在 cacheDir 也不在 filesDir 的固定位置，取决于爬虫 jar 自己选了哪个目录，
     * 因此原来的缓存目录遍历根本扫不到，用户想清也清不掉——这正是
     * "扫码之后不知道去哪看、想清也清不了、清完重启状态还在"的根因。
     */
    public static final String CLOUD_CREDENTIALS = "cloud_credentials";

    private static final String RUNTIME_DATA = "runtime_data";
    private static final long STALE_AFTER_MS = 24L * 60 * 60 * 1000;

    private static final Definition[] DEFINITIONS = {
            new Definition(IMAGE_HTTP, "图片缓存", "已浏览的海报和封面，清理后会重新加载", true),
            new Definition(LOGS, "运行日志", "业务日志记录、错误日志文件及旧版日志残留（内容约大小）", true),
            new Definition(TEMP_FILES, "遗留的副本", "保存图片、分享或导入导出后遗留的旧副本", true),
            new Definition(PLAYBACK_CACHE, "播放缓存", "边播边缓存与下一集预载的视频片段，清理后重新缓存", true),
            new Definition(CLOUD_CREDENTIALS, "网盘登录凭据",
                    "扫码登录后保存的 Cookie / Token（文件或网页登录状态），清理后相关源需重新扫码", true),
            new Definition(RUNTIME_DATA, "应用运行文件", "站点等功能使用的运行文件，不可清理", false)
    };

    private final File internalRoot;
    private final File externalRoot;
    private final File filesRoot;
    /** {@code /sdcard/Android/data/<包名>/files}：爬虫 jar 写 Context 目录时可能落在这里。 */
    private final File externalFilesRoot;
    private final ImageCacheEvictor imageCacheEvictor;
    private final LogCacheAccess logCacheAccess;
    private final PlaybackCacheEvictor playbackCacheEvictor;
    /** 以下三个只用于诊断（只读）：登录状态最可能藏在偏好设置、数据库或 WebView 数据里。 */
    private final File sharedPrefsRoot;
    private final File databasesRoot;
    private final File webviewRoot;
    private final CredentialResetter credentialResetter;

    private static final LogCacheAccess NO_LOGS = new LogCacheAccess() {
        @Override public long bytes() { return 0L; }
        @Override public boolean clear() { return true; }
    };

    private static final CredentialResetter NO_RESET = () -> {};

    public CacheCatalog(Context context) {
        Context app = context.getApplicationContext();
        internalRoot = app.getCacheDir();
        externalRoot = app.getExternalCacheDir();
        filesRoot = app.getFilesDir();
        externalFilesRoot = app.getExternalFilesDir(null);
        File dataDir = null;
        try {
            dataDir = new File(app.getApplicationInfo().dataDir);
        } catch (Throwable ignored) {
            // 极端受限环境下拿不到包目录，诊断项退化成空
        }
        sharedPrefsRoot = dataDir == null ? null : new File(dataDir, "shared_prefs");
        databasesRoot = dataDir == null ? null : new File(dataDir, "databases");
        webviewRoot = dataDir == null ? null : new File(dataDir, "app_webview");
        imageCacheEvictor = OkGoHelper::evictImageDiskCache;
        playbackCacheEvictor = () -> ExoMediaSourceHelper.getInstance(app).clearPlaybackCache();
        credentialResetter = CacheCatalog::resetWebLoginState;
        LogStore logs = LogStore.get();
        logCacheAccess = new LogCacheAccess() {
            @Override public long bytes() { return logs.storedLogBytes(); }
            @Override public boolean clear() { return logs.clearStoredLogsBlocking(); }
        };
    }

    /**
     * 扫码登录最常走的两条路：写文件，或者干脆用 WebView 完成登录、cookie 留在系统 WebView 里。
     * 后者既不在 cacheDir 也不在外部存储，只有 API 能清，所以清理凭据时一并重置。
     */
    private static void resetWebLoginState() {
        try {
            android.webkit.CookieManager cookies = android.webkit.CookieManager.getInstance();
            cookies.removeAllCookies(null);
            cookies.flush();
        } catch (Throwable ignored) {
            // 没有 WebView 实现（极少见）时忽略，不影响文件清理结果
        }
        try {
            android.webkit.WebStorage.getInstance().deleteAllData();
        } catch (Throwable ignored) {
        }
    }

    // Package-private seam for the file safety regression test. Production always uses the
    // owning OkHttp Cache API rather than deleting its live journal files.
    CacheCatalog(File internalRoot, File externalRoot, ImageCacheEvictor imageCacheEvictor) {
        this(internalRoot, externalRoot, null, imageCacheEvictor);
    }

    CacheCatalog(File internalRoot, File externalRoot, File filesRoot,
                 ImageCacheEvictor imageCacheEvictor) {
        this(internalRoot, externalRoot, filesRoot, imageCacheEvictor, NO_LOGS);
    }

    CacheCatalog(File internalRoot, File externalRoot, File filesRoot,
                 ImageCacheEvictor imageCacheEvictor, LogCacheAccess logCacheAccess) {
        this.internalRoot = internalRoot;
        this.externalRoot = externalRoot;
        this.filesRoot = filesRoot;
        this.externalFilesRoot = null;
        this.sharedPrefsRoot = null;
        this.databasesRoot = null;
        this.webviewRoot = null;
        this.imageCacheEvictor = imageCacheEvictor;
        this.logCacheAccess = logCacheAccess;
        this.credentialResetter = NO_RESET;
        this.playbackCacheEvictor = () -> {};
    }

    /** Cache/file bytes plus an estimate of stored log content. */
    public Snapshot scan() {
        final Map<String, Long> sizes = new LinkedHashMap<>();
        for (Definition definition : DEFINITIONS) sizes.put(definition.id, 0L);
        long staleBefore = System.currentTimeMillis() - STALE_AFTER_MS;
        walkBoth(staleBefore, (file, category) -> sizes.put(category,
                saturatedAdd(sizes.get(category), Math.max(0L, file.length()))), null);
        sizes.put(RUNTIME_DATA, saturatedAdd(sizes.get(RUNTIME_DATA), sourceCacheBytes()));
        boolean logSizeUnavailable = false;
        try {
            sizes.put(LOGS, Math.max(0L, logCacheAccess.bytes()));
        } catch (RuntimeException error) {
            // 日志库暂时不可读时，仍允许用户清理其它独立的缓存分类。
            logSizeUnavailable = true;
        }
        // 网盘凭据在外部存储，不在缓存目录树里，所以单独扫一次；扫不到按 0 计，不影响其余分类。
        CredentialScan credentials = scanCloudCredentials();
        sizes.put(CLOUD_CREDENTIALS, credentials.bytes);

        List<Entry> entries = new ArrayList<>(DEFINITIONS.length);
        long total = 0L;
        long clearable = 0L;
        for (Definition definition : DEFINITIONS) {
            long size = sizes.get(definition.id);
            boolean logError = logSizeUnavailable && LOGS.equals(definition.id);
            entries.add(new Entry(definition.id, definition.title,
                    logError ? "日志大小读取失败，请稍后重试" : definition.description,
                    size, definition.clearable && !logError,
                    CLOUD_CREDENTIALS.equals(definition.id) ? credentials.detail : null));
            total = saturatedAdd(total, size);
            if (definition.clearable && !logError) clearable = saturatedAdd(clearable, size);
        }
        return new Snapshot(entries, total, clearable,
                Math.max(0L, total - clearable), logSizeUnavailable);
    }

    /** Source JARs and downloaded subscription snapshots live in filesDir for offline fallback. */
    private long sourceCacheBytes() {
        if (filesRoot == null) return 0L;
        try {
            if (!filesRoot.isDirectory()) return 0L;
            File root = filesRoot.getCanonicalFile();
            if (sameRoot(root, internalRoot) || sameRoot(root, externalRoot)) return 0L;
            File[] files = filesRoot.listFiles();
            if (files == null) return 0L;
            long size = 0L;
            for (File file : files) {
                if (!sourceCacheName(file.getName()) || !file.isFile()) continue;
                try {
                    if (isContainedDirectChild(root, filesRoot, file))
                        size = saturatedAdd(size, Math.max(0L, file.length()));
                } catch (IOException | SecurityException ignored) {
                    // An inaccessible or redirected file must not inflate the reported size.
                }
            }
            return size;
        } catch (IOException | SecurityException ignored) {
            return 0L;
        }
    }

    private static boolean sameRoot(File root, File candidate) throws IOException {
        return candidate != null && root.equals(candidate.getCanonicalFile());
    }

    private static boolean sourceCacheName(String name) {
        if ("csp.jar".equals(name)) return true;
        String stem = name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
        if (stem.length() != 32) return false;
        for (int i = 0; i < stem.length(); i++) {
            char c = stem.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    /** Clears selected safe categories, then returns a fresh inventory and per-category failures. */
    public ClearResult clear(Set<String> ids) {
        Set<String> cleared = new LinkedHashSet<>();
        Map<String, String> failures = new LinkedHashMap<>();
        if (ids == null) ids = Collections.emptySet();
        for (String id : ids) {
            if (IMAGE_HTTP.equals(id)) {
                try {
                    if (safeImageCacheDirectory()) imageCacheEvictor.evict();
                    cleared.add(id);
                } catch (IOException | RuntimeException error) {
                    failures.put(id, "图片缓存清理失败：" + safeMessage(error));
                }
            } else if (TEMP_FILES.equals(id)) {
                int failed = deleteStaleFiles();
                if (failed == 0) cleared.add(id);
                else failures.put(id, "有 " + failed + " 项未能清理，请重试");
            } else if (LOGS.equals(id)) {
                try {
                    if (logCacheAccess.clear()) cleared.add(id);
                    else failures.put(id, "有日志未能清理，请重试");
                } catch (RuntimeException error) {
                    failures.put(id, "运行日志清理失败：" + safeMessage(error));
                }
            } else if (PLAYBACK_CACHE.equals(id)) {
                try {
                    playbackCacheEvictor.evict();
                    cleared.add(id);
                } catch (RuntimeException error) {
                    failures.put(id, "播放缓存清理失败：" + safeMessage(error));
                }
            } else if (CLOUD_CREDENTIALS.equals(id)) {
                try {
                    int failed = deleteCloudCredentials();
                    if (failed == 0) cleared.add(id);
                    else failures.put(id, "有 " + failed + " 个凭据文件未能清理，请重试");
                } catch (RuntimeException error) {
                    // SecurityException 也走这里：外部存储不可读时按"清理失败"处理
                    failures.put(id, "网盘凭据清理失败：" + safeMessage(error));
                }
            } else {
                failures.put(id == null ? "" : id, "此类数据不可清理");
            }
        }
        return new ClearResult(cleared, failures, scan());
    }

    // ---- 网盘登录凭据：可能落在外部存储，也可能在缓存/私有目录里 ----

    /**
     * 网盘/下载源约定把 cookie、token 放在这些目录下（相对外部存储根）。
     * 目录名单一来源见 {@link Path}：{@code tvfan/}、{@code TVBox/} 是第三方源的
     * 路径契约不能改名，本 App 自己的数据统一在 {@code MBox/} 下。
     */
    private static final String[] CREDENTIAL_DIRS = Path.CREDENTIAL_DIRS;

    /** 凭据只有几百字节；超上限的一律不认，避免把用户自己的长文本当成凭据删掉。 */
    private static final long CREDENTIAL_MAX_BYTES = 256L * 1024L;

    /**
     * 只认"看起来就是凭据"的文件名：{@code Cloud-drive.txt}、{@code cookie.txt}、
     * {@code token.txt}、{@code xunlei.txt} 等。宁可漏，不可误删用户放在同目录的笔记。
     */
    private static final String[] CREDENTIAL_PREFIXES = {
            "token", "cookie", "cloud-drive", "cloud", "drive", "quark", "xunlei",
            "ali", "uc", "115", "baidu", "tianyi", "pan"};

    private static final String[] CREDENTIAL_SUFFIXES = {".txt", ".cookie", ".token"};

    private CredentialScan scanCloudCredentials() {
        List<File> files = credentialFiles();
        List<File> cookies = webCookieFiles();
        List<File> compatDb = spiderCompatDbFiles();

        long bytes = 0L;
        boolean hasEmpty = false;
        boolean hasFileCredential = false;
        StringBuilder detail = new StringBuilder(loginStateSummary(files, cookies, compatDb));

        if (!files.isEmpty()) {
            detail.append("\n\n外部凭据文件：");
            for (File file : files) {
                long length = Math.max(0L, file.length());
                bytes = saturatedAdd(bytes, length);
                detail.append('\n').append(describe(file)).append(" · ").append(CacheSizeText.format(length));
                if (length == 0) {
                    detail.append("（空文件）");
                    hasEmpty = true;
                } else {
                    hasFileCredential = true;
                }
            }
        }
        if (!cookies.isEmpty()) {
            detail.append("\n\n网页登录状态（WebView Cookie 库，只能通过 API 清除，删文件无效）：");
            for (File file : cookies) {
                long length = Math.max(0L, file.length());
                bytes = saturatedAdd(bytes, length);
                detail.append('\n').append(describe(file)).append(" · ").append(CacheSizeText.format(length))
                        .append(" · ").append(relativeTime(file.lastModified()));
            }
        }
        if (!compatDb.isEmpty()) {
            detail.append("\n\n爬虫兼容库（插件自建，删除后下次加载会自动重建）：");
            for (File file : compatDb) {
                long length = Math.max(0L, file.length());
                bytes = saturatedAdd(bytes, length);
                detail.append('\n').append(describe(file)).append(" · ").append(CacheSizeText.format(length))
                        .append(" · ").append(relativeTime(file.lastModified()));
            }
        }

        if (bytes == 0L && !hasEmpty) {
            detail.append("\n\n本地没有任何凭据痕迹。")
                    .append("\n\n如果这个源扫码后能正常播放，登录态很可能在源服务器侧——")
                    .append("扫码只是把账号和设备绑在服务端，接口每次下发新的 sToken，"
                            + "本地不会留下文件。这种情况清理缓存不会退出登录，也确实没有本地数据可清。")
                    .append("\n\n" ).append(storageWritableDetail())
                    .append("\n\n").append(credentialDirListing())
                    .append("\n\n").append(probeDetail())
                    .append("\n\n").append(databaseDetail())
                    .append("\n\n").append(webDataDetail())
                    .append("\n\n").append(sharedPrefsDetail());
        } else if (hasEmpty && !hasFileCredential) {
            detail.append("\n\n0 字节的凭据文件会被源当成「已登录」却读不到内容——"
                    + "表现就是既不弹二维码、也没有播放地址。清掉它就能重新弹码。");
        }
        return new CredentialScan(bytes, detail.toString());
    }

    /** WebView cookie 库：扫码登录若走网页，登录态只存在这里，且没有任何文件清单能反映出来。 */
    private List<File> webCookieFiles() {
        List<File> out = new ArrayList<>();
        if (webviewRoot == null) return out;
        for (String name : new String[]{"Cookies", "Cookies-journal"}) {
            File hit = findFile(webviewRoot, name, 2);
            if (hit != null && hit.isFile()) out.add(hit);
        }
        return out;
    }

    /** 爬虫自己的兼容库 {@code databases/tv}：无扩展名，既不会被凭据清单扫到，也列不进"最近修改的文本文件"。 */
    private List<File> spiderCompatDbFiles() {
        List<File> out = new ArrayList<>();
        if (databasesRoot == null) return out;
        for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) {
            File file = new File(databasesRoot, "tv" + suffix);
            if (file.isFile()) out.add(file);
        }
        return out;
    }

    /**
     * 登录态概览：文件凭据 / 网页 Cookie / 爬虫兼容库。
     * <p>网盘源不一定把登录态写进 {@code tvfan/Cloud-drive.txt}——有的只留在 WebView 的
     * Cookie 里，有的干脆绑在源服务器（本地什么都不落）。面板若只报"没有凭据文件"，
     * 很容易被读成"登录没生效"，所以这里先给三行结论。
     */
    private static String loginStateSummary(List<File> files, List<File> cookies, List<File> compatDb) {
        return "登录态概览："
                + "\n· 外部凭据文件：" + (files.isEmpty() ? "无" : files.size() + " 个")
                + "\n· 网页 Cookie 库：" + describeFileSet(cookies)
                + "\n· 爬虫兼容库：" + describeFileSet(compatDb);
    }

    /** "无" / "有（16 KB，刚刚）"：概览行需要的是结论，不是清单。 */
    private static String describeFileSet(List<File> files) {
        if (files.isEmpty()) return "无";
        long bytes = 0L;
        long latest = 0L;
        for (File file : files) {
            bytes = saturatedAdd(bytes, Math.max(0L, file.length()));
            latest = Math.max(latest, file.lastModified());
        }
        return "有（" + CacheSizeText.format(bytes) + "，" + relativeTime(latest) + "）";
    }

    private static File findFile(File dir, String name, int depth) {
        if (dir == null || depth < 0) return null;
        File direct = new File(dir, name);
        if (direct.isFile()) return direct;
        File[] children;
        try {
            children = dir.listFiles();
        } catch (SecurityException error) {
            return null;
        }
        if (children == null || depth == 0) return null;
        for (File child : children) {
            if (child == null || !child.isDirectory()) continue;
            File hit = findFile(child, name, depth - 1);
            if (hit != null) return hit;
        }
        return null;
    }

    /** 把约定凭据目录里现有的文件全部列出来（不限扩展名）：漏掉的那些通常就在这里。 */
    private String credentialDirListing() {
        List<File> dirs = new ArrayList<>();
        try {
            File storage = Environment.getExternalStorageDirectory();
            if (storage != null) {
                for (String name : CREDENTIAL_DIRS) dirs.add(new File(storage, name));
            }
        } catch (Throwable ignored) {
            // 受限环境没有外部存储
        }
        for (File root : credentialScanRoots()) {
            for (String name : CREDENTIAL_DIRS) dirs.add(new File(root, name));
        }
        StringBuilder builder = new StringBuilder("凭据目录里现有：");
        int shownDirs = 0;
        for (File dir : dirs) {
            boolean isDir;
            try {
                isDir = dir.isDirectory();
            } catch (SecurityException error) {
                continue;
            }
            if (!isDir) continue;
            if (shownDirs++ >= 8) break;
            builder.append('\n').append(describe(dir)).append('/');
            // 展开两层：jar 完全可能写 /sdcard/TVBox/tvfan/Cloud-drive.txt 这种再套一层的路径，
            // 只列直接子文件时它显示成 "tvfan/" 一个目录名，等于什么都没看到。
            List<String> entries = listNested(dir, 2, 12);
            if (entries.isEmpty()) {
                // 空目录也要列出来：预建成功但还没写入，和"目录压根不存在"是两种完全不同的状态，
                // 都显示成"一个都没有"会让排查彻底失去方向。
                builder.append(" （空，等待爬虫写入）");
                continue;
            }
            for (String entry : entries) builder.append(' ').append(entry);
        }
        if (shownDirs == 0) {
            builder.append("\n一个都没有（tvfan/、TVBox/ 等目录都不存在：")
                    .append("爬虫加载时预建失败，或还没加载过该源）");
        }
        return builder.toString();
    }

    /**
     * 递归列出目录下的条目（相对路径），目录以 {@code /} 结尾，文件带字节数。
     * 深度和条数都受限：这里只服务于诊断展示，不能扫爆存储。
     */
    private static List<String> listNested(File dir, int maxDepth, int limit) {
        List<String> out = new ArrayList<>();
        String base = dir.getAbsolutePath();
        ArrayDeque<File> pending = new ArrayDeque<>();
        ArrayDeque<Integer> levels = new ArrayDeque<>();
        pending.add(dir);
        levels.add(0);
        while (!pending.isEmpty() && out.size() < limit) {
            File current = pending.removeFirst();
            int level = levels.removeFirst();
            File[] children;
            try {
                children = current.listFiles();
            } catch (SecurityException error) {
                continue;
            }
            if (children == null) continue;
            for (File child : children) {
                if (child == null || out.size() >= limit) break;
                String relative = child.getAbsolutePath().substring(base.length());
                if (relative.startsWith(File.separator)) {
                    relative = relative.substring(File.separator.length());
                }
                if (child.isDirectory()) {
                    out.add(relative + "/");
                    if (level + 1 < maxDepth && !isSkippedCredentialDir(child.getName())) {
                        pending.addLast(child);
                        levels.addLast(level + 1);
                    }
                } else {
                    out.add(relative + "(" + child.length() + "B)");
                }
            }
        }
        return out;
    }

    /**
     * 外部存储到底能不能写。爬虫把 cookie 落盘失败，最常见的原因就是这个权限没给：
     * 目录不存在时可以 mkdirs，但 {@code /sdcard} 根下写入需要「所有文件访问权限」。
     * 写不进去时，扫码结果只能留在 jar 内存里，重启就没了 —— 表现正是"扫一次之后既不再问也播不了"。
     */
    static String storageWritableDetail() {
        File storage;
        try {
            storage = Environment.getExternalStorageDirectory();
        } catch (Throwable error) {
            return "外部存储：拿不到路径。";
        }
        if (storage == null) return "外部存储：拿不到路径。";
        File probe = new File(storage, ".mbox_write_probe");
        java.io.FileOutputStream out = null;
        try {
            out = new java.io.FileOutputStream(probe);
            out.write('1');
            out.flush();
            probe.delete();
            return "外部存储：可写（/sdcard 能落盘）";
        } catch (Throwable error) {
            return "外部存储：写入失败（" + error.getClass().getSimpleName()
                    + "）→ 爬虫拿到的 cookie 无法落盘，请到系统设置里授予「所有文件访问权限」";
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (java.io.IOException ignored) {
                }
            }
        }
    }

    /**
     * 数据库目录里的全部文件，<b>不按扩展名过滤</b>。
     * <p>爬虫用的兼容库 {@code databases/tv}（SQLite，没有扩展名）不在任何"小文本文件"规则里，
     * 而它是 jar 唯一能写、又不会被前面那些清单扫到的位置。登录状态若藏在里面，
     * 之前每一次"清凭据"都等于没做。
     */
    private String databaseDetail() {
        if (databasesRoot == null) return "数据库目录：拿不到。";
        File[] children;
        try {
            children = databasesRoot.listFiles();
        } catch (SecurityException error) {
            return "数据库目录：不可读。";
        }
        if (children == null || children.length == 0) return "数据库目录：空的。";
        StringBuilder builder = new StringBuilder("数据库目录（不分扩展名）：");
        int shown = 0;
        for (File child : children) {
            if (child == null || !child.isFile()) continue;
            if (shown++ >= 8) {
                builder.append("\n…");
                break;
            }
            builder.append('\n').append(child.getName())
                    .append(" · ").append(relativeTime(child.lastModified()))
                    .append(" · ").append(CacheSizeText.format(child.length()));
        }
        return builder.toString();
    }

    /**
     * 凭据可能出现的位置。爬虫 jar 拿到的是 App 的 {@code Context}，它写"登录成功"完全可能落在
     * {@code files/}、{@code cache/} 或 {@code /sdcard/Android/data/<包名>/files} 里。
     */
    private List<File> credentialScanRoots() {
        List<File> roots = new ArrayList<>(5);
        addCredentialRoot(roots, filesRoot);
        addCredentialRoot(roots, externalFilesRoot);
        addCredentialRoot(roots, internalRoot);
        addCredentialRoot(roots, externalRoot);
        return roots;
    }

    private static void addCredentialRoot(List<File> roots, File candidate) {
        if (candidate == null) return;
        for (File root : roots) {
            if (root.equals(candidate)) return;
        }
        roots.add(candidate);
    }

    /** 诊断用：只读枚举"登录状态最可能落在这里"的目录，绝不参与删除。 */
    private List<ProbeRoot> probeRoots() {
        List<ProbeRoot> roots = new ArrayList<>();
        addProbeRoot(roots, "[首选项]", sharedPrefsRoot, false);
        addProbeRoot(roots, "[数据库]", databasesRoot, false);
        addProbeRoot(roots, "[网页数据]", webviewRoot, false);
        addProbeRoot(roots, "[应用私有]", filesRoot, false);
        addProbeRoot(roots, "[外置私有]", externalFilesRoot, false);
        addProbeRoot(roots, "[缓存]", internalRoot, false);
        addProbeRoot(roots, "[外置缓存]", externalRoot, false);
        try {
            // 外部存储只看约定目录：整块存储里有相册、微信等海量文件，不能遍历。
            addProbeRoot(roots, "[外部存储]", Environment.getExternalStorageDirectory(), true);
        } catch (Throwable ignored) {
            // 单元测试等受限环境没有真实外部存储
        }
        return roots;
    }

    private static void addProbeRoot(List<ProbeRoot> roots, String label, File dir,
                                     boolean knownDirsOnly) {
        if (dir == null) return;
        for (ProbeRoot root : roots) {
            if (root.dir.equals(dir)) return;
        }
        roots.add(new ProbeRoot(label, dir, knownDirsOnly));
    }

    /** 显示用：{@code [应用私有] spider/tvfan.json}，一眼看出这条落在哪。 */
    private String describe(File file) {
        File best = null;
        String label = "[?]";
        for (ProbeRoot root : probeRoots()) {
            if (!file.equals(root.dir) && !isInside(file, root.dir)) continue;
            if (best == null || root.dir.getAbsolutePath().length() > best.getAbsolutePath().length()) {
                best = root.dir;
                label = root.label;
            }
        }
        if (best == null) return "[?] " + file.getName();
        String relative = file.getAbsolutePath().substring(best.getAbsolutePath().length());
        if (relative.startsWith(File.separator)) relative = relative.substring(File.separator.length());
        return relative.isEmpty() ? label : label + " " + relative;
    }

    private static boolean isInside(File file, File dir) {
        if (file == null || dir == null) return false;
        try {
            return file.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator);
        } catch (IOException | SecurityException error) {
            return false;
        }
    }

    /** 应用私有目录下浅层递归：jar 常把 token 放在 {@code files/spider/xxx.txt} 这类子目录。 */
    private static final int CREDENTIAL_PRIVATE_DEPTH = 3;

    /** 这些目录整棵跳过：要么是别的分类在管，要么只会有框架自己的二进制文件。 */
    private static final String[] CREDENTIAL_SKIP_DIRS = {
            "image_http_cache", "exo-video-cache", "code_cache", "app_textures"};

    private List<File> credentialFiles() {
        List<File> found = new ArrayList<>();
        try {
            File storage = Environment.getExternalStorageDirectory();
            if (storage != null) {
                // 只认约定目录，绝不遍历整块存储；深度 2 是为了覆盖
                // /sdcard/TVBox/tvfan/Cloud-drive.txt 这种"再套一层"的写法，
                // 之前只看直接子文件，这类路径会被整个漏掉（缓存页显示为空、也清不掉）。
                for (String dirName : CREDENTIAL_DIRS) {
                    collectCredentials(new File(storage, dirName), 2, found);
                }
            }
        } catch (Throwable ignored) {
            // 单元测试等受限环境没有真实外部存储
        }
        for (File root : credentialScanRoots()) {
            collectCredentials(root, CREDENTIAL_PRIVATE_DEPTH, found);
        }
        return found;
    }

    private static void collectCredentials(File root, int maxDepth, List<File> out) {
        if (root == null) return;
        ArrayDeque<File> pending = new ArrayDeque<>();
        ArrayDeque<Integer> levels = new ArrayDeque<>();
        pending.add(root);
        levels.add(0);
        while (!pending.isEmpty()) {
            File dir = pending.removeFirst();
            int level = levels.removeFirst();
            File[] children;
            try {
                if (!dir.isDirectory()) continue;
                children = dir.listFiles();
            } catch (SecurityException error) {
                continue;
            }
            if (children == null) continue;
            for (File child : children) {
                if (child == null) continue;
                try {
                    if (child.isDirectory()) {
                        if (level + 1 >= maxDepth || isSkippedCredentialDir(child.getName())) continue;
                        pending.addLast(child);
                        levels.addLast(level + 1);
                    } else if (isCredentialFile(child)) {
                        out.add(child);
                    }
                } catch (SecurityException ignored) {
                    // 单个条目不可读不影响其余条目
                }
            }
        }
    }

    private static boolean isSkippedCredentialDir(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String skip : CREDENTIAL_SKIP_DIRS) {
            if (skip.equals(lower)) return true;
        }
        return lower.startsWith(".");
    }

    // ---- 诊断：登录状态到底存在哪 ----

    private static final int PROBE_LIMIT = 10;
    private static final int PROBE_MAX_VISITED = 4000;
    private static final int PROBE_DEPTH = 3;
    private static final String[] PROBE_SUFFIXES = {
            ".txt", ".json", ".xml", ".dat", ".cookie", ".token", ".conf", ".cfg", ".ini", ".yaml"};

    private String probeDetail() {
        List<File> recent = recentCandidateFiles(PROBE_LIMIT);
        if (recent.isEmpty()) return "最近修改的文件：一个都没扫到。";
        StringBuilder builder = new StringBuilder("最近修改的小文件（登录状态大概率在其中）：");
        for (File file : recent) {
            builder.append('\n').append(describe(file))
                    .append(" · ").append(relativeTime(file.lastModified()))
                    .append(" · ").append(CacheSizeText.format(file.length()));
        }
        return builder.toString();
    }

    /** 按修改时间倒序取最新的若干个小文本文件：刚扫码登录过，那一条一定排在最前面。 */
    private List<File> recentCandidateFiles(int limit) {
        List<File> found = new ArrayList<>();
        int[] visited = {0};
        for (ProbeRoot root : probeRoots()) {
            collectProbeFiles(root, root.dir, 0, found, visited);
        }
        Collections.sort(found, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        return found.size() <= limit ? found : new ArrayList<>(found.subList(0, limit));
    }

    /**
     * WebView 自己的数据目录：{@code Cookies}（SQLite）、{@code Local Storage} 都在这里。
     * 它们既不在 cacheDir 也不在外部存储，所以"清缓存 + 重启"永远碰不到——
     * 也正是扫码登录最可能落脚的地方。
     */
    private static final String[] WEB_DATA_SKIP_DIRS = {
            "http cache", "gpucache", "blob_storage", "service worker", "code cache", "shadercache"};
    private static final int WEB_DATA_MAX_VISITED = 400;
    private static final int WEB_DATA_LIMIT = 6;

    private String webDataDetail() {
        StringBuilder builder = new StringBuilder("网页登录数据（就是清缓存碰不到的那部分）：");
        if (webviewRoot == null) return builder.append("\n拿不到 app_webview 目录。").toString();
        List<File> files = new ArrayList<>();
        collectWebDataFiles(webviewRoot, 0, files, new int[]{0});
        if (files.isEmpty()) return builder.append("\napp_webview 里没有可列的文件。").toString();
        Collections.sort(files, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        int shown = 0;
        for (File file : files) {
            if (shown++ >= WEB_DATA_LIMIT) break;
            builder.append('\n').append(describe(file))
                    .append(" · ").append(relativeTime(file.lastModified()))
                    .append(" · ").append(CacheSizeText.format(file.length()));
        }
        return builder.toString();
    }

    /**
     * 偏好设置目录（{@code shared_prefs}）里的全部 .xml：很多源把「设备绑定 / 已登录」标记
     * 存进 SharedPreferences 而不是外部文件，删除凭据文件根本不碰它——这就是"清完还自动续期、
     * 不弹二维码"的另一处落点。这里只读枚举，按修改时间倒序，方便一眼定位"谁在续命"。
     */
    private static final int PREFS_LIMIT = 10;

    private String sharedPrefsDetail() {
        if (sharedPrefsRoot == null) return "偏好设置目录：拿不到。";
        File[] children;
        try {
            children = sharedPrefsRoot.listFiles();
        } catch (SecurityException error) {
            return "偏好设置目录：不可读。";
        }
        if (children == null || children.length == 0) return "偏好设置目录：空的。";
        List<File> files = new ArrayList<>();
        for (File child : children) {
            if (child == null || !child.isFile()) continue;
            String name = child.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".xml")) continue;
            files.add(child);
        }
        if (files.isEmpty()) return "偏好设置目录：没有 .xml 文件。";
        Collections.sort(files, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        StringBuilder builder = new StringBuilder("偏好设置目录（设备绑定标记常在这里）：");
        int shown = 0;
        for (File file : files) {
            if (shown++ >= PREFS_LIMIT) {
                builder.append("\n…");
                break;
            }
            builder.append('\n').append(file.getName())
                    .append(" · ").append(relativeTime(file.lastModified()))
                    .append(" · ").append(CacheSizeText.format(file.length()));
        }
        return builder.toString();
    }

    private void collectWebDataFiles(File dir, int level, List<File> out, int[] visited) {
        if (dir == null || visited[0] >= WEB_DATA_MAX_VISITED) return;
        File[] children;
        try {
            if (!dir.isDirectory()) return;
            children = dir.listFiles();
        } catch (SecurityException error) {
            return;
        }
        if (children == null) return;
        for (File child : children) {
            if (visited[0] >= WEB_DATA_MAX_VISITED) return;
            visited[0]++;
            try {
                if (child.isDirectory()) {
                    String name = child.getName().toLowerCase(Locale.ROOT);
                    boolean skip = name.startsWith(".");
                    for (String dirName : WEB_DATA_SKIP_DIRS) {
                        if (dirName.equals(name)) skip = true;
                    }
                    if (!skip && level + 1 < PROBE_DEPTH) collectWebDataFiles(child, level + 1, out, visited);
                } else if (child.isFile() && child.length() > 0) {
                    out.add(child);
                }
            } catch (SecurityException ignored) {
                // 单个条目不可读不影响其余条目
            }
        }
    }

    private void collectProbeFiles(ProbeRoot root, File dir, int level,
                                   List<File> out, int[] visited) {
        if (dir == null || visited[0] >= PROBE_MAX_VISITED) return;
        File[] children;
        try {
            if (!dir.isDirectory()) return;
            children = dir.listFiles();
        } catch (SecurityException error) {
            return;
        }
        if (children == null) return;
        for (File child : children) {
            if (visited[0] >= PROBE_MAX_VISITED) return;
            visited[0]++;
            try {
                if (child.isDirectory()) {
                    if (level + 1 >= PROBE_DEPTH || isSkippedCredentialDir(child.getName())) continue;
                    if (level == 0 && root.knownDirsOnly && !isCredentialDirName(child.getName()))
                        continue;
                    collectProbeFiles(root, child, level + 1, out, visited);
                } else if (isProbeCandidate(child)) {
                    out.add(child);
                }
            } catch (SecurityException ignored) {
                // 单个条目不可读不影响其余条目
            }
        }
    }

    private static boolean isCredentialDirName(String name) {
        if (name == null) return false;
        for (String dirName : CREDENTIAL_DIRS) {
            if (dirName.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static boolean isProbeCandidate(File file) {
        long length;
        try {
            if (!file.isFile()) return false;
            length = file.length();
        } catch (SecurityException error) {
            return false;
        }
        if (length <= 0 || length > CREDENTIAL_MAX_BYTES) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        for (String suffix : PROBE_SUFFIXES) {
            if (name.endsWith(suffix)) return true;
        }
        return false;
    }

    private static String relativeTime(long timestamp) {
        if (timestamp <= 0) return "时间未知";
        long diff = System.currentTimeMillis() - timestamp;
        if (diff < 60_000L) return "刚刚";
        if (diff < 3_600_000L) return (diff / 60_000L) + "分钟前";
        if (diff < 86_400_000L) return (diff / 3_600_000L) + "小时前";
        return (diff / 86_400_000L) + "天前";
    }

    /**
     * 该文件是否位于约定的凭据目录、且文件名像凭据。
     * <p>缓存清理和本地文件服务共用同一条判定：能读到的一定能清掉，清掉的一定不再被读。
     */
    public static boolean isCloudCredential(File file, File storageRoot) {
        if (file == null || storageRoot == null) return false;
        File parent = file.getParentFile();
        if (parent == null) return false;
        boolean dirOk = false;
        for (String dirName : CREDENTIAL_DIRS) {
            if (dirName.equalsIgnoreCase(parent.getName())) {
                dirOk = true;
                break;
            }
        }
        if (!dirOk) return false;
        File grandParent = parent.getParentFile();
        if (grandParent == null) return false;
        try {
            if (!grandParent.getCanonicalPath().equals(storageRoot.getCanonicalPath())) return false;
        } catch (IOException | SecurityException error) {
            return false;
        }
        return isCredentialFile(file);
    }

    private static boolean isCredentialFile(File file) {
        try {
            // 注意：0 字节也必须认。登录失败但文件被创建出来时就是 0 字节，而源往往只看
            // 「文件存在」就当已登录 → 既不弹二维码也没有播放地址，正是最难查的那种状态。
            if (!file.isFile() || file.length() > CREDENTIAL_MAX_BYTES) return false;
        } catch (SecurityException error) {
            return false;
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        boolean suffixOk = false;
        for (String suffix : CREDENTIAL_SUFFIXES) {
            if (name.endsWith(suffix)) {
                suffixOk = true;
                break;
            }
        }
        if (!suffixOk) return false;
        for (String prefix : CREDENTIAL_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private int deleteCloudCredentials() {
        int failed = 0;
        for (File file : credentialFiles()) {
            try {
                if (!isCredentialFile(file)) continue;
                if (!file.delete() && file.exists()) failed++;
            } catch (SecurityException error) {
                failed++;
            }
        }
        // 文件删完还不够：WebView 的 cookie 只有 API 能清，而扫码登录常常就走这条路，
        // 它既不在 cacheDir 也不在外部存储，所以"清缓存 + 重启"看起来毫无作用。
        credentialResetter.reset();
        // 爬虫兼容库 databases/tv 是 jar 唯一能写、又不会被任何文本清单扫到的位置：
        // 没有扩展名，也就不会出现在"最近修改的小文件"里。它由 JarLoader.ensureSpiderDb
        // 在下次加载 jar 时重建，删掉不丢应用自身数据（本 App 的库是 tvbox.v3.db）。
        deleteSpiderCompatDb();
        return failed;
    }

    private void deleteSpiderCompatDb() {
        if (databasesRoot == null) return;
        for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) {
            try {
                File file = new File(databasesRoot, "tv" + suffix);
                if (file.isFile()) file.delete();
            } catch (SecurityException ignored) {
                // 单个文件不可删不影响其它清理动作
            }
        }
    }

    private boolean safeImageCacheDirectory() throws IOException {
        if (internalRoot == null) throw new IOException("内部缓存目录不可用");
        File root = canonicalCacheRoot(internalRoot);
        File directory = new File(root, "image_http_cache");
        // A missing directory has no files to clear. In particular, do not create a new OkHttp
        // Cache against a dangling link or an unavailable cache root.
        if (!directory.exists()) return false;
        if (!directory.isDirectory() || !isContainedDirectChild(root, root, directory))
            throw new IOException("图片缓存目录不安全");
        return true;
    }

    private int deleteStaleFiles() {
        final int[] failures = {0};
        long staleBefore = System.currentTimeMillis() - STALE_AFTER_MS;
        walkBoth(staleBefore, (file, category) -> {
            if (!TEMP_FILES.equals(category)) return;
            try {
                // Recheck immediately before unlinking; the scan's path validation alone is
                // insufficient if another writer has replaced a directory since then.
                File parent = file.getParentFile();
                File root = rootFor(file);
                if (root == null || parent == null || !isContainedDirectChild(root, parent, file)
                        || (!file.delete() && file.exists())) failures[0]++;
            } catch (IOException | SecurityException error) {
                failures[0]++;
            }
        }, (top, external) -> {
            if (top == null || isSelectedTempRoot(external, top)) failures[0]++;
        });
        return failures[0];
    }

    private static boolean isSelectedTempRoot(boolean external, String top) {
        if (external) return "subscription_export".equals(top);
        return "image_viewer".equals(top) || "config_theme_exports".equals(top)
                || top.startsWith("lan_config_")
                || top.startsWith("mbox_config_") || top.startsWith("mbox_restore_")
                || top.startsWith("subscription_import_") || top.startsWith("log_export_")
                || top.startsWith("logcat_export_") || top.startsWith("theme_import_");
    }

    private File rootFor(File file) throws IOException {
        File internal = internalRoot == null ? null : canonicalCacheRoot(internalRoot);
        if (isWithin(file, internal)) return internal;
        File external = externalRoot == null ? null : canonicalCacheRoot(externalRoot);
        return isWithin(file, external) ? external : null;
    }

    private void walkBoth(long staleBefore, FileVisitor visitor, ScanError onError) {
        File internal = walkRoot(internalRoot, false, staleBefore, visitor, onError);
        if (externalRoot == null) return;
        try {
            if (internal != null && internal.equals(canonicalCacheRoot(externalRoot))) return;
        } catch (IOException | SecurityException error) {
            if (onError != null) onError.failed(null, true);
            return;
        }
        walkRoot(externalRoot, true, staleBefore, visitor, onError);
    }

    private File walkRoot(File root, boolean external, long staleBefore,
                          FileVisitor visitor, ScanError onError) {
        if (root == null || !root.exists()) return null;
        final File canonicalRoot;
        try {
            canonicalRoot = canonicalCacheRoot(root);
        } catch (IOException | SecurityException error) {
            if (onError != null) onError.failed(null, external);
            return null;
        }
        if (!root.isDirectory()) {
            if (onError != null) onError.failed(null, external);
            return canonicalRoot;
        }
        File[] children = root.listFiles();
        if (children == null) {
            if (onError != null) onError.failed(null, external);
            return canonicalRoot;
        }
        ArrayDeque<Node> pending = new ArrayDeque<>();
        for (File child : children) pending.add(new Node(child, root, child.getName(), 1));
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            try {
                if (!isContainedDirectChild(canonicalRoot, node.parent, node.file)) continue;
                if (node.file.isDirectory()) {
                    File[] nested = node.file.listFiles();
                    if (nested == null) {
                        if (onError != null) onError.failed(node.topName, external);
                        continue;
                    }
                    for (File child : nested)
                        pending.add(new Node(child, node.file, node.topName, node.depth + 1));
                } else if (node.file.isFile()) {
                    visitor.visit(node.file, classify(external, node.topName, node.depth,
                            node.file, staleBefore));
                }
            } catch (IOException | SecurityException error) {
                if (onError != null) onError.failed(node.topName, external);
            }
        }
        return canonicalRoot;
    }

    private static String classify(boolean external, String top, int depth,
                                   File file, long staleBefore) {
        if ("exo-video-cache".equals(top)) return PLAYBACK_CACHE;
        if (!external && "image_http_cache".equals(top)) return IMAGE_HTTP;
        if (!external && "image_viewer".equals(top) && depth == 2
                && file.getName().startsWith("img_") && isStale(file, staleBefore))
            return TEMP_FILES;
        if (isTransferFile(external, top, depth, file) && isStale(file, staleBefore))
            return TEMP_FILES;
        // Includes known playback/network/site data, recent work files, and unrecognized files.
        // All are counted, but none may be deleted by this catalog.
        return RUNTIME_DATA;
    }

    private static boolean isTransferFile(boolean external, String top, int depth, File file) {
        String name = file.getName();
        if (external) return "subscription_export".equals(top) && depth == 2
                && (name.endsWith(".json") || name.endsWith(".zip"));
        if ("config_theme_exports".equals(top)) return depth == 2
                && (name.endsWith(".json") || name.endsWith(".zip"));
        if (depth != 1) return false;
        if (name.endsWith(".txt"))
            return name.startsWith("log_export_") || name.startsWith("logcat_export_");
        if (name.startsWith("theme_import_"))
            return name.endsWith(".bin") || name.endsWith(".webp");
        if (!name.endsWith(".zip")) return false;
        return name.startsWith("lan_config_") || name.startsWith("mbox_config_")
                || name.startsWith("mbox_restore_") || name.startsWith("subscription_import_");
    }

    private static boolean isStale(File file, long cutoff) {
        long modified = file.lastModified();
        return modified > 0 && modified <= cutoff;
    }

    /** A node must be the literal child of its canonical parent, never a traversed symlink. */
    private static boolean isContainedDirectChild(File root, File parent, File child) throws IOException {
        String rootPath = root.getCanonicalPath();
        String parentPath = parent.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        if (!parentPath.equals(rootPath) && !parentPath.startsWith(rootPath + File.separator))
            return false;
        return childPath.equals(parentPath + File.separator + child.getName())
                && childPath.startsWith(rootPath + File.separator);
    }

    /** Parent aliases are normal on Android; the cache root itself must remain its parent's child. */
    private static File canonicalCacheRoot(File root) throws IOException {
        File parent = root.getAbsoluteFile().getParentFile();
        if (parent == null || !isContainedDirectChild(parent.getCanonicalFile(), parent, root))
            throw new IOException("缓存根目录不安全");
        return root.getCanonicalFile();
    }

    private static boolean isWithin(File file, File root) throws IOException {
        return root != null && file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? "请稍后重试" : message;
    }

    interface ImageCacheEvictor { void evict() throws IOException; }
    interface LogCacheAccess { long bytes(); boolean clear(); }
    interface PlaybackCacheEvictor { void evict(); }
    /** 清理 WebView 里的登录状态（cookie / localStorage）；单测注入空实现。 */
    interface CredentialResetter { void reset(); }
    private interface FileVisitor { void visit(File file, String category); }
    private interface ScanError { void failed(String topName, boolean external); }

    private static final class Definition {
        final String id, title, description;
        final boolean clearable;
        Definition(String id, String title, String description, boolean clearable) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.clearable = clearable;
        }
    }

    /** 诊断用目录：{@code label} 只用于在提示里标明这条落在哪。 */
    private static final class ProbeRoot {
        final String label;
        final File dir;
        /** 为 true 时第一层只进入约定目录（用于外部存储根，避免遍历整块存储）。 */
        final boolean knownDirsOnly;
        ProbeRoot(String label, File dir, boolean knownDirsOnly) {
            this.label = label;
            this.dir = dir;
            this.knownDirsOnly = knownDirsOnly;
        }
    }

    private static final class Node {
        final File file, parent;
        final String topName;
        final int depth;
        Node(File file, File parent, String topName, int depth) {
            this.file = file;
            this.parent = parent;
            this.topName = topName;
            this.depth = depth;
        }
    }

    public static final class Entry {
        public final String id, title, description;
        public final long sizeBytes;
        public final boolean clearable;
        /** 附加明细（如网盘凭据的文件清单）；只在有内容时非空。 */
        public final String detail;
        Entry(String id, String title, String description, long sizeBytes, boolean clearable) {
            this(id, title, description, sizeBytes, clearable, null);
        }
        Entry(String id, String title, String description, long sizeBytes, boolean clearable,
              String detail) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.sizeBytes = sizeBytes;
            this.clearable = clearable;
            this.detail = detail;
        }
    }

    private static final class CredentialScan {
        final long bytes;
        final String detail;
        CredentialScan(long bytes, String detail) {
            this.bytes = bytes;
            this.detail = detail;
        }
    }

    public static final class Snapshot {
        public final List<Entry> entries;
        public final long totalBytes, clearableBytes, protectedBytes;
        public final boolean logSizeUnavailable;
        Snapshot(List<Entry> entries, long totalBytes, long clearableBytes,
                 long protectedBytes, boolean logSizeUnavailable) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.totalBytes = totalBytes;
            this.clearableBytes = clearableBytes;
            this.protectedBytes = protectedBytes;
            this.logSizeUnavailable = logSizeUnavailable;
        }
    }

    public static final class ClearResult {
        public final Set<String> clearedIds;
        public final Map<String, String> failures;
        public final Snapshot after;
        ClearResult(Set<String> clearedIds, Map<String, String> failures, Snapshot after) {
            this.clearedIds = Collections.unmodifiableSet(new LinkedHashSet<>(clearedIds));
            this.failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
            this.after = after;
        }
    }
}

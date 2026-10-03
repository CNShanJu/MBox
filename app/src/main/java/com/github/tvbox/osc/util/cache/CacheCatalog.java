package com.github.tvbox.osc.util.cache;

import android.content.Context;

import com.github.tvbox.osc.util.OkGoHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App cache inventory and narrowly scoped cleanup. Both methods perform disk IO and must run on
 * a module-level background executor. Known source cache files in filesDir are counted as
 * protected data and are never deleted here.
 */
public final class CacheCatalog {
    public static final String IMAGE_HTTP = "image_http";
    public static final String TEMP_FILES = "temp_files";

    private static final String RUNTIME_DATA = "runtime_data";
    private static final long STALE_AFTER_MS = 24L * 60 * 60 * 1000;

    private static final Definition[] DEFINITIONS = {
            new Definition(IMAGE_HTTP, "图片缓存", "已浏览的海报和封面，清理后会重新加载", true),
            new Definition(TEMP_FILES, "遗留的副本", "保存图片、分享或导入导出后遗留的旧副本", true),
            new Definition(RUNTIME_DATA, "应用运行文件", "播放、站点等功能使用的文件，不可清理", false)
    };

    private final File internalRoot;
    private final File externalRoot;
    private final File filesRoot;
    private final ImageCacheEvictor imageCacheEvictor;

    public CacheCatalog(Context context) {
        Context app = context.getApplicationContext();
        internalRoot = app.getCacheDir();
        externalRoot = app.getExternalCacheDir();
        filesRoot = app.getFilesDir();
        imageCacheEvictor = OkGoHelper::evictImageDiskCache;
    }

    // Package-private seam for the file safety regression test. Production always uses the
    // owning OkHttp Cache API rather than deleting its live journal files.
    CacheCatalog(File internalRoot, File externalRoot, ImageCacheEvictor imageCacheEvictor) {
        this(internalRoot, externalRoot, null, imageCacheEvictor);
    }

    CacheCatalog(File internalRoot, File externalRoot, File filesRoot,
                 ImageCacheEvictor imageCacheEvictor) {
        this.internalRoot = internalRoot;
        this.externalRoot = externalRoot;
        this.filesRoot = filesRoot;
        this.imageCacheEvictor = imageCacheEvictor;
    }

    /** Logical bytes in both Android cache directories and protected source cache files. */
    public Snapshot scan() {
        final Map<String, Long> sizes = new LinkedHashMap<>();
        for (Definition definition : DEFINITIONS) sizes.put(definition.id, 0L);
        long staleBefore = System.currentTimeMillis() - STALE_AFTER_MS;
        walkBoth(staleBefore, (file, category) -> sizes.put(category,
                saturatedAdd(sizes.get(category), Math.max(0L, file.length()))), null);
        sizes.put(RUNTIME_DATA, saturatedAdd(sizes.get(RUNTIME_DATA), sourceCacheBytes()));

        List<Entry> entries = new ArrayList<>(DEFINITIONS.length);
        long total = 0L;
        long clearable = 0L;
        for (Definition definition : DEFINITIONS) {
            long size = sizes.get(definition.id);
            entries.add(new Entry(definition.id, definition.title, definition.description,
                    size, definition.clearable));
            total = saturatedAdd(total, size);
            if (definition.clearable) clearable = saturatedAdd(clearable, size);
        }
        return new Snapshot(entries, total, clearable, Math.max(0L, total - clearable));
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
            } else {
                failures.put(id == null ? "" : id, "此类数据不可清理");
            }
        }
        return new ClearResult(cleared, failures, scan());
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
        Entry(String id, String title, String description, long sizeBytes, boolean clearable) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.sizeBytes = sizeBytes;
            this.clearable = clearable;
        }
    }

    public static final class Snapshot {
        public final List<Entry> entries;
        public final long totalBytes, clearableBytes, protectedBytes;
        Snapshot(List<Entry> entries, long totalBytes, long clearableBytes, long protectedBytes) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.totalBytes = totalBytes;
            this.clearableBytes = clearableBytes;
            this.protectedBytes = protectedBytes;
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

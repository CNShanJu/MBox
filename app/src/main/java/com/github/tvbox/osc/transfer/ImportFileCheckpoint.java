package com.github.tvbox.osc.transfer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A bounded, private snapshot of the three directories that a configuration import can write.
 * The caller must serialize imports and other writers until the import succeeds or rollback ends.
 */
public final class ImportFileCheckpoint implements AutoCloseable {
    private static final long MAX_BACKUP_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_DEPTH = 64;
    private static final int BUFFER_SIZE = 64 * 1024;

    private final File workDirectory;
    private final List<RootSnapshot> roots;
    private final List<File> displacedDirectories = new ArrayList<>();
    private boolean rollbackFailed;
    private boolean rolledBack;
    private boolean closed;

    private ImportFileCheckpoint(File workDirectory, List<RootSnapshot> roots) {
        this.workDirectory = workDirectory;
        this.roots = roots;
    }

    /**
     * Capture the original trees before the first import write. Work directory must already exist
     * and belong exclusively to this import; cleanup removes the whole directory.
     */
    public static ImportFileCheckpoint prepare(File workDirectory, List<File> targetDirectories)
            throws IOException {
        if (workDirectory == null || targetDirectories == null || targetDirectories.size() > 3) {
            throw new IOException("无效的导入快照目录");
        }
        rejectDotSegments(workDirectory);
        requireExistingDirectory(workDirectory);
        File work = workDirectory.getCanonicalFile();
        File snapshot = new File(work, "file_checkpoint");
        if (snapshot.exists() || listedButMissing(snapshot) || !snapshot.mkdir()) {
            throw new IOException("无法创建独立的导入快照目录");
        }

        try {
            List<RootSnapshot> roots = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Budget budget = new Budget();
            for (int i = 0; i < targetDirectories.size(); i++) {
                File requested = targetDirectories.get(i);
                if (requested == null) throw new IOException("导入目标目录为空");
                rejectDotSegments(requested);
                String name = requested.getName();
                if (!("themes".equals(name) || "theme_bg".equals(name)
                        || "subscription_import".equals(name))) {
                    throw new IOException("不允许快照此导入目标: " + name);
                }
                File parent = requested.getAbsoluteFile().getParentFile();
                requireExistingDirectory(parent);
                File target = requested.getCanonicalFile();
                if (!seen.add(target.getPath()) || overlaps(work, target)) {
                    throw new IOException("导入快照与目标目录重叠或重复");
                }
                requireNoLink(requested);
                boolean existed = requested.exists();
                if (existed && !requested.isDirectory()) {
                    throw new IOException("导入目标不是目录: " + name);
                }
                File backup = new File(snapshot, "root_" + i);
                RootSnapshot root = new RootSnapshot(target, backup, existed);
                if (existed) {
                    scan(root, target, "", 0, budget);
                    createDirectory(backup);
                    for (Entry entry : root.entries) {
                        File destination = resolveChild(backup, entry.relative);
                        if (entry.directory) createDirectory(destination);
                        else copyFileAtomic(resolveChild(target, entry.relative), destination,
                                entry.length, backup);
                    }
                }
                roots.add(root);
            }
            return new ImportFileCheckpoint(work, roots);
        } catch (IOException | RuntimeException failure) {
            // Preparation has not touched live files; remove only our partial backup.
            try { deleteTree(snapshot, snapshot); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    /**
     * Restore original trees while retaining the snapshot. The caller should call cleanup only
     * after file, preferences, and database state have all been restored successfully.
     */
    public synchronized void rollback() throws IOException {
        if (closed) throw new IOException("导入快照已清理");
        if (rolledBack) return;
        rollbackFailed = true;
        List<RestorePlan> plans = new ArrayList<>();
        try {
            // Build every replacement first; a copy failure leaves all live targets untouched.
            for (RootSnapshot root : roots) {
                requireExistingDirectory(root.target.getParentFile());
                requireNoLink(root.target);
                if (root.target.exists()) inspectCurrentTree(root.target, root.target, 0);
                File staging = root.existed ? uniqueSibling(root.target, "restore") : null;
                // Register before copying so finally can remove a partially populated staging tree.
                plans.add(new RestorePlan(root, staging));
                if (staging != null) {
                    createDirectory(staging);
                    for (Entry entry : root.entries) {
                        File destination = resolveChild(staging, entry.relative);
                        if (entry.directory) createDirectory(destination);
                        else copyFileAtomic(resolveChild(root.backup, entry.relative), destination,
                                entry.length, staging);
                    }
                }
            }

            for (RestorePlan plan : plans) {
                File target = plan.root.target;
                if (target.exists()) {
                    File displaced = uniqueSibling(target, "displaced");
                    if (!target.renameTo(displaced)) {
                        throw new IOException("无法暂存当前导入目录: " + target.getName());
                    }
                    displacedDirectories.add(displaced);
                    plan.displaced = displaced;
                }
                if (plan.root.existed) {
                    if (!plan.staging.renameTo(target)) {
                        if (plan.displaced != null && !target.exists()) {
                            if (!plan.displaced.renameTo(target)) {
                                throw new IOException("恢复原目录失败，当前目录保留在: "
                                        + plan.displaced.getAbsolutePath());
                            }
                            displacedDirectories.remove(plan.displaced);
                        }
                        throw new IOException("无法恢复原导入目录: " + target.getName());
                    }
                    plan.staging = null;
                }
            }

            for (File displaced : new ArrayList<>(displacedDirectories)) {
                deleteTree(displaced, displaced);
                displacedDirectories.remove(displaced);
            }
            rolledBack = true;
            rollbackFailed = false;
        } catch (IOException failure) {
            throw failure;
        } finally {
            // Staging contains only copies. Never remove the original snapshot on failure.
            for (RestorePlan plan : plans) {
                if (plan.staging != null && plan.staging.exists()) {
                    try { deleteTree(plan.staging, plan.staging); }
                    catch (IOException ignored) { /* snapshot remains; retry uses a new staging path */ }
                }
            }
        }
    }

    /** Remove the checkpoint after a successful import or a complete rollback. */
    public synchronized void cleanup() throws IOException {
        if (closed) return;
        if (rollbackFailed) throw new IOException("回滚尚未完成，必须保留导入快照");
        if (workDirectory.exists()) deleteTree(workDirectory, workDirectory);
        closed = true;
    }

    @Override
    public void close() throws IOException {
        cleanup();
    }

    private static void scan(RootSnapshot root, File directory, String relative,
                             int depth, Budget budget) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("导入目录层级过深");
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("无法读取导入目录");
        for (File child : children) {
            requireNoLink(child);
            if (!within(root.target, child.getCanonicalFile())) {
                throw new IOException("导入目录路径越界");
            }
            if (++budget.entries > MAX_ENTRIES) throw new IOException("导入目录文件数过多");
            String childRelative = relative.isEmpty() ? child.getName()
                    : relative + File.separator + child.getName();
            if (child.isDirectory()) {
                root.entries.add(new Entry(childRelative, true, 0));
                scan(root, child, childRelative, depth + 1, budget);
            } else if (child.isFile()) {
                long length = child.length();
                if (length < 0 || length > MAX_BACKUP_BYTES - budget.bytes) {
                    throw new IOException("导入目录备份超过 512 MiB");
                }
                budget.bytes += length;
                root.entries.add(new Entry(childRelative, false, length));
            } else {
                throw new IOException("导入目录含不可读取的特殊文件");
            }
        }
    }

    private static void inspectCurrentTree(File root, File directory, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("当前导入目录层级过深");
        requireNoLink(directory);
        if (!within(root, directory.getCanonicalFile())) throw new IOException("当前导入目录路径越界");
        if (!directory.isDirectory()) return;
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("无法读取当前导入目录");
        for (File child : children) inspectCurrentTree(root, child, depth + 1);
    }

    private static void copyFileAtomic(File source, File destination, long expectedLength,
                                       File allowedRoot) throws IOException {
        requireNoLink(source);
        if (!source.isFile()) throw new IOException("导入快照源文件缺失");
        if (!within(allowedRoot.getCanonicalFile(), destination.getCanonicalFile())) {
            throw new IOException("导入快照写入路径越界");
        }
        createDirectory(destination.getParentFile());
        File temp = new File(destination.getParentFile(), destination.getName() + ".tmp-" + UUID.randomUUID());
        long total = 0;
        try {
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(temp)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > expectedLength) throw new IOException("快照时源文件发生变化");
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            if (total != expectedLength || source.length() != expectedLength) {
                throw new IOException("快照时源文件发生变化");
            }
            if (!temp.renameTo(destination)) throw new IOException("无法原子写入导入快照文件");
            temp = null;
        } finally {
            if (temp != null && temp.exists()) temp.delete();
        }
    }

    private static void deleteTree(File root, File item) throws IOException {
        requireNoLink(item);
        if (!within(root.getCanonicalFile(), item.getCanonicalFile())) {
            throw new IOException("清理目录路径越界");
        }
        if (item.isDirectory()) {
            File[] children = item.listFiles();
            if (children == null) throw new IOException("无法读取待清理目录");
            for (File child : children) deleteTree(root, child);
        }
        if (!item.delete()) throw new IOException("无法清理导入快照文件: " + item.getName());
    }

    private static File resolveChild(File root, String relative) throws IOException {
        File child = new File(root, relative);
        if (!within(root.getCanonicalFile(), child.getCanonicalFile())) {
            throw new IOException("导入快照路径越界");
        }
        return child;
    }

    private static File uniqueSibling(File target, String purpose) throws IOException {
        File parent = target.getParentFile();
        for (int attempt = 0; attempt < 5; attempt++) {
            File sibling = new File(parent, "." + target.getName() + "." + purpose
                    + "-" + UUID.randomUUID());
            if (!sibling.exists() && !listedButMissing(sibling)) return sibling;
        }
        throw new IOException("无法保留导入目录的临时路径");
    }

    private static void createDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建导入快照目录");
        }
        requireNoLink(directory);
    }

    private static void requireExistingDirectory(File directory) throws IOException {
        if (directory == null || !directory.isDirectory()) throw new IOException("导入快照父目录不可用");
        requireNoLink(directory);
    }

    private static void requireNoLink(File file) throws IOException {
        File absolute = file.getAbsoluteFile();
        File parent = absolute.getParentFile();
        if (parent == null) return;
        File expected = new File(parent.getCanonicalFile(), absolute.getName());
        if (!expected.equals(absolute.getCanonicalFile()) || listedButMissing(absolute)) {
            throw new IOException("导入目录包含符号链接或路径逃逸: " + absolute.getName());
        }
    }

    private static boolean listedButMissing(File file) throws IOException {
        if (file.exists()) return false;
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) return false;
        File[] children = parent.listFiles();
        if (children == null) throw new IOException("无法检查导入目录");
        for (File child : children) if (child.getName().equals(file.getName())) return true;
        return false;
    }

    private static void rejectDotSegments(File file) throws IOException {
        for (String segment : file.getPath().replace('\\', '/').split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IOException("导入目录包含路径逃逸");
            }
        }
    }

    private static boolean within(File root, File candidate) {
        String prefix = root.getPath() + File.separator;
        return candidate.equals(root) || candidate.getPath().startsWith(prefix);
    }

    private static boolean overlaps(File a, File b) {
        return within(a, b) || within(b, a);
    }

    private static final class RootSnapshot {
        final File target;
        final File backup;
        final boolean existed;
        final List<Entry> entries = new ArrayList<>();

        RootSnapshot(File target, File backup, boolean existed) {
            this.target = target;
            this.backup = backup;
            this.existed = existed;
        }
    }

    private static final class Entry {
        final String relative;
        final boolean directory;
        final long length;

        Entry(String relative, boolean directory, long length) {
            this.relative = relative;
            this.directory = directory;
            this.length = length;
        }
    }

    private static final class RestorePlan {
        final RootSnapshot root;
        File staging;
        File displaced;

        RestorePlan(RootSnapshot root, File staging) {
            this.root = root;
            this.staging = staging;
        }
    }

    private static final class Budget {
        int entries;
        long bytes;
    }
}

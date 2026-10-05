package com.github.tvbox.osc.transfer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** App-private, one-hour system snapshots made before restoring another backup. */
public final class SystemBackupStore {
    public static final long RETENTION_MILLIS = 60L * 60L * 1000L;
    private static final long MAX_ZIP_BYTES = 1024L * 1024L * 1024L;
    private static final String META_SUFFIX = ".meta";
    private static final Pattern SYSTEM_NAME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}(?:-(?:[2-9]|[1-9]\\d+))?-临时备份数据\\.zip");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Map<String, DirectoryState> STATES = new ConcurrentHashMap<>();

    public enum DeleteReason { EXPIRED, CONSUMED }

    /** Receives completed file operations; implementations should not throw. */
    public interface OperationListener {
        default void onSnapshotCreated(File file) { }
        default void onSnapshotReused(File file) { }
        default void onSnapshotDeleted(File file, DeleteReason reason) { }
        default void onSnapshotDeleteFailed(File file, DeleteReason reason, IOException error) { }
    }

    private static final OperationListener NO_OP_LISTENER = new OperationListener() { };

    private final File directory;
    private final DirectoryState state;
    private final OperationListener listener;

    public SystemBackupStore(File directory) throws IOException {
        this(directory, NO_OP_LISTENER);
    }

    public SystemBackupStore(File directory, OperationListener listener) throws IOException {
        if (directory == null) throw new IOException("系统备份目录为空");
        this.directory = directory.getCanonicalFile();
        state = STATES.computeIfAbsent(this.directory.getPath(), ignored -> new DirectoryState());
        this.listener = listener == null ? NO_OP_LISTENER : listener;
    }

    /** Keep the candidate with the caller; return a protected copy or a still-valid matching snapshot. */
    public File ensureSnapshot(File candidateZip, long nowMillis) throws IOException {
        requireTime(nowMillis);
        synchronized (state.lock) {
            String contentHash = BackupContentHash.sha256(candidateZip);
            String archiveHash = BackupContentHash.archiveSha256(candidateZip);
            ensureDirectory();
            for (Snapshot snapshot : snapshotsLocked(false)) {
                if (!snapshot.hash.equals(contentHash)) continue;
                try {
                    if (!snapshot.archiveHash.equals(BackupContentHash.archiveSha256(snapshot.file))) continue;
                    if (!contentHash.equals(BackupContentHash.sha256(snapshot.file))) continue;
                } catch (IOException corrupt) {
                    continue;
                }
                // A new restore attempt starts a fresh protection hour without changing the filename.
                writeMetadata(snapshot.file, nowMillis, contentHash, snapshot.archiveHash);
                Snapshot renewed = snapshotFor(snapshot.file, false);
                if (renewed == null || renewed.createdAt != nowMillis) {
                    throw new IOException("系统备份保护时间更新失败");
                }
                listener.onSnapshotReused(snapshot.file);
                return snapshot.file;
            }
            File saved = uniqueName(nowMillis);
            copyAtomic(candidateZip, saved);
            try {
                if (!archiveHash.equals(BackupContentHash.archiveSha256(saved))) {
                    throw new IOException("系统备份复制后校验失败");
                }
                writeMetadata(saved, nowMillis, contentHash, archiveHash);
                if (snapshotFor(saved, false) == null) throw new IOException("系统备份登记后校验失败");
            } catch (IOException failure) {
                File metadata = metadataFile(saved);
                if (metadata.exists() && !metadata.delete())
                    failure.addSuppressed(new IOException("无法清理未登记的系统备份元数据"));
                if (!saved.delete()) failure.addSuppressed(new IOException("无法清理未登记的系统备份"));
                throw failure;
            }
            listener.onSnapshotCreated(saved);
            return saved;
        }
    }

    /** Registered snapshots, newest protection time first. Call cleanupExpired before presenting the list. */
    public List<File> list() throws IOException {
        synchronized (state.lock) {
            ensureDirectory();
            List<Snapshot> snapshots = snapshotsLocked(true);
            snapshots.sort(Comparator.comparingLong((Snapshot snapshot) -> snapshot.createdAt)
                    .reversed().thenComparing(snapshot -> snapshot.file.getName()));
            List<File> files = new ArrayList<>(snapshots.size());
            for (Snapshot snapshot : snapshots) files.add(snapshot.file);
            return Collections.unmodifiableList(files);
        }
    }

    /** A backup-looking filename alone is never protected; its private metadata must be valid. */
    public boolean isProtected(File file) throws IOException {
        synchronized (state.lock) {
            return snapshotFor(file, true) != null;
        }
    }

    /** Hold a snapshot through a restore so concurrent expiry cleanup cannot remove its ZIP. */
    public Lease acquire(File file) throws IOException {
        synchronized (state.lock) {
            Snapshot snapshot = snapshotFor(file, true);
            if (snapshot == null) throw new IOException("系统备份不存在或已失去保护");
            state.acquire(snapshot.file);
            return new Lease(snapshot.file);
        }
    }

    /** Delete a successfully consumed system snapshot, including when the caller still holds its lease. */
    public boolean deleteConsumed(File file) throws IOException {
        synchronized (state.lock) {
            Snapshot snapshot = snapshotFor(file, true);
            if (snapshot == null) return false;
            try {
                deleteSnapshot(snapshot);
            } catch (IOException failure) {
                listener.onSnapshotDeleteFailed(snapshot.file, DeleteReason.CONSUMED, failure);
                throw failure;
            }
            listener.onSnapshotDeleted(snapshot.file, DeleteReason.CONSUMED);
            return true;
        }
    }

    /** Expiry is inclusive: a snapshot is removed at exactly one hour unless it has an active lease. */
    public int cleanupExpired(long nowMillis) throws IOException {
        requireTime(nowMillis);
        synchronized (state.lock) {
            ensureDirectory();
            return cleanupExpiredLocked(nowMillis);
        }
    }

    private int cleanupExpiredLocked(long nowMillis) throws IOException {
        int removed = 0;
        IOException failures = null;
        // The private registration, not ZIP integrity, decides what expiry may remove.
        // Corrupted registered archives must not remain forever just because list/acquire hide them.
        for (Snapshot snapshot : snapshotsLocked(false)) {
            if (!expired(snapshot.createdAt, nowMillis) || state.isLeased(snapshot.file)) continue;
            try {
                deleteSnapshot(snapshot);
                removed++;
                listener.onSnapshotDeleted(snapshot.file, DeleteReason.EXPIRED);
            } catch (IOException failure) {
                listener.onSnapshotDeleteFailed(snapshot.file, DeleteReason.EXPIRED, failure);
                if (failures == null) failures = failure;
                else failures.addSuppressed(failure);
            }
        }
        if (failures != null) throw failures;
        return removed;
    }

    private void deleteSnapshot(Snapshot snapshot) throws IOException {
        if (snapshot.file.exists() && !snapshot.file.delete()) {
            throw new IOException("无法删除系统备份: " + snapshot.file.getName());
        }
        File metadata = metadataFile(snapshot.file);
        if (metadata.exists() && !metadata.delete()) {
            throw new IOException("无法删除系统备份元数据: " + metadata.getName());
        }
    }

    private List<Snapshot> snapshotsLocked(boolean verifyArchive) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("无法读取系统备份目录");
        List<Snapshot> snapshots = new ArrayList<>();
        for (File entry : files) {
            if (!entry.isFile() || !entry.getName().endsWith(META_SUFFIX)) continue;
            String zipName = entry.getName().substring(0, entry.getName().length() - META_SUFFIX.length());
            if (!SYSTEM_NAME.matcher(zipName).matches()) continue;
            Snapshot snapshot = snapshotFor(new File(directory, zipName), verifyArchive);
            if (snapshot != null) snapshots.add(snapshot);
        }
        return snapshots;
    }

    private Snapshot snapshotFor(File requested, boolean verifyArchive) throws IOException {
        if (requested == null || !SYSTEM_NAME.matcher(requested.getName()).matches()) return null;
        File file = requested.getCanonicalFile();
        if (!directory.equals(file.getParentFile()) || !file.isFile()) return null;
        File meta = metadataFile(file);
        if (!meta.isFile() || meta.length() <= 0 || meta.length() > 4096) return null;
        Properties values = new Properties();
        try (InputStream input = new FileInputStream(meta)) {
            values.load(input);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        if (!"1".equals(values.getProperty("version"))) return null;
        String hash = values.getProperty("contentHash", "");
        String archiveHash = values.getProperty("archiveHash", "");
        if (!HASH.matcher(hash).matches() || !HASH.matcher(archiveHash).matches()) return null;
        try {
            long createdAt = Long.parseLong(values.getProperty("createdAt", ""));
            long length = Long.parseLong(values.getProperty("zipLength", ""));
            if (createdAt < 0 || length <= 0 || length > MAX_ZIP_BYTES) return null;
            if (verifyArchive && (length != file.length()
                    || !archiveHash.equals(BackupContentHash.archiveSha256(file)))) return null;
            return new Snapshot(file, createdAt, hash, archiveHash);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private void writeMetadata(File zip, long nowMillis, String hash, String archiveHash) throws IOException {
        Properties values = new Properties();
        values.setProperty("version", "1");
        values.setProperty("createdAt", Long.toString(nowMillis));
        values.setProperty("contentHash", hash);
        values.setProperty("archiveHash", archiveHash);
        values.setProperty("zipLength", Long.toString(zip.length()));
        File target = metadataFile(zip);
        File temporary = new File(directory, target.getName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                values.store(output, "MBox system restore snapshot");
                output.getFD().sync();
            }
            if (!temporary.renameTo(target)) {
                // Android renames over an existing file; the fallback supports Windows JVM tests.
                if (!target.exists() || !target.delete() || !temporary.renameTo(target)) {
                    throw new IOException("无法保存系统备份元数据");
                }
            }
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private File uniqueName(long nowMillis) throws IOException {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.ROOT)
                .format(new Date(nowMillis));
        for (int number = 1; number < 10_000; number++) {
            String suffix = number == 1 ? "" : "-" + number;
            File candidate = new File(directory, timestamp + suffix + "-临时备份数据.zip");
            if (!candidate.exists() && !metadataFile(candidate).exists()) return candidate;
        }
        throw new IOException("无法为系统备份生成唯一文件名");
    }

    private static void copyAtomic(File source, File target) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp-" + UUID.randomUUID());
        long total = 0;
        try {
            try (InputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_ZIP_BYTES) throw new IOException("系统备份过大");
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            if (total != source.length()) throw new IOException("备份源文件复制期间发生变化");
            if (!temporary.renameTo(target)) throw new IOException("无法保存系统备份");
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建系统备份目录");
        }
    }

    private static File metadataFile(File zip) { return new File(zip.getParentFile(), zip.getName() + META_SUFFIX); }

    private static boolean expired(long createdAt, long nowMillis) {
        return nowMillis >= createdAt && nowMillis - createdAt >= RETENTION_MILLIS;
    }

    private static void requireTime(long nowMillis) throws IOException {
        if (nowMillis < 0) throw new IOException("系统备份时间无效");
    }

    public final class Lease implements AutoCloseable {
        private final File file;
        private boolean closed;

        private Lease(File file) { this.file = file; }

        public File file() { return file; }

        @Override public void close() {
            synchronized (state.lock) {
                if (closed) return;
                state.release(file);
                closed = true;
            }
        }
    }

    private static final class Snapshot {
        final File file;
        final long createdAt;
        final String hash;
        final String archiveHash;

        Snapshot(File file, long createdAt, String hash, String archiveHash) {
            this.file = file;
            this.createdAt = createdAt;
            this.hash = hash;
            this.archiveHash = archiveHash;
        }
    }

    private static final class DirectoryState {
        final Object lock = new Object();
        final Map<String, Integer> leases = new HashMap<>();

        void acquire(File file) { leases.put(file.getPath(), leases.getOrDefault(file.getPath(), 0) + 1); }

        void release(File file) {
            Integer count = leases.get(file.getPath());
            if (count == null || count <= 1) leases.remove(file.getPath());
            else leases.put(file.getPath(), count - 1);
        }

        boolean isLeased(File file) { return leases.containsKey(file.getPath()); }
    }
}

package com.github.tvbox.osc.transfer;

import android.content.Context;
import android.os.Environment;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.share.domain.TransferHook;
import com.github.tvbox.osc.share.domain.TransferSession;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Local manual backups and protected pre-restore snapshots. Invoke on the shared background executor. */
public final class LocalBackupRepository {
    private static final Object OPERATIONS_LOCK = new Object();
    private static final String SYSTEM_DIRECTORY = "system_restore_backups";
    private final Context context;
    private final BackupArchive archive;
    private SystemBackupStore systemBackups;

    public LocalBackupRepository(Context context) {
        this.context = context.getApplicationContext();
        archive = new BackupArchive(this.context);
    }

    public List<LocalBackupEntry> list() throws Exception {
        synchronized (OPERATIONS_LOCK) {
            List<LocalBackupEntry> result = new ArrayList<>();
            File[] manual = manualDirectory().listFiles();
            if (manual != null) for (File file : manual) {
                if (file.isDirectory() || file.isFile() && file.getName().endsWith(".zip"))
                    result.add(new LocalBackupEntry("manual/" + file.getName(), file.getName(), false));
            }
            for (File file : systemBackups().list())
                result.add(new LocalBackupEntry("system/" + file.getName(), file.getName(), true));
            result.sort(Comparator.comparing(LocalBackupEntry::name).reversed());
            return result;
        }
    }

    public File createManual() throws Exception {
        synchronized (OPERATIONS_LOCK) {
            try {
                File saved = archive.create(manualDirectory());
                LogStore.success(Category.SYSTEM, "本地备份: 创建手动备份成功 " + saved.getName());
                return saved;
            } catch (Exception failure) {
                LogStore.fail(Category.SYSTEM, "本地备份: 创建手动备份失败，原因=" + failureMessage(failure));
                throw failure;
            } finally { flushOperationLogs(); }
        }
    }

    public String restore(LocalBackupEntry entry) throws Exception {
        synchronized (OPERATIONS_LOCK) {
            try { return restoreLocked(entry); }
            finally { flushOperationLogs(); }
        }
    }

    private String restoreLocked(LocalBackupEntry entry) throws Exception {
        File selected = selected(entry);
        SystemBackupStore store = systemBackups();
        // A selected system backup must remain readable while a new safety snapshot is made.
        try (SystemBackupStore.Lease selectedLease = entry.isSystem() ? store.acquire(selected) : null) {
            RestoreProtection protection = new RestoreProtection();
            try {
                // 定制化钩子：通用还原流程前先做 1 小时保护快照。整机一点可备份数据都没有时不做
                // (空壳无需保护,否则还原永远被"临时备份"卡住);只要有任何数据(设置/历史/收藏/
                // 搜索记录/订阅/背景配置等)就必须先保护再还原。
                TransferHook protect = new TransferHook() {
                    @Override public void before(TransferSession session) throws IOException {
                        if (!BackupModules.INSTANCE.anyHasData(context)) {
                            // 空机:无任何可备份数据,无需保护快照
                            return;
                        }
                        try {
                            File snapshot = preserveCurrent();
                            protection.snapshot = snapshot;
                            protection.lease = store.acquire(snapshot);
                        } catch (IOException snapshotFailure) {
                            // 保护快照创建失败(非空机):与"空机无快照"区分开,避免排查误导
                            LogStore.fail(Category.SYSTEM, "本地备份: 保护快照创建失败，还原中止 "
                                    + failureMessage(snapshotFailure));
                            throw snapshotFailure;
                        }
                    }
                };
                String result;
                try {
                    result = archive.restore(selected, protect);
                } catch (Exception failure) {
                    if (protection.snapshot == null) {
                        LogStore.fail(Category.SYSTEM, "本地备份: 还原失败(空机无保护快照) "
                                + failureMessage(failure));
                        throw new IOException("还原失败：" + failure.getMessage(), failure);
                    }
                    // 边界:若当前数据与所选系统备份内容一致,保护快照=待还原文件本身,回滚落空(内容不变,可接受)
                    boolean rolledBack = false;
                    try {
                        archive.restore(protection.snapshot, null);
                        rolledBack = true;
                    } catch (Exception recoveryFailure) { failure.addSuppressed(recoveryFailure); }
                    LogStore.fail(Category.SYSTEM, rolledBack ? "本地备份: 还原失败，已回滚当前数据"
                            : "本地备份: 还原失败，自动回滚未完成，保留临时备份 " + protection.snapshot.getName());
                    throw new IOException(rolledBack ? "还原失败，已恢复还原前的数据：" + failure.getMessage()
                            : "还原失败，自动回滚未完成；请使用临时备份回滚：" + failure.getMessage(), failure);
                }
                if (entry.isSystem()) {
                    try {
                        if (!store.deleteConsumed(selected))
                            LogStore.fail(Category.SYSTEM, "本地备份: 使用后的临时备份未能删除 " + selected.getName());
                    } catch (IOException cleanupFailure) {
                        // Restoration has committed; cleanup must not turn it into a failed restore.
                        LogStore.fail(Category.SYSTEM, "本地备份: 使用后的临时备份清理失败 " + selected.getName());
                    }
                }
                LogStore.success(Category.SYSTEM, "本地备份: 还原成功 " + entry.name());
                return result;
            } finally {
                // 保护快照的租约覆盖整个还原(含失败回滚)：过期清理不能在还原途中删掉它。
                if (protection.lease != null) protection.lease.close();
            }
        }
    }

    /** 还原前的保护快照句柄：由 TransferHook.before 创建，失败回滚与租约释放都要用到。 */
    private static final class RestoreProtection {
        File snapshot;
        SystemBackupStore.Lease lease;
    }

    private File preserveCurrent() throws IOException {
        File staging = new File(context.getCacheDir(), "pre_restore_" + java.util.UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("无法准备临时备份目录");
        File candidate = null;
        try {
            candidate = archive.create(staging);
            return systemBackups().ensureSnapshot(candidate, System.currentTimeMillis());
        } finally {
            if (candidate != null) candidate.delete();
            staging.delete();
        }
    }

    public void delete(LocalBackupEntry entry) throws Exception {
        synchronized (OPERATIONS_LOCK) {
            try {
                if (entry == null || entry.isSystem()) throw new IOException("系统临时备份不能手动删除");
                File file = selected(entry);
                deleteTree(file, file);
                LogStore.success(Category.SYSTEM, "本地备份: 删除手动备份成功 " + entry.name());
            } catch (Exception failure) {
                LogStore.fail(Category.SYSTEM, "本地备份: 删除备份失败 " + (entry == null ? "" : entry.name())
                        + "，原因=" + failureMessage(failure));
                throw failure;
            } finally { flushOperationLogs(); }
        }
    }

    public void cleanupExpired() throws Exception {
        synchronized (OPERATIONS_LOCK) {
            try { systemBackups().cleanupExpired(System.currentTimeMillis()); }
            finally { flushOperationLogs(); }
        }
    }

    /** Called after the startup home frame; only the shared executor touches backup files. */
    public static void cleanupOnStartup(Context context) {
        Context application = context.getApplicationContext();
        com.github.tvbox.osc.util.HeavyTaskUtil.getSerialExecutorService().execute(() -> {
            try { new LocalBackupRepository(application).cleanupExpired(); }
            catch (Exception failure) {
                LogStore.fail(Category.SYSTEM, "本地备份: 启动清理临时备份失败，原因=" + failure.getClass().getSimpleName());
            }
        });
    }

    private File selected(LocalBackupEntry entry) throws IOException {
        if (entry == null || entry.name() == null || entry.name().isEmpty()
                || entry.name().contains("/") || entry.name().contains("\\")
                || ".".equals(entry.name()) || "..".equals(entry.name())) throw new IOException("备份名称无效");
        String prefix = entry.isSystem() ? "system/" : "manual/";
        if (!(prefix + entry.name()).equals(entry.key())) throw new IOException("备份标识无效");
        File directory = (entry.isSystem() ? new File(context.getFilesDir(), SYSTEM_DIRECTORY)
                : manualDirectory()).getCanonicalFile();
        File file = new File(directory, entry.name()).getCanonicalFile();
        if (!directory.equals(file.getParentFile()) || !file.exists()) throw new IOException("备份不存在或路径无效");
        if (entry.isSystem() && !systemBackups().isProtected(file)) throw new IOException("系统临时备份校验失败");
        return file;
    }

    /** Construct the filesystem store only on a background operation, never while opening UI. */
    private SystemBackupStore systemBackups() throws IOException {
        if (systemBackups == null)
            systemBackups = new SystemBackupStore(new File(context.getFilesDir(), SYSTEM_DIRECTORY),
                    new SystemBackupStore.OperationListener() {
                        @Override public void onSnapshotCreated(File file) {
                            LogStore.success(Category.SYSTEM, "本地备份: 创建系统临时备份成功 " + file.getName());
                        }
                        @Override public void onSnapshotReused(File file) {
                            LogStore.success(Category.SYSTEM, "本地备份: 复用系统临时备份并更新保护时间 " + file.getName());
                        }
                        @Override public void onSnapshotDeleted(File file, SystemBackupStore.DeleteReason reason) {
                            LogStore.success(Category.SYSTEM, "本地备份: 删除系统临时备份成功 " + file.getName()
                                    + "，原因=" + deletionReason(reason));
                        }
                        @Override public void onSnapshotDeleteFailed(File file,
                                SystemBackupStore.DeleteReason reason, IOException failure) {
                            LogStore.fail(Category.SYSTEM, "本地备份: 删除系统临时备份未完成 " + file.getName()
                                    + "，触发原因=" + deletionReason(reason) + "，失败原因=" + failureMessage(failure));
                        }
                    });
        return systemBackups;
    }

    private static String deletionReason(SystemBackupStore.DeleteReason reason) {
        return reason == SystemBackupStore.DeleteReason.CONSUMED ? "用户还原成功" : "保留已满一小时，启动清理";
    }

    private static String failureMessage(Exception failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /** Complete the accepted business logs on the worker before a caller can restart the process. */
    private static void flushOperationLogs() {
        try {
            if (!LogStore.get().flushPendingBlocking(2_000L)) {
                LogStore.fail(Category.SYSTEM, "本地备份: 操作已结束，业务日志写入未完成");
                LogStore.get().flushPendingBlocking(2_000L);
            }
        } catch (Throwable ignored) { /* Logging failure must not change an already committed restore. */ }
    }

    private static File manualDirectory() {
        return new File(Environment.getExternalStorageDirectory(), "tvbox_backup");
    }

    private static void deleteTree(File root, File file) throws IOException {
        File canonical = file.getCanonicalFile();
        if (!canonical.equals(file.getAbsoluteFile()) || !(canonical.equals(root)
                || canonical.getPath().startsWith(root.getPath() + File.separator)))
            throw new IOException("备份目录包含路径逃逸");
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (file.isDirectory() && children == null) throw new IOException("无法读取备份目录");
        if (children != null) for (File child : children) deleteTree(root, child);
        if (!file.delete()) throw new IOException("删除备份失败");
    }
}

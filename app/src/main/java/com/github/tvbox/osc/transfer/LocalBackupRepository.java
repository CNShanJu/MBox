package com.github.tvbox.osc.transfer;

import android.content.Context;

import com.github.catvod.utils.Path;
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

    /** 手动备份结果：{@link #reused}=true 表示同一份数据已经备份过，没有产生新文件。 */
    public static final class ManualResult {
        private final LocalBackupEntry entry;
        private final boolean reused;

        ManualResult(LocalBackupEntry entry, boolean reused) {
            this.entry = entry;
            this.reused = reused;
        }

        public LocalBackupEntry entry() { return entry; }
        public boolean isReused() { return reused; }
    }

    public List<LocalBackupEntry> list() throws Exception {
        synchronized (OPERATIONS_LOCK) {
            List<LocalBackupEntry> result = new ArrayList<>();
            File[] manual = manualDirectory().listFiles();
            if (manual != null) for (File file : manual) {
                if (!file.isFile()) continue;
                if (file.getName().endsWith(BackupNaming.TEMP_SUFFIX)) {
                    // 未走完 rename 的导出产物：绝不可能是有效备份，顺手清掉（进程被杀会留下）
                    file.delete();
                    continue;
                }
                if (!BackupNaming.isBackup(file)) continue;
                result.add(new LocalBackupEntry(file.getName(), BackupNaming.displayName(file),
                        false, file.lastModified()));
            }
            for (File file : systemBackups().list())
                result.add(new LocalBackupEntry(file.getName(), file.getName(), true, file.lastModified()));
            // 按写入时间倒序：改过名的备份不会因此跳到列表别处
            result.sort(Comparator.comparingLong(LocalBackupEntry::createdAt).reversed());
            return result;
        }
    }

    /**
     * 新建手动备份。
     *
     * <p>文件名取数据内容摘要（见 {@link BackupNaming}），所以<b>同一份数据只会有一个包</b>：
     * 重复点"立即备份"时目标文件已存在，直接复用并告诉用户数据没变，不再产生重复备份。
     *
     * @param requestedName 用户输入的名字；{@code useTimestamp} 为 true 时忽略它，改用系统时间
     */
    public ManualResult createManual(String requestedName, boolean useTimestamp) throws Exception {
        synchronized (OPERATIONS_LOCK) {
            File staged = null;
            try {
                long now = System.currentTimeMillis();
                String name = useTimestamp ? BackupNaming.timeName(now) : BackupNaming.sanitize(requestedName);
                if (name.isEmpty()) throw new IOException("请先给备份起个名字");
                File directory = manualDirectory();
                staged = archive.createStaged(directory);
                File target = new File(directory, BackupNaming.fileName(BackupContentHash.sha256(staged)));
                if (target.exists()) {
                    String existing = BackupNaming.displayName(target);
                    LogStore.success(Category.SYSTEM, "本地备份: 数据未变化，复用已有备份 " + target.getName()
                            + "「" + existing + "」");
                    return new ManualResult(new LocalBackupEntry(target.getName(), existing, false,
                            target.lastModified()), true);
                }
                if (!staged.renameTo(target)) throw new IOException("备份文件落盘失败");
                staged = null;
                BackupNaming.writeName(target, name);
                LogStore.success(Category.SYSTEM, "本地备份: 创建手动备份成功 " + target.getName() + "「" + name + "」");
                return new ManualResult(new LocalBackupEntry(target.getName(), name, false,
                        target.lastModified()), false);
            } catch (Exception failure) {
                LogStore.fail(Category.SYSTEM, "本地备份: 创建手动备份失败，原因=" + failureMessage(failure));
                throw failure;
            } finally {
                if (staged != null) staged.delete();
                flushOperationLogs();
            }
        }
    }

    /** 只改展示名：文件与内容都不动，还原链路不受影响。 */
    public String rename(LocalBackupEntry entry, String requestedName) throws Exception {
        synchronized (OPERATIONS_LOCK) {
            try {
                if (entry == null || entry.isSystem()) throw new IOException("系统临时备份不能改名");
                String name = BackupNaming.sanitize(requestedName);
                if (name.isEmpty()) throw new IOException("备份名称不能为空");
                File file = selected(entry);
                BackupNaming.writeName(file, name);
                LogStore.success(Category.SYSTEM, "本地备份: 修改备份名称成功 " + file.getName() + "「" + name + "」");
                return name;
            } catch (Exception failure) {
                LogStore.fail(Category.SYSTEM, "本地备份: 修改备份名称失败 "
                        + (entry == null ? "" : entry.fileName()) + "，原因=" + failureMessage(failure));
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

    /**
     * 用系统文件管理里选中的外部 ZIP 还原（用户自己挑的备份包,可能来自 U 盘/下载目录/别的机器）。
     *
     * <p>与本地备份的差别只有"它不在本机备份目录里":没有系统临时备份的身份,所以不做租约、
     * 也不在还原成功后删除它;"还原前先做保护快照 + 失败自动回滚"这套保护完全一致。
     */
    public String restoreExternal(android.net.Uri source) throws Exception {
        synchronized (OPERATIONS_LOCK) {
            String label = displayName(source);
            try {
                String result = restoreProtected(hook -> archive.restore(source, hook));
                LogStore.success(Category.SYSTEM, "本地备份: 外部备份还原成功 " + label);
                return result;
            } catch (Exception failure) {
                LogStore.fail(Category.SYSTEM, "本地备份: 外部备份还原失败 " + label
                        + "，原因=" + failureMessage(failure));
                throw failure;
            } finally { flushOperationLogs(); }
        }
    }

    private String restoreLocked(LocalBackupEntry entry) throws Exception {
        File selected = selected(entry);
        SystemBackupStore store = systemBackups();
        // A selected system backup must remain readable while a new safety snapshot is made.
        try (SystemBackupStore.Lease selectedLease = entry.isSystem() ? store.acquire(selected) : null) {
            String result = restoreProtected(hook -> archive.restore(selected, hook));
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
        }
    }

    /** 一次"归档还原"动作；本地条目与外部 ZIP 共用同一套保护/回滚流程,只是数据来源不同。 */
    private interface ArchiveRestore {
        String restore(TransferHook hook) throws Exception;
    }

    /**
     * 还原前先做 1 小时保护快照,失败自动回滚。
     *
     * <p>整机一点可备份数据都没有时不做快照(空壳无需保护,否则还原永远被"临时备份"卡住);
     * 只要有任何数据(设置/历史/收藏/搜索记录/订阅/背景配置等)就必须先保护再还原。
     */
    private String restoreProtected(ArchiveRestore action) throws Exception {
        RestoreProtection protection = new RestoreProtection();
        SystemBackupStore store = systemBackups();
        try {
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
            try {
                return action.restore(protect);
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
        } finally {
            // 保护快照的租约覆盖整个还原(含失败回滚)：过期清理不能在还原途中删掉它。
            if (protection.lease != null) protection.lease.close();
        }
    }

    /** 只用于日志与提示：文件管理里显示的名字,取不到就退回 uri 末段。 */
    private String displayName(android.net.Uri source) {
        if (source == null) return "所选文件";
        try (android.database.Cursor cursor = context.getContentResolver().query(source,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name.trim();
            }
        } catch (Exception ignored) { /* 名称只是展示信息,取不到不影响还原 */ }
        String fallback = source.getLastPathSegment();
        return fallback == null || fallback.trim().isEmpty() ? "所选文件" : fallback;
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
                // 名字侧车跟着备份一起走：留下它只会变成永远对不上号的孤儿文件
                BackupNaming.deleteName(file);
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
        // 路径只认真实文件名（展示名是用户随手改的，不能进路径）
        String fileName = entry == null ? null : entry.fileName();
        if (fileName == null || fileName.isEmpty()
                || fileName.contains("/") || fileName.contains("\\")
                || ".".equals(fileName) || "..".equals(fileName)) throw new IOException("备份名称无效");
        String prefix = entry.isSystem() ? "system/" : "manual/";
        if (!(prefix + fileName).equals(entry.key())) throw new IOException("备份标识无效");
        File directory = (entry.isSystem() ? new File(context.getFilesDir(), SYSTEM_DIRECTORY)
                : manualDirectory()).getCanonicalFile();
        File file = new File(directory, fileName).getCanonicalFile();
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

    /**
     * 手动备份目录：{@code /sdcard/MBox/backup}。
     * <p>以前直接放在存储根的 {@code tvbox_backup/}，和 App 的其它数据（MBox/、TVBox/）平铺在一起；
     * 现在统一收进 App 根目录下。创建交给 {@code BackupArchive.create} 里的 ensureDirectory。
     */
    private static File manualDirectory() {
        return Path.backup();
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

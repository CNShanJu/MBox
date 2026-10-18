package com.github.tvbox.osc.share.domain;

import android.content.Context;

import com.github.tvbox.osc.config.PrefsDataStore;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.storage.theme.ThemeBackgroundLibrary;
import com.github.tvbox.osc.storage.theme.ThemeStore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 默认导入保护：快照三个可写目录（主题 / 主题背景 / 订阅本地文件）+ Room 串行线程、主题存储锁、
 * 订阅文件锁 + Prefs 回滚 + Room 事务。失败时回滚文件快照与偏好/数据库，成功时清理快照。
 *
 * <p>对应原配置导入的 applyWithRollback 语义，下沉到 share 作为 {@link ImportProtection}
 * 的默认实现；下游可用自己的保护替换（{@link DataTransferHub#setProtection}）。
 */
public final class DefaultImportProtection implements ImportProtection {

    private final Context context;

    public DefaultImportProtection(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 注意:本默认保护把导入动作包进 Room 事务(见 {@link #protectedRun} 的 runInTransaction)。
     * room 域(整库替换:close 库 + rename 换文件)不能在事务内替换库文件,语义未定义;
     * 该域必须用独立保护实现({@link DataTransferHub#setProtection}),不能用本事务型默认保护。
     */
    @Override
    public List<String> runProtected(TransferSession session, ImportAction action) throws IOException {
        if (session != null && session.domains().contains("room")) {
            throw new IOException("room 域需专用导入保护(整库替换不能在事务内执行)");
        }
        try {
            return AppDataManager.runOnDb(() -> ThemeStore.runWithStorageLock(() ->
                    SubscriptionImportFiles.runLocked(() -> protectedRun(session, action))));
        } catch (RuntimeException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw error;
        }
    }

    private List<String> protectedRun(TransferSession session, ImportAction action) throws IOException {
        List<File> directories = snapshotDirectories(session.domains());
        // 与解包目录分开：恢复失败时保留快照，不能被调用方的 finally 删除。
        File rollbackDirectory = new File(context.getFilesDir(), "config_rollback_" + UUID.randomUUID());
        if (!rollbackDirectory.mkdir()) throw new IOException("无法准备导入回滚目录");
        ImportFileCheckpoint checkpoint;
        try {
            checkpoint = ImportFileCheckpoint.prepare(rollbackDirectory, directories);
        } catch (IOException | RuntimeException preparationFailure) {
            if (!rollbackDirectory.delete())
                LogStore.fail(Category.SYSTEM, "配置导入: 未完成的快照目录清理失败，目录=" + rollbackDirectory.getName());
            throw preparationFailure;
        }
        List<String> result;
        try {
            // 偏好作用域包住 Room 的提交/结束：任一步失败都恢复原键值，Room 回滚历史。
            result = PrefsDataStore.runWithRollback(() -> AppDataManager.runInTransaction(action::run));
        } catch (Throwable failure) {
            try {
                checkpoint.rollback();
            } catch (Throwable restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            try {
                ThemeStore.reload();
                SystemConfig.notifyRestored();
            } catch (Throwable restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            boolean restored = !hasRecoveryFailure(failure);
            if (restored) {
                try {
                    checkpoint.cleanup();
                } catch (IOException cleanupFailure) {
                    LogStore.fail(Category.SYSTEM, "配置导入: 回滚完成后的快照清理失败，目录=" + rollbackDirectory.getName());
                }
            }
            LogStore.fail(Category.SYSTEM, restored ? "配置导入: 失败，已恢复导入前的数据"
                    : "配置导入: 失败，恢复未完成，文件快照=" + rollbackDirectory.getName());
            throw new IOException(restored ? "导入失败，已恢复导入前的记录和配置：" + failure.getMessage()
                    : "导入失败，恢复未完成，请勿继续导入；保留的恢复快照为 "
                    + rollbackDirectory.getName() + "：" + failure.getMessage(), failure);
        }
        // 数据已成功提交；临时快照清理失败只留日志，不改报导入失败。
        try {
            checkpoint.cleanup();
        } catch (IOException cleanupFailure) {
            LogStore.fail(Category.SYSTEM, "配置导入: 成功后的回滚快照清理失败，目录=" + rollbackDirectory.getName());
        }
        LogStore.log(Category.SYSTEM, "配置导入: 所选类别全部完成并落盘");
        return result;
    }

    /** 配置导入可写的三个目录；目录名由 ImportFileCheckpoint 白名单约束。 */
    private List<File> snapshotDirectories(Set<String> domains) throws IOException {
        List<File> directories = new ArrayList<>();
        if (domains.contains("themes")) {
            directories.add(new File(context.getFilesDir(), ThemeStore.DIR_THEMES));
            directories.add(new File(context.getFilesDir(), ThemeBackgroundLibrary.DIR));
        }
        if (domains.contains("subscriptions")) {
            File external = context.getExternalFilesDir(null);
            if (external == null) throw new IOException("应用存储目录不可用");
            directories.add(new File(external, "subscription_import"));
        }
        return directories;
    }

    private static boolean hasRecoveryFailure(Throwable failure) {
        if (failure.getSuppressed().length > 0 || failure instanceof AppDataManager.TransactionCompletionException)
            return true;
        Throwable cause = failure.getCause();
        return cause != null && cause != failure && hasRecoveryFailure(cause);
    }
}

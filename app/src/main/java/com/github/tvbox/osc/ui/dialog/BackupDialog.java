package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.AppUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.transfer.LocalBackupEntry;
import com.github.tvbox.osc.transfer.LocalBackupRepository;
import com.github.tvbox.osc.ui.adapter.LocalBackupAdapter;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.HeavyTaskUtil;
import com.lxj.xpopup.core.BasePopupView;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

/** Local backup list and actions; file and database work belongs to LocalBackupRepository. */
public class BackupDialog extends AppBottomPopupView {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String SYSTEM_BACKUP_EXPLANATION =
            "系统在使用本地备份前，会先保存当前设置和历史记录，日志独立保留。" +
            "如果还原后的数据不符合预期，可使用这份临时备份恢复到还原前。\n\n" +
            "临时备份不能手动删除；主动使用它恢复成功后会自动移除。" +
            "若一直未使用，保存满 1 小时后的下次启动会自动清理。";

    private final LocalBackupRepository repository;
    private LocalBackupAdapter adapter;
    private boolean busy;

    public BackupDialog(@NonNull @NotNull Context context) {
        super(context);
        repository = new LocalBackupRepository(context.getApplicationContext());
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_backup;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        TvRecyclerView list = findViewById(R.id.list);
        adapter = new LocalBackupAdapter();
        list.setAdapter(adapter);
        adapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter clicked, View view, int position) {
                LocalBackupEntry entry = adapter.getItem(position);
                if (entry == null) return;
                if (view.getId() == R.id.tvName) {
                    restore(entry);
                } else if (view.getId() == R.id.ivAction) {
                    if (entry.isSystem()) showSystemBackupExplanation();
                    else confirmDelete(entry);
                }
            }
        });
        findViewById(R.id.backupNow).setOnClickListener(view -> createManual());
        list.post(this::refreshBackups);
    }

    @Override
    public BasePopupView show() {
        if (popupInfo == null) return DialogCoordinator.bottom(getContext(), this, -1).show();
        return super.show();
    }

    private void showSystemBackupExplanation() {
        new TextTipDialog(getContext(), "系统临时备份", SYSTEM_BACKUP_EXPLANATION).show();
    }

    private void refreshBackups() {
        WeakReference<BackupDialog> dialogRef = new WeakReference<>(this);
        LocalBackupRepository source = repository;
        HeavyTaskUtil.getSerialExecutorService().execute(() -> {
            List<LocalBackupEntry> entries = null;
            String error = null;
            try { entries = source.list(); }
            catch (Exception failure) { error = failureMessage(failure); }
            List<LocalBackupEntry> loaded = entries;
            String message = error;
            MAIN.post(() -> {
                BackupDialog dialog = dialogRef.get();
                if (dialog == null || !dialog.isAttachedToWindow()) return;
                if (message != null) {
                    AppBubble.toast("读取备份失败：" + message);
                    return;
                }
                dialog.adapter.setNewData(loaded);
            });
        });
    }

    private void createManual() {
        if (busy) return;
        busy = true;
        WeakReference<BackupDialog> dialogRef = new WeakReference<>(this);
        LocalBackupRepository source = repository;
        HeavyTaskUtil.getSerialExecutorService().execute(() -> {
            File created = null;
            String error = null;
            try { created = source.createManual(); }
            catch (Exception failure) { error = failureMessage(failure); }
            if (error == null && created == null) error = "未生成备份文件";
            File result = created;
            String message = error;
            MAIN.post(() -> {
                BackupDialog dialog = dialogRef.get();
                if (dialog != null) dialog.busy = false;
                if (message != null) AppBubble.toast("备份失败：" + message);
                else AppBubble.toast("备份完成：" + result.getName());
                if (dialog != null && dialog.isAttachedToWindow()) dialog.refreshBackups();
            });
        });
    }

    private void confirmDelete(LocalBackupEntry entry) {
        if (busy || entry.isSystem()) return;
        ConfirmDialog.showDanger(getContext(), "删除备份",
                "确定删除备份「" + entry.name() + "」吗？", "删除", () -> delete(entry));
    }

    private void delete(LocalBackupEntry entry) {
        if (busy || entry.isSystem()) return;
        busy = true;
        WeakReference<BackupDialog> dialogRef = new WeakReference<>(this);
        LocalBackupRepository source = repository;
        HeavyTaskUtil.getSerialExecutorService().execute(() -> {
            String error = null;
            try { source.delete(entry); }
            catch (Exception failure) { error = failureMessage(failure); }
            String message = error;
            MAIN.post(() -> {
                BackupDialog dialog = dialogRef.get();
                if (dialog != null) dialog.busy = false;
                AppBubble.toast(message == null ? "删除成功" : "删除失败：" + message);
                if (dialog != null && dialog.isAttachedToWindow()) dialog.refreshBackups();
            });
        });
    }

    private void restore(LocalBackupEntry entry) {
        if (busy) return;
        ConfirmDialog.show(getContext(), "还原备份",
                "将覆盖本机设置和历史记录，不会覆盖日志。恢复前会自动保留当前数据供失败时回滚；" +
                        "恢复成功后应用会自动重启。确认还原「" + entry.name() + "」吗？",
                "开始还原", () -> beginRestore(entry));
    }

    private void beginRestore(LocalBackupEntry entry) {
        if (busy) return;
        busy = true;
        WeakReference<BackupDialog> dialogRef = new WeakReference<>(this);
        LocalBackupRepository source = repository;
        HeavyTaskUtil.getSerialExecutorService().execute(() -> {
            String result = null;
            String error = null;
            try { result = source.restore(entry); }
            catch (Exception failure) { error = failureMessage(failure); }
            if (error == null && result == null) error = "未返回恢复结果";
            String restored = result;
            String message = error;
            MAIN.post(() -> {
                BackupDialog dialog = dialogRef.get();
                if (dialog != null) dialog.busy = false;
                if (message != null) {
                    AppBubble.toast("恢复失败：" + message);
                    if (dialog != null && dialog.isAttachedToWindow()) dialog.refreshBackups();
                    return;
                }
                restartApp(restored);
            });
        });
    }

    private static String failureMessage(Exception failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /** Runs independently of the popup lifecycle after a completed restore. */
    private static void restartApp(String restored) {
        try {
            SystemConfig.markInternalRestart();
            AppBubble.toast(restored + "，正在重启");
            AppUtils.relaunchApp(true);
        } catch (Throwable ignored) {
            try { SystemConfig.clearInternalRestart(); }
            catch (Throwable ignoredClear) { }
            AppBubble.toast("恢复完成，但自动重启失败，请手动重开应用");
        }
    }
}

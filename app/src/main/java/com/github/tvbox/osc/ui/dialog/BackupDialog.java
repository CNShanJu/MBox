package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
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

import java.lang.ref.WeakReference;
import java.util.List;

/** Local backup list and actions; file and database work belongs to LocalBackupRepository. */
public class BackupDialog extends AppBottomPopupView {

    /**
     * 「使用外部备份数据」的系统文件选择入口:弹窗不能自己拉 ActivityResult,
     * 由宿主页(设置页)提供,选中结果经 {@link #onExternalBackupPicked(Uri)} 回传本弹窗。
     */
    public interface ExternalBackupHost {
        void requestPickExternalBackup(@NonNull BackupDialog dialog);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String SYSTEM_BACKUP_EXPLANATION =
            "系统在使用本地备份前，会先保存当前设置、订阅源文件和历史记录，日志独立保留。" +
            "如果还原后的数据不符合预期，可使用这份临时备份恢复到还原前。\n\n" +
            "临时备份不能手动删除；主动使用它恢复成功后会自动移除。" +
            "若一直未使用，保存满 1 小时后的下次启动会自动清理。";

    private final LocalBackupRepository repository;
    private final ExternalBackupHost host;
    private LocalBackupAdapter adapter;
    private boolean busy;
    /** 备份/还原在后台串行执行时的全屏遮罩:给用户"正在干活"的反馈,避免点完毫无动静。 */
    private LoadingDialog progress;

    public BackupDialog(@NonNull @NotNull Context context) {
        this(context, null);
    }

    public BackupDialog(@NonNull @NotNull Context context, ExternalBackupHost host) {
        super(context);
        this.host = host;
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
        // 整行点击 = 还原(动作行除外);删除/说明键走子项点击(见 adapter 的 addOnClickListener)。
        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter clicked, View view, int position) {
                Object item = adapter.getItem(position);
                if (item == null) return;
                if (LocalBackupAdapter.isExternalRow(item)) {
                    requestPickExternalBackup();
                    return;
                }
                restore((LocalBackupEntry) item);
            }
        });
        adapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter clicked, View view, int position) {
                Object item = adapter.getItem(position);
                if (item == null || LocalBackupAdapter.isExternalRow(item)) return;
                if (view.getId() != R.id.ivAction) return;
                LocalBackupEntry entry = (LocalBackupEntry) item;
                if (entry.isSystem()) showSystemBackupExplanation();
                else confirmDelete(entry);
            }
        });
        // 长按:改名字/删除都在这里(遥控器上没有别的地方能挂这两个动作)
        adapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter clicked, View view, int position) {
                Object item = adapter.getItem(position);
                if (item == null || LocalBackupAdapter.isExternalRow(item)) return false;
                LocalBackupEntry entry = (LocalBackupEntry) item;
                if (entry.isSystem()) {
                    showSystemBackupExplanation();
                    return true;
                }
                showActions(view, entry);
                return true;
            }
        });
        findViewById(R.id.backupNow).setOnClickListener(view -> {
            // 连点会一次弹两个命名弹窗：先按全站口径吃掉 500ms 内的重复点击
            com.github.tvbox.osc.util.FastClickCheckUtil.check(view);
            promptCreateBackup();
        });
        list.post(this::refreshBackups);
    }

    /** 先在弹窗里问名字(可勾系统时间),再真正开始导出。 */
    private void promptCreateBackup() {
        if (busy) return;
        BackupNameDialog.showCreate(getContext(), this::startCreate);
    }

    /** 长按气泡:与订阅管理同一套锚点气泡(贴着条目弹出「修改名称/删除」)。 */
    private void showActions(View anchor, LocalBackupEntry entry) {
        if (busy) return;
        AttachActionDialog.show(anchor,
                new String[]{"修改名称", "删除"},
                new int[]{AttachActionDialog.NORMAL, AttachActionDialog.DANGER},
                position -> {
                    if (position == 0) promptRename(entry);
                    else confirmDelete(entry);
                });
    }

    private void promptRename(LocalBackupEntry entry) {
        if (busy) return;
        BackupNameDialog.showRename(getContext(), entry.name(), (name, useTimestamp) -> rename(entry, name));
    }

    /** 「使用外部备份数据」排在备份列表最后面,由宿主页拉起系统文件管理选 ZIP。 */
    private void requestPickExternalBackup() {
        if (busy) return;
        if (host == null) {
            AppBubble.toast("当前页面无法打开文件管理，请重新进入设置");
            return;
        }
        host.requestPickExternalBackup(this);
    }

    /** 宿主页选完外部 ZIP 后回传:先确认,再走与本地备份相同的还原流程。 */
    public void onExternalBackupPicked(@NonNull Uri source) {
        if (busy) return;
        ConfirmDialog.show(getContext(), "还原外部备份",
                "将用所选 ZIP 覆盖本机设置、订阅源文件和历史记录，不会覆盖日志。" +
                        "恢复前会自动保留当前数据供失败时回滚；恢复成功后应用会自动重启。确认还原吗？",
                "开始还原", () -> beginExternalRestore(source));
    }

    private void beginExternalRestore(@NonNull Uri source) {
        if (busy) return;
        busy = true;
        showProgress("正在还原外部备份…");
        WeakReference<BackupDialog> dialogRef = new WeakReference<>(this);
        LocalBackupRepository backupSource = repository;
        HeavyTaskUtil.getSerialExecutorService().execute(() -> {
            String result = null;
            String error = null;
            try { result = backupSource.restoreExternal(source); }
            catch (Exception failure) { error = failureMessage(failure); }
            if (error == null && result == null) error = "未返回恢复结果";
            String restored = result;
            String message = error;
            MAIN.post(() -> {
                BackupDialog dialog = dialogRef.get();
                if (dialog != null) {
                    dialog.busy = false;
                    dialog.dismissProgress();
                }
                if (message != null) {
                    AppBubble.toast("恢复失败：" + message);
                    if (dialog != null && dialog.isAttachedToWindow()) dialog.refreshBackups();
                    return;
                }
                restartApp(restored);
            });
        });
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
                    // 读取失败也保留动作行:外部 ZIP 还原不依赖本机备份列表
                    AppBubble.toast("读取备份失败：" + message);
                    dialog.adapter.submit(null, dialog.host != null);
                    return;
                }
                dialog.adapter.submit(loaded, dialog.host != null);
            });
        });
    }

    private void startCreate(String name, boolean useTimestamp) {
        if (busy) return;
        busy = true;
        showProgress("正在生成备份…");
        WeakReference<BackupDialog> dialogRef = new WeakReference<>(this);
        LocalBackupRepository source = repository;
        HeavyTaskUtil.getSerialExecutorService().execute(() -> {
            LocalBackupRepository.ManualResult created = null;
            String error = null;
            try { created = source.createManual(name, useTimestamp); }
            catch (Exception failure) { error = failureMessage(failure); }
            if (error == null && created == null) error = "未生成备份文件";
            LocalBackupRepository.ManualResult result = created;
            String message = error;
            MAIN.post(() -> {
                BackupDialog dialog = dialogRef.get();
                if (dialog != null) {
                    dialog.busy = false;
                    dialog.dismissProgress();
                }
                if (message != null) {
                    AppBubble.toast("备份失败：" + message);
                } else if (result.isReused()) {
                    // 内容摘要一致 = 同一份数据,不重复占空间
                    AppBubble.toast("数据没有变化，已有相同的备份「" + result.entry().name() + "」");
                } else {
                    AppBubble.toast("备份完成：" + result.entry().name());
                }
                if (dialog != null && dialog.isAttachedToWindow()) dialog.refreshBackups();
            });
        });
    }

    private void rename(LocalBackupEntry entry, String name) {
        if (busy) return;
        busy = true;
        WeakReference<BackupDialog> dialogRef = new WeakReference<>(this);
        LocalBackupRepository source = repository;
        HeavyTaskUtil.getSerialExecutorService().execute(() -> {
            String error = null;
            try { source.rename(entry, name); }
            catch (Exception failure) { error = failureMessage(failure); }
            String message = error;
            MAIN.post(() -> {
                BackupDialog dialog = dialogRef.get();
                if (dialog != null) dialog.busy = false;
                AppBubble.toast(message == null ? "名称已修改" : "修改名称失败：" + message);
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
                "将覆盖本机设置、订阅源文件和历史记录，不会覆盖日志。恢复前会自动保留当前数据供失败时回滚；" +
                        "恢复成功后应用会自动重启。确认还原「" + entry.name() + "」吗？",
                "开始还原", () -> beginRestore(entry));
    }

    private void beginRestore(LocalBackupEntry entry) {
        if (busy) return;
        busy = true;
        showProgress("正在还原备份…");
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
                if (dialog != null) {
                    dialog.busy = false;
                    dialog.dismissProgress();
                }
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

    /** 全屏遮罩 + 状态文字,须在主线程调用;复用同一个实例,避免重复弹窗叠层。 */
    private void showProgress(String hint) {
        Context ctx = getContext();
        if (!(ctx instanceof Activity)) return;
        if (progress == null) {
            progress = (LoadingDialog) DialogCoordinator.loading((Activity) ctx);
        }
        progress.setHint(hint);
        if (!progress.isShow()) progress.show();
    }

    /** 关闭遮罩,须在主线程调用。 */
    private void dismissProgress() {
        if (progress != null && progress.isShow()) progress.dismiss();
    }

    /** Runs independently of the popup lifecycle after a completed restore. */
    public static void restartApp(String restored) {
        try {
            com.github.tvbox.osc.base.App app = com.github.tvbox.osc.base.App.getInstance();
            if (app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()) == null) {
                throw new IllegalStateException("未找到应用启动入口");
            }
            SystemConfig.markInternalRestart();
            AppBubble.toast(restored + "，正在重启");
            AppUtils.relaunchApp(true);
            throw new IllegalStateException("重启未执行");
        } catch (Throwable ignored) {
            try { SystemConfig.clearInternalRestart(); }
            catch (Throwable ignoredClear) { }
            AppBubble.toast("恢复完成，但自动重启失败，请手动重开应用");
        }
    }
}

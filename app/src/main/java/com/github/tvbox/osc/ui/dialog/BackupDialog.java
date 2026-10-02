package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.AppUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.share.ShareArchives;
import com.github.tvbox.osc.transfer.BackupArchive;
import com.github.tvbox.osc.ui.adapter.TitleWithDelAdapter;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HeavyTaskUtil;
import com.lxj.xpopup.core.BasePopupView;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 备份/还原弹窗（统一走 XPopup 底部弹窗 AppBottomPopupView；观感与其它 XPopup 弹窗一致）。
 * <p>外部用法不变：{@code new BackupDialog(ctx).show()}——{@link #show()} 在 popupInfo 未绑定时
 * 自动经 XPopup.Builder 绑定，兼容旧 Dialog 式调用点。
 */
public class BackupDialog extends AppBottomPopupView {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private boolean busy;

    public BackupDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_backup;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        TvRecyclerView tvRecyclerView = ((TvRecyclerView) findViewById(R.id.list));
        TitleWithDelAdapter adapter = new TitleWithDelAdapter();
        tvRecyclerView.setAdapter(adapter);
        adapter.setNewData(allBackup());
        adapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, View view, int position) {
                if (view.getId() == R.id.tvName) {
                    restore((String) adapter.getItem(position));
                } else if (view.getId() == R.id.tvDel) {
                    String name = (String) adapter.getItem(position);
                    ConfirmDialog.showDanger(getContext(), "删除备份", "确定删除备份「" + name + "」吗？", "删除", () -> {
                        delete(name);
                        adapter.setNewData(allBackup());
                    });
                }
            }
        });
        findViewById(R.id.backupNow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) return;
                busy = true;
                BackupArchive archive = new BackupArchive(getContext().getApplicationContext());
                WeakReference<BackupDialog> dialogRef = new WeakReference<>(BackupDialog.this);
                WeakReference<TitleWithDelAdapter> adapterRef = new WeakReference<>(adapter);
                HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
                    String error = null;
                    File created = null;
                    try { created = archive.create(backupRoot()); }
                    catch (Exception failure) { error = failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage(); }
                    String message = error;
                    File result = created;
                    MAIN.post(() -> {
                        BackupDialog dialog = dialogRef.get();
                        if (dialog != null) {
                            dialog.busy = false;
                            TitleWithDelAdapter current = adapterRef.get();
                            if (current != null) current.setNewData(dialog.allBackup());
                        }
                        if (message != null) { AppBubble.toast("备份失败：" + message); return; }
                        com.github.tvbox.osc.share.ShareManifest manifest = ShareArchives.manifest(result);
                        boolean withRoom = manifest != null && manifest.domains().contains("room");
                        AppBubble.toast((withRoom ? "备份完成：" : "仅设置已备份：") + result.getName());
                    });
                });
            }
        });
    }

    /** 兼容旧调用点：popupInfo 未绑定（直接 new 未走 Builder）时经 Builder 绑定后展示 */
    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return DialogCoordinator.bottom(getContext(), this, -1).show();
        }
        return super.show();
    }

    List<String> allBackup() {
        ArrayList<String> result = new ArrayList<>();
        try {
            File file = backupRoot();
            File[] list = file.exists() ? file.listFiles() : null;
            if (list != null) {
                Arrays.sort(list, new Comparator<File>() {
                    @Override
                    public int compare(File o1, File o2) {
                        if (o1.isDirectory() && o2.isFile()) return -1;
                        return o1.isFile() && o2.isDirectory() ? 1 : o2.getName().compareTo(o1.getName());
                    }
                });
                for (File f : list) {
                    if (f.isDirectory() || f.isFile() && f.getName().endsWith(".zip")) {
                        result.add(f.getName());
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return result;
    }

    void restore(String dir) {
        if (busy) return;
        ConfirmDialog.show(getContext(), "还原备份", "将覆盖本机设置，数据库记录也可能被替换。确认还原「" + dir + "」吗？", "开始还原", () -> {
            busy = true;
            BackupArchive archive = new BackupArchive(getContext().getApplicationContext());
            WeakReference<BackupDialog> dialogRef = new WeakReference<>(BackupDialog.this);
            HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
                String error = null;
                String result = null;
                try {
                    File backup = selectedBackup(dir);
                    result = backup.isFile() ? archive.restore(backup) : restoreLegacy(backup);
                } catch (Exception failure) { error = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage(); }
                String message = error;
                String restored = result;
                MAIN.post(() -> {
                    BackupDialog dialog = dialogRef.get();
                    if (dialog == null) return;
                    dialog.busy = false;
                    if (message != null) { AppBubble.toast("恢复失败：" + message); return; }
                    AppBubble.toast(restored + "，正在重启");
                    dialog.restartApp();
                });
            });
        });
    }

    private static String restoreLegacy(File backup) throws Exception {
        if (!backup.isDirectory()) throw new java.io.IOException("未找到备份目录");
        File cfgFile = firstExisting(backup, "prefs.json", "config.json");
        String json = null;
        if (cfgFile != null) {
            json = new String(readLegacyPrefs(cfgFile), java.nio.charset.StandardCharsets.UTF_8);
            if (!com.google.gson.JsonParser.parseString(json).isJsonObject())
                throw new IOException("旧版设置数据格式无效");
        }
        File db = firstExisting(backup, "room.db", "sqlite");
        boolean dbOk = db != null && AppDataManager.restore(db);
        int prefsCount = json == null ? 0 : com.github.tvbox.osc.config.PrefsDataStore.importJson(json);
        if (prefsCount < 0) throw new IOException(dbOk
                ? "数据库已恢复，但旧版设置写入失败" : "旧版设置写入失败");
        if (prefsCount <= 0 && !dbOk) throw new java.io.IOException("备份中无可恢复数据");
        return "旧版备份已恢复";
    }

    private static byte[] readLegacyPrefs(File file) throws IOException {
        final int limit = 32 * 1024 * 1024;
        if (file.length() > limit) throw new IOException("旧版设置数据超过 32 MB");
        try (FileInputStream in = new FileInputStream(file);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0) {
                if (out.size() + count > limit) throw new IOException("旧版设置数据超过 32 MB");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static File backupRoot() {
        return new File(Environment.getExternalStorageDirectory(), "tvbox_backup");
    }

    private static File selectedBackup(String name) throws java.io.IOException {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".."))
            throw new java.io.IOException("备份名称无效");
        File root = backupRoot().getCanonicalFile();
        File selected = new File(root, name).getCanonicalFile();
        if (!root.equals(selected.getParentFile())) throw new java.io.IOException("备份路径无效");
        return selected;
    }

    /** 依序返回目录中第一个存在的文件(新格式优先,旧格式兜底),均不存在返回 null */
    private static File firstExisting(File dir, String... names) throws IOException {
        File root = dir.getCanonicalFile();
        for (String n : names) {
            File f = new File(root, n).getCanonicalFile();
            if (!root.equals(f.getParentFile())) throw new IOException("旧版备份路径无效");
            if (f.exists() && f.isFile()) return f;
        }
        return null;
    }

    /**
     * 还原后重启:直接清空任务栈重建主界面,不杀进程。
     * 理由:配置导入已同步更新内存与 DataStore 磁盘(每键阻塞落盘),Room 已关闭句柄并替换文件;
     * 立刻杀进程可能抢在 DataStore 异步落盘前退出导致数据丢失(曾出现"提示成功但重启无数据")。
     * 重启后 Home 会重拉配置/重开 Room,读取的即为还原后的数据。
     */
    private void restartApp() {
        com.github.tvbox.osc.config.SystemConfig.markInternalRestart();
        try {
            android.content.Intent launch = getContext().getPackageManager()
                    .getLaunchIntentForPackage(getContext().getPackageName());
            if (launch != null) {
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                getContext().startActivity(launch);
                return;
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        try {
            AppUtils.relaunchApp(true);
        } catch (Throwable ignored) {
        }
    }

    void delete(String dir) {
        try {
            File backup = selectedBackup(dir);
            if (backup.isDirectory()) FileUtils.recursiveDelete(backup);
            else if (!backup.delete()) throw new java.io.IOException("删除失败");
            AppBubble.toast("删除成功");
            com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM,
                    "备份: 删除备份目录 " + dir);
        } catch (Throwable e) {
            e.printStackTrace();
            com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.SYSTEM,
                    "备份: 删除失败 " + dir + " " + e);
        }
    }
}

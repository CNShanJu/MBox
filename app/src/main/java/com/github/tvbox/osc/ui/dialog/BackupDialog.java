package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.view.View;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.AppUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.ui.adapter.TitleWithDelAdapter;
import com.github.tvbox.osc.util.FileUtils;
import com.lxj.xpopup.core.BasePopupView;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * 备份/还原弹窗（统一走 XPopup 底部弹窗 AppBottomPopupView；观感与其它 XPopup 弹窗一致）。
 * <p>外部用法不变：{@code new BackupDialog(ctx).show()}——{@link #show()} 在 popupInfo 未绑定时
 * 自动经 XPopup.Builder 绑定，兼容旧 Dialog 式调用点。
 */
public class BackupDialog extends AppBottomPopupView {

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
                    delete((String) adapter.getItem(position));
                    adapter.setNewData(allBackup());
                }
            }
        });
        findViewById(R.id.backupNow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backup();
                adapter.setNewData(allBackup());
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
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File file = new File(root + "/tvbox_backup/");
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
                    if (result.size() > 10) {
                        FileUtils.recursiveDelete(f);
                        continue;
                    }
                    if (f.isDirectory()) {
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
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File backup = new File(root + "/tvbox_backup/" + dir);
            if (!backup.exists() || !backup.isDirectory()) {
                AppBubble.toast("未找到备份目录");
                return;
            }
            int prefsCount = 0;
            boolean dbOk = false;

            // 1) 配置域(DataStore:设置/订阅/搜索历史/源勾选记忆...):新格式 prefs.json,兼容旧格式 config.json
            File cfgFile = firstExisting(backup, "prefs.json", "config.json");
            if (cfgFile != null) {
                byte[] cfgData = FileUtils.readSimple(cfgFile);
                if (cfgData != null) {
                    prefsCount = com.github.tvbox.osc.config.PrefsDataStore
                            .importJson(new String(cfgData, "UTF-8"));
                }
            }
            // 2) Room DB(播放历史/收藏/缓存):新格式 room.db,兼容旧格式 sqlite
            File db = firstExisting(backup, "room.db", "sqlite");
            if (db != null) {
                try {
                    dbOk = AppDataManager.restore(db);
                } catch (Throwable ignored) {
                }
            }

            if (prefsCount <= 0 && !dbOk) {
                AppBubble.toast("未找到可恢复的数据,请先备份");
                com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.SYSTEM,
                        "备份还原: 未找到可恢复的数据(目录 " + backup.getName() + ")");
                com.github.tvbox.osc.util.AppLog.log("备份", "还原失败: 备份目录无有效数据");
                return;
            }
            StringBuilder msg = new StringBuilder();
            if (prefsCount > 0) msg.append("设置/订阅/搜索历史");
            if (dbOk) {
                if (msg.length() > 0) msg.append("、");
                msg.append("播放历史/收藏");
            }
            msg.append(" 已恢复,即将重启应用!");
            AppBubble.toast(msg.toString());
            com.github.tvbox.osc.log.LogStore.success(com.github.tvbox.osc.log.Category.SYSTEM,
                    "备份还原: 恢复成功 " + msg + " (键数=" + prefsCount + ",db=" + dbOk + ")");
            com.github.tvbox.osc.util.AppLog.log("备份", "还原成功: " + msg);
            restartApp();
        } catch (Throwable e) {
            e.printStackTrace();
            AppBubble.toast("恢复数据流异常");
            com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.SYSTEM,
                    "备份还原: 数据流异常 " + e);
            com.github.tvbox.osc.util.AppLog.log("备份", "还原异常: " + e);
        }
    }

    void backup() {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File dir = new File(root + "/tvbox_backup/");
            if (!dir.exists())
                dir.mkdirs();
            File backup = new File(dir, new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new Date()));
            backup.mkdirs();

            // 1) 配置域(DataStore:设置/订阅/搜索历史/源勾选记忆...)
            boolean cfgOk = FileUtils.writeSimple(
                    com.github.tvbox.osc.config.PrefsDataStore.exportJson().getBytes("UTF-8"),
                    new File(backup, "prefs.json"));
            // 2) Room DB(播放历史/收藏/缓存条目):新装可缺失,不阻塞整体备份
            boolean dbOk = false;
            try {
                dbOk = AppDataManager.backup(new File(backup, "room.db"));
            } catch (Throwable ignored) {
            }
            // 3) 归档清单:记录格式版本/应用版本,保证"后期改动后老备份仍可读、新字段可后向兼容"
            FileUtils.writeSimple(buildManifest().getBytes("UTF-8"), new File(backup, "manifest.json"));

            if (cfgOk) {
                String tip = dbOk
                        ? "备份成功(设置/订阅/搜索历史+播放历史/收藏)"
                        : "备份成功(设置/订阅/搜索历史,暂无播放历史/收藏)";
                AppBubble.toast(tip);
                com.github.tvbox.osc.log.LogStore.success(com.github.tvbox.osc.log.Category.SYSTEM,
                        "数据备份: 备份成功 " + backup.getName() + " (cfg=" + cfgOk + ",db=" + dbOk + ")");
                com.github.tvbox.osc.util.AppLog.log("备份", "备份成功: " + backup.getName());
            } else {
                FileUtils.recursiveDelete(backup);
                AppBubble.toast("备份失败!");
                com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.SYSTEM,
                        "数据备份: 写配置失败,已清理备份目录");
                com.github.tvbox.osc.util.AppLog.log("备份", "备份失败: 写配置失败");
            }
        } catch (Throwable e) {
            e.printStackTrace();
            AppBubble.toast("备份失败!");
            com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.SYSTEM,
                    "数据备份: 异常 " + e);
            com.github.tvbox.osc.util.AppLog.log("备份", "备份异常: " + e);
        }
    }

    /** 归档清单:格式版本 schema + 来源应用信息 + 覆盖域,便于后续版本升级读取/校验 */
    private String buildManifest() {
        try {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("schema", 1);
            o.addProperty("createdAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            o.addProperty("appVersion", AppUtils.getAppVersionName());
            o.addProperty("versionCode", AppUtils.getAppVersionCode());
            o.addProperty("domains", "prefs,room");
            return o.toString();
        } catch (Throwable ignored) {
            return "{}";
        }
    }

    /** 依序返回目录中第一个存在的文件(新格式优先,旧格式兜底),均不存在返回 null */
    private static File firstExisting(File dir, String... names) {
        for (String n : names) {
            File f = new File(dir, n);
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
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File backup = new File(root + "/tvbox_backup/" + dir);
            FileUtils.recursiveDelete(backup);
            AppBubble.toast("删除成功");
            com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM,
                    "备份: 删除备份目录 " + dir);
            com.github.tvbox.osc.util.AppLog.log("备份", "删除备份: " + dir);
        } catch (Throwable e) {
            e.printStackTrace();
            com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.SYSTEM,
                    "备份: 删除失败 " + dir + " " + e);
            com.github.tvbox.osc.util.AppLog.log("备份", "删除备份异常: " + dir);
        }
    }
}

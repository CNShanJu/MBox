package com.github.tvbox.osc.download.internal;

import android.util.Log;

import android.content.Context;
import com.github.tvbox.osc.bean.DownloadTask;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 任务记录器（Task-Recorder）：任务列表存取 / 状态查询 / 完成记录对账 / 海报资源。
 * 数据在下载模块内部维护（私有文件 download_tasks_v1.json），对外经 DownloadManager 门面访问。
 */
public class DownloadStore {

    /** 注入的 application context(独立模块 :download, 海报目录用) */
    private static volatile Context appContext;

    static void setAppContext(Context c) {
        appContext = c == null ? null : c.getApplicationContext();
    }

    private final DownloadManager dm;

    /** 剧集海报下载:单线程串行,避免并发写同一文件;私有目录不入系统相册 */
    private final ExecutorService posterExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvbox-poster");
        t.setDaemon(true);
        return t;
    });
    /** 正在拉取的海报文件(去重,避免列表多次刷新重复下载) */
    private final Set<String> posterFetching = new HashSet<>();

    DownloadStore(DownloadManager dm) {
        this.dm = dm;
    }

    /** 任务列表持久化文件(App init 注入 appContext 后可用) */
    static File tasksFile() {
        return JsonFiles.privateFile("download_tasks_v1.json");
    }

    private static final Gson GSON = new Gson();
    private static final Type TASK_LIST_TYPE = new TypeToken<List<DownloadTask>>() {
    }.getType();

    private static List<DownloadTask> readTasksFile() {
        String s = JsonFiles.readUtf8(tasksFile());
        if (s == null) return null;
        List<DownloadTask> r = GSON.fromJson(s, TASK_LIST_TYPE);
        return r == null ? new ArrayList<>() : r;
    }

    /** 任务列表写私有文件(调用方保证频率/串行;persist 异步,迁移一次性) */
    static void writeTasksFile(List<DownloadTask> list) {
        File f = tasksFile();
        if (f == null) return;
        try {
            JsonFiles.writeUtf8Atomic(f, GSON.toJson(list == null ? new ArrayList<DownloadTask>() : list, TASK_LIST_TYPE));
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 启动加载:读任务文件+ 进程重启状态归位(下载中/等待/调度暂停 -> 用户暂停,待手动继续) */
    void load() {
        try {
            List<DownloadTask> saved = readTasksFile();
            if (saved != null) dm.tasks.addAll(saved);
        } catch (Throwable th) {
            th.printStackTrace(); // 存储损坏时兜底为空列表,不阻塞下载器启动
        }
        boolean needPersist = false;
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.state == DownloadTask.STATE_DOWNLOADING
                        || t.state == DownloadTask.STATE_SYSTEM_PAUSED
                        || t.state == DownloadTask.STATE_WAITING) {
                    t.state = DownloadTask.STATE_PAUSED;
                    t.needReResolve = true;
                    needPersist = true;
                }
                t.speed = 0;
                // 历史版本:公共 Download 下的 HLS 临时目录迁移到应用私有目录(碎片保留,续传不丢)
                if (t.tmpDir != null) {
                    String migrated = FileCleaner.migrateTmpDirToPrivate(t.tmpDir);
                    if (migrated != null && !migrated.equals(t.tmpDir)) {
                        t.tmpDir = migrated;
                        needPersist = true;
                    }
                }
            }
        }
        if (needPersist) {
            dm.persist();
            Log.i("TVBox-Download", "进程重启:未完成任务置为暂停,等待用户手动开始(继续时自动重新解析地址)");
        }
        // 启动磁盘对账(4.5):内存计数被杀后滞后,以磁盘实况修正
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.state == DownloadTask.STATE_COMPLETED) continue;
                // 直链:downloadedBytes 以 .part 实际长度为准(修复 Range 续传错位产生空洞)
                if (t.partPath != null) {
                    File part = new File(t.partPath);
                    if (part.exists() && part.length() > 0) {
                        t.downloadedBytes = part.length();
                    }
                }
                // HLS:丢弃残缺 .part(分片原子写后只信任 rename 的 .ts),避免被当成完整片
                if (t.tmpDir != null) {
                    File tmp = new File(t.tmpDir);
                    File[] segs = tmp.listFiles();
                    if (segs != null) {
                        for (File seg : segs) {
                            if (seg.isFile() && seg.getName().endsWith(".part")) {
                                //noinspection ResultOfMethodCallIgnored
                                seg.delete();
                            }
                        }
                    }
                }
            }
        }
    }

    /** 任务快照(安全遍历,避免外部迭代与任务增删并发冲突) */
    List<DownloadTask> getTasks() {
        synchronized (dm.tasks) {
            return new ArrayList<>(dm.tasks);
        }
    }

    /**
     * 查询某集(来源+剧名+剧集名)的下载状态,用于下载选择弹窗去重:
     * 0=无记录,可下载;1=已下载完成且文件存在;2=已有任务(下载中/排队/暂停/失败等,未完成)
     */
    int getEpisodeDownloadState(String sourceName, String vodName, String episodeName) {
        String src = dm.sanitize(sourceName);
        if (src.isEmpty()) src = "未分类";
        String vn = dm.sanitize(vodName);
        if (vn.isEmpty()) vn = "未命名";
        String ep = episodeName == null ? "" : episodeName.trim();
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.vodName == null || !vn.equals(t.vodName)) continue;
                if (t.sourceName != null && !src.equals(t.sourceName)) continue;
                if (t.fileName == null || t.savePath == null) continue;
                // 文件名匹配:剧名_第X集.ext 或 剧名_第X集_720P.ext;单集为 剧名.ext
                boolean match;
                String base = vn + "_" + dm.sanitize(ep);
                if (ep.isEmpty() || ep.equals(vn)) {
                    match = t.fileName.startsWith(vn + ".");
                } else {
                    match = t.fileName.startsWith(base + ".") || t.fileName.startsWith(base + "_");
                }
                if (!match) continue;
                if (t.state == DownloadTask.STATE_COMPLETED) {
                    // 完成但文件已丢:视为无记录(下载完成列表对账时会清理该记录)
                    if (new File(t.savePath).exists()) return 1;
                    continue;
                }
                if (t.state == DownloadTask.STATE_FAILED) {
                    return 3; // 失败: 单独一档(抽屉显示失败,可重新勾选下载), 与 getEpisodeStates 一致
                }
                return 2;
            }
        }
        return 0;
    }

    /**
     * 清理"已完成但文件已不存在"的失效记录(下载完成列表基于记录展示前的对账)。
     * 不广播事件(调用方正处于刷新流程,避免刷新循环)。
     *
     * @return 移除的记录数
     */
    int pruneMissingCompleted() {
        List<DownloadTask> toRemove = new ArrayList<>();
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.state == DownloadTask.STATE_COMPLETED && t.savePath != null) {
                    if (!new File(t.savePath).exists()) {
                        toRemove.add(t);
                    }
                }
            }
            if (!toRemove.isEmpty()) {
                dm.tasks.removeAll(toRemove);
            }
        }
        if (!toRemove.isEmpty()) {
            dm.persist();
            Log.i("TVBox-Download", "清理失效下载完成记录 " + toRemove.size() + " 条");
        }
        return toRemove.size();
    }

    // ------------------------------------------------------------------
    // 剧集海报:下载触发时把海报存到应用私有目录(poster/剧名/poster.jpg),
    // 不进系统相册/媒体库;下载页组级与条目级展示用本地文件,缺失时显示占位图并懒拉取。
    // ------------------------------------------------------------------

    /** 剧集海报目录:应用私有目录 poster/<剧名> */
    static File getPosterDir(String vodName) {
        return new File(new File(appContext.getFilesDir(), "poster"), DownloadManager.sanitizeName(vodName));
    }

    /** 剧集海报本地文件:已存在返回 File,否则返回 null(调用方显示占位图) */
    static File getPosterFile(String vodName) {
        if (vodName == null || vodName.isEmpty()) return null;
        File f = new File(getPosterDir(vodName), "poster.jpg");
        return f.exists() ? f : null;
    }

    /** 确保剧集海报已下载到本地(缺失才异步拉取,同文件去重);pic 为空/拉取失败静默跳过 */
    void ensurePosterAsync(String pic, String vodName) {
        if (pic == null || pic.isEmpty() || vodName == null || vodName.isEmpty()) return;
        final File target = new File(getPosterDir(vodName), "poster.jpg");
        if (target.exists()) return;
        final String key = target.getAbsolutePath();
        synchronized (posterFetching) {
            if (posterFetching.contains(key)) return;
            posterFetching.add(key);
        }
        posterExecutor.execute(() -> {
            try {
                Request req = new Request.Builder().url(pic).build();
                try (Response resp = dm.downloadClient.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) return;
                    String ct = resp.header("Content-Type");
                    if (ct != null && !ct.toLowerCase(java.util.Locale.ROOT).contains("image")) return;
                    File dir = target.getParentFile();
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    try (InputStream is = resp.body().byteStream();
                         FileOutputStream fos = new FileOutputStream(target)) {
                        byte[] buf = new byte[DownloadManager.BUFFER];
                        int n;
                        while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    Log.i("TVBox-Download", "海报已下载 " + target.getAbsolutePath());
                    dm.notifyChanged(); // 海报就绪,刷新下载页
                }
            } catch (Throwable th) {
                Log.i("TVBox-Download", "海报下载失败 " + pic + " : " + th.getMessage());
            } finally {
                synchronized (posterFetching) {
                    posterFetching.remove(key);
                }
            }
        });
    }
}

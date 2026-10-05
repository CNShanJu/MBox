package com.github.tvbox.osc.download.internal;

import android.util.Log;

import android.content.Context;
import com.github.tvbox.osc.bean.DownloadTask;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    private static final long MAX_POSTER_BYTES = 8L * 1024L * 1024L;

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
    /** 同名请求到来时保留最新一次，当前请求结束后复核海报是否仍存在。 */
    private final Map<String, PosterRequest> posterRetries = new HashMap<>();

    private static final class PosterRequest {
        final String pic;
        final String vodName;

        PosterRequest(String pic, String vodName) {
            this.pic = pic;
            this.vodName = vodName;
        }
    }

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
        File file = tasksFile();
        String s = JsonFiles.readUtf8(file);
        if (s == null) {
            if (JsonFiles.hasAtomicState(file)) throw new IllegalStateException("下载任务记录无法读取");
            return null;
        }
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

    /**
     * 启动加载:读任务文件 + 进程重启状态归位(下载中/等待/调度暂停 -> 用户暂停,待手动继续)。
     * <p>
     * 任务文件读取失败时停止本次下载器装载，避免把空任务表写回并覆盖原数据；
     * 单个任务的状态归位和磁盘对账仍逐项兜底。
     * 所以除读盘外,后面的状态归位/磁盘对账两段也必须包在 try 里(与 {@code DownloadArchive} 的口径一致),
     * 且显式剔除列表里的 null 项(Gson 对 {@code [null]} 会产出 null 元素,后面 {@code t.state} 直接 NPE)。
     */
    boolean load() {
        synchronized (dm.tasks) {
            dm.tasks.clear(); // 上一轮装载若中途失败，重试时从磁盘重新建立完整快照。
        }
        List<DownloadTask> saved = null;
        try {
            saved = readTasksFile();
        } catch (Throwable th) {
            throw new IllegalStateException("下载任务记录装载失败，已保留原文件", th);
        }
        if (saved != null && !saved.isEmpty()) {
            List<DownloadTask> usable = new ArrayList<>(saved.size());
            for (DownloadTask t : saved) {
                if (t != null) usable.add(t);
            }
            dm.tasks.addAll(usable);
        }
        boolean needPersist = false;
        try {
            synchronized (dm.tasks) {
                for (DownloadTask t : dm.tasks) {
                    if (t.state == DownloadTask.STATE_DOWNLOADING
                            || t.state == DownloadTask.STATE_SYSTEM_PAUSED
                            || t.state == DownloadTask.STATE_WAITING
                            || t.state == DownloadTask.STATE_NETWORK_PAUSED) {
                        t.state = dm.policy.isAutoResume() ? DownloadTask.STATE_WAITING : DownloadTask.STATE_PAUSED;
                        t.message = dm.policy.isAutoResume() ? "已排队，启动前刷新播放地址" : "重启后已暂停，继续时刷新播放地址";
                        t.needReResolve = true;
                        needPersist = true;
                    }
                    t.speed = 0;
                    // 历史版本:公共 Download 下的 HLS 临时目录迁移到应用私有目录(碎片保留,续传不丢)
                    // 单个任务的迁移失败不该打断整轮归位(逐个兜底)
                    try {
                        if (t.tmpDir != null) {
                            String migrated = FileCleaner.migrateTmpDirToPrivate(t.tmpDir);
                            if (migrated != null && !migrated.equals(t.tmpDir)) {
                                t.tmpDir = migrated;
                                needPersist = true;
                            }
                        }
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
            }
            if (needPersist) {
                Log.i("TVBox-Download", "进程重启:按自动继续设置恢复任务，开跑前刷新地址");
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
        } catch (Throwable th) {
            // 归位/对账失败只影响本次启动的任务状态呈现,不影响下载器可用;坏任务也已被剔除/不再抛出
            th.printStackTrace();
            Log.e("TVBox-Download", "启动任务归位/对账失败(已忽略):" + th);
        }
        return needPersist;
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
        return isUsablePoster(f) ? f : null;
    }

    /** Serialize cleanup with poster downloads, then recheck all sources and disk files. */
    void requestPosterCleanup(String vodName, String savePath, String tmpDir) {
        if (appContext == null || PosterCleanup.sanitizeName(vodName).isEmpty()) return;
        posterExecutor.execute(() -> {
            try {
                PosterCleanup.deleteIfUnused(appContext.getFilesDir(), vodName,
                        dm.getTasks(), dm.archive.getAll(), FileCleaner.knownSaveRoots(),
                        new File(appContext.getFilesDir(), "download_tmp"),
                        savePath == null ? null : new File(savePath),
                        savePath == null ? null : new File(savePath + ".part"),
                        tmpDir == null ? null : new File(tmpDir));
            } catch (RuntimeException error) {
                Log.w("TVBox-Download", "海报清理检查失败", error);
            }
        });
    }

    private boolean hasPosterOwner(String vodName) {
        return PosterCleanup.hasReference(PosterCleanup.sanitizeName(vodName),
                dm.getTasks(), dm.archive.getAll());
    }

    private static boolean isUsablePoster(File file) {
        return file.isFile() && file.length() > 0 && file.length() <= MAX_POSTER_BYTES;
    }

    /** 确保剧集海报已下载到本地(缺失才异步拉取,同文件去重);pic 为空/拉取失败静默跳过 */
    void ensurePosterAsync(String pic, String vodName) {
        if (pic == null || pic.isEmpty() || vodName == null || vodName.isEmpty()) return;
        final File target = new File(getPosterDir(vodName), "poster.jpg");
        final String key = target.getAbsolutePath();
        synchronized (posterFetching) {
            if (posterFetching.contains(key)) {
                posterRetries.put(key, new PosterRequest(pic, vodName));
                return;
            }
            posterFetching.add(key);
        }
        posterExecutor.execute(() -> {
            try {
                if (!hasPosterOwner(vodName)) return;
                // 与清理共用串行队列检查：新任务若在清理扫描期间入队，
                // 这里会在清理后重新确认海报是否还在，避免留下无海报的新任务。
                if (isUsablePoster(target)) return;
                Request req = new Request.Builder().url(pic).build();
                try (Response resp = dm.downloadClient().newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) return;
                    String ct = resp.header("Content-Type");
                    if (ct != null && !ct.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) return;
                    if (!hasPosterOwner(vodName)) return;
                    try (InputStream is = resp.body().byteStream()) {
                        savePoster(is, target, resp.body().contentLength());
                    }
                    Log.i("TVBox-Download", "海报已下载 " + target.getAbsolutePath());
                    dm.notifyChanged(); // 海报就绪,刷新下载页
                }
            } catch (Throwable th) {
                Log.i("TVBox-Download", "海报下载失败: " + th.getMessage());
            } finally {
                PosterRequest retry;
                synchronized (posterFetching) {
                    posterFetching.remove(key);
                    retry = posterRetries.remove(key);
                }
                if (retry != null) ensurePosterAsync(retry.pic, retry.vodName);
            }
        });
    }

    /** 下载先写同目录临时文件，完整且不超限后才发布为可见海报。 */
    static void savePoster(InputStream input, File target, long contentLength) throws IOException {
        if (contentLength > MAX_POSTER_BYTES) throw new IOException("海报超过 8 MB");
        File dir = target.getParentFile();
        if (dir == null || (!dir.exists() && !dir.mkdirs())) throw new IOException("无法创建海报目录");
        File part = new File(dir, target.getName() + ".part");
        if (part.exists() && !part.delete()) throw new IOException("无法清理旧海报临时文件");
        try {
            long written = 0;
            try (FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[DownloadManager.BUFFER];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    written += n;
                    if (written > MAX_POSTER_BYTES) throw new IOException("海报超过 8 MB");
                    out.write(buffer, 0, n);
                }
                if (written == 0) throw new IOException("海报响应为空");
                out.getFD().sync();
            }
            if (target.exists() && !isUsablePoster(target) && !target.delete())
                throw new IOException("无法清理残缺海报");
            if (!part.renameTo(target)) throw new IOException("海报文件发布失败");
        } finally {
            if (part.exists()) part.delete();
        }
    }
}

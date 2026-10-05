package com.github.tvbox.osc.download;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.download.internal.DownloadArchive;
import com.github.tvbox.osc.download.internal.DownloadManager;
import com.github.tvbox.osc.log.LogEntry;
import com.github.tvbox.osc.log.LogStore;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 下载对外门面（Download-Facade）：详情页/下载页等外部程序唯一的下载 API 入口。
 * <ul>
 *   <li>查询快照：剧集级展示态 / 视频级聚合（对外 5 态，内部状态不外泄）；</li>
 *   <li>事件订阅：状态/进度变化（DownloadManager 已去抖 500ms 广播）；</li>
 *   <li>任务日志：委托 LogStore.queryByTask（7 天）；</li>
 *   <li>档案管理：已下载档案长期保留，删文件联动删档案。</li>
 * </ul>
 * 展示态：{@link #ST_NOT_DOWNLOADED} 未下载 / {@link #ST_DOWNLOADED} 已下载 /
 * {@link #ST_DOWNLOADING} 下载中（含排队/校验/合并/网络暂停/调度暂停）/
 * {@link #ST_PAUSED} 已暂停（手动）/ {@link #ST_FAILED} 失败可重试。
 */
public final class DownloadFacade {

    public static final int ST_NOT_DOWNLOADED = 0;
    public static final int ST_DOWNLOADED = 1;
    public static final int ST_DOWNLOADING = 2;
    public static final int ST_PAUSED = 3;
    public static final int ST_FAILED = 4;

    // ── 任务阶段文案（唯一权威来源；DownloadManager 内部任务以这些前缀写 message，
    //    UI 用它判断“校验/合并/补片/封装”等阶段）──
    public static final String MSG_VERIFYING = "文件校验中";
    public static final String MSG_MERGING = "文件合并中";
    public static final String MSG_REMUX = "文件封装中";
    public static final String MSG_REPAIRING = "补片中";
    /** 仅WiFi开启且当前非WiFi时,等待任务的状态文案(让用户知道为何等待,而非莫名"等待中") */
    public static final String MSG_WAIT_WIFI = "已排队，等待 Wi-Fi";
    /** 存储看门狗发现可用空间见底时,等待任务的文案(与"等待Wi-Fi"同性质:说清为何没在下载) */
    public static final String MSG_WAIT_STORAGE = "存储空间不足,清理后继续下载";

    public interface DownloadStatusListener {
        /** 下载状态/进度变化（去抖 500ms 合并后回调，主线程） */
        void onChanged();
    }

    /** 任务级进度监听（高频：某任务进度推进即回调一次，主线程；UI 只做该任务行局部刷新） */
    public interface TaskProgressListener {
        void onTaskProgress(String taskId);
    }

    private static final DownloadFacade instance = new DownloadFacade();
    private final List<DownloadStatusListener> listeners = new CopyOnWriteArrayList<>();
    private final List<TaskProgressListener> progressListeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private DownloadFacade() {
        // 替代历史 EventBus 订阅:向内部 DownloadManager 注册事件下沉口,直调扇出给监听者。
        // DownloadManager 可能在建连前未实例化,这里 get() 触发其单例创建(轻量,不含 boot)。
        DownloadManager.get().setEventSink(new DownloadManager.EventSink() {
            @Override
            public void onStructuralChange() {
                runOnMain(() -> {
                    for (DownloadStatusListener l : listeners) {
                        try {
                            l.onChanged();
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }

            @Override
            public void onTaskProgress(String taskId) {
                if (taskId == null || taskId.isEmpty()) return;
                runOnMain(() -> {
                    for (TaskProgressListener l : progressListeners) {
                        try {
                            l.onTaskProgress(taskId);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        });
    }

    /** 复刻原 EventBus ThreadMode.MAIN 语义:已在主线程则同步执行,否则 post 到主线程 */
    private void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    public static DownloadFacade get() {
        return instance;
    }

    // ------------------------------------------------------------------
    // 装配入口(App 组合根调用一次;UI 不接触):转发到模块内部 DownloadManager
    // ------------------------------------------------------------------

    /** App 启动初始化:只注入 context/通知渠道，避免在 Application 主线程扫描下载文件。 */
    public static void init(android.content.Context context) {
        com.github.tvbox.osc.download.internal.DownloadManager.init(context);
        com.github.tvbox.osc.download.internal.DownloadNotifier.init(context);
    }

    /** 首页首帧后调用；服务单独唤起进程时也可直接调用。重复调用安全。 */
    public static void startAfterFirstHomeFrame() {
        DownloadManager.get().startBackgroundBoot();
    }

    /** 下载页可据此显示装载状态；就绪时会向已注册监听器派发一次刷新。 */
    public boolean isReady() {
        return DownloadManager.get().isBooted();
    }

    private boolean readyForRead() {
        DownloadManager manager = DownloadManager.get();
        if (manager.isBooted()) return true;
        manager.startBackgroundBoot();
        return false;
    }

    /** 有返回值的入队在原有后台解析线程等待，以便保留精确结果；主线程直接排队。 */
    private boolean readyForEnqueue() {
        if (readyForRead()) return true;
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        return DownloadManager.get().awaitBootInBackground(30_000);
    }

    /** 注册播放地址解析契约实现(:spider 提供;App 组合根注入) */
    public static void setUrlResolverApi(com.github.tvbox.osc.spiderapi.PlayUrlResolverApi api) {
        com.github.tvbox.osc.download.internal.DownloadManager.setUrlResolverApi(api);
    }

    public static void setRequestContextProvider(DownloadRequestContextProvider provider) {
        com.github.tvbox.osc.download.internal.DownloadManager.contextProvider = provider;
    }

    /** 注册下载地址嗅探器(type0 嗅探源用;:app 模块实现并注入) */
    public static void setUrlSniffer(com.github.tvbox.osc.download.DownloadUrlSniffer sniffer) {
        com.github.tvbox.osc.download.internal.DownloadManager.setUrlSniffer(sniffer);
    }

    // ------------------------------------------------------------------
    // 查询快照
    // ------------------------------------------------------------------

    /**
     * 剧集级展示态（一次快照）：
     *
     * @param videoId      sourceKey|vodId
     * @param playFlag     线路名（episodeId 组成部分）
     * @param episodeCount 集数
     */
    public int[] queryEpisodes(String videoId, String playFlag, int episodeCount) {
        int[] states = new int[Math.max(0, episodeCount)];
        if (!readyForRead()) return states;
        List<DownloadTask> tasks = DownloadManager.get().getTasks();
        for (int i = 0; i < states.length; i++) {
            String episodeId = buildEpisodeId(videoId, playFlag, i);
            DownloadTask t = findTask(tasks, episodeId);
            if (t != null) {
                states[i] = mapTaskState(t);
                continue;
            }
            states[i] = DownloadArchive.get().isDownloaded(episodeId) ? ST_DOWNLOADED : ST_NOT_DOWNLOADED;
        }
        return states;
    }

    /** 视频级聚合（已下载/下载中/已暂停/失败/未下载） */
    public VideoSummary queryVideo(String videoId, String playFlag, int episodeCount) {
        VideoSummary sum = new VideoSummary();
        sum.total = Math.max(0, episodeCount);
        int[] states = queryEpisodes(videoId, playFlag, episodeCount);
        for (int s : states) {
            switch (s) {
                case ST_DOWNLOADED:
                    sum.downloaded++;
                    break;
                case ST_DOWNLOADING:
                    sum.downloading++;
                    break;
                case ST_PAUSED:
                    sum.paused++;
                    break;
                case ST_FAILED:
                    sum.failed++;
                    break;
                default:
                    sum.notDownloaded++;
            }
        }
        return sum;
    }

    /** 单任务详情（进度等） */
    public DownloadTask getTask(String episodeId) {
        if (episodeId == null) return null;
        for (DownloadTask t : getTasks()) {
            if (episodeId.equals(t.episodeId)) return t;
        }
        return null;
    }

    public List<DownloadTask> getTasks() {
        if (!readyForRead()) return Collections.emptyList();
        return DownloadManager.get().getTasks();
    }

    // ------------------------------------------------------------------
    // 入队 / 队列控制（改进.txt 第一阶段:UI 只走 Facade,不再直接调 DownloadManager）
    // ------------------------------------------------------------------

    /** 入队(与详情页原 DownloadManager.enqueue 全参数一致;headers 可为 null) */
    public boolean enqueue(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String pic, java.util.Map<String, String> headers,
                           String sourceName, String vodName, String episodeName) {
        if (!readyForEnqueue()) {
            if (Looper.myLooper() != Looper.getMainLooper()) return false;
            DownloadRequest copy = new DownloadRequest(url, sourceKey, playFlag, episodeRawUrl,
                    episodeId, pic, headers, sourceName, vodName, episodeName);
            DownloadManager manager = DownloadManager.get();
            manager.whenBooted(() -> manager.enqueue(copy.url, copy.sourceKey, copy.playFlag,
                    copy.episodeRawUrl, copy.episodeId, copy.pic, copy.headers,
                    copy.sourceName, copy.vodName, copy.episodeName));
            return true;
        }
        return DownloadManager.get().enqueue(url, sourceKey, playFlag, episodeRawUrl,
                episodeId, pic, headers, sourceName, vodName, episodeName);
    }

    /** 入队(DownloadRequest 化入口:UI 只构造请求对象,见改进.txt §六下载) */
    public EnqueueResult enqueue(DownloadRequest request) {
        if (request == null) return EnqueueResult.of(EnqueueResult.Code.INVALID_REQUEST, "下载请求为空");
        if (!readyForEnqueue()) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                return EnqueueResult.of(EnqueueResult.Code.STORAGE_ERROR, "下载记录装载超时，请稍后重试");
            }
            DownloadManager manager = DownloadManager.get();
            manager.whenBooted(() -> manager.enqueueResult(request.url, request.sourceKey, request.playFlag,
                    request.episodeRawUrl, request.episodeId, request.pic, request.headers,
                    request.sourceName, request.vodName, request.episodeName, request.altRoutes,
                    request.requiresResolution));
            return EnqueueResult.queued(null, "下载记录装载中，任务已排队");
        }
        return DownloadManager.get().enqueueResult(request.url, request.sourceKey, request.playFlag,
                request.episodeRawUrl, request.episodeId, request.pic, request.headers,
                request.sourceName, request.vodName, request.episodeName, request.altRoutes, request.requiresResolution);
    }

    /** 按任务对象暂停 */
    public void pause(DownloadTask t) {
        if (t != null) {
            DownloadManager manager = DownloadManager.get();
            manager.whenBooted(() -> manager.pause(t));
        }
    }

    /** 按任务对象恢复 */
    public void resume(DownloadTask t) {
        if (t != null) {
            DownloadManager manager = DownloadManager.get();
            manager.whenBooted(() -> manager.resume(t));
        }
    }

    /** 暂停全部 */
    public void pauseAll() {
        DownloadManager manager = DownloadManager.get();
        manager.whenBooted(manager::pauseAll);
    }

    /** 恢复全部 */
    public void startAll() {
        DownloadManager manager = DownloadManager.get();
        manager.whenBooted(manager::startAll);
    }

    /** 删除任务(不删文件) */
    public void remove(DownloadTask t) {
        if (t != null) {
            DownloadManager manager = DownloadManager.get();
            manager.whenBooted(() -> manager.remove(t));
        }
    }

    /** 删除任务(deleteFiles=true 连文件一起删) */
    public void remove(DownloadTask t, boolean deleteFiles) {
        if (t != null) {
            DownloadManager manager = DownloadManager.get();
            manager.whenBooted(() -> manager.remove(t, deleteFiles));
        }
    }

    /** 按本地路径清理下载任务(本地文件删除联动),返回清理条数 */
    public int removeTasksByPath(String savePath) {
        if (!readyForRead()) {
            DownloadManager manager = DownloadManager.get();
            manager.whenBooted(() -> manager.removeTasksByPath(savePath));
            return 0;
        }
        return DownloadManager.get().removeTasksByPath(savePath);
    }

    /** 按本地路径删除档案(与 removeTasksByPath 配套,一次调用完成文件联动) */
    public boolean removeArchiveByPath(String savePath) {
        if (!readyForRead()) {
            DownloadManager.get().whenBooted(() -> removeArchiveByPathNow(savePath));
            return false;
        }
        return removeArchiveByPathNow(savePath);
    }

    private boolean removeArchiveByPathNow(String savePath) {
        ArchiveItem item = DownloadArchive.get().findByPath(savePath);
        boolean removed = DownloadArchive.get().removeByPath(savePath);
        if (removed && item != null) {
            DownloadManager.get().requestPosterCleanup(item.vodName, savePath, null);
        }
        return removed;
    }

    /** 海报本地文件(不存在返回 null) */
    public java.io.File getPosterFile(String vodName) {
        return DownloadManager.getPosterFile(vodName);
    }

    /** 异步拉取并缓存海报文件 */
    public void ensurePosterAsync(String pic, String vodName) {
        DownloadManager.get().ensurePosterAsync(pic, vodName);
    }

    /** Recheck a series after its confirmed batch file deletion. */
    public void requestPosterCleanup(String vodName) {
        DownloadManager manager = DownloadManager.get();
        manager.whenBooted(() -> manager.requestPosterCleanup(vodName, null, null));
    }

    // ------------------------------------------------------------------
    // 排队 / 插队（4.4，按 episodeId 操作）
    // ------------------------------------------------------------------

    /** 排队插队(温和): 提到队首, 不打断运行中任务 */
    public void moveToFront(String episodeId) {
        DownloadManager manager = DownloadManager.get();
        manager.whenBooted(() -> {
            DownloadTask t = findTask(manager.getTasks(), episodeId);
            if (t != null) manager.moveToFront(t);
        });
    }

    /** 设置优先级(HIGH/NORMAL/LOW); 置 HIGH 且并发满时抢占让位(被抢占者排最前) */
    public void setPriority(String episodeId, int level) {
        DownloadManager manager = DownloadManager.get();
        manager.whenBooted(() -> {
            DownloadTask t = findTask(manager.getTasks(), episodeId);
            if (t != null) manager.setPriority(t, level);
        });
    }

    // ------------------------------------------------------------------
    // 任务日志（委托 LogStore，7 天）
    // ------------------------------------------------------------------

    public List<LogEntry> getTaskLog(String episodeId, int limit, int offset) {
        if (LogStore.get() == null) return null;
        return LogStore.get().queryByTask(episodeId, limit, offset);
    }

    // ------------------------------------------------------------------
    // 事件订阅
    // ------------------------------------------------------------------

    public void register(DownloadStatusListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void unregister(DownloadStatusListener l) {
        listeners.remove(l);
    }

    /** 订阅任务级进度（高频；按 taskId 局部刷新用） */
    public void registerProgress(TaskProgressListener l) {
        if (l != null && !progressListeners.contains(l)) progressListeners.add(l);
    }

    public void unregisterProgress(TaskProgressListener l) {
        progressListeners.remove(l);
    }

    // ------------------------------------------------------------------
    // 配置门面(仅 WiFi / 并发 / 网络判定 / 保存目录;单一事实源在 DownloadManager)
    // ------------------------------------------------------------------

    /** 是否仅 WiFi 下载(默认开启;开启时蜂窝/断网不启动并自动挂起,切换开关即时生效) */
    public boolean isWifiOnly() {
        return com.github.tvbox.osc.download.internal.DownloadManager.get().isWifiOnly();
    }

    public boolean isAutoResume() { return com.github.tvbox.osc.download.internal.DownloadManager.get().isAutoResume(); }
    public void setAutoResume(boolean enabled) { com.github.tvbox.osc.download.internal.DownloadManager.get().setAutoResume(enabled); }

    public void setWifiOnly(boolean wifiOnly) {
        com.github.tvbox.osc.download.internal.DownloadManager.get().setWifiOnly(wifiOnly);
    }

    /** 最大并发下载数(**1-3**;2026-10-01 上限由 5 收到 3) */
    public int getMaxConcurrent() {
        return com.github.tvbox.osc.download.internal.DownloadManager.get().getMaxConcurrent();
    }

    /** 设置最大并发数(1-3,上限见 DownloadPolicy.MAX_CONCURRENT),触发重新调度 */
    public void setMaxConcurrent(int n) {
        com.github.tvbox.osc.download.internal.DownloadManager.get().setMaxConcurrent(n);
    }

    /** 全局限速(字节/秒;0=不限速):UI 设置项,持久化,改后对运行中任务立即生效 */
    public long getSpeedLimitBytesPerSec() {
        return com.github.tvbox.osc.download.internal.DownloadManager.get().getSpeedLimitBytesPerSec();
    }

    /** 设置全局限速(字节/秒;0=不限速) */
    public void setSpeedLimitBytesPerSec(long bytesPerSecond) {
        com.github.tvbox.osc.download.internal.DownloadManager.get().setSpeedLimitBytesPerSec(bytesPerSecond);
    }

    /** 当前网络是否为移动网络(蜂窝) */
    public boolean isMobileNetwork() {
        return com.github.tvbox.osc.download.internal.DownloadManager.isMobileNetwork();
    }

    /** 下载保存根目录 */
    public java.io.File getSaveDir() {
        return com.github.tvbox.osc.download.internal.DownloadManager.getSaveDir();
    }

    /** 是否已具备存储权限(Android 11+ 需「所有文件访问」;无权限时 enqueue 会被拒绝) */
    public boolean hasStoragePermission() {
        return com.github.tvbox.osc.download.internal.FileCleaner.hasStoragePermission();
    }

    // ------------------------------------------------------------------
    // 剧集状态(统一 EpisodeId 语义,见 DownloadCore;UI 不再直读下载内部实现)
    // ------------------------------------------------------------------

    /** 构建统一剧集标识: sourceKey|vodId|playFlag|playIndex */
    public String buildEpisodeId(String sourceKey, String vodId, String playFlag, int playIndex) {
        return com.github.tvbox.osc.download.internal.DownloadCore.buildEpisodeId(sourceKey, vodId, playFlag, playIndex);
    }

    /** 批量查询剧集状态:0=未下载;1=已下载完成且文件存在;2=已有任务(下载中/排队/暂停);3=失败 */
    public int[] getEpisodeStates(String[] episodeIds, String sourceName, String vodName, String[] episodeNames) {
        if (!readyForRead()) return new int[episodeIds == null ? 0 : episodeIds.length];
        return com.github.tvbox.osc.download.internal.DownloadCore.getEpisodeStates(episodeIds, sourceName, vodName, episodeNames);
    }

    /** 单集下载状态(语义同上) */
    public int getEpisodeState(String episodeId, String sourceName, String vodName, String episodeName) {
        if (!readyForRead()) return 0;
        return com.github.tvbox.osc.download.internal.DownloadCore.getEpisodeState(episodeId, sourceName, vodName, episodeName);
    }

    // ------------------------------------------------------------------
    // 档案管理（长期保留）
    // ------------------------------------------------------------------

    /** 某视频的已下载列表（下载完成 tab 数据源） */
    public List<ArchiveItem> queryArchive(String videoId) {
        if (!readyForRead()) return Collections.emptyList();
        return DownloadArchive.get().queryByVideo(videoId);
    }

    public ArchiveItem getArchive(String episodeId) {
        if (!readyForRead()) return null;
        return DownloadArchive.get().get(episodeId);
    }

    /** 删除档案（deleteFile=true 连文件一起删） */
    public boolean deleteArchive(String episodeId, boolean deleteFile) {
        if (!readyForRead()) {
            DownloadManager.get().whenBooted(() -> deleteArchiveNow(episodeId, deleteFile));
            return false;
        }
        return deleteArchiveNow(episodeId, deleteFile);
    }

    private boolean deleteArchiveNow(String episodeId, boolean deleteFile) {
        ArchiveItem item = DownloadArchive.get().get(episodeId);
        boolean removed = DownloadArchive.get().remove(episodeId, deleteFile);
        // 文件都要删了,同一集的"已完成"任务记录也必须一起清掉:
        // 否则它会挡住重新下载(入队按 episodeId 判重)却又不在列表显示
        // (下载列表只聚合"未完成任务 + 已完成且文件存在"),用户看到的就是
        //「所选剧集已在下载任务中,可下载列表里什么都没有」。
        DownloadManager.get().removeTasksByEpisode(episodeId);
        if (removed && item != null) {
            DownloadManager.get().requestPosterCleanup(item.vodName, item.savePath, null);
        }
        return removed;
    }

    /** 重命名成品文件（同步更新档案） */
    public boolean renameArchive(String episodeId, String newName) {
        if (!readyForRead()) {
            DownloadManager.get().whenBooted(() -> DownloadArchive.get().rename(episodeId, newName));
            return false;
        }
        return DownloadArchive.get().rename(episodeId, newName);
    }

    /** 某剧(名称+源名)的已下载档案(下载管理页分组用) */
    public List<ArchiveItem> queryArchiveByVod(String vodName, String sourceName) {
        if (!readyForRead()) return Collections.emptyList();
        return DownloadArchive.get().queryByVod(vodName, sourceName);
    }

    /** 全部已下载档案(下载管理页分组数据源) */
    public List<ArchiveItem> getAllArchive() {
        if (!readyForRead()) return Collections.emptyList();
        return DownloadArchive.get().getAll();
    }

    /** 按成品文件路径查档案(下载管理页删除完成项定位用) */
    public ArchiveItem findArchiveByPath(String savePath) {
        if (!readyForRead()) return null;
        return DownloadArchive.get().findByPath(savePath);
    }

    /** 删除某剧的孤儿档案记录(文件名全没了只剩档案) */
    public int removeArchiveOrphansByVod(String vodName, String sourceName) {
        if (!readyForRead()) {
            DownloadManager.get().whenBooted(() -> removeArchiveOrphansByVodNow(vodName, sourceName));
            return 0;
        }
        return removeArchiveOrphansByVodNow(vodName, sourceName);
    }

    private int removeArchiveOrphansByVodNow(String vodName, String sourceName) {
        int removed = DownloadArchive.get().removeOrphansByVod(vodName, sourceName);
        if (removed > 0) DownloadManager.get().requestPosterCleanup(vodName, null, null);
        return removed;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static String buildEpisodeId(String videoId, String playFlag, int playIndex) {
        return (videoId == null ? "" : videoId) + "|"
                + (playFlag == null ? "" : playFlag) + "|" + playIndex;
    }

    private static DownloadTask findTask(List<DownloadTask> tasks, String episodeId) {
        if (episodeId == null || episodeId.isEmpty()) return null;
        for (DownloadTask t : tasks) {
            if (episodeId.equals(t.episodeId)) return t;
        }
        return null;
    }

    private static int mapTaskState(DownloadTask t) {
        switch (t.state) {
            case DownloadTask.STATE_COMPLETED:
                return t.savePath != null && new File(t.savePath).exists() ? ST_DOWNLOADED : ST_NOT_DOWNLOADED;
            case DownloadTask.STATE_PAUSED:
                return ST_PAUSED;
            case DownloadTask.STATE_FAILED:
                return ST_FAILED;
            default:
                // WAITING / DOWNLOADING / SYSTEM_PAUSED / NETWORK_PAUSED
                return ST_DOWNLOADING;
        }
    }
}

package com.github.tvbox.osc.download.internal;

import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.util.OkGoHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * 下载管理器（门面）——5.1 已按职责拆分为五个内部组件，行为零变化：
 * <ul>
 *   <li>{@link DownloadStore}    任务记录器：任务列表 / 查询 / 对账 / 海报</li>
 *   <li>{@link DownloadScheduler} 任务调度器：队列 / 并发 / 启停 / 生命周期</li>
 *   <li>{@link DownloadExecutor}  下载执行单元：直链 / HLS 分片 / 校验 / 合并</li>
 *   <li>{@link DownloadPolicy}    决策器：并发 / 仅WiFi / 磁盘水位</li>
 *   <li>{@link FileCleaner}       文件清理器：删除 / 复制 / 保存目录</li>
 * </ul>
 * 对外 API（enqueue/pause/resume/remove/查询等）保持不变，调用方（详情页/下载页/配置门面）无感。
 */
public class DownloadManager {

    static final String HAWK_KEY = "download_tasks_v1";
    static final String HAWK_MAX_CONCURRENT = "download_max_concurrent";
    static final String HAWK_WIFI_ONLY = "download_wifi_only";
    /** 全局限速(存 KB/s,0=不限速;见 DownloadPolicy.getSpeedLimitBytesPerSec) */
    static final String HAWK_SPEED_LIMIT_KBPS = "download_speed_limit_kbps";
    static final int BUFFER = 64 * 1024;

    private static DownloadManager instance;

    public static volatile com.github.tvbox.osc.download.DownloadRequestContextProvider contextProvider;

    public boolean isAutoResume() { return policy.isAutoResume(); }
    public void setAutoResume(boolean enabled) { policy.setAutoResume(enabled); }

    /** 播放地址解析契约(:spider 实现经 App 组合根注入;download 模块不依赖 :spider 实现) */
    static volatile com.github.tvbox.osc.spiderapi.PlayUrlResolverApi urlResolverApi =
                com.github.tvbox.osc.spiderapi.PlayUrlResolverApi.NONE;

    public static void setUrlResolverApi(com.github.tvbox.osc.spiderapi.PlayUrlResolverApi api) {
        if (api != null) {
            urlResolverApi = api;
            android.util.Log.i("TVBox-Download", "setUrlResolverApi: 已注入播放地址解析契约实现=" + api.getClass().getName());
        }
    }

    /** 注入的 application context（独立模块 :download，App 启动时 init 注入） */
    static volatile android.content.Context appContext;

    /** 数据加载/组件启动完成标记；仅在后台 boot 全部结束后发布。 */
    private volatile boolean booted = false;
    private boolean bootScheduled = false;
    private final Object bootLock = new Object();
    private final List<Runnable> pendingBootActions = new ArrayList<>();
    /** 下载模块自己的串行启动执行器，不占首页预取与动画线程。 */
    private final ExecutorService bootExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvbox-download-boot");
        t.setDaemon(true);
        return t;
    });

    final List<DownloadTask> tasks = new ArrayList<>();
    final Object lock = new Object();

    /** 持久化:后台单线程执行,避免主线程批量入队时被磁盘 IO 卡死 */
    private final ExecutorService persistExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvbox-persist");
        t.setDaemon(true);
        return t;
    });
    /** 大小预检:后台单线程(HEAD/播放列表请求轻量),不占下载线程与持久化线程 */
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvbox-size");
        t.setDaemon(true);
        return t;
    });
    /** 持久化合并锁:始终只写最新快照,批量入队不会重复写几十次 */
    private final Object persistLock = new Object();
    private List<DownloadTask> pendingSnapshot = null;
    private boolean writeScheduled = false;

    /** 每个任务当前活动的 HTTP 响应,用于暂停/删除时关闭对应连接 */
    final Map<String, Response> activeResponses = new ConcurrentHashMap<>();
    /**
     * 下载专用客户端:基于 :core-network 公共根(共享 TLS/UA/Brotli/DNS),超时比播放请求长。
     * <p>
     * 为什么是"懒建 + 可替换"而不是 {@code static final}:DoH 是 build 时写进 client 的,
     * 定死实例就要求用户重启才能换安全 DNS。改为 volatile + 统一 {@link #buildDownloadClient()} 后,
     * 安全 DNS 变更时整体换新实例,下一次取用即用新 DNS。
     * 旧实例<b>不 shutdown</b>:它可能正被 {@link DownloadExecutor}/{@link DownloadStore} 的在跑请求持有,
     * 主动关闭会打断正在下的任务;让它们自然结束后交给 GC。
     */
    static volatile OkHttpClient downloadClient = null;

    /** 取下载客户端(懒建):首次取用与每次 DoH 变更后各建一次,不在请求热路径上重复构建 */
    static OkHttpClient downloadClient() {
        OkHttpClient c = downloadClient;
        if (c == null) {
            synchronized (DownloadManager.class) {
                c = downloadClient;
                if (c == null) {
                    c = buildDownloadClient();
                    downloadClient = c;
                }
            }
        }
        return c;
    }

    private static OkHttpClient buildDownloadClient() {
        // 静默根(false):下载是后台链路——自动续传/恢复网络后重试失败不该把用户弹到"网络不可用"整屏页
        // (用户可能正在看本地视频/本地文件)。仍会快速失败,只是不上报。
        return OkGoHelper.newBaseBuilder(false)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    // :download 是业务模块,订阅 :core-network 的 DoH 变更属正向依赖(基础模块不认识业务模块);
    // 放在静态初始化:本类被首次触碰(DownloadFacade.init)即完成订阅,不依赖 app 侧另行调用。
    static {
        OkGoHelper.addDohChangeListener(url -> downloadClient = null);
    }

    /** 失败自动重试次数(不含首次) */
    static final int MAX_RETRY = 2;
    /** 网络错误(断网/切网)重试次数上限(不含首次),配合退避最长约2分钟 */
    static final int MAX_NETWORK_RETRY = 8;
    /** 地址可能过期时重新解析地址的次数上限(每次解析后重置下载重试计数) */
    static final int MAX_RE_RESOLVE = 3;
    /** 碎片校验后自动补下缺失分片的最大轮数 */
    static final int MAX_SEGMENT_REPAIR = 3;

    /** 分段信息 TXT 文件名 */
    public static final String SEGMENTS_INFO = "segments.txt";
    // ── 任务阶段文案：唯一权威在 DownloadFacade(对外门面)，内部仅作兼容转发 ──
    public static final String MSG_VERIFYING = com.github.tvbox.osc.download.DownloadFacade.MSG_VERIFYING;
    public static final String MSG_MERGING = com.github.tvbox.osc.download.DownloadFacade.MSG_MERGING;
    public static final String MSG_REMUX = com.github.tvbox.osc.download.DownloadFacade.MSG_REMUX;
    public static final String MSG_REPAIRING = com.github.tvbox.osc.download.DownloadFacade.MSG_REPAIRING;

    /**
     * 事件下沉口:由对外门面 {@link com.github.tvbox.osc.download.DownloadFacade} 注册,
     * 替代历史内部 EventBus 广播(全模块仅一个订阅者,直调即可)。
     */
    public interface EventSink {
        /** 结构性变更(新增/删除/状态机切换/批量),UI 全量刷新信号;已去抖,调用在主线程 */
        void onStructuralChange();
        /** 任务进度推进(节流后),UI 局部刷新;调用线程不保证主线程,由消费方自行切主线程 */
        void onTaskProgress(String taskId);
    }

    private volatile EventSink eventSink;

    /** 注册事件下沉口(门面构造时调用一次) */
    public void setEventSink(EventSink sink) {
        this.eventSink = sink;
    }

    /** 结构性变更事件去抖:合并为至多每 500ms 下沉一次(全量刷新信号);
     *  高频"进度"变更不再走这里(见 flushProgress -> onTaskProgress,带任务id,UI 局部刷新) */
    private final Handler notifyHandler = new Handler(Looper.getMainLooper());
    private final Runnable notifyRunnable = () -> {
        EventSink s = eventSink;
        if (s != null) s.onStructuralChange();
    };

    // ------------------------------------------------------------------
    // 内部组件（5.1 拆分）
    // ------------------------------------------------------------------

    final DownloadStore store;
    final DownloadScheduler scheduler;
    final DownloadExecutor executor;
    final DownloadPolicy policy;
    /**
     * 存储看门狗(全下载链路唯一实现,见 {@link StorageWatchdog}):可用空间低于 1GB 时暂停全部任务。
     * 由调度闸门(不许新开)、下载热路径(写循环自检)、后台巡检(2s 一轮)三处共用同一实例。
     */
    final StorageWatchdog watchdog;
    /** 已下载档案（长期保留，5.3） */
    final com.github.tvbox.osc.download.internal.DownloadArchive archive;

    private DownloadManager() {
        store = new DownloadStore(this);
        scheduler = new DownloadScheduler(this);
        executor = new DownloadExecutor(this);
        policy = new DownloadPolicy(this);
        watchdog = new StorageWatchdog(this);
        archive = com.github.tvbox.osc.download.internal.DownloadArchive.get();
    }

    /** 在首页首帧后或下载功能被独立唤起时启动，不在 Application 主线程读盘。 */
    public void startBackgroundBoot() {
        synchronized (bootLock) {
            if (appContext == null || bootScheduled || booted) return;
            bootScheduled = true;
        }
        try {
            bootExecutor.execute(() -> {
            try {
                // 先确认档案可读，再迁移旧分片目录；读取失败时尽量不动用户文件。
                archive.load();
                boolean taskStateChanged = store.load();
                // 对账和递归清理必须在调度器开跑前完成，避免误删正在写入的分片。
                FileCleaner.cleanupOrphanTmpDirs(tasks);
                if (taskStateChanged) persist();
                scheduler.startWorker();
                scheduler.subscribeNetworkEvents();
                // 装载期间到达的操作依次执行；队列清空与 ready 发布用同一把锁，
                // 新操作不能越过较早排队的删除或入队。
                while (true) {
                    List<Runnable> queued;
                    synchronized (bootLock) {
                        if (pendingBootActions.isEmpty()) {
                            booted = true;
                            bootScheduled = false;
                            bootLock.notifyAll();
                            break;
                        }
                        queued = new ArrayList<>(pendingBootActions);
                        pendingBootActions.clear();
                    }
                    for (Runnable action : queued) {
                        try {
                            action.run();
                        } catch (Throwable th) {
                            android.util.Log.e("TVBox-Download", "下载启动后操作失败", th);
                        }
                    }
                }
                try {
                    notifyChanged();
                } catch (Throwable th) {
                    android.util.Log.e("TVBox-Download", "下载记录就绪通知失败", th);
                }
            } catch (Throwable th) {
                android.util.Log.e("TVBox-Download", "后台装载下载记录失败", th);
                // 失败时不发布空记录、不执行待处理写操作；下次访问可重新装载。
                synchronized (bootLock) {
                    bootScheduled = false;
                    bootLock.notifyAll();
                }
            }
            });
        } catch (Throwable th) {
            synchronized (bootLock) {
                bootScheduled = false;
                bootLock.notifyAll();
            }
            android.util.Log.e("TVBox-Download", "无法安排下载记录后台装载", th);
        }
    }

    public boolean isBooted() {
        return booted;
    }

    /** UI 的无返回值操作在加载期间排队，加载完成后由模块执行器顺序执行。 */
    public void whenBooted(Runnable action) {
        if (action == null) return;
        boolean queued = false;
        synchronized (bootLock) {
            if (!booted) {
                pendingBootActions.add(action);
                queued = true;
            }
        }
        if (queued) {
            startBackgroundBoot();
            return;
        }
        action.run();
    }

    /** 仅供已有后台入队调用保留精确结果；主线程绝不调用此等待。 */
    public boolean awaitBootInBackground(long timeoutMs) {
        startBackgroundBoot();
        if (Looper.myLooper() == Looper.getMainLooper()
                || Thread.currentThread().getName().equals("tvbox-download-boot")) return false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        try {
            synchronized (bootLock) {
                while (!booted && bootScheduled) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) return false;
                    TimeUnit.NANOSECONDS.timedWait(bootLock, remaining);
                }
                return booted;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static synchronized DownloadManager get() {
        if (instance == null) {
            instance = new DownloadManager();
        }
        return instance;
    }

    /** App 启动时调用一次：注入 context（保存目录/海报/网络监听等用，独立模块不依赖 app 类） */
    public static void init(android.content.Context context) {
        appContext = context == null ? null : context.getApplicationContext();
        FileCleaner.setAppContext(appContext);
        DownloadStore.setAppContext(appContext);
        com.github.tvbox.osc.download.internal.DownloadNotifier.init(context);
        // Application 只注入 Context；首页首帧、下载服务或下载 API 的首次访问负责触发装载。
        DownloadManager manager = get();
        synchronized (manager.bootLock) {
            if (manager.pendingBootActions.isEmpty()) return;
        }
        manager.startBackgroundBoot();
    }

    // ------------------------------------------------------------------
    // 下载地址嗅探器（方案 A：无头 WebView 串行复用，:app 模块实现并注册）
    // ------------------------------------------------------------------

    private static volatile com.github.tvbox.osc.download.DownloadUrlSniffer urlSniffer;

    /** App 启动时注册：嗅探型源（type 0）任务启动前用它嗅探剧集页拿真实播放地址 */
    public static void setUrlSniffer(com.github.tvbox.osc.download.DownloadUrlSniffer sniffer) {
        urlSniffer = sniffer;
    }

    /** 当前注册的嗅探器；未注册（非嗅探型源场景）返回 null */
    public static com.github.tvbox.osc.download.DownloadUrlSniffer getUrlSniffer() {
        return urlSniffer;
    }

    // ------------------------------------------------------------------
    // 基础设施（组件共用）
    // ------------------------------------------------------------------

    /** 持久化(异步+合并):后台单线程写私有文件,且始终写最新快照 */
    void persist() {
        List<DownloadTask> snapshot;
        synchronized (tasks) {
            snapshot = new ArrayList<>(tasks);
        }
        synchronized (persistLock) {
            pendingSnapshot = snapshot;
            if (writeScheduled) return; // 已有写任务在跑,跑完会再写最新快照
            writeScheduled = true;
        }
        persistExecutor.execute(() -> {
            while (true) {
                List<DownloadTask> toWrite;
                synchronized (persistLock) {
                    toWrite = pendingSnapshot;
                    pendingSnapshot = null;
                }
                try {
                    DownloadStore.writeTasksFile(toWrite);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                synchronized (persistLock) {
                    if (pendingSnapshot == null) {
                        writeScheduled = false;
                        return;
                    }
                }
            }
        });
    }

    void notifyChanged() {
        // 结构/状态变更去抖:高频事件合并,避免下载页被刷屏(进度高频走 flushProgress)
        notifyHandler.removeCallbacks(notifyRunnable);
        notifyHandler.postDelayed(notifyRunnable, 500);
        // 前台服务保活:调度状态变化后同步(有下载中→启动/刷新, 无→停止)
        updateForegroundService();
    }

    // ------------------------------------------------------------------
    // 进度型刷新(任务 A/B 共用):高频进度只更新内存,落盘+广播合并到窗口内
    // ------------------------------------------------------------------

    /** 高频进度(直链窗口 / HLS 每分片 / 合并进度)落盘与广播的合并窗口(ms)。
     * 450ms ≈ 2.2Hz:网速/百分比文本保持约半秒级平滑刷新,同时落盘频率仍有上限。 */
    private static final long PROGRESS_FLUSH_MS = 450L;
    /**
     * 进度快照真正落盘的最小间隔(ms),与上面的"广播窗口"分开:UI 刷新要跟手,磁盘快照不必同样频繁。
     * <p>
     * 审计发现:下载中整表 JSON(内存全量序列化 + 写 .tmp + rename)每 450ms 就被重写一次(≈2.2 次/秒),
     * 长时间下载时是持续的 CPU/IO 与发热来源。降到 2s 的代价是"进程被杀最多丢约 2s 的进度":
     * 直链续传启动时还会用 {@code .part} 实际长度对账、HLS 以磁盘上已 rename 的分片为准,
     * 实际能丢的只有这 2s 内的计数,重下量极小。
     */
    private static final long PROGRESS_PERSIST_MS = 2000L;
    /** 进度节流共享锁(多下载线程并发调用) */
    private final Object progressLock = new Object();
    /** 上次实际落盘时间戳 */
    private long lastProgressFlush = 0L;
    /** 上次进度快照落盘时间(仅 progressLock 内读写) */
    private long lastProgressPersist = 0L;
    /** 上次落盘快照的进度指纹(仅 progressLock 内读写);见 {@link #progressSignature(List)} */
    private long lastPersistSignature = 0L;
    /** 是否已有指纹基线(false=第一次进度必落盘) */
    private boolean hasPersistSignature = false;
    /** 窗口内最近一次进度的任务 id(兜底广播携带它,UI 据此局部刷新该行,不漏尾部) */
    private String pendingProgressTaskId = null;
    /** 是否已安排窗口末的兜底落盘定时器 */
    private boolean progressTimerScheduled = false;
    private final Handler progressFlushHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTimerRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (progressLock) {
                progressTimerScheduled = false;
                lastProgressFlush = System.currentTimeMillis();
            }
            doProgressFlush();
        }
    };

    /**
     * 进度型刷新(高频调用,如每下载一个 HLS 分片/直链每 800ms 窗口/合并进度):
     * 被节流的是"任务列表快照的磁盘落盘 + 进度广播"——进度字段(下载字节/分片数/网速等)
     * 由执行器线程直接写任务对象内存,其余代码读取时始终是最新内存值,不受节流影响。
     * <p>
     * 策略:窗口(450ms)内多次调用只广播一次(窗口满后的调用立即执行);
     * 窗口末尾由主线程定时器兜底一次,持续进度不漏尾部;
     * 落盘比广播更粗(另有 {@link #PROGRESS_PERSIST_MS} 间隔 + 进度指纹两道闸,见 persistProgressIfChanged)。
     * 暂停/失败/完成等终态事件不经过本方法,由调用方 persist+notifyChanged 立即强制落盘(不丢终态)。
     */
    void flushProgress(DownloadTask t) {
        String id = t == null ? null : t.id;
        boolean fireNow = false;
        boolean needTimer = false;
        long delay = PROGRESS_FLUSH_MS;
        synchronized (progressLock) {
            pendingProgressTaskId = id; // 记住最近进度任务(窗口末兜底事件带它)
            long now = System.currentTimeMillis();
            if (now - lastProgressFlush >= PROGRESS_FLUSH_MS) {
                lastProgressFlush = now;
                fireNow = true;
            } else if (!progressTimerScheduled) {
                progressTimerScheduled = true;
                needTimer = true;
                delay = Math.max(1L, lastProgressFlush + PROGRESS_FLUSH_MS - now);
            }
        }
        if (fireNow) {
            doProgressFlush();
        } else if (needTimer) {
            progressFlushHandler.postDelayed(progressTimerRunnable, delay);
        }
    }

    /** 执行一次进度落盘 + 轻量下沉(带最近进度任务 id);锁外调用,幂等 */
    private void doProgressFlush() {
        String id;
        synchronized (progressLock) {
            id = pendingProgressTaskId;
            pendingProgressTaskId = null;
        }
        persistProgressIfChanged();
        if (id != null) {
            EventSink s = eventSink;
            if (s != null) s.onTaskProgress(id);
        }
    }

    /**
     * 进度快照落盘(两道闸,替代原先每次窗口都无条件 persist):
     * ① 距上次落盘不足 {@link #PROGRESS_PERSIST_MS} 不写(把 2.2 次/秒压到 0.5 次/秒);
     * ② 任务表的"进度指纹"与上次落盘的一致不写——缓冲/校验/等待重试等阶段回调照来,
     *    但没有任何可持久化的变化,整表 JSON 重写纯属浪费(没有下载在推进时连一次写盘都没有)。
     * <p>
     * 只是"相等即跳过"的保守判断:指纹覆盖进度回调会改的全部字段(下载字节/分片数/合并文案等),
     * 而结构性变更(入队/暂停/完成/删除/改地址)都有各自的强制 {@link #persist()},
     * 不经过这里,所以跳过不会漏掉任何"立即落盘"的状态。
     */
    private void persistProgressIfChanged() {
        synchronized (progressLock) {
            if (System.currentTimeMillis() - lastProgressPersist < PROGRESS_PERSIST_MS) return;
        }
        List<DownloadTask> snapshot;
        synchronized (tasks) {
            snapshot = new ArrayList<>(tasks);
        }
        long signature = progressSignature(snapshot);
        synchronized (progressLock) {
            long now = System.currentTimeMillis();
            if (now - lastProgressPersist < PROGRESS_PERSIST_MS) return; // 多下载线程并发进来的二次确认
            if (hasPersistSignature && signature == lastPersistSignature) return;
            lastProgressPersist = now;
            lastPersistSignature = signature;
            hasPersistSignature = true;
        }
        persist();
    }

    /**
     * 进度指纹:进度回调(直链字节数 / HLS 已完成分片 / 合并百分比文案)会改的字段的轻量哈希。
     * 只做内存比较(不序列化、不碰磁盘),用来判断"这次的窗口里到底有没有值得重写整表的变化"。
     * <p>
     * 包内可见(非 private)是为了可 JVM 单测:见 DownloadProgressSignatureTest。
     */
    static long progressSignature(List<DownloadTask> snapshot) {
        long h = 1L;
        for (DownloadTask t : snapshot) {
            if (t == null) continue;
            h = h * 31 + (t.id == null ? 0 : t.id.hashCode());
            h = h * 31 + t.state;
            h = h * 31 + t.downloadedBytes;
            h = h * 31 + t.totalBytes;
            h = h * 31 + t.doneSegments;
            h = h * 31 + t.totalSegments;
            h = h * 31 + t.segmentBytes;
            h = h * 31 + (t.message == null ? 0 : t.message.hashCode());
        }
        return h;
    }

    /** 前台服务保活(可选增强):存在下载中/等待任务时拉起,全部结束停止;进度通知去抖由 persist 频控 */
    private void updateForegroundService() {
        if (appContext == null) return;
        try {
            List<DownloadTask> snapshot;
            synchronized (tasks) {
                snapshot = new ArrayList<>(tasks);
            }
            com.github.tvbox.osc.download.internal.DownloadForegroundService.startIfNeeded(appContext, snapshot);
            com.github.tvbox.osc.download.internal.DownloadForegroundService.stopIfIdle(appContext, snapshot);
        } catch (Throwable ignored) {
        }
    }

    void wakeWorker() {
        scheduler.wakeWorker();
    }

    /**
     * 任务大小异步预检(入队后调用,下载页尽早显示"约大小",启动前磁盘判断直接复用结果):
     * 直链探测精确 Content-Length(写 totalBytes);m3u8 由执行器抽样探测分片后推算(写 estimatedBytes)。
     * 单飞去重;完成后大小有变化才落盘并广播一次(驱动下载页行内大小展示)。
     */
    void probeSizeAsync(final DownloadTask t) {
        if (t == null || t.state == DownloadTask.STATE_COMPLETED || t.state == DownloadTask.STATE_CANCELLED) return;
        // m3u8 不做入队预检:抽样探测要先拉一次整份播放列表(大且带 sign)再发十几个 HEAD,
        // 而下载任务本身启动时也要拉同一份清单,再预检等于把这份开销翻倍、还多一次 CDN 掐连接的机会。
        // m3u8 的"约大小/磁盘预检"由启动时的阻塞探测(checkDiskSpace → probeSizeBlocking)负责;
        // 直链仅一个轻量 HEAD,保留入队异步预检。
        if (t.url != null && t.url.toLowerCase().contains(".m3u8")) return;
        probeExecutor.execute(() -> {
            try {
                if (t.totalBytes > 0 || t.estimatedBytes > 0) return;
                long prevTotal = t.totalBytes, prevEst = t.estimatedBytes;
                executor.probeSize(t);
                if (t.totalBytes != prevTotal || t.estimatedBytes != prevEst) {
                    persist();
                    notifyChanged();
                }
            } catch (Throwable th) {
                th.printStackTrace();
            }
        });
    }

    String sanitize(String name) {
        return sanitizeName(name);
    }

    /** 去除文件名的非法字符;null/空白返回空串,由调用方决定兜底名 */
    public static String sanitizeName(String name) {
        return PosterCleanup.sanitizeName(name);
    }

    // ------------------------------------------------------------------
    // 对外接口（委托组件）
    // ------------------------------------------------------------------

    /** 任务快照(安全遍历) */
    public List<DownloadTask> getTasks() {
        return store.getTasks();
    }

    /** 新增下载任务(简化入口,不含重新解析信息) */
    public com.github.tvbox.osc.download.EnqueueResult enqueueResult(String url, String sourceKey, String playFlag,
            String episodeRawUrl, String episodeId, String pic, Map<String, String> headers,
            String sourceName, String vodName, String episodeName, List<com.github.tvbox.osc.bean.DownloadRoute> altRoutes,
            boolean requiresResolution) {
        return scheduler.enqueueResultInternal(url, sourceKey, playFlag, episodeRawUrl, episodeId,
                pic, headers, sourceName, vodName, episodeName, altRoutes, requiresResolution);
    }

    public boolean enqueue(String url, String sourceName, String vodName, String episodeName) {
        return scheduler.enqueueInternal(url, null, null, null, null, null, null, sourceName, vodName, episodeName, null);
    }

    /** 新增下载任务(含 EpisodeId 统一剧集标识) */
    public boolean enqueue(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String sourceName, String vodName, String episodeName) {
        return scheduler.enqueueInternal(url, sourceKey, playFlag, episodeRawUrl, episodeId, null, null, sourceName, vodName, episodeName, null);
    }

    /** 带 EpisodeId 与封面图 URL 的入队 */
    public boolean enqueue(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String pic, String sourceName, String vodName, String episodeName) {
        return scheduler.enqueueInternal(url, sourceKey, playFlag, episodeRawUrl, episodeId, pic, null, sourceName, vodName, episodeName, null);
    }

    /** 带 EpisodeId、封面图 URL 与解析请求头(防盗链源下载必须携带)的入队 */
    public boolean enqueue(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String pic, Map<String, String> headers,
                           String sourceName, String vodName, String episodeName) {
        return scheduler.enqueueInternal(url, sourceKey, playFlag, episodeRawUrl, episodeId, pic, headers, sourceName, vodName, episodeName, null);
    }

    /**
     * 带备用线路候选的入队(换线路重下用):候选按"同集"从其它线路收集,
     * 本线路整体失效时按顺序切换(见 DownloadScheduler#switchRoute)。
     */
    public boolean enqueue(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String episodeId, String pic, Map<String, String> headers,
                           String sourceName, String vodName, String episodeName,
                           java.util.List<com.github.tvbox.osc.bean.DownloadRoute> altRoutes) {
        return scheduler.enqueueInternal(url, sourceKey, playFlag, episodeRawUrl, episodeId, pic, headers, sourceName, vodName, episodeName, altRoutes);
    }

    /** 新增下载任务(不含 EpisodeId) */
    public boolean enqueue(String url, String sourceKey, String playFlag, String episodeRawUrl,
                           String sourceName, String vodName, String episodeName) {
        return scheduler.enqueueInternal(url, sourceKey, playFlag, episodeRawUrl, null, null, null, sourceName, vodName, episodeName, null);
    }

    /** 暂停(用户手动) */
    public void pause(DownloadTask t) {
        scheduler.pause(t);
    }

    /** 继续:恢复单个暂停/失败任务 */
    public void resume(DownloadTask t) {
        scheduler.resume(t);
    }

    /** 排队插队(温和): 提到队首(该优先级最前), 不打断运行中任务 */
    public void moveToFront(DownloadTask t) {
        scheduler.moveToFront(t);
    }

    /** 设置优先级(HIGH/NORMAL/LOW); 置 HIGH 且并发满时抢占让位(被抢占者排最前) */
    public void setPriority(DownloadTask t, int level) {
        scheduler.setPriority(t, level);
    }

    /** 全部暂停 */
    public void pauseAll() {
        scheduler.pauseAll();
    }

    /** 全部开始 */
    public void startAll() {
        scheduler.startAll();
    }

    /** 删除任务(默认只删记录保留碎片——Bug2: 重入队同 episodeId 复用碎片续传,不白下) */
    public void remove(DownloadTask t) {
        scheduler.remove(t, false);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.DOWNLOAD,
                "删除下载记录: " + (t == null || t.fileName == null ? "?" : t.fileName));
    }

    /** 删除任务(deleteFiles=true 连本地文件一起删;false 只删记录保留文件) */
    public void remove(DownloadTask t, boolean deleteFiles) {
        scheduler.remove(t, deleteFiles);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.DOWNLOAD,
                (deleteFiles ? "删除任务及文件: " : "删除下载记录: ")
                        + (t == null || t.fileName == null ? "?" : t.fileName));
    }

    /**
     * 按文件路径删除下载任务(本地视频页删除文件时联动):
     * 文件已删, 只删任务记录——否则任务残留会在网络恢复时自动续传, 把已删文件又下回来。
     * 先收集快照再逐个 remove(remove 内部会同步 tasks), 避免遍历中修改集合。
     */
    public int removeTasksByPath(String savePath) {
        if (savePath == null) return 0;
        java.util.List<DownloadTask> matched = new java.util.ArrayList<>();
        synchronized (tasks) {
            for (DownloadTask t : tasks) {
                if (savePath.equals(t.savePath)) matched.add(t);
            }
        }
        for (DownloadTask t : matched) {
            scheduler.remove(t, false);
            requestPosterCleanup(t.vodName == null ? t.groupName : t.vodName,
                    t.savePath, t.tmpDir);
        }
        if (!matched.isEmpty()) {
            com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.DOWNLOAD,
                    "删除本地文件联动移除下载任务: " + new java.io.File(savePath).getName());
        }
        return matched.size();
    }

    /**
     * 按 episodeId 删除下载任务记录(不删文件):
     * 档案被删(文件同时被删)时联动清掉同集的"已完成"任务记录 —— 否则该记录既挡住重新下载
     * (入队按 episodeId 判重)、又不在下载列表显示(列表只聚合未完成任务 + 已完成且文件存在的)。
     */
    public int removeTasksByEpisode(String episodeId) {
        if (episodeId == null || episodeId.isEmpty()) return 0;
        java.util.List<DownloadTask> matched = new java.util.ArrayList<>();
        synchronized (tasks) {
            for (DownloadTask t : tasks) {
                if (episodeId.equals(t.episodeId)) matched.add(t);
            }
        }
        for (DownloadTask t : matched) {
            scheduler.remove(t, false); // 文件由档案删除路径负责,这里只清记录
            requestPosterCleanup(t.vodName == null ? t.groupName : t.vodName,
                    t.savePath, t.tmpDir);
        }
        if (!matched.isEmpty()) {
            com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.DOWNLOAD,
                    "删除档案联动移除同集任务记录: " + matched.size() + " 条");
        }
        return matched.size();
    }

    /**
     * 把"磁盘上已有成品文件、但没有任何任务/档案记录"的文件登记为已下载(自愈)。
     * 只写档案(已下载列表的数据源),不塞进任务列表,避免又被当成"未完成任务"。
     */
    void adoptExistingAsDownloaded(java.io.File file, String episodeId, String sourceKey, String sourceName,
                                   String vodName, String episodeName, String pic) {
        if (file == null || !file.exists()) return;
        DownloadTask t = new DownloadTask();
        t.episodeId = episodeId;
        t.sourceKey = sourceKey;
        t.sourceName = sourceName;
        t.vodName = vodName;
        t.episodeName = episodeName;
        t.pic = pic;
        t.url = "";
        t.fileName = file.getName();
        t.savePath = file.getAbsolutePath();
        t.partPath = t.savePath + ".part";
        t.state = DownloadTask.STATE_COMPLETED;
        t.downloadedBytes = file.length();
        t.totalBytes = file.length();
        archive.add(t);
        persist();
        notifyChanged();
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.DOWNLOAD,
                "已存在成品文件登记为已下载: " + t.fileName);
    }

    /** 查询某集下载状态(0=无记录,1=已下载且文件存在,2=已有任务) */
    public int getEpisodeDownloadState(String sourceName, String vodName, String episodeName) {
        return store.getEpisodeDownloadState(sourceName, vodName, episodeName);
    }

    /** 清理"已完成但文件已不存在"的失效记录 */
    public int pruneMissingCompleted() {
        return store.pruneMissingCompleted();
    }

    // ------------------------------------------------------------------
    // 配置（委托决策器）
    // ------------------------------------------------------------------

    public int getMaxConcurrent() {
        return policy.getMaxConcurrent();
    }

    /** 设置最大并发数(1-3,上限见 DownloadPolicy.MAX_CONCURRENT),触发重新调度 */
    public void setMaxConcurrent(int n) {
        policy.setMaxConcurrent(n);
    }

    /**
     * 每任务限速(字节/秒;0=不限速)。5.4 增强,仅内存生效(重启需重新设置)。
     * <p>
     * 注意:这是"单个任务"的底层入口,正常路径由 UI 设置项经 {@link #setSpeedLimitBytesPerSec} 统一供值
     * (任务启动时套用、改设置立即生效),业务侧不需要逐个任务调用。
     */
    public void setSpeedLimit(DownloadTask t, long bytesPerSecond) {
        if (t != null) {
            t.speedLimit = Math.max(0, bytesPerSecond);
        }
    }

    /** 全局限速(字节/秒;0=不限速):持久化 + 立即套用到所有任务 */
    public long getSpeedLimitBytesPerSec() {
        return policy.getSpeedLimitBytesPerSec();
    }

    public void setSpeedLimitBytesPerSec(long bytesPerSecond) {
        policy.setSpeedLimitBytesPerSec(bytesPerSecond);
    }

    /** 是否仅 WiFi 下载(默认开启;开启时蜂窝/断网不启动并自动挂起) */
    public boolean isWifiOnly() {
        return policy.isWifiOnly();
    }

    public void setWifiOnly(boolean wifiOnly) {
        policy.setWifiOnly(wifiOnly);
        // 立即生效,不依赖网络切换事件:开启且当前非WiFi → 暂停全部;关闭 → 恢复挂起任务并唤醒调度
        scheduler.enforceNetworkGate();
    }

    /** 当前网络是否为移动网络(蜂窝) */
    public static boolean isMobileNetwork() {
        return DownloadPolicy.isMobileNetwork();
    }

    // ------------------------------------------------------------------
    // 存储 / 海报（委托组件）
    // ------------------------------------------------------------------

    /** 下载保存根目录 */
    public static File getSaveDir() {
        return FileCleaner.getSaveDir();
    }

    /** 剧集海报目录:应用私有目录 poster/<剧名> */
    public static File getPosterDir(String vodName) {
        return DownloadStore.getPosterDir(vodName);
    }

    /** 剧集海报本地文件:已存在返回 File,否则返回 null */
    public static File getPosterFile(String vodName) {
        return DownloadStore.getPosterFile(vodName);
    }

    /** 确保剧集海报已下载到本地 */
    public void ensurePosterAsync(String pic, String vodName) {
        store.ensurePosterAsync(pic, vodName);
    }

    /** Check poster ownership after a confirmed deletion; disk inspection runs on the poster executor. */
    public void requestPosterCleanup(String vodName, String savePath, String tmpDir) {
        store.requestPosterCleanup(vodName, savePath, tmpDir);
    }
}

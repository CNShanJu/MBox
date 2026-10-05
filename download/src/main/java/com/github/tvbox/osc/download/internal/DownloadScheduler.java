package com.github.tvbox.osc.download.internal;

import android.util.Log;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.download.DownloadSubType;
import com.github.tvbox.osc.download.EnqueueResult;
import com.github.tvbox.osc.util.DownloadHeaders;
import com.github.tvbox.osc.state.SystemEvent;
import com.github.tvbox.osc.state.SystemState;
import com.github.tvbox.osc.state.SystemStateMonitor;
import com.github.tvbox.osc.util.RouteSwitchPolicy;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import okhttp3.Response;

/**
 * 任务调度器（Task-Scheduler）：队列 / 并发调度 / 启停 / 任务生命周期。
 * 5.1 从 DownloadManager 按职责拆分，行为零变化。
 */
public class DownloadScheduler {

    private final DownloadManager dm;
    private Thread worker;
    private final java.util.Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService taskExecutor = java.util.concurrent.Executors.newFixedThreadPool(5, r -> {
        Thread thread = new Thread(r, "mbox-download");
        thread.setDaemon(true);
        return thread;
    });

    DownloadScheduler(DownloadManager dm) {
        this.dm = dm;
    }

    /**
     * 任务对象上报通道（4.6）: 目前执行器内部已直连 dm 持久化/广播,
     * 此处先保持空转发(协议就位,行为零变化); 4.7 重封装时把 持久化/广播/日志 收敛到此通道。
     */
    private final TaskListener taskListener = new TaskListener() {
        @Override
        public void onProgress(DownloadTask t) {
            // 进度落盘/广播由执行器内部经 DownloadManager.flushProgress 节流合并(450ms 窗口)
        }

        @Override
        public void onState(DownloadTask t, int state, String message) {
            // 状态落库/广播由执行器内部完成(顺序铁律: 落盘→档案→清理→COMPLETED)
        }

        @Override
        public void onCleanupRequest(DownloadTask t) {
            // 清理请求由框架仲裁(当前清理时序已由执行器按铁律执行)
        }
    };

    /**
     * 订阅全局网络事件(任务C:网络监听统一)。系统级 ConnectivityManager 回调此前在下载侧
     * (DownloadScheduler/DownloadPolicy)重复注册,造成同一网络变化多次唤醒调度器;
     * 现以 SystemStateMonitor 为唯一网络事件源(它在 state 模块内部已注册默认网络回调,
     * 事件在主线程派发且带 300ms 去抖),此处仅订阅其 TYPE_NETWORK 事件触发原有逻辑:
     * 网络"可用/类型变化/恢复"时自动续传因网络失败的任务(离线挂起/恢复自动续传策略不变)。
     * <p>
     * 语义映射(与原 ConnectivityManager.NetworkCallback.onAvailable 一致):
     * - 事件值 WIFI        → 网络可用,续传(无论是否仅WiFi);
     * - 事件值 CELLULAR    → 仅当未开启"仅WiFi"才续传(Bug1: 仅WiFi+蜂窝维持挂起语义);
     * - 事件值 NONE(断网)  → 不处理,下载中任务由 Policy(仅WiFi挂起)或网络错误重试负责。
     * 订阅成功后按当前网络自检一次,等价旧注册 registerNetworkCallback 注册即回调 onAvailable。
     */
    void subscribeNetworkEvents() {
        try {
            SystemStateMonitor monitor = SystemStateMonitor.get();
            if (monitor == null) return; // 监控未初始化(启动早期):防御策略与 DownloadPolicy 一致
            monitor.register((SystemEvent e) -> {
                if (!SystemStateMonitor.TYPE_NETWORK.equals(e.type)) return;
                if (SystemStateMonitor.VAL_WIFI.equals(e.value)) {
                    resumeNetworkFailedTasks();
                    wakeWorker(); // WiFi 恢复:被"仅WiFi"闸门拦下的等待任务立即按调度开跑
                } else if (SystemStateMonitor.VAL_CELLULAR.equals(e.value)
                        && !dm.policy.isWifiOnly()) {
                    resumeNetworkFailedTasks();
                    wakeWorker(); // 未开仅WiFi:蜂窝恢复同样唤醒调度
                }
                // VAL_NONE(断网): 不在此恢复
            }, SystemStateMonitor.TYPE_NETWORK);
            // 订阅时自检:按当前网络态立即执行一次(等价旧的"注册即回调")
            SystemState current = monitor.getCurrentState();
            if (current != null && !SystemStateMonitor.VAL_NONE.equals(current.network)) {
                if (SystemStateMonitor.VAL_WIFI.equals(current.network)
                        || !dm.policy.isWifiOnly()) {
                    resumeNetworkFailedTasks();
                    wakeWorker();
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 网络恢复:因网络错误失败的任务(FAILED+networkFailed)转等待,自动续传并重新解析地址 */
    private void resumeNetworkFailedTasks() {
        Log.i("TVBox-Download", "网络恢复:自动续传因网络失败的任务");
        boolean changed = false;
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.networkFailed && t.state == DownloadTask.STATE_FAILED) {
                    t.state = DownloadTask.STATE_WAITING;
                    t.needReResolve = true;
                    t.networkFailed = false;
                    t.message = "";
                    changed = true;
                }
            }
        }
        if (changed) {
            dm.persist();
            dm.notifyChanged();
        }
        wakeWorker();
    }

    void startWorker() {
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                workLoop();
            }
        }, "tvbox-download");
        worker.setDaemon(true);
        worker.start();
    }

    void wakeWorker() {
        synchronized (dm.lock) {
            dm.lock.notifyAll();
        }
    }

    private void workLoop() {
        while (true) {
            try {
                if (!schedule()) {
                    synchronized (dm.lock) {
                        dm.lock.wait();
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    /**
     * 并发调度(任务按加入时间排序):
     * 1) 正在下载数超过并发上限 → 按加入时间从后往前把最晚加入的多余任务置为"调度暂停"(等待调度,有空位自动恢复)
     * 2) 正在下载数不足 → 按加入时间从前往后启动等待/调度暂停任务
     *
     * @return 是否有调度动作(避免空转忙等)
     */
    private boolean schedule() {
        synchronized (dm.tasks) {
            List<DownloadTask> sorted = new ArrayList<>(dm.tasks);
            // 调度顺序(4.4): ① 优先级高先(HIGH>NORMAL>LOW) ② 同优先级内被抢占者(SYSTEM_PAUSED)先
            // ③ 多个被抢占者:后抢占先恢复(preemptTime 降序) ④ 普通等待:queueOrder FIFO
            sorted.sort((a, b) -> {
                int pa = a.priority, pb = b.priority;
                if (pa != pb) return pb - pa;
                boolean sa = a.state == DownloadTask.STATE_SYSTEM_PAUSED;
                boolean sb = b.state == DownloadTask.STATE_SYSTEM_PAUSED;
                if (sa != sb) return sa ? -1 : 1;
                if (sa) return Long.compare(b.preemptTime, a.preemptTime);
                return Long.compare(a.queueOrder, b.queueOrder);
            });
            int running = 0;
            for (DownloadTask t : sorted) {
                if (t.state == DownloadTask.STATE_DOWNLOADING) running++;
            }
            if (running > dm.policy.getMaxConcurrent()) {
                // 并发调低:从队尾(最低优先级且最后加入)转为"调度暂停",并记录被抢占时间
                int toPause = running - dm.policy.getMaxConcurrent();
                long now = System.currentTimeMillis();
                for (int i = sorted.size() - 1; i >= 0 && toPause > 0; i--) {
                    DownloadTask t = sorted.get(i);
                    if (t.state == DownloadTask.STATE_DOWNLOADING) {
                        t.state = DownloadTask.STATE_SYSTEM_PAUSED;
                        t.preemptTime = now;
                        Response r = dm.activeResponses.remove(t.id);
                        if (r != null) {
                            try {
                                r.close();
                            } catch (Throwable ignored) {
                            }
                        }
                        toPause--;
                    }
                }
                dm.persist();
                dm.notifyChanged();
                return true;
            }
            if (running < dm.policy.getMaxConcurrent()) {
                // 仅WiFi开启且当前非WiFi(蜂窝/断网):不启动任何任务。
                // 给这些等待任务打上"等待Wi-Fi"文案(仅变化时落盘/广播一次),让用户知道为何等待;
                // 切回 WiFi 后由网络事件唤醒调度自动开跑(见 subscribeNetworkEvents)
                if (dm.policy.isWifiOnly() && !DownloadPolicy.isWifiActive()) {
                    boolean marked = false;
                    for (DownloadTask tt : sorted) {
                        if (tt.state == DownloadTask.STATE_WAITING || tt.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                            if (!com.github.tvbox.osc.download.DownloadFacade.MSG_WAIT_WIFI.equals(tt.message)) {
                                tt.message = com.github.tvbox.osc.download.DownloadFacade.MSG_WAIT_WIFI;
                                marked = true;
                            }
                        }
                    }
                    if (marked) {
                        dm.persist();
                        dm.notifyChanged();
                    }
                    return false;
                }
                // 存储看门狗闸门:可用空间低于兜底阈值(1GB)时不启动任何新任务。
                // 这与"看门狗发现空间见底后暂停运行中任务"是同一条口径的两端 —— 停掉在跑的 + 不许新开,
                // 缺任何一端都会留个口子(只停在跑:刚入队的任务立刻又把空间吃回去)。
                // 同样给等待任务打上可读文案(否则用户看到的是"等待中"却不知道在等什么)。
                if (dm.watchdog.isLow()) {
                    boolean marked = false;
                    for (DownloadTask tt : sorted) {
                        if (tt.state == DownloadTask.STATE_WAITING || tt.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                            if (!com.github.tvbox.osc.download.DownloadFacade.MSG_WAIT_STORAGE.equals(tt.message)) {
                                tt.message = com.github.tvbox.osc.download.DownloadFacade.MSG_WAIT_STORAGE;
                                marked = true;
                            }
                        }
                    }
                    if (marked) {
                        dm.persist();
                        dm.notifyChanged();
                    }
                    return false;
                }
                // 并发调高:按调度顺序补足(队首=高优先级/被抢占者先恢复)
                int toStart = dm.policy.getMaxConcurrent() - running;
                boolean startedAny = false;
                for (DownloadTask t : sorted) {
                    if (toStart <= 0) break;
                    if (t.state == DownloadTask.STATE_WAITING || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                        // 同目标(savePath)已有任务在下载时,不重复启动,避免并发下载同一文件互相踩踏
                        if (isSameTargetDownloading(t, sorted) || inFlight.contains(t.id)
                                || !DownloadConcurrency.canStart(t, sorted)) continue;
                        if (t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                            t.state = DownloadTask.STATE_WAITING;
                    t.needReResolve = true; // 调度暂停自动恢复
                        }
                        startTask(t);
                        toStart--;
                        startedAny = true;
                    }
                }
                // 关键:只有确实启动了任务才返回 true(让 worker 立即再调度);
                // 若所有等待任务都因同目标被跳过(无动作),返回 false 让 worker 休眠,
                // 等待任务结束 wakeWorker 再唤醒——否则这里会 100% CPU 忙等死循环(ANR 根因)
                return startedAny;
            }
            return false;
        }
    }

    /** 是否有其他任务正在下载同一 savePath(防同目标并发下载互相踩踏) */
    private boolean isSameTargetDownloading(DownloadTask t, List<DownloadTask> sorted) {
        if (t.savePath == null) return false;
        for (DownloadTask other : sorted) {
            if (other == t) continue;
            if ((other.state == DownloadTask.STATE_DOWNLOADING || inFlight.contains(other.id))
                    && other.savePath != null && other.savePath.equals(t.savePath)) {
                return true;
            }
        }
        return false;
    }

    /** 启动任务到模块共享执行器；同一任务/目标的旧执行退出后才允许继续。 */
    private void startTask(final DownloadTask t) {
        if (!inFlight.add(t.id)) return;
        t.state = DownloadTask.STATE_DOWNLOADING;
        t.message = "";
        t.speed = 0;
        dm.persist();
        dm.notifyChanged();
        taskExecutor.execute(() -> { try { new Runnable() {
            @Override
            public void run() {
                // Bug4: 存储权限硬门槛,启动前检查(权限被撤销则拒绝启动)
                if (!FileCleaner.canWritePath(t.savePath)) {
                    t.state = DownloadTask.STATE_FAILED;
                    t.message = "保存到公共目录需要所有文件访问权限，请授权后继续";
                    Log.i("TVBox-Download", "启动失败:无存储权限 " + t.fileName);
                    dm.persist();
                    dm.notifyChanged();
                    wakeWorker();
                    return;
                }
                int retries = 0;
                // 每次启动任务时套用全局设置项(限速):speedLimit 是 transient,重启/新入队都要重新套,
                // 设置改动也会由 DownloadPolicy 直接刷到运行中的任务上(见 setSpeedLimitBytesPerSec)
                t.speedLimit = dm.policy.getSpeedLimitBytesPerSec();
                t.throttleWindowStart = 0;
                t.throttleWindowBytes = 0;
                // 进程重启后首次启动:代理签名URL通常已过期,先重新解析一次(与下载中过期重解析共用逻辑)
                if (t.needReResolve) {
                    t.needReResolve = false;
                    if (!reResolveUrl(t)) {
                        pauseFor(t, "未获取到可下载地址，请检查登录状态、切换线路或先播放该集后继续");
                        return;
                    }
                }
                // 方案A:嗅探型源(type0)首次启动——入队地址是剧集页(非视频),先嗅探真实播放地址
                if (t.sourceKey != null && t.playFlag != null && t.episodeRawUrl != null
                        && !t.resolvedRequest && t.reResolveCount == 0 && !isPlayableUrl(t.url)) {
                    t.reResolveCount++;
                    t.message = "地址嗅探中(1/" + DownloadManager.MAX_RE_RESOLVE + ")";
                    dm.persist();
                    dm.notifyChanged();
                    if (!reResolveUrl(t)) {
                        pauseFor(t, "解析或嗅探未获得视频地址，请切换线路或先播放该集后继续");
                        return;
                    }
                }
                try {
                    if (!applyRequestContext(t, t.url, t.headers)) return;
                } catch (Exception e) {
                    pauseFor(t, "请求上下文读取失败，请稍后继续");
                    return;
                }
                if (isTaskStopped(t)) return;
                synchronized (dm.tasks) {
                    if (DownloadConcurrency.scoped(t)) {
                        int scoped = 0;
                        for (DownloadTask task : dm.tasks)
                            if (task.state == DownloadTask.STATE_DOWNLOADING && DownloadConcurrency.scoped(task)) scoped++;
                        if (scoped > 2) {
                            t.state = DownloadTask.STATE_SYSTEM_PAUSED;
                            t.message = "已排队，该源同时下载上限为 2";
                            t.needReResolve = true;
                            dm.persist(); dm.notifyChanged(); wakeWorker();
                            return;
                        }
                    }
                }
                // 下载前磁盘空间预检:放在地址重解析/嗅探之后,确保大小探测按最终真实 URL 进行;
                // 入队时已异步探测并持久化大小的任务这里直接复用,不再重复探测(失败的任务本会话也只探测一次)
                String spaceErr = dm.policy.checkDiskSpace(t);
                if (spaceErr != null) {
                    t.state = DownloadTask.STATE_FAILED;
                    t.message = spaceErr;
                    Log.i("TVBox-Download", "磁盘空间预检失败: " + t.fileName + " " + spaceErr);
                    dm.persist();
                    dm.notifyChanged();
                    wakeWorker();
                    return;
                }
                // 存储看门狗:任务真正开跑时确保后台巡检在跑(全部停下后线程自行退出,不常驻)
                dm.watchdog.ensureMonitor();
                while (true) {
                    try {
                        // 4.6 任务对象化: 经注册表创建任务对象执行(直链/HLS 按特征分发,行为与 processTask 一致)
                        BaseDownloadTask obj = DownloadTaskRegistry.create(t, taskListener, dm.executor);
                        obj.start();
                        return; // 成功
                    } catch (Throwable th) {
                        th.printStackTrace();
                        if (isTaskStopped(t)) return; // 暂停(用户/调度/网络)或已取消,不再重试
                        if (th instanceof DownloadErrors.SessionExpired || th instanceof DownloadErrors.LayoutChanged) {
                            pauseFor(t, th.getMessage());
                            return;
                        }
                        // 断网/切网等网络错误:允许更多次重试 + 指数退避(最长约2分钟),并标记网络失败待恢复后自动续传
                        boolean netErr = isNetworkError(th);
                        // 缺片全是"源侧永久失效":整任务重试等于把同一批 404 再请求一遍,直接失败并给出可读原因
                        boolean goneErr = DownloadErrors.isPermanentlyGone(th);
                        DownloadErrors.Kind kind = DownloadErrors.classify(th);
                        int maxRetry = goneErr || kind == DownloadErrors.Kind.STORAGE ? 0
                                : kind == DownloadErrors.Kind.MERGE ? 1
                                : kind == DownloadErrors.Kind.SOURCE ? 2
                                : (netErr ? DownloadManager.MAX_NETWORK_RETRY : DownloadManager.MAX_RETRY);
                        // 地址可能过期(HTTP 403/404/410 等或 HTML 防盗链响应):重新解析地址后继续,
                        // 重置下载重试计数;解析次数有限制(MAX_RE_RESOLVE),避免无限重解析
                        if (!netErr && shouldReResolve(t, th) && t.reResolveCount < DownloadManager.MAX_RE_RESOLVE
                                && t.sourceKey != null && t.playFlag != null && t.episodeRawUrl != null) {
                            t.reResolveCount++;
                            retries = 0;
                            Log.i("TVBox-Download", "地址可能过期,重新解析(" + t.reResolveCount + "/" + DownloadManager.MAX_RE_RESOLVE + "): "
                                    + t.fileName + " " + t.message);
                            t.message = "地址更新中(" + t.reResolveCount + "/" + DownloadManager.MAX_RE_RESOLVE + ")";
                            dm.persist();
                            dm.notifyChanged();
                            try {
                                Thread.sleep(3000L);
                            } catch (InterruptedException ie) {
                                return;
                            }
                            if (reResolveUrl(t)) {
                                continue; // 用新地址继续下载
                            }
                            if (DownloadErrors.isAuthentication(th)) {
                                pauseFor(t, new DownloadErrors.SessionExpired().getMessage());
                                return;
                            }
                            // 解析失败:继续走普通重试逻辑
                        }
                        if (DownloadErrors.isAuthentication(th) && t.reResolveCount >= DownloadManager.MAX_RE_RESOLVE) {
                            pauseFor(t, new DownloadErrors.SessionExpired().getMessage());
                            return;
                        }
                        // 换线路(4.8②):同线路重解析已到上限,或本线路整体失效(整集缺片全是源侧永久失效 /
                        // 连续多片下载失败)→ 原地怎么试都是同一个结果,改走这条集的另一条线路:
                        // 换线路=另一份播放列表,旧碎片整份丢弃重下(见 switchRoute/clearRouteProgress)。
                        // 止损:已下太多就不自动换(否则几百 MB 进度被悄悄扔掉),保留下载进度按失败收尾,
                        // 由用户决定重试还是换源(见 RouteSwitchPolicy)
                        boolean keptProgressNoSwitch = false;
                        if (!netErr) {
                            if (canSwitchRoute(t, th)) {
                                if (switchRoute(t)) {
                                    retries = 0; // 新线路有自己的重试预算
                                    continue;
                                }
                            } else if (maySwitchRoute(t, th)) {
                                keptProgressNoSwitch = true;
                                Log.i("TVBox-Download", "本线路分片不可用,但已下进度较多("
                                        + t.doneSegments + "/" + t.totalSegments + " 片, "
                                        + t.downloadedBytes + "B),不自动换线路(避免整集重下): " + t.fileName);
                                DownloadLog.LOG.warn(DownloadSubType.RESOLVE,
                                        "本线路分片不可用,已保留下载进度不自动换线路(换线路需整集重下): "
                                                + t.fileName + " 进度 " + t.doneSegments + "/" + t.totalSegments + " 片",
                                        DownloadLog.extras(t.episodeId));
                            }
                        }
                        if (retries < maxRetry) {
                            retries++;
                            long delay = netErr ? (3000L + retries * 3000L) : 3000L;
                            Log.i("TVBox-Download", "任务重试 " + retries + "/" + maxRetry + (netErr ? "(网络)" : "")
                                    + ": " + t.fileName + " " + t.message);
                            DownloadLog.LOG.warn(DownloadSubType.FAIL,
                                    "下载失败,重试 " + retries + "/" + maxRetry + (netErr ? "(网络)" : "") + ": "
                                            + t.fileName + " | "
                                            + (th.getMessage() == null ? th.toString() : th.getMessage()),
                                    DownloadLog.extras(t.episodeId));
                            t.message = "重试中(" + retries + "/" + maxRetry + ")";
                            dm.persist();
                            dm.notifyChanged();
                            try {
                                Thread.sleep(delay);
                            } catch (InterruptedException ie) {
                                return;
                            }
                            continue;
                        }
                        t.state = DownloadTask.STATE_FAILED;
                        t.message = th.getMessage() == null ? th.toString() : th.getMessage();
                        if (goneErr) {
                            // 死片类失败:本线路已经没办法了(换过线路也如此),提示用户换源而不是干等
                            t.message = t.message + "(源站该分片已失效,可换源重下)";
                        } else if (netErr) {
                            t.networkFailed = true; // 网络恢复后自动续传
                            t.message = t.message + "(网络恢复后自动继续)";
                        }
                        if (t.routeSwitchCount > 0) {
                            // 换过线路说明"这条集的其它线路也试过了":让用户知道不是没尝试,而是整个源这集不行
                            t.message = t.message + "(已换 " + t.routeSwitchCount + " 条线路)";
                        }
                        if (keptProgressNoSwitch) {
                            // 本线路没救、但已下不少:明确告诉用户进度还在,别以为白下了
                            t.message = t.message + "(该线路分片不可用;已保留下载进度,重试可续传)";
                        }
                        DownloadLog.LOG.fail(DownloadSubType.FAIL, "任务失败: " + t.fileName + " | " + t.message,
                                DownloadLog.extras(t.episodeId));
                        dm.persist();
                        dm.notifyChanged();
                        return;
                    } finally {
                        wakeWorker(); // 任务结束,重新调度下一个
                    }
                }
            }
        }.run(); } finally { inFlight.remove(t.id); wakeWorker(); } });
    }

    private void pauseFor(DownloadTask t, String reason) {
        if (isTaskStopped(t)) return;
        t.state = DownloadTask.STATE_PAUSED;
        t.message = reason;
        t.speed = 0;
        DownloadLog.LOG.warn(DownloadSubType.RESOLVE, reason + ": " + t.fileName, DownloadLog.extras(t.episodeId));
        dm.persist();
        dm.notifyChanged();
    }

    /** 判断异常是否为网络类错误(断网/超时/无法连接/服务端中途断连/SSL):分类逻辑在 DownloadErrors(与分段循环共用) */
    private boolean isNetworkError(Throwable th) {
        return DownloadErrors.isNetworkError(th);
    }

    /**
     * 判断下载失败是否可能因"地址过期"(而非网络/源本身问题):
     * - 非网络类错误
     * - 异常信息含 HTTP 4xx(尤其 403 禁止/404 不存在/410 已失效)或 HTML 防盗链提示
     * 满足条件时尝试重新解析地址。
     */
    private boolean shouldReResolve(DownloadTask t, Throwable th) {
        String msg = th.getMessage();
        if (msg == null) return false;
        // 缺片全部是"源侧永久失效"(HTTP 404/410 且已确认补不回来):重新解析地址拿到的还是同一个 URL、
        // 还是同一个 404,重解析纯属浪费(实测 8 片死片被重解析 3 轮 × 3 次),交给失败/缺片完成路径
        if (DownloadErrors.isPermanentlyGone(th)) return false;
        String m = msg.toLowerCase();
        if (m.contains("html") || m.contains("防盗链") || m.contains("网页")) return true;
        // 内容根本不是音视频(分片返回的是图片/JSON/错误页):同样是"地址失效/被替换"的表现,
        // 走重新解析地址(换线路)这条路,而不是让用户对着一个下不动的任务反复重试
        if (m.contains("不是视频") || m.contains("图片") || m.contains("伪装") || m.contains("错误响应")) return true;
        // HTTP 状态码
        java.util.regex.Matcher mat = java.util.regex.Pattern.compile("http\\s*(\\d{3})").matcher(m);
        if (mat.find()) {
            int code = Integer.parseInt(mat.group(1));
            return code == 401 || code == 403 || code == 404 || code == 410 || code == 451;
        }
        return false;
    }

    /**
     * 重新解析播放地址与请求头（经 SpiderApi 串行执行，避免 quickjs 并发卡死）。
     * 只要解析成功就同步请求头（旧任务缺头时靠 403/404 触发重解析补头，即使地址未变也要继续重试）。
     *
     * @return true=地址或请求头已更新(调用方应继续重试下载)
     */
    boolean reResolveUrl(DownloadTask t) {
        if (isTaskStopped(t)) return false;
        try {
            if (t.sourceKey != null && t.episodeRawUrl != null) {
                com.github.tvbox.osc.spiderapi.ResolveResult rr = DownloadManager.urlResolverApi
                        .resolvePlayUrl(t.sourceKey, t.playFlag, t.episodeRawUrl);
                if (rr != null && rr.url != null && rr.url.startsWith("http")) {
                    return applyRequestContext(t, rr.url, rr.headers);
                }
                return sniffResolve(t);
            }
            return applyRequestContext(t, t.url, t.headers);
        } catch (Exception e) {
            DownloadLog.LOG.warn(DownloadSubType.RESOLVE, "请求上下文刷新失败: " + t.fileName,
                    DownloadLog.extras(t.episodeId));
            return false;
        }
    }

    private boolean applyRequestContext(DownloadTask t, String url, java.util.Map<String, String> headers)
            throws Exception {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return false;
        java.util.Map<String, String> merged = DownloadHeaders.mergeForOrigin(t.url, url, t.headers, headers);
        if (DownloadManager.contextProvider != null) {
            merged = DownloadManager.contextProvider.refresh(t.sourceKey, t.playFlag, t.episodeRawUrl, url, merged);
        }
        synchronized (t) {
            if (isTaskStopped(t)) return false;
            // 已有 HLS 分片时不能切成直链；首次从剧集页解析出 m3u8 则允许。
            if (t.totalSegments == 0 && !url.equals(t.url) && t.partPath != null
                    && com.github.tvbox.osc.util.DirectResumePolicy.validator(t.entityTag, t.lastModified) == null) {
                FileCleaner.deleteQuietly(new File(t.partPath));
                t.downloadedBytes = 0;
                t.totalBytes = 0;
            }
            t.url = url;
            t.resolvedRequest = true;
            t.headers = DownloadHeaders.merge(merged);
            t.requestEpoch++;
            t.estimatedBytes = 0;
            t.probeDone = false;
        }
        dm.persist();
        return true;
    }
    /**
     * 入队时清洗备用线路候选:丢掉不可用/与本线路相同/重复线路名,并按上限截断。
     * 空结果返回 null(调用方只需一次判空)。
     */
    private static java.util.List<com.github.tvbox.osc.bean.DownloadRoute> trimRoutes(
            java.util.List<com.github.tvbox.osc.bean.DownloadRoute> altRoutes, String currentFlag) {
        if (altRoutes == null || altRoutes.isEmpty()) return null;
        java.util.List<com.github.tvbox.osc.bean.DownloadRoute> out = new java.util.ArrayList<>();
        java.util.Set<String> flags = new java.util.HashSet<>();
        if (currentFlag != null) flags.add(currentFlag);
        for (com.github.tvbox.osc.bean.DownloadRoute r : altRoutes) {
            if (r == null || !r.isUsable()) continue;
            if (!flags.add(r.playFlag)) continue; // 当前线路与重复线路都跳过
            out.add(new com.github.tvbox.osc.bean.DownloadRoute(r.playFlag, r.episodeRawUrl, r.episodeName));
            if (out.size() >= com.github.tvbox.osc.util.DownloadRoutePlan.MAX_ALTERNATIVES) break;
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 是否<b>值得</b>换线路(4.8②):还有候选,且本线路已"整体不可用"——
     * ①整集缺片全是源侧永久失效(HTTP 404/410,本线路彻底没救);
     * ②连续多片下载失败(该线路的分片地址整体失效);
     * ③本线路的"地址过期"重解析已到上限(再解析还是同一个地址,原地重试没意义)。
     * 不含"损失是否可接受"的止损判断(见 {@link #routeSwitchLossAcceptable} / {@link #canSwitchRoute})。
     */
    private boolean maySwitchRoute(DownloadTask t, Throwable th) {
        if (t.altRoutes == null || t.altRoutes.isEmpty()) return false;
        if (t.sourceKey == null || t.episodeRawUrl == null) return false;
        if (DownloadErrors.isPermanentlyGone(th)) return true;
        if (DownloadErrors.isRouteSuspect(th)) return true;
        return shouldReResolve(t, th) && t.reResolveCount >= DownloadManager.MAX_RE_RESOLVE;
    }

    /**
     * 止损:换线路=整集重下,已下的分片全废,所以只在"损失小"时才自动换(见 {@link RouteSwitchPolicy})。
     * 已经下了不少就<b>不自动换</b>,让任务按普通失败收尾(碎片保留,同线路重试可续传),
     * 由用户决定是重试还是换源 —— 不能替用户把几百 MB 的进度悄悄扔掉。
     */
    private boolean routeSwitchLossAcceptable(DownloadTask t) {
        if (t.totalSegments > 0) return RouteSwitchPolicy.lossAcceptable(t.doneSegments, t.totalSegments);
        return RouteSwitchPolicy.lossAcceptableBytes(t.downloadedBytes, t.totalBytes);
    }

    /** 可以自动换线路:本线路整体不可用 + 有候选 + 损失可接受 */
    private boolean canSwitchRoute(DownloadTask t, Throwable th) {
        return maySwitchRoute(t, th) && routeSwitchLossAcceptable(t);
    }

    /**
     * 换线路重下:消费一条候选线路,改用它重新解析地址并重下整集。
     * <p>
     * 为什么必须丢弃旧碎片:换线路拿到的是<b>另一份播放列表</b>(分片数/切分点都不同),
     * 两份视频的分片拼一起会得到放不了的文件 —— 与"换线路保护"(segments.sig 指纹不一致就丢弃)同理,
     * 只是这里必然不同,所以直接清,不依赖指纹是否写得下来。
     * <p>
     * 候选里任何一条解析失败就继续试下一条(每条线路的解析都可能失败);
     * 全部试完或没有候选时返回 false,由调用方走原来的重试/失败路径。
     *
     * @return true=已切到一条可用线路(调用方应继续下载)
     */
    private boolean switchRoute(DownloadTask t) {
        if (t.altRoutes == null || t.altRoutes.isEmpty()) return false;
        int guard = 0;
        while (!t.altRoutes.isEmpty() && guard < com.github.tvbox.osc.util.DownloadRoutePlan.MAX_ALTERNATIVES) {
            guard++;
            com.github.tvbox.osc.bean.DownloadRoute next = t.altRoutes.remove(0);
            if (next == null || !next.isUsable()) continue;
            if (next.playFlag.equals(t.playFlag) && next.episodeRawUrl.equals(t.episodeRawUrl)) continue;
            String from = t.playFlag;
            t.playFlag = next.playFlag;
            t.episodeRawUrl = next.episodeRawUrl;
            t.routeSwitchCount++;
            t.reResolveCount = 0;      // 新线路有自己的重解析预算
            t.networkFailed = false;   // 旧线路的网络失败标记对新线路不成立
            t.headers = null;          // 请求头属于旧线路,新线路按解析结果重新取
            clearRouteProgress(t);
            t.message = "换线路重下(" + next.playFlag + ")";
            Log.i("TVBox-Download", "换线路重下: " + t.fileName + " " + from + " → " + next.describe());
            DownloadLog.LOG.warn(DownloadSubType.RESOLVE,
                    "换线路重下: 线路 " + from + " → " + next.describe() + " (原线路分片/地址整体失效,已丢弃旧碎片重下)",
                    DownloadLog.extras(t.episodeId));
            dm.persist();
            dm.notifyChanged();
            if (reResolveUrl(t)) return true;
            Log.i("TVBox-Download", "换线路后解析失败,继续试下一条: " + t.fileName + " " + next.describe());
        }
        return false;
    }

    /** 换线路前清空旧线路的进度与碎片(另一份播放列表,分片拼不进去) */
    private void clearRouteProgress(DownloadTask t) {
        try {
            dm.executor.deleteSegmentsDir(t);
        } catch (Throwable th) {
            Log.i("TVBox-Download", "清理旧线路分片失败(继续换线路): " + t.fileName);
        }
        if (t.partPath != null) FileCleaner.deleteQuietly(new File(t.partPath)); // 直链残片:新地址的 Range 偏移对不上
        // 不动 t.savePath:那是用户可见的成品文件,删文件是危险操作,只允许在"确认内容无效"的路径上做
        // (合并阶段的自检会处理),换线路不该顺手删掉可能已存在的完整文件
        t.doneSegments = 0;
        t.totalSegments = 0;
        t.segmentBytes = 0;
        t.downloadedBytes = 0;
        t.totalBytes = 0;
        t.estimatedBytes = 0;
        t.probeDone = false;
        t.mergeCount = 0;
        t.mergeFailReason = "";
    }

    /** 是否可直接下载的地址(视频格式或 m3u8;剧集页 html/空/非 http 返回 false,需嗅探) */
    private static boolean isPlayableUrl(String url) {        if (url == null || url.isEmpty() || !url.startsWith("http")) return false;
        String u = url.toLowerCase();
        if (u.contains(".m3u8") || u.contains(".mp4") || u.contains(".mkv") || u.contains(".flv")
                || u.contains(".ts") || u.contains(".webm") || u.contains(".avi")) return true;
        try {
            return com.github.tvbox.osc.spiderapi.MediaUrlUtil.isVideoFormat(url);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 方案A:无头 WebView 嗅探剧集页拿真实播放地址+请求头(串行复用 DownloadManager 注册的嗅探器) */
    private boolean sniffResolve(DownloadTask t) {
        com.github.tvbox.osc.download.DownloadUrlSniffer sniffer = DownloadManager.getUrlSniffer();
        if (sniffer == null || t.episodeRawUrl == null) return false;
        try {
            com.github.tvbox.osc.download.DownloadUrlSniffer.SniffResult sr =
                    sniffer.sniff(t.sourceKey, t.playFlag, t.episodeRawUrl, 20000L);
            if (sr == null || sr.url == null || sr.url.isEmpty()) {
                Log.i("TVBox-Download", "嗅探未命中(超时/无视频): " + t.fileName);
                DownloadLog.LOG.warn(DownloadSubType.RESOLVE, "嗅探未命中(超时/无视频): " + t.fileName,
                        DownloadLog.extras(t.episodeId));
                return false;
            }
            return applyRequestContext(t, sr.url, sr.headers);

        } catch (Throwable th) {
            Log.i("TVBox-Download", "嗅探异常: " + t.fileName + " " + th.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 入队 / 启停
    // ------------------------------------------------------------------

    /**
     * 新增下载任务（含 EpisodeId、封面图与解析请求头）
     *
     * @param url         播放地址(直链或 m3u8)
     * @param sourceKey   来源 key(存任务,重启后重新解析地址用,可空)
     * @param playFlag    线路名(可空)
     * @param episodeRawUrl 源站原始集地址(可空)
     * @param episodeId   统一剧集标识(可空)
     * @param pic         封面图 URL(可空)
     * @param headers     解析请求头(可空)
     * @param sourceName  来源名称(取自订阅里配置的源名),一级目录
     * @param vodName     剧名,二级目录与显示分组
     * @param episodeName 选集名称(播放页选集列表的名称,如 第1集)
     * @return true=已加入任务;false=该集已下载,不能重复下载
     */
    boolean enqueueInternal(String url, String sourceKey, String playFlag, String episodeRawUrl,
                            String episodeId, String pic, java.util.Map<String, String> headers,
                            String sourceName, String vodName, String episodeName) {
        return enqueueInternal(url, sourceKey, playFlag, episodeRawUrl, episodeId, pic, headers,
                sourceName, vodName, episodeName, null);
    }

    /**
     * @param altRoutes 备用线路候选(换线路重下用,可 null):本线路整体失效时按顺序切换
     */
    boolean enqueueInternal(String url, String sourceKey, String playFlag, String episodeRawUrl,
                            String episodeId, String pic, java.util.Map<String, String> headers,
                            String sourceName, String vodName, String episodeName,
                            java.util.List<com.github.tvbox.osc.bean.DownloadRoute> altRoutes) {
        return enqueueResultInternal(url, sourceKey, playFlag, episodeRawUrl, episodeId, pic, headers,
                sourceName, vodName, episodeName, altRoutes, false).isQueued();
    }

    synchronized EnqueueResult enqueueResultInternal(String url, String sourceKey, String playFlag,
                            String episodeRawUrl, String episodeId, String pic, java.util.Map<String, String> headers,
                            String sourceName, String vodName, String episodeName,
                            java.util.List<com.github.tvbox.osc.bean.DownloadRoute> altRoutes, boolean requiresResolution) {
        if ((url == null || url.trim().isEmpty()) && (episodeRawUrl == null || episodeRawUrl.trim().isEmpty())) {
            return EnqueueResult.of(EnqueueResult.Code.RESOLVE_FAILED, "无可解析的剧集地址");
        }
        if (url == null || url.trim().isEmpty()) url = episodeRawUrl;
        if (!(url.startsWith("https://") || url.startsWith("http://"))
                && (sourceKey == null || episodeRawUrl == null)) {
            return EnqueueResult.of(EnqueueResult.Code.RESOLVE_FAILED, "无有效下载地址或源解析上下文");
        }
        String src = dm.sanitize(sourceName);
        if (src.isEmpty()) src = "未分类";
        String vn = dm.sanitize(vodName);
        if (vn.isEmpty()) vn = "未命名";

        // 文件扩展名:按 URL 后缀快速判定;m3u8 统一后续合并 mp4,其余直链保留原格式
        String ext = ".mp4";
        String lower = url == null ? "" : url.toLowerCase();
        if (!lower.contains(".m3u8")) {
            if (lower.contains(".mkv")) ext = ".mkv";
            else if (lower.contains(".flv")) ext = ".flv";
            else if (lower.contains(".avi")) ext = ".avi";
            else if (lower.contains(".mov")) ext = ".mov";
            else if (lower.contains(".webm")) ext = ".webm";
            else if (lower.contains(".wmv")) ext = ".wmv";
            else if (lower.contains(".m4v")) ext = ".m4v";
            else if (lower.contains(".3gp")) ext = ".3gp";
            else if (lower.contains(".mpg") || lower.contains(".mpeg")) ext = ".mpg";
            else if (lower.contains(".ts")) ext = ".ts";
            else if (lower.contains(".mp4")) ext = ".mp4";
        }

        String ep = episodeName == null ? "" : episodeName.trim();
        String fileName;
        if (ep.isEmpty() || ep.equals(vn)) {
            fileName = vn + ext;
        } else {
            fileName = vn + "_" + dm.sanitize(ep) + ext;
        }

        // Bug4: 存储权限是硬门槛,无权限不入队、不触发调度
        if (!FileCleaner.hasStoragePermission()) {
            Log.i("TVBox-Download", "enqueue 拒绝:无存储权限 " + fileName);
            return EnqueueResult.of(EnqueueResult.Code.PERMISSION_DENIED, "下载保存目录未就绪");
        }

        File dir = new File(dm.getSaveDir(), src + File.separator + vn);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return EnqueueResult.of(EnqueueResult.Code.STORAGE_ERROR, "无法创建下载目录");
        }
        if (!FileCleaner.canWritePath(dir.getAbsolutePath())) {
            return EnqueueResult.of(EnqueueResult.Code.PERMISSION_DENIED, "保存到公共目录需要所有文件访问权限");
        }
        long free = com.github.tvbox.osc.state.StorageSpace.freeBytes(dir);
        if (free >= 0 && free < DownloadPolicy.MIN_ABSOLUTE_FREE) {
            return EnqueueResult.of(EnqueueResult.Code.NO_SPACE, "存储空间不足，清理后再下载");
        }
        File finalFile = new File(dir, fileName);
        if (finalFile.exists()) {
            // 磁盘上已有同名成品文件,但没有对应记录(只删了记录没删文件 / 删除文件失败 / 文件是外部放进来的):
            // 原实现一律拒绝 → 该集永远"已在下载列表"却哪儿都看不到。这里自愈:
            // 像视频 → 登记成已下载(列表可见、可删);不像视频(0 字节/网页残留) → 删掉重下。
            if (dm.executor.looksLikeVideoFile(finalFile)) {
                Log.i("TVBox-Download", "enqueue: 已存在成品文件,登记为已下载 " + finalFile.getAbsolutePath());
                dm.adoptExistingAsDownloaded(finalFile, episodeId, sourceKey, src, vn, ep, pic);
                return EnqueueResult.of(EnqueueResult.Code.ALREADY_DOWNLOADED, "该集已下载完成");
            }
            Log.i("TVBox-Download", "enqueue: 清理无效残留文件 " + finalFile.getAbsolutePath());
            FileCleaner.deleteQuietly(finalFile);
        }
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (episodeId != null && !episodeId.isEmpty() && episodeId.equals(t.episodeId)) {
                    if (isStaleTaskRecord(t)) {
                        // 失败任务 / "标记完成但文件已不在"的陈旧记录: 覆盖重下
                        Log.i("TVBox-Download", "enqueue 覆盖陈旧任务记录: " + finalFile.getAbsolutePath());
                        dm.tasks.remove(t);
                        break;
                    }
                    Log.i("TVBox-Download", "enqueue 拒绝:任务已存在(episodeId) " + finalFile.getAbsolutePath());
                    return EnqueueResult.of(EnqueueResult.Code.DUPLICATE, "该集已在下载任务中");
                }
                if (t.savePath != null && t.savePath.equals(finalFile.getAbsolutePath())) {
                    if (isStaleTaskRecord(t)) {
                        Log.i("TVBox-Download", "enqueue 覆盖陈旧任务记录(路径): " + finalFile.getAbsolutePath());
                        dm.tasks.remove(t);
                        break;
                    }
                    Log.i("TVBox-Download", "enqueue 拒绝:任务已存在 " + finalFile.getAbsolutePath());
                    return EnqueueResult.of(EnqueueResult.Code.DUPLICATE, "该集已在下载任务中");
                }
            }
        }

        DownloadTask t = new DownloadTask();
        // 任务唯一ID:由 时间+文件名 计算(紧凑hex,确定可推导;同名任务由入队查重保证唯一)
        t.createTime = System.currentTimeMillis();
        t.id = Integer.toHexString((int) (t.createTime & 0xFFFFFFFFL))
                + Integer.toHexString(fileName.hashCode());
        t.queueOrder = t.createTime; // 同优先级 FIFO 队列序
        t.url = url;
        t.sourceKey = sourceKey;
        t.playFlag = playFlag;
        t.episodeRawUrl = episodeRawUrl;
        t.episodeId = episodeId;
        t.episodeName = episodeName;
        t.altRoutes = trimRoutes(altRoutes, playFlag);
        t.pic = pic;
        t.headers = DownloadHeaders.merge(headers);
        t.sourceName = src;
        t.vodName = vn;
        t.groupName = vn;
        t.fileName = fileName;
        t.savePath = finalFile.getAbsolutePath();
        t.partPath = t.savePath + ".part";
        // 复用残留的 .part(上次任务丢失/进程被杀后遗留):直链按已有大小断点续传,避免从头下载
        if (!lower.contains(".m3u8")) {
            File partFile = new File(t.partPath);
            if (partFile.exists() && partFile.length() > 0) {
                t.downloadedBytes = partFile.length();
            }
        }
        if (lower.contains(".m3u8")) {
            // Bug2: tmpDir 由 episodeId 派生(稳定可复用)——重入队同 episodeId 复用旧碎片续传,
            // 不再因 taskId 变化导致全部重下;无 episodeId 的旧任务回退 taskId
            String dirKey = (episodeId != null && !episodeId.isEmpty())
                    ? com.github.tvbox.osc.util.MD5.encode(episodeId) : t.id;
            // 分片目录放应用私有目录(镜像 来源/剧名/tmp/key):相册/媒体库与魅族等系统
            // 文件管理都管不到,清理 = 彻底删除;成品 mp4 仍写公共 Download(savePath 不变)
            t.tmpDir = new File(com.github.tvbox.osc.download.internal.FileCleaner.getPrivateTmpRoot(),
                    src + File.separator + vn + File.separator + "tmp" + File.separator + dirKey).getAbsolutePath();
        }
        t.state = DownloadTask.STATE_WAITING;
        t.message = dm.policy.isWifiOnly() && !DownloadPolicy.isWifiActive()
                ? com.github.tvbox.osc.download.DownloadFacade.MSG_WAIT_WIFI : "已排队";
        t.needReResolve = requiresResolution;
        t.resolvedRequest = !requiresResolution && (url.startsWith("https://") || url.startsWith("http://"));
        synchronized (dm.tasks) {
            dm.tasks.add(t);
        }
        Log.i("TVBox-Download", "enqueue 加入任务: " + episodeName + " -> " + t.savePath
                + " 请求头数=" + t.headers.size());
        DownloadLog.LOG.info(DownloadSubType.ENQUEUE, "加入任务: " + episodeName + " -> " + t.fileName,
                DownloadLog.extras(episodeId));
        dm.persist();
        dm.notifyChanged();
        wakeWorker();
        // 触发时顺带把剧集海报下载到本地(按剧集分文件夹,私有目录),下载页组级/条目级展示用
        dm.store.ensurePosterAsync(t.pic, t.vodName);
        // 入队即预检任务大小(仅直链做一次轻量 HEAD;m3u8 估算由启动时的探测负责,避免多拉一份播放列表):
        // 直链写 totalBytes(精确),让下载页尽早显示大小、启动前的磁盘空间判断直接复用结果
        if (!requiresResolution && (!dm.policy.isWifiOnly() || DownloadPolicy.isWifiActive())) dm.probeSizeAsync(t);
        return EnqueueResult.queued(t.id, t.message);
    }

    /** 暂停(用户手动):下载中/等待中/排队中的任务都可手动暂停,暂停后不再参与自动调度 */
    void pause(DownloadTask t) {
        if (t.state == DownloadTask.STATE_DOWNLOADING
                || t.state == DownloadTask.STATE_WAITING
                || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
            pauseInternal(t);
        }
    }

    /** 暂停指定任务为"用户暂停"(下载中则关闭其连接) */
    private void pauseInternal(DownloadTask t) {
        t.state = DownloadTask.STATE_PAUSED;
        t.speed = 0;
        dm.persist();
        dm.notifyChanged();
        Response r = dm.activeResponses.remove(t.id);
        if (r != null) {
            try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 继续:恢复单个暂停/失败任务。
     * 网络暂停(NETWORK_PAUSED)也允许手动继续,避免"断网自动挂起"后切回 WiFi 用户点击无反应;
     * 若仍处于蜂窝且开启仅WiFi,调度闸门会拦住(等 WiFi),属预期。
     * 若并发已满,把最早开始下载的任务置为"调度暂停"给被恢复的任务让位,保证严格按并发上限执行。
     */
    void resume(DownloadTask t) {
        if (t.state != DownloadTask.STATE_PAUSED
                && t.state != DownloadTask.STATE_FAILED
                && t.state != DownloadTask.STATE_NETWORK_PAUSED) {
            return;
        }
        t.state = DownloadTask.STATE_WAITING;
        t.message = "";
        t.needReResolve = true;
        t.reResolveCount = 0;
        synchronized (dm.tasks) {
            int running = 0;
            DownloadTask victim = null;
            for (DownloadTask tt : dm.tasks) {
                if (tt.state == DownloadTask.STATE_DOWNLOADING) {
                    running++;
                    // 4.4: 让位选"运行中优先级最低, 同级最后加入"的(避免挤掉高优先级)
                    if (victim == null
                            || tt.priority < victim.priority
                            || (tt.priority == victim.priority && tt.queueOrder > victim.queueOrder)) {
                        victim = tt;
                    }
                }
            }
            if (running >= dm.policy.getMaxConcurrent() && victim != null) {
                // 让位:被让位任务置"调度暂停"(被抢占者排同优先级最前, 有空位立即恢复)
                victim.state = DownloadTask.STATE_SYSTEM_PAUSED;
                victim.preemptTime = System.currentTimeMillis();
                Response r = dm.activeResponses.remove(victim.id);
                if (r != null) {
                    try {
                        r.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        dm.persist();
        dm.notifyChanged();
        wakeWorker();
    }

    // ------------------------------------------------------------------
    // 排队 / 插队(4.4)
    // ------------------------------------------------------------------

    /**
     * 排队插队(温和): 提到队首(该优先级最前, 不打断运行中任务)。
     * 实现: 置 HIGH 优先级 + 队列序提到最前。
     */
    void moveToFront(DownloadTask t) {
        if (t == null) return;
        synchronized (dm.tasks) {
            t.priority = DownloadTask.PRIORITY_HIGH;
            long min = Long.MAX_VALUE;
            for (DownloadTask tt : dm.tasks) {
                if (tt != t && tt.queueOrder < min) min = tt.queueOrder;
            }
            t.queueOrder = (min == Long.MAX_VALUE) ? 0 : Math.max(0, min - 1);
        }
        dm.persist();
        dm.notifyChanged();
        wakeWorker();
    }

    /**
     * 设置优先级(4.4):
     * - 置 HIGH 且并发已满 → 抢占让位(让"运行中最低优先级且最后加入"的任务腾出槽位, 被抢占者排最前)
     * - 其他级别仅改变排队顺序
     */
    void setPriority(DownloadTask t, int level) {
        if (t == null) return;
        level = Math.max(DownloadTask.PRIORITY_LOW, Math.min(DownloadTask.PRIORITY_HIGH, level));
        synchronized (dm.tasks) {
            t.priority = level;
            if (level == DownloadTask.PRIORITY_HIGH) {
                int running = 0;
                DownloadTask victim = null;
                for (DownloadTask tt : dm.tasks) {
                    if (tt.state == DownloadTask.STATE_DOWNLOADING) {
                        running++;
                        if (victim == null
                                || tt.priority < victim.priority
                                || (tt.priority == victim.priority && tt.queueOrder > victim.queueOrder)) {
                            victim = tt;
                        }
                    }
                }
                if (running >= dm.policy.getMaxConcurrent() && victim != null && victim != t) {
                    // 抢占: 被抢占者置 SYSTEM_PAUSED 并排最前(preemptTime 最新 -> 同级最先恢复)
                    victim.state = DownloadTask.STATE_SYSTEM_PAUSED;
                    victim.preemptTime = System.currentTimeMillis();
                    Response r = dm.activeResponses.remove(victim.id);
                    if (r != null) {
                        try {
                            r.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        dm.persist();
        dm.notifyChanged();
        wakeWorker();
    }

    /** 全部暂停:暂停所有下载中/等待中的任务(一次性持久化与通知) */
    void pauseAll() {
        boolean changed = false;
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.state == DownloadTask.STATE_DOWNLOADING || t.state == DownloadTask.STATE_WAITING) {
                    t.state = DownloadTask.STATE_PAUSED;
                    changed = true;
                    Response r = dm.activeResponses.remove(t.id);
                    if (r != null) {
                        try {
                            r.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        if (changed) {
            dm.persist();
            dm.notifyChanged();
        }
    }

    /** 全部开始:继续所有已暂停/失败/调度暂停的任务(失败等同重试),一次性持久化与通知 */
    void startAll() {
        boolean changed = false;
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.state == DownloadTask.STATE_PAUSED
                        || t.state == DownloadTask.STATE_FAILED
                        || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                    t.state = DownloadTask.STATE_WAITING;
                    t.needReResolve = true;
                    t.message = "";
                    changed = true;
                }
            }
        }
        if (changed) {
            dm.persist();
            dm.notifyChanged();
            wakeWorker();
        }
    }

    /** 任务是否已停止(暂停/调度暂停/网络暂停/取消) */
    private static boolean isTaskStopped(DownloadTask t) {
        return t.state != DownloadTask.STATE_DOWNLOADING || Thread.currentThread().isInterrupted();
    }

    /**
     * 陈旧任务记录:失败任务、或"标记完成但成品文件已不在"的记录 —— 都可以被新任务覆盖重下。
     * 后者是实际踩到的坑:从"已下载"里删掉文件后,同集的已完成任务记录若没被清掉,
     * 入队会判"任务已存在"拒绝,而列表又只显示"未完成 + 已完成且文件存在",于是两边都看不到。
     */
    private static boolean isStaleTaskRecord(DownloadTask t) {
        if (t.state == DownloadTask.STATE_FAILED) return true;
        return t.state == DownloadTask.STATE_COMPLETED
                && (t.savePath == null || !new File(t.savePath).exists());
    }

    /**
     * 删除任务
     *
     * @param t           任务
     * @param deleteFiles true=连本地文件(.part/成品/临时分片)一起删;false=只删记录保留文件
     */
    void remove(DownloadTask t, boolean deleteFiles) {
        // Bug2: 先置 CANCELLED 再关连接——下载线程每步检查 CANCELLED 立即中止,不落最终文件
        if (t.state == DownloadTask.STATE_DOWNLOADING) {
            t.state = DownloadTask.STATE_CANCELLED;
            Response r = dm.activeResponses.remove(t.id);
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        synchronized (dm.tasks) {
            dm.tasks.remove(t);
        }
        if (deleteFiles) {
            FileCleaner.deleteQuietly(new File(t.partPath));
            FileCleaner.deleteQuietly(new File(t.savePath));
            // 分段目录递归删除,父级 tmp 仅当为空才删
            dm.executor.deleteSegmentsDir(t);
            // 5.3: 删文件联动删档案(已下载档案长期保留,与文件生命周期一致)
            if (t.episodeId != null && !t.episodeId.isEmpty()) {
                dm.archive.remove(t.episodeId, false);
            }
        }
        dm.persist();
        // 即使只删记录也检查一次；留下的成品或分片会阻止海报删除。
        dm.requestPosterCleanup(t.vodName == null ? t.groupName : t.vodName,
                t.savePath, t.tmpDir);
        dm.notifyChanged();
        wakeWorker(); // 删除后重新调度
    }

    /**
     * Bug1: 仅WiFi开启且网络变为蜂窝/断开 → 全部任务置 NETWORK_PAUSED(关闭连接),
     * WiFi 恢复后自动恢复(见 {@link #resumeAllNetwork})。
     */
    void pauseAllNetwork() {
        boolean changed = false;
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.state == DownloadTask.STATE_DOWNLOADING
                        || t.state == DownloadTask.STATE_WAITING
                        || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                    t.state = DownloadTask.STATE_NETWORK_PAUSED;
                    changed = true;
                    Response r = dm.activeResponses.remove(t.id);
                    if (r != null) {
                        try {
                            r.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        if (changed) {
            dm.persist();
            dm.notifyChanged();
        }
    }

    /** Bug1: WiFi 恢复 → 自动恢复 NETWORK_PAUSED 任务(用户手动 PAUSED 不自动恢复) */
    void resumeAllNetwork() {
        boolean changed = false;
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.state == DownloadTask.STATE_NETWORK_PAUSED) {
                    t.state = DownloadTask.STATE_WAITING;
                    t.needReResolve = true;
                    changed = true;
                }
            }
        }
        if (changed) {
            dm.persist();
            dm.notifyChanged();
            wakeWorker();
        }
    }

    /**
     * 仅WiFi 开关即时生效(设置页/下载页切换后立即执行,不依赖网络切换事件):
     * 开启且当前非WiFi → 暂停全部(置网络暂停);关闭 → 恢复被挂起任务并唤醒调度。
     */
    void enforceNetworkGate() {
        if (dm.policy.isWifiOnly()) {
            if (!DownloadPolicy.isWifiActive()) {
                pauseAllNetwork();
            }
        } else {
            resumeAllNetwork();
        }
        wakeWorker(); // 关闭时立即按新配置调度;开启但原本就在 WiFi 上无动作也无需等待
    }

    /**
     * 存储看门狗:可用空间见底 → 暂停全部下载中/等待中/被抢占的任务(置用户暂停并写明原因)。
     * 与 {@link #pauseAllPermission} 同一形态:只暂停不自动恢复(空间刚过线就自动续传会变成
     * "清一点 → 又写满"的反复起停),用户清理空间后点"继续/全部开始"即按原进度续传。
     *
     * @return 是否有任务状态真的被改变(没有在跑的任务时返回 false,调用方据此避免重复落盘/广播)
     */
    boolean pauseAllStorage(String message) {
        boolean changed = false;
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (t.state == DownloadTask.STATE_DOWNLOADING
                        || t.state == DownloadTask.STATE_WAITING
                        || t.state == DownloadTask.STATE_SYSTEM_PAUSED) {
                    t.state = DownloadTask.STATE_PAUSED;
                    t.speed = 0;
                    t.message = message;
                    changed = true;
                    Response r = dm.activeResponses.remove(t.id);
                    if (r != null) {
                        try {
                            r.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        if (changed) {
            dm.persist();
            dm.notifyChanged();
        }
        return changed;
    }

    /**
     * Bug4: 存储权限被撤销 → 暂停全部运行任务(置用户暂停并提示,避免半截文件)。
     * 权限恢复后由用户手动继续(startTask 启动前也会再检查权限)。
     */
    void pauseAllPermission() {
        boolean changed = false;
        synchronized (dm.tasks) {
            for (DownloadTask t : dm.tasks) {
                if (!FileCleaner.canWritePath(t.savePath) && (t.state == DownloadTask.STATE_DOWNLOADING
                        || t.state == DownloadTask.STATE_WAITING
                        || t.state == DownloadTask.STATE_SYSTEM_PAUSED)) {
                    t.state = DownloadTask.STATE_PAUSED;
                    t.message = "存储权限已撤销";
                    changed = true;
                    Response r = dm.activeResponses.remove(t.id);
                    if (r != null) {
                        try {
                            r.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        if (changed) {
            dm.persist();
            dm.notifyChanged();
        }
    }
}

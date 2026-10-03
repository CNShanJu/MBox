package com.github.tvbox.osc.update;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import com.github.tvbox.osc.di.AppCompositionRoot;
import com.github.tvbox.osc.util.HeavyTaskUtil;
import com.github.tvbox.osc.util.LOG;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 更新 APK 下载控制器(与 UI 解耦,独立于 {@link Updater} 实现):
 * <ul>
 *   <li>可暂停/继续/取消的断点续传下载(OkHttp Range + 本地追加流);</li>
 *   <li>下载独立于任何弹窗/页面(应用级单例 + HeavyTaskUtil 共享线程),关闭抽屉不中断;</li>
 *   <li>同一版本 APK 已下载完整则直接复用(不再重复下载);</li>
 *   <li>应用启动清理:已安装版本的本地 APK 自动删除(更新完成后首次打开);</li>
 *   <li>状态经 {@link Listener} 广播,供全局悬浮圈({@code UpdateFloatIndicator})驱动;</li>
 * </ul>
 * <p>下载/安装逻辑从各 {@link Updater} 实现抽离到本控制器:后续切换更新源(GitHub/自建JSON/应用市场)
 * 只需实现各自 {@code checkUpdate},下载安装统一走本控制器。
 */
public final class UpdateManager {

    private static final String TAG = "UpdateManager";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile UpdateManager instance;

    public enum State { IDLE, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }

    /** 状态变更监听(主线程回调;驱动全局悬浮圈/弹窗) */
    public interface Listener {
        void onUpdate(State state, long downloaded, long total, UpdateInfo info);
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final UpdateDownloadSourcePolicy sourcePolicy = new UpdateDownloadSourcePolicy();
    private final UpdateDownloadEpoch epochs = new UpdateDownloadEpoch();
    private final ReentrantLock fileIoLock = new ReentrantLock();
    private volatile WorkerRun activeWorker;

    private static final class WorkerRun {
        volatile boolean cleanupRequested;
    }

    private volatile State state = State.IDLE;
    private volatile UpdateInfo info;
    private volatile long downloaded;
    private volatile long total;
    private volatile String errMsg;
    private volatile File targetFile;
    /** 当前实际发起请求的候选入口；缓存命中或尚未开始网络请求时为 null。 */
    private volatile String currentDownloadUrl;
    private volatile boolean pausedFlag;
    private volatile boolean cancelFlag;
    private volatile okhttp3.Call currentCall;
    private volatile Updater.Callback callback;
    private volatile Context appContext;

    private UpdateManager() {
    }

    public static UpdateManager get() {
        if (instance == null) {
            synchronized (UpdateManager.class) {
                if (instance == null) instance = new UpdateManager();
            }
        }
        return instance;
    }

    // ── 查询 ──

    public State getState() { return state; }
    public UpdateInfo getInfo() { return info; }
    public long getDownloaded() { return downloaded; }
    public long getTotal() { return total; }
    public String getError() { return errMsg; }
    public String getCurrentDownloadUrl() { return currentDownloadUrl; }
    /** 下载完成的 APK 文件(无则 null) */
    public File getApkFile() {
        File f = targetFile;
        return (state == State.COMPLETED && f != null && f.exists()) ? f : null;
    }

    public void addListener(Listener l) { if (l != null) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    // ── 控制 ──

    /**
     * 开始下载(调用方事先拿到 {@link UpdateInfo};回调桥接 Updater.Callback,与旧 API 兼容)。
     * 已缓存完整同名 APK 时直接复用,不再发起网络下载。
     */
    public void start(Context context, UpdateInfo info, Updater.Callback cb) {
        if (context == null) return;
        LOG.i(TAG, "开始下载 version=" + (info == null ? "?" : info.versionName)
                + " size=" + (info == null ? "?" : info.apkSize));
        synchronized (this) {
            if (state == State.DOWNLOADING || state == State.PAUSED) return;
            long workerEpoch = epochs.next();
            File previousTarget = this.targetFile;
            this.appContext = context.getApplicationContext();
            this.info = info;
            this.callback = cb;
            this.errMsg = null;
            this.pausedFlag = false;
            this.cancelFlag = false;
            this.downloaded = 0;
            this.total = info == null ? -1 : info.apkSize;
            this.targetFile = info == null ? null : apkFile(context, info);
            this.currentDownloadUrl = null;
            if (previousTarget == null || !previousTarget.equals(this.targetFile)) sourcePolicy.reset();
            // 断点续传起点
            if (targetFile != null && targetFile.exists()) {
                this.downloaded = targetFile.length();
            }
            this.state = State.DOWNLOADING;
            notifyListeners();
            startDownload(workerEpoch);
        }
    }

    /** 暂停下载(断点保留,可在{@link #resume()}继续) */
    public void pause() {
        LOG.i(TAG, "暂停下载(断点保留)");
        pausedFlag = true;
        cancelCurrentCall();
        // 状态在 worker 结束处确认;若 worker 已在读,标志位使其退出
    }

    /** 继续下载(从断点 Range 续传) */
    public void resume() {
        synchronized (this) {
            if (state != State.PAUSED) return;
            long workerEpoch = epochs.next();
            LOG.i(TAG, "继续下载(从断点续传)");
            pausedFlag = false;
            currentDownloadUrl = null;
            state = State.DOWNLOADING;
            notifyListeners();
            startDownload(workerEpoch);
        }
    }

    /** 取消并清除(用户"不再更新"):删除半成品并回到空闲,同时隐藏悬浮圈 */
    public void cancel() {
        LOG.i(TAG, "放弃更新,清除缓存");
        cancelFlag = true;
        pausedFlag = true; // 让 worker 读循环退出
        cancelCurrentCall();
        synchronized (this) {
            epochs.next(); // 旧 worker 及其已排队的主线程回调立即失效
            WorkerRun worker = activeWorker;
            if (worker != null) worker.cleanupRequested = true;
            if (targetFile != null && targetFile.exists()) {
                // 仅删除不完整/未安装的缓存(保留完整且已安装版本由启动清理负责)
                try { targetFile.delete(); } catch (Throwable ignored) {}
            }
            state = State.CANCELLED;
            info = null;
            downloaded = 0;
            total = -1;
            errMsg = null;
            currentDownloadUrl = null;
            sourcePolicy.reset();
            notifyListeners();
        }
    }

    /** 安装已下载的 APK(经系统安装器;API26+ 先校验"安装未知应用"授权) */
    public boolean installCurrent(Context context) {
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        File apk = getApkFile();
        if (ctx == null || apk == null) {
            LOG.e(TAG, "安装失败:上下文或已下载 APK 缺失");
            return false;
        }
        boolean ok = installApk(ctx, apk);
        LOG.i(TAG, "安装已下载 APK 结果=" + ok + " path=" + apk.getAbsolutePath());
        return ok;
    }

    /** 应用启动清理:后台删除已安装版本的 APK 与损坏半成品。 */
    public static void cleanupOnAppStart(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> get().cleanupInstalledApks(app));
    }

    private void cleanupInstalledApks(Context context) {
        // 下载正写文件时本轮跳过；下次启动仍会清理，避免等待网络请求占住后台池。
        if (!fileIoLock.tryLock()) return;
        try {
            File dir = apkDir(context);
            if (dir == null || !dir.exists()) {
                LOG.i(TAG, "启动清理: update 目录不存在,跳过");
                return;
            }
            int installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                LOG.i(TAG, "启动清理: update 目录为空,无需清理");
                return;
            }
            int deleted = 0;
            for (File f : files) {
                if (f.isDirectory()) continue;
                if ((state == State.DOWNLOADING || state == State.PAUSED)
                        && f.equals(targetFile)) continue;
                try {
                    int apkCode = readApkVersionCode(context, f.getAbsolutePath());
                    if (apkCode > 0 && apkCode == installed) {
                        if (f.delete()) {
                            deleted++;
                            LOG.i(TAG, "启动清理: 删除已安装版本安装包 " + f.getName() + " (vc=" + apkCode + ")");
                        }
                    } else if (apkCode < 0) {
                        // 非 APK/损坏:半成品,删除
                        if (f.delete()) {
                            deleted++;
                            LOG.i(TAG, "启动清理: 删除损坏/半成品 " + f.getName());
                        }
                    } else {
                        LOG.i(TAG, "启动清理: 保留未安装版本缓存 " + f.getName() + " (vc=" + apkCode + " != " + installed + ")");
                    }
                } catch (Throwable e) {
                    if (f.delete()) deleted++;
                }
            }
            LOG.i(TAG, "启动清理: 完成,删除 " + deleted + " 个,保留其余缓存");
        } catch (Throwable e) {
            LOG.e(TAG, "启动清理异常: " + e);
        } finally {
            fileIoLock.unlock();
        }
    }

    // ── 内部 ──

    /** 单次候选下载结果 */
    private enum DownloadResult { COMPLETE, FAIL, STOPPED }

    private void startDownload(long workerEpoch) {
        final Context ctx = appContext;
        final UpdateInfo ui = info;
        final File dest = targetFile;
        if (ctx == null || ui == null || ui.downloadUrls == null || ui.downloadUrls.isEmpty() || dest == null) {
            state = State.FAILED;
            errMsg = ui != null && dest == null ? "安装包文件名无效" : "更新信息不完整";
            notifyListeners(workerEpoch);
            fireError(workerEpoch);
            return;
        }
        WorkerRun worker = new WorkerRun();
        activeWorker = worker;
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            fileIoLock.lock();
            try {
            if (!epochs.isCurrent(workerEpoch)) return;
            boolean completes = false;
            String lastErr = null;
            try {
                // PackageManager archive parsing may read the whole APK; keep cache validation off UI.
                if (isCachedComplete(ctx, dest, ui, workerEpoch)) {
                    completes = true;
                    synchronized (this) {
                        if (!epochs.isCurrent(workerEpoch)) return;
                        downloaded = dest.length();
                    }
                }
                OkHttpClient client = completes ? null : AppCompositionRoot.network().general();
                // 有可归属的片段时先续传其来源；失败后按原顺序回退其它候选。
                // 换来源不能把不同响应拼成一份 APK。
                for (String url : sourcePolicy.orderedCandidates(ui.downloadUrls,
                        dest.exists() ? dest.length() : 0)) {
                    if (completes) break;
                    if (!prepareCandidate(workerEpoch, url, dest, ui)) break;
                    DownloadResult dr = downloadFromCandidate(client, url, dest, ui, workerEpoch);
                    if (dr == DownloadResult.COMPLETE) {
                        if (isCachedComplete(ctx, dest, ui, workerEpoch)) {
                            completes = true;
                            break;
                        }
                        synchronized (this) {
                            if (!epochs.isCurrent(workerEpoch)) return;
                            sourcePolicy.reset();
                            errMsg = "下载文件不是完整的 APK";
                        }
                    } else if (dr == DownloadResult.STOPPED) {
                        break;
                    }
                    lastErr = errMsg; // FAIL:记录本次错误,切换下一候选
                }
            } catch (Throwable t) {
                lastErr = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }

            final boolean finished = completes;
            final String failure = lastErr;
            synchronized (this) {
                epochs.runIfCurrent(workerEpoch, () -> {
                    if (cancelFlag) {
                        state = State.CANCELLED;
                        notifyListeners(workerEpoch);
                    } else if (pausedFlag) {
                        state = State.PAUSED;
                        notifyListeners(workerEpoch);
                    } else if (finished) {
                        // 缓存与网络候选都已经通过 APK 格式校验。
                        if (dest.exists() && dest.length() > 0) {
                            state = State.COMPLETED;
                            downloaded = dest.length();
                            notifyListeners(workerEpoch);
                            fireReady(workerEpoch);
                        } else {
                            state = State.FAILED;
                            errMsg = "下载文件不是完整的 APK";
                            notifyListeners(workerEpoch);
                            fireError(workerEpoch);
                        }
                    } else {
                        // 所有候选源均失败
                        state = State.FAILED;
                        errMsg = failure == null ? "下载失败" : ("下载失败: " + failure);
                        LOG.e(TAG, "下载失败: " + errMsg);
                        notifyListeners(workerEpoch);
                        fireError(workerEpoch);
                    }
                });
            }
            } finally {
                try {
                    boolean deleteCancelledFile;
                    synchronized (this) {
                        // A newer worker may have reached the same path first. Never remove its APK.
                        boolean newerOwnsSameFile = activeWorker != worker && dest.equals(targetFile)
                                && state != State.CANCELLED;
                        deleteCancelledFile = worker.cleanupRequested && !newerOwnsSameFile;
                    }
                    if (deleteCancelledFile && dest.exists() && !dest.delete()) {
                        LOG.e(TAG, "取消后无法清除 APK 片段: " + dest.getName());
                    }
                } catch (Throwable t) {
                    LOG.e(TAG, "取消后清除 APK 片段异常: " + t);
                } finally {
                    synchronized (this) {
                        if (activeWorker == worker) activeWorker = null;
                    }
                    fileIoLock.unlock();
                }
            }
        });
    }

    private boolean prepareCandidate(long workerEpoch, String url, File dest, UpdateInfo ui) {
        synchronized (this) {
            if (!epochs.isCurrent(workerEpoch) || cancelFlag || pausedFlag) return false;
            long existingBytes = dest.exists() ? dest.length() : 0;
            if (sourcePolicy.mustDiscardPartial(url, existingBytes)) {
                if (dest.exists() && !dest.delete()) {
                    sourcePolicy.reset();
                    errMsg = "无法清除上一下载链路的片段";
                    return false;
                }
                downloaded = 0;
                total = ui.apkSize;
            } else {
                downloaded = existingBytes;
            }
            currentDownloadUrl = url;
            notifyListeners(workerEpoch);
            return true;
        }
    }

    /**
     * 尝试从一个候选地址下载(支持断点续传)。
     *
     * @return COMPLETE 本候选已完整下载;FAIL 本候选失效(可切换下一候选);STOPPED 用户暂停/取消。
     */
    private DownloadResult downloadFromCandidate(OkHttpClient client, String url, File dest,
                                                 UpdateInfo ui, long workerEpoch) {
        final long startFrom;
        synchronized (this) {
            if (workerStopped(workerEpoch)) return DownloadResult.STOPPED;
            startFrom = downloaded;
        }
        OutputStream fos = null;
        InputStream is = null;
        okhttp3.Call call = null;
        try {
            Request.Builder rb = new Request.Builder().url(url);
            if (startFrom > 0) {
                rb.header("Range", "bytes=" + startFrom + "-");
            }
            // 静默标记:APK 下载是后台链路(用户可能已经切走去看别的),失败由更新弹窗自己报错,
            // 不该把用户弹到"网络不可用"整屏页
            Request req = com.github.tvbox.osc.util.NetworkGuardInterceptor.markQuiet(rb.build());
            call = client.newCall(req);
            synchronized (this) {
                if (workerStopped(workerEpoch)) return DownloadResult.STOPPED;
                currentCall = call;
            }
            Response resp = call.execute();
            try {
                if (workerStopped(workerEpoch)) return DownloadResult.STOPPED;
                boolean invalidRange = startFrom > 0 && resp.code() == 206
                        && !UpdateDownloadSourcePolicy.matchesContentRangeStart(
                                resp.header("Content-Range"), startFrom);
                boolean retriedWithoutRange = resp.code() == 416 || invalidRange;
                if (retriedWithoutRange) {
                    // 416 = Range 起点超出资源长度(服务端换了文件/不接受 Range 时常见)。
                    // 206 的 Content-Range 缺失/错位时也不能把响应追加到旧片段。
                    // 两种情况都先丢弃片段，再不带 Range 重试同一候选一次。
                    resp.close();
                    synchronized (this) {
                        if (workerStopped(workerEpoch)) return DownloadResult.STOPPED;
                        if (dest.exists() && !dest.delete()) {
                            errMsg = "无法清除旧下载片段";
                            return DownloadResult.FAIL;
                        }
                        downloaded = 0;
                        total = ui.apkSize;
                    }
                    Request retryReq = com.github.tvbox.osc.util.NetworkGuardInterceptor
                            .markQuiet(new Request.Builder().url(url).build());
                    call = client.newCall(retryReq);
                    synchronized (this) {
                        if (workerStopped(workerEpoch)) return DownloadResult.STOPPED;
                        currentCall = call;
                    }
                    resp = call.execute();
                    LOG.i(TAG, invalidRange
                            ? "Content-Range 与续传起点不符,已改为不带 Range 重下"
                            : "Range 不被接受(416),已改为不带 Range 重下");
                }
                if (workerStopped(workerEpoch)) return DownloadResult.STOPPED;
                if (!resp.isSuccessful() || resp.body() == null) {
                    // 该候选失效(如代理不可用/限流/404):交外层切换下一候选
                    synchronized (this) {
                        if (workerStopped(workerEpoch)) return DownloadResult.STOPPED;
                        errMsg = "HTTP " + resp.code();
                    }
                    return DownloadResult.FAIL;
                } else if (resp.code() == 206 && (retriedWithoutRange || startFrom <= 0)) {
                    // 无 Range 请求收到的 206 不是完整文件，不能作为新的下载起点。
                    synchronized (this) {
                        if (workerStopped(workerEpoch)) return DownloadResult.STOPPED;
                        errMsg = "无 Range 请求收到不完整响应(HTTP 206)";
                    }
                    return DownloadResult.FAIL;
                } else if (startFrom > 0 && !retriedWithoutRange && resp.code() != 206) {
                    // 服务端忽略 Range(返回 200):重新从头写
                    synchronized (this) {
                        if (workerStopped(workerEpoch)) return DownloadResult.STOPPED;
                        if (dest.exists() && !dest.delete()) {
                            errMsg = "无法清除旧下载片段";
                            return DownloadResult.FAIL;
                        }
                        downloaded = 0;
                        total = ui.apkSize;
                    }
                }
                long bodyLen = resp.body() == null ? 0 : resp.body().contentLength();
                synchronized (this) {
                    if (workerStopped(workerEpoch)) return DownloadResult.STOPPED;
                    if (bodyLen > 0) {
                        total = (downloaded == 0) ? bodyLen : downloaded + bodyLen;
                    } else if (total <= 0) {
                        total = -1;
                    }
                    fos = new FileOutputStream(dest, downloaded > 0);
                }
                is = resp.body() == null ? null : resp.body().byteStream();
                if (is != null) {
                    byte[] buf = new byte[8192];
                    int len;
                    long lastPost = 0;
                    while (!workerStopped(workerEpoch) && (len = is.read(buf)) > 0) {
                        synchronized (this) {
                            if (workerStopped(workerEpoch)) return DownloadResult.STOPPED;
                            fos.write(buf, 0, len);
                            downloaded += len;
                        }
                        // 进度节流:约 150ms 或跨 512KB 才上报一次,避免高频主线程回调
                        long now = System.currentTimeMillis();
                        if (now - lastPost >= 150) {
                            lastPost = now;
                            postProgress(workerEpoch);
                        }
                    }
                    postProgress(workerEpoch);
                }
            } finally {
                try { if (resp != null) resp.close(); } catch (Throwable ignored) {}
            }

            if (workerStopped(workerEpoch)) return DownloadResult.STOPPED;

            // 体积校验:已知 apkSize 且下载大小不符(代理可能返回错误页/截断)→ 判本候选失败,换下一候选
            if (ui.apkSize > 0 && dest.exists() && dest.length() != ui.apkSize) {
                // 响应已结束仍不符预期，可能是完整错误页；重试不能从它的末尾续传。
                if (fos != null) {
                    try { fos.close(); } catch (Throwable ignored) { }
                    fos = null;
                }
                synchronized (this) {
                    if (workerStopped(workerEpoch)) return DownloadResult.STOPPED;
                    errMsg = "下载大小不符(" + dest.length() + " != " + ui.apkSize + ")";
                    sourcePolicy.reset();
                    if (!dest.delete()) errMsg += "，且无法清除损坏片段";
                    else downloaded = 0;
                }
                return DownloadResult.FAIL;
            }
            return DownloadResult.COMPLETE;
        } catch (Throwable t) {
            synchronized (this) {
                if (workerStopped(workerEpoch)) return DownloadResult.STOPPED;
                errMsg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            return DownloadResult.FAIL;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
            synchronized (this) {
                if (currentCall == call) currentCall = null;
            }
        }
    }

    private boolean workerStopped(long workerEpoch) {
        return !epochs.isCurrent(workerEpoch) || cancelFlag || pausedFlag;
    }

    private void cancelCurrentCall() {
        okhttp3.Call c = currentCall;
        if (c != null) {
            try { c.cancel(); } catch (Throwable ignored) {}
        }
    }

    private void notifyListeners() {
        notifyListeners(epochs.current());
    }

    private void notifyListeners(long workerEpoch) {
        if (!epochs.isCurrent(workerEpoch)) return;
        final State s = state;
        final long d = downloaded;
        final long t = total;
        final UpdateInfo fi = info;
        MAIN.post(() -> {
            if (!epochs.isCurrent(workerEpoch)) return;
            for (Listener l : listeners) {
                try { l.onUpdate(s, d, t, fi); } catch (Throwable ignored) {}
            }
        });
    }

    private void postProgress(long workerEpoch) {
        if (!epochs.isCurrent(workerEpoch)) return;
        final long d = downloaded;
        final long t = total;
        MAIN.post(() -> {
            if (!epochs.isCurrent(workerEpoch)) return;
            Updater.Callback cb = callback;
            if (cb != null) {
                try { cb.onDownloadProgress(d, t); } catch (Throwable ignored) {}
            }
            for (Listener l : listeners) {
                try { l.onUpdate(State.DOWNLOADING, d, t, info); } catch (Throwable ignored) {}
            }
        });
    }

    private void fireReady(long workerEpoch) {
        MAIN.post(() -> {
            if (!epochs.isCurrent(workerEpoch)) return;
            Updater.Callback cb = callback;
            if (cb != null) {
                try { cb.onDownloadReady(info); } catch (Throwable ignored) {}
            }
        });
    }

    private void fireError(long workerEpoch) {
        MAIN.post(() -> {
            if (!epochs.isCurrent(workerEpoch)) return;
            Updater.Callback cb = callback;
            if (cb != null) {
                try { cb.onError(errMsg); } catch (Throwable ignored) {}
            }
        });
    }

    /** 通过系统安装器安装 APK(API 26+ 先校验"安装未知应用"授权) */
    private boolean installApk(Context context, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (!context.getPackageManager().canRequestPackageInstalls()) {
                    try {
                        Intent intent = new Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + context.getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } catch (Throwable ignored) {
                    }
                    return false;
                }
            }
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── 文件/路径 ──

    /** APK 缓存目录(应用专属外部存储,持久;兜底内部存储) */
    private static File apkDir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(base, "update");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File apkFile(Context c, UpdateInfo info) {
        String name = (info.apkName == null || info.apkName.isEmpty())
                ? ("update-" + (info.versionTag == null ? "apk" : info.versionTag) + ".apk")
                : info.apkName;
        return UpdateApkFilePolicy.resolve(apkDir(c), name);
    }

    /** 体积与已知版本符合预期且能解析为 APK 才复用；损坏缓存删除后重新下载。 */
    private boolean isCachedComplete(Context context, File file, UpdateInfo info, long workerEpoch) {
        if (workerStopped(workerEpoch) || file == null || !file.exists() || file.length() <= 0) return false;
        if (info != null && info.apkSize > 0 && file.length() != info.apkSize) return false;
        int archiveVersion = readApkVersionCode(context, file.getAbsolutePath());
        synchronized (this) {
            if (workerStopped(workerEpoch)) return false;
            if (UpdateArchivePolicy.acceptsVersion(info == null ? -1 : info.versionCode,
                    archiveVersion)) return true;
            LOG.i(TAG, "缓存 APK 不完整、损坏或版本不符,删除后重新下载: " + file.getName());
            if (file.exists() && !file.delete()) throw new IllegalStateException("无法清除损坏的 APK 缓存");
            sourcePolicy.reset();
            downloaded = 0;
            return false;
        }
    }

    /** 读取 APK 包 versionCode;非 APK/损坏返回 -1 */
    private static int readApkVersionCode(Context context, String path) {
        try {
            PackageManager pm = context.getPackageManager();
            android.content.pm.PackageInfo pi;
            if (Build.VERSION.SDK_INT >= 33) {
                pi = pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0));
            } else {
                pi = pm.getPackageArchiveInfo(path, 0);
            }
            return pi == null || !context.getPackageName().equals(pi.packageName)
                    ? -1 : pi.versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }
}

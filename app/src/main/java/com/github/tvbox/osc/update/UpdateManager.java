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

            // 已下载完整?直接复用(对应"检查本地已下载对应版本 apk,存在即使用")
            if (isCachedComplete(context, targetFile, info)) {
                this.downloaded = targetFile.length();
                this.state = State.COMPLETED;
                notifyListeners();
                fireReady();
                return;
            }
            // 断点续传起点
            if (targetFile != null && targetFile.exists()) {
                this.downloaded = targetFile.length();
            }
            this.state = State.DOWNLOADING;
            notifyListeners();
            startDownload();
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
        if (state != State.PAUSED) return;
        LOG.i(TAG, "继续下载(从断点续传)");
        pausedFlag = false;
        currentDownloadUrl = null;
        state = State.DOWNLOADING;
        notifyListeners();
        startDownload();
    }

    /** 取消并清除(用户"不再更新"):删除半成品并回到空闲,同时隐藏悬浮圈 */
    public void cancel() {
        LOG.i(TAG, "放弃更新,清除缓存");
        cancelFlag = true;
        pausedFlag = true; // 让 worker 读循环退出
        cancelCurrentCall();
        synchronized (this) {
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

    /** 应用启动清理:删除"版本与当前安装一致"的本地 APK(更新完成后首次打开);并清讨厌半成品 */
    public static void cleanupOnAppStart(Context context) {
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
        }
    }

    // ── 内部 ──

    /** 单次候选下载结果 */
    private enum DownloadResult { COMPLETE, FAIL, STOPPED }

    private void startDownload() {
        final Context ctx = appContext;
        final UpdateInfo ui = info;
        final File dest = targetFile;
        if (ctx == null || ui == null || ui.downloadUrls == null || ui.downloadUrls.isEmpty() || dest == null) {
            state = State.FAILED;
            errMsg = "更新信息不完整";
            notifyListeners();
            fireError();
            return;
        }
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            boolean completes = false;
            String lastErr = null;
            try {
                OkHttpClient client = AppCompositionRoot.network().general();
                // 候选列表依序尝试:代理优先,直连兜底;某候选失败(网络/HTTP/流中断)自动切下一个,
                // 已写字节保留(断点续传),若服务端支持 Range 则用 downloaded 偏移续下。
                for (String url : ui.downloadUrls) {
                    if (cancelFlag || pausedFlag) break;
                    // 只有走到这里的 URL 才是当前下载链路；每次回退都立即刷新面板。
                    currentDownloadUrl = url;
                    notifyListeners();
                    DownloadResult dr = downloadFromCandidate(client, url, dest, ui);
                    if (dr == DownloadResult.COMPLETE) {
                        completes = true;
                        break;
                    } else if (dr == DownloadResult.STOPPED) {
                        break;
                    }
                    lastErr = errMsg; // FAIL:记录本次错误,切换下一候选
                }
            } catch (Throwable t) {
                lastErr = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }

            if (cancelFlag) {
                state = State.CANCELLED;
                notifyListeners();
            } else if (pausedFlag) {
                state = State.PAUSED;
                notifyListeners();
            } else if (completes) {
                // 校验完整性:可继续复用(甚至可能已完整)
                if (dest.exists() && dest.length() > 0) {
                    state = State.COMPLETED;
                    downloaded = dest.length();
                    notifyListeners();
                    fireReady();
                } else {
                    state = State.FAILED;
                    errMsg = "下载文件为空";
                    notifyListeners();
                    fireError();
                }
            } else {
                // 所有候选源均失败
                state = State.FAILED;
                errMsg = lastErr == null ? "下载失败" : ("下载失败: " + lastErr);
                LOG.e(TAG, "下载失败: " + errMsg);
                notifyListeners();
                fireError();
            }
        });
    }

    /**
     * 尝试从一个候选地址下载(支持断点续传)。
     *
     * @return COMPLETE 本候选已完整下载;FAIL 本候选失效(可切换下一候选);STOPPED 用户暂停/取消。
     */
    private DownloadResult downloadFromCandidate(OkHttpClient client, String url, File dest, UpdateInfo ui) {
        final long startFrom = downloaded;
        OutputStream fos = null;
        InputStream is = null;
        try {
            Request.Builder rb = new Request.Builder().url(url);
            if (startFrom > 0) {
                rb.header("Range", "bytes=" + startFrom + "-");
            }
            // 静默标记:APK 下载是后台链路(用户可能已经切走去看别的),失败由更新弹窗自己报错,
            // 不该把用户弹到"网络不可用"整屏页
            Request req = com.github.tvbox.osc.util.NetworkGuardInterceptor.markQuiet(rb.build());
            okhttp3.Call call = client.newCall(req);
            currentCall = call;
            Response resp = call.execute();
            try {
                if (resp.code() == 416) {
                    // 416 = Range 起点超出资源长度(服务端换了文件/不接受 Range 时常见)。
                    // 原实现只是把计数清零,然后继续读 **416 的错误响应体**当数据写进目标文件 ——
                    // apkSize 未知时那份错误页还会被当成"下载完整"直接去安装。
                    // 这里关掉响应,**不带 Range 重发一次**从头下载。
                    resp.close();
                    downloaded = 0;
                    dest.delete();
                    Request retryReq = com.github.tvbox.osc.util.NetworkGuardInterceptor
                            .markQuiet(new Request.Builder().url(url).build());
                    call = client.newCall(retryReq);
                    currentCall = call;
                    resp = call.execute();
                    LOG.i(TAG, "Range 不被接受(416),已改为不带 Range 重下: " + url);
                }
                if (!resp.isSuccessful() || resp.body() == null) {
                    // 该候选失效(如代理不可用/限流/404):交外层切换下一候选
                    return DownloadResult.FAIL;
                } else if (startFrom > 0 && resp.code() != 206) {
                    // 服务端忽略 Range(返回 200):重新从头写
                    downloaded = 0;
                    dest.delete();
                }
                long bodyLen = resp.body() == null ? 0 : resp.body().contentLength();
                if (bodyLen > 0) {
                    total = (downloaded == 0) ? bodyLen : downloaded + bodyLen;
                } else if (total <= 0) {
                    total = -1;
                }
                boolean append = downloaded > 0;
                fos = new FileOutputStream(dest, append);
                is = resp.body() == null ? null : resp.body().byteStream();
                if (is != null) {
                    byte[] buf = new byte[8192];
                    int len;
                    long lastPost = 0;
                    while (!pausedFlag && !cancelFlag && (len = is.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                        downloaded += len;
                        // 进度节流:约 150ms 或跨 512KB 才上报一次,避免高频主线程回调
                        long now = System.currentTimeMillis();
                        if (now - lastPost >= 150) {
                            lastPost = now;
                            postProgress();
                        }
                    }
                    postProgress();
                }
            } finally {
                try { if (resp != null) resp.close(); } catch (Throwable ignored) {}
            }

            if (cancelFlag || pausedFlag) return DownloadResult.STOPPED;

            // 体积校验:已知 apkSize 且下载大小不符(代理可能返回错误页/截断)→ 判本候选失败,换下一候选
            if (ui.apkSize > 0 && dest.exists() && dest.length() != ui.apkSize) {
                errMsg = "下载大小不符(" + dest.length() + " != " + ui.apkSize + ")";
                return DownloadResult.FAIL;
            }
            return DownloadResult.COMPLETE;
        } catch (Throwable t) {
            if (cancelFlag || pausedFlag) return DownloadResult.STOPPED;
            errMsg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            return DownloadResult.FAIL;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
            currentCall = null;
        }
    }

    private void cancelCurrentCall() {
        okhttp3.Call c = currentCall;
        if (c != null) {
            try { c.cancel(); } catch (Throwable ignored) {}
        }
    }

    private void notifyListeners() {
        final State s = state;
        final long d = downloaded;
        final long t = total;
        final UpdateInfo fi = info;
        MAIN.post(() -> {
            for (Listener l : listeners) {
                try { l.onUpdate(s, d, t, fi); } catch (Throwable ignored) {}
            }
        });
    }

    private void postProgress() {
        final long d = downloaded;
        final long t = total;
        MAIN.post(() -> {
            Updater.Callback cb = callback;
            if (cb != null) {
                try { cb.onDownloadProgress(d, t); } catch (Throwable ignored) {}
            }
            for (Listener l : listeners) {
                try { l.onUpdate(State.DOWNLOADING, d, t, info); } catch (Throwable ignored) {}
            }
        });
    }

    private void fireReady() {
        MAIN.post(() -> {
            Updater.Callback cb = callback;
            if (cb != null) {
                try { cb.onDownloadReady(info); } catch (Throwable ignored) {}
            }
        });
    }

    private void fireError() {
        MAIN.post(() -> {
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
        return new File(apkDir(c), name);
    }

    /** 体积符合预期且非空判定为"已下载完整"(命中即复用,不重新下载) */
    private static boolean isCachedComplete(Context context, File file, UpdateInfo info) {
        if (file == null || !file.exists() || file.length() <= 0) return false;
        if (info != null && info.apkSize > 0) return file.length() == info.apkSize;
        // 资产没给 size 时不能只凭"文件非空"就当完整:上次中断留下的半截文件会被直接拿去安装,
        // 用户看到的是"解析软件包时出现问题"。这里解析 APK 头验证(损坏/截断返回 -1),不通过就删掉重下。
        if (readApkVersionCode(context, file.getAbsolutePath()) > 0) return true;
        LOG.i(TAG, "缓存 APK 不完整或损坏,删除后重新下载: " + file.getName());
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return false;
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
            return pi == null ? -1 : pi.versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }
}

package com.github.tvbox.osc.share.transport.lan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.share.ShareAvailability;
import com.github.tvbox.osc.share.ShareCallback;
import com.github.tvbox.osc.share.ShareCapability;
import com.github.tvbox.osc.share.ShareErrorCode;
import com.github.tvbox.osc.share.ShareException;
import com.github.tvbox.osc.share.ShareImportListener;
import com.github.tvbox.osc.share.ShareLimits;
import com.github.tvbox.osc.share.ShareLink;
import com.github.tvbox.osc.share.ShareManifest;
import com.github.tvbox.osc.share.SharePackage;
import com.github.tvbox.osc.share.SharePlatform;
import com.github.tvbox.osc.share.ShareProgress;
import com.github.tvbox.osc.share.ShareRequest;
import com.github.tvbox.osc.share.internal.BaseTransport;
import com.github.tvbox.osc.share.internal.ShareArchive;
import com.github.tvbox.osc.share.internal.ShareCallbackHandle;
import com.github.tvbox.osc.share.internal.ShareExecutors;
import com.github.tvbox.osc.share.internal.ShareMainThread;

import java.io.File;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 局域网传输:本机当服务端,同网段设备<b>下载</b>导出包 / <b>上传</b>导入包。
 *
 * <p>能力:{@link ShareCapability#EXPORT}(挂下载地址)+ {@link ShareCapability#IMPORT_PUSH}
 * (开接收口等对端上传)。<b>没有</b> {@link ShareCapability#IMPORT_PULL} —— 局域网分享是
 * "对端来连本机",本机不去连对端,所以"给个地址让本机去拉"在这里没有对应物。
 *
 * <p>前置条件(不可用时 {@link #availability()} 会说清是哪一条):
 * <ol>
 *   <li>{@link LanShareHost} 已注入(未注入 = 本 App 没接入局域网分享);</li>
 *   <li>设置里「局域网服务」已开启;</li>
 *   <li>当前运行的 HTTP 服务<b>真的绑到了局域网网卡</b> —— 开关是重启生效的,
 *       刚打开开关时服务还在 127.0.0.1 上,此时必须如实说"重启后生效",
 *       否则用户会对着一个同网段根本连不上的地址干等;</li>
 *   <li>能取到局域网 IPv4 地址(没连 Wi-Fi/网线就取不到)。</li>
 * </ol>
 *
 * <p>关于"导出成功"的语义:挂出地址<b>即</b>成功({@link ShareCallback#onSuccess} 给回
 * {@link ShareLink},里面就含对端要访问的地址)。不等对端真的来拿 —— 否则界面会一直转圈,
 * 而用户此时要做的是"把地址告诉另一台设备"。对端真的把导入包推上来时,走
 * {@link #listen(ShareImportListener)} 那条线。
 */
public final class LanShareTransport extends BaseTransport {

    private static final Set<ShareCapability> CAPABILITIES = Collections.unmodifiableSet(
            EnumSet.of(ShareCapability.EXPORT, ShareCapability.IMPORT_PUSH));

    private final LanShareHost host;

    /** 当前已挂出的导出会话(供撤销用);无会话为 null */
    private volatile LanShareSession session;
    /** 当前的导入监听(推送式);无监听为 null */
    private volatile ShareImportListener importListener;

    /** 上传接收口:由服务端在 HTTP 线程调进来,这里负责校验 + 切主线程 */
    private final LanImportReceiver receiver = new Receiver();

    public LanShareTransport(@NonNull LanShareHost host) {
        if (host == null) throw new IllegalArgumentException("host == null");
        this.host = host;
    }

    // ------------------------------------------------------------------
    // 契约
    // ------------------------------------------------------------------

    @NonNull
    @Override
    public SharePlatform platform() {
        return SharePlatform.LAN;
    }

    @NonNull
    @Override
    public Set<ShareCapability> capabilities() {
        return CAPABILITIES;
    }

    @NonNull
    @Override
    public ShareAvailability availability() {
        if (!host.isLanServiceEnabled()) {
            return ShareAvailability.unavailable("请先在「设置」里开启局域网服务");
        }
        if (!host.isLanBound()) {
            // 开关已开但服务实例还绑在 127.0.0.1 上:这是最容易误报的一档,必须说清"要重启"
            return ShareAvailability.unavailable("局域网服务已开启,重启应用后生效");
        }
        boolean hasAddress = false;
        try {
            List<String> urls = host.accessUrls();
            hasAddress = urls != null && !urls.isEmpty();
        } catch (Throwable ignored) {
        }
        if (!hasAddress) {
            return ShareAvailability.unavailable("未获取到局域网地址,请确认设备已连接 Wi-Fi 或网线");
        }
        return ShareAvailability.available();
    }

    @NonNull
    @Override
    public ShareLimits limits() {
        // 局域网不走平台限额,受本机资源限制;有效期是会话 TTL(小时级都没有,所以天数用 0 表示不适用)
        return new ShareLimits(
                LanShareOptions.DEFAULT_MAX_UPLOAD_BYTES,
                -1,
                0,
                0,
                0,
                "局域网直传,不计平台额度;会话默认 "
                        + (LanShareOptions.DEFAULT_TTL_MILLIS / 60000L) + " 分钟后自动失效");
    }

    @Override
    public void export(@NonNull ShareRequest request, @NonNull ShareCallback<ShareLink> callback) {
        final ShareCallbackHandle<ShareLink> handle = ShareCallbackHandle.of(callback);

        ShareAvailability availability = availability();
        if (!availability.isAvailable()) {
            handle.error(ShareException.unavailable(availability.reason()));
            return;
        }
        final SharePackage pkg = request.pkg();
        if (pkg == null || !pkg.isUsable()) {
            handle.error(ShareException.invalid("要导出的归档文件不可用"));
            return;
        }
        handle.progress(ShareProgress.of(ShareProgress.Phase.PREPARING, "正在挂出局域网地址"));

        final long op = beginOperation(handle);
        final LanShareOptions options = LanShareOptions.builder()
                .allowImport(request.allowImport())
                .maxDownloads(request.maxDownloads())
                .build();
        ShareExecutors.io().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isCurrent(op) || handle.isTerminal()) return;
                    LanShareSession published = host.publish(pkg, options);
                    if (!isCurrent(op) || handle.isTerminal()) {
                        // 已被新操作/取消取代:刚挂出的会话要立刻收回,别留一个没人管的可访问地址
                        safeRetract(published);
                        return;
                    }
                    session = published;
                    if (published.canImport()) {
                        // 同一会话也开放上传:两台设备互换配置时一次到位,不用"先导出再导入"两步
                        host.setImportReceiver(receiver);
                    }
                    ShareLink link = ShareLink.builder(SharePlatform.LAN)
                            .pageUrl(published.hint())
                            .directUrl(published.downloadUrl())
                            .fileId(published.sessionId())
                            .fileName(published.fileName())
                            .sizeBytes(published.sizeBytes())
                            .expiresAtMillis(published.expiresAtMillis())
                            .maxDownloads(published.maxDownloads())
                            .note(published.canDownload()
                                    ? "同一局域网内的设备打开该地址即可下载;地址过期自动失效"
                                    : "")
                            .build();
                    handle.success(link);
                } catch (ShareException e) {
                    handle.error(e);
                } catch (Throwable t) {
                    handle.error(new ShareException(ShareErrorCode.UNKNOWN,
                            "局域网分享失败:" + t.getMessage(), t));
                } finally {
                    finishOperation(op);
                }
            }
        });
    }

    /**
     * 局域网不支持"按链接拉取":它是服务端角色,没有可供主动访问的对端地址。
     * 门面已按能力拦截;这里再以 {@code UNSUPPORTED} 失败一次,避免直接调用时静默无响应。
     */
    @Override
    public void pull(@NonNull String shareRef, @NonNull ShareCallback<SharePackage> callback) {
        callback.onError(ShareException.unsupported(
                "局域网分享由对端来访问本机,不支持按链接拉取;请让对方上传,或改用在线平台"));
    }

    @Override
    public void listen(@Nullable final ShareImportListener listener) {
        if (listener == null) {
            // 注销:先摘掉旧监听(它不该再收到任何回调),再撤接收口
            ShareImportListener previous = importListener;
            importListener = null;
            try {
                host.setImportReceiver(null);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                final ShareImportListener p = previous;
                ShareMainThread.post(new Runnable() {
                    @Override
                    public void run() {
                        p.onWaiting(false, "");
                    }
                });
            }
            return;
        }

        ShareAvailability availability = availability();
        if (!availability.isAvailable()) {
            // 两条都发:onError 说明"为什么起不来",onWaiting(false) 让界面把"等待中"的状态收掉
            // (只发 onError 时,已经画出来的等待态没人负责撤销)。
            listener.onError(ShareException.unavailable(availability.reason()));
            listener.onWaiting(false, availability.reason());
            return;
        }
        importListener = listener;
        String url = "";
        try {
            host.setImportReceiver(receiver);
            url = host.importUrl();
        } catch (Throwable t) {
            importListener = null;
            listener.onError(new ShareException(ShareErrorCode.UNKNOWN,
                    "无法开启局域网接收口:" + t.getMessage(), t));
            listener.onWaiting(false, "");
            return;
        }
        final String hint = url;
        ShareMainThread.post(new Runnable() {
            @Override
            public void run() {
                if (importListener == listener) listener.onWaiting(true, hint);
            }
        });
    }

    /** 当前已挂出的会话(界面重绘时问"地址还在吗"),无会话返回 null */
    @Nullable
    public LanShareSession currentSession() {
        return session;
    }

    // ------------------------------------------------------------------
    // 取消/释放:会话与接收口都要收干净
    // ------------------------------------------------------------------

    @Override
    protected void onCancelRequested() {
        retractSession();
        // 接收口也要一并关掉:取消的语义是"别等了",留着接收口会让对端以为还能传
        try {
            host.setImportReceiver(null);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onRelease() {
        // 释放语义:接收口与会话都已在 onCancelRequested 里收回,这里只把监听引用摘掉。
        // 刻意不回调 onWaiting(false) —— release 的典型触发是"页面正在销毁",
        // 此时再往界面推一次回调没有意义,还可能碰到已经在拆的 View。
        importListener = null;
    }

    private void retractSession() {
        LanShareSession s = session;
        session = null;
        safeRetract(s);
    }

    private void safeRetract(@Nullable LanShareSession s) {
        if (s == null || s.sessionId().isEmpty()) return;
        try {
            host.retract(s.sessionId());
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 接收口实现(服务端 HTTP 线程 → 校验 → 主线程分发)
    // ------------------------------------------------------------------

    private final class Receiver implements LanImportReceiver {

        @Override
        public void onSessionClosed(@Nullable final String sessionId) {
            // 服务端侧会话被撤(过期/关门):界面回到"等待中=否",别让它一直显示地址
            ShareMainThread.post(new Runnable() {
                @Override
                public void run() {
                    ShareImportListener l = importListener;
                    if (l != null) l.onWaiting(false, "");
                }
            });
        }

        @NonNull
        @Override
        public String onUploadReceived(@Nullable String fileName, @NonNull File tempFile) {
            final ShareImportListener listener = importListener;
            if (listener == null) {
                deleteQuietly(tempFile);
                return "接收口已关闭";
            }
            if (tempFile == null || !tempFile.isFile() || tempFile.length() <= 0) {
                deleteQuietly(tempFile);
                postError(listener, new ShareException(ShareErrorCode.IO, "上传内容为空"));
                return "上传内容为空";
            }
            // 校验(在 HTTP 线程上做:读的只是一个 zip 头部 + 可能的 SHA-256,包体有上限)
            ShareManifest manifest = ShareArchive.readManifest(tempFile);
            try {
                ShareArchive.check(tempFile, manifest);
            } catch (ShareException e) {
                deleteQuietly(tempFile);
                postError(listener, e);
                return e.getMessage();
            }
            final SharePackage pkg = SharePackage.of(tempFile, fileName, null, manifest);
            ShareMainThread.post(new Runnable() {
                @Override
                public void run() {
                    ShareImportListener current = importListener;
                    if (current == null) {
                        // 界面上这一步已经撤了:包没人接,直接清掉,别留在临时目录里
                        deleteQuietly(pkg.file());
                        return;
                    }
                    current.onPackage(pkg);
                }
            });
            return "导入包已接收,请在电视上确认";
        }

        @Override
        public void onUploadRejected(@Nullable String reason) {
            ShareImportListener l = importListener;
            if (l == null) return;
            postError(l, new ShareException(ShareErrorCode.INVALID_INPUT,
                    reason == null || reason.trim().isEmpty() ? "对端上传被拒绝" : reason));
        }

        private void postError(@NonNull final ShareImportListener l, @NonNull final ShareException e) {
            ShareMainThread.post(new Runnable() {
                @Override
                public void run() {
                    if (importListener == l) l.onError(e);
                }
            });
        }

        private void deleteQuietly(@Nullable File f) {
            if (f == null) return;
            try {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 便于日志/调试:把实现类型与平台对上(避免注册错了平台) */
    @NonNull
    @Override
    public String toString() {
        return "LanShareTransport{" + platform().id() + "}";
    }

    /** 供 :app 侧日志用的一句状态描述(不含令牌) */
    @NonNull
    public String debugState() {
        LanShareSession s = session;
        return "lan: available=" + availability().isAvailable()
                + ", session=" + (s == null ? "-" : s.sessionId())
                + ", listening=" + (importListener != null);
    }
}

package com.github.tvbox.osc.share.online;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.net.NetworkProvider;
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

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * storage.to 在线传输(导出 + 从链接导入)。
 *
 * <p>导出流程(与官方 CLI 对齐,每一步都可在失败时干净回滚):
 * <pre>
 * 1. POST /api/collection      → 建集合(即使只传一个文件,理由见下)
 * 2. POST /api/upload/init     → type=single|multipart
 * 3. PUT  &lt;预签名地址&gt;         → 单文件一次 PUT;大文件按 part_size 切片依次 PUT 并收集 ETag
 *    (multipart 还需 POST /api/upload/complete-multipart)
 * 4. POST /api/upload/confirm  → 拿到文件页地址 + owner_token
 * 5. POST /api/file/{id}/max-downloads (可选,阅后即焚/限次)
 * 6. POST /api/collection/{id}/ready
 * </pre>
 *
 * <p><b>为什么单文件也建集合</b>:storage.to 把 {@code /r/} 直链下线后,单文件的分享页是 HTML,
 * 程序没有稳定的读取入口;而集合有官方 README 写明的机器可读清单
 * ({@code GET /c/<id>.json})。所以"导出时建集合"是为了让<b>自己导出的东西自己一定能导入</b>,
 * 这是在线导入导出闭环的前提,不是多余动作。
 *
 * <p>回滚策略(宁可失败，也不给一个"看起来成了"的链接):
 * <ul>
 *   <li>分片会话中断 → {@code /api/upload/abort} 释放(否则会占服务端并发分片额度到 24h 回收);</li>
 *   <li>已 confirm 但后续步骤失败/被取消 → {@code DELETE /api/file/{id}} 删掉,不留孤儿文件;</li>
 *   <li>下载次数上限设置失败 → 删除并整体失败(否则用户以为"阅后即焚",实际文件长期可下载)。</li>
 * </ul>
 *
 * <p>取消:OkHttp 的阻塞读打不断线程,只能取消 Call(见 {@link StorageToApi#cancelInFlight()});
 * 加上 epoch 自检避免"取消了又报成功"。{@link #onCancelRequested()} 里同时兜住
 * "正在上传的分片会话"与"在跑的 HTTP 请求"。
 *
 * <p><b>⚠ 联网实测提醒</b>:上传链路(init/PUT/confirm/集合)完全按官方端点实现,可信度高;
 * <b>导入</b>依赖的清单结构与分享页结构未经实测(开发环境被 Cloudflare 拦截),
 * 详见 {@link StorageToCollectionResolver} 的说明。
 */
public final class StorageToTransport extends BaseTransport {

    private static final Set<ShareCapability> CAPABILITIES = Collections.unmodifiableSet(
            EnumSet.of(ShareCapability.EXPORT, ShareCapability.IMPORT_PULL));

    /** 分片地址一批取多少个(官方 CLI 的 partURLBatchSize 同量级) */
    private static final int PART_URL_BATCH = 8;

    /** 导入临时目录保留的归档份数(缓存目录也做有界清理,避免越积越多) */
    private static final int KEEP_IMPORT_FILES = 4;

    private final Context appContext;
    private final NetworkProvider network;

    /** 在跑的请求所属的 api 实例(cancel 时要去取消它的 Call) */
    private volatile StorageToApi currentApi;
    /** 正在进行的分片会话 id(取消时要 abort 掉) */
    private volatile String pendingUploadId;
    /**
     * 当前分片会话的 owner_token(来自 {@code /upload/init})。
     * <p>分片相关的 parts/complete/abort 都是 "Owner only"(文档),取消路径上要能带着它;
     * 只靠访客令牌 + 同 IP 在移动网络换网时会 403,会话就释放不掉了。
     */
    private volatile String pendingUploadOwnerToken;

    public StorageToTransport(@NonNull Context context) {
        this(context, NetworkProvider.DEFAULT);
    }

    public StorageToTransport(@NonNull Context context, @NonNull NetworkProvider network) {
        if (context == null) throw new IllegalArgumentException("context == null");
        this.appContext = context.getApplicationContext();
        this.network = network == null ? NetworkProvider.DEFAULT : network;
    }

    // ------------------------------------------------------------------
    // 契约
    // ------------------------------------------------------------------

    @NonNull
    @Override
    public SharePlatform platform() {
        return SharePlatform.STORAGE_TO;
    }

    @NonNull
    @Override
    public Set<ShareCapability> capabilities() {
        return CAPABILITIES;
    }

    @NonNull
    @Override
    public ShareAvailability availability() {
        if (!StorageToConfig.isEnabled()) {
            return ShareAvailability.unavailable("已在设置里关闭在线分享");
        }
        if (appContext == null) {
            return ShareAvailability.unavailable("应用上下文未就绪");
        }
        // 不在这里探网络:可用性查询会被界面高频调用(每次渲染平台列表),
        // 真去发请求既慢又费流量。断网/被墙在真正导出时以 NETWORK 失败体现,文案足够清楚。
        return ShareAvailability.available();
    }

    @NonNull
    @Override
    public ShareLimits limits() {
        return new ShareLimits(
                StorageToConfig.MAX_FILE_BYTES,
                StorageToConfig.MAX_UPLOADS_PER_DAY,
                1,
                StorageToConfig.MAX_ANONYMOUS_EXPIRY_DAYS,
                StorageToConfig.DEFAULT_EXPIRY_DAYS,
                "匿名额度:每 24 小时 50 个文件、单文件 25 GB,另有上传带宽配额"
                        + "(每访客标识 100 GB / 每 IP 500 GB,滚动 24 小时);"
                        + "下载不计入配额。精确余量以服务端返回为准");
    }

    @Override
    public void export(@NonNull ShareRequest request, @NonNull ShareCallback<ShareLink> callback) {
        final ShareCallbackHandle<ShareLink> handle = ShareCallbackHandle.of(callback);

        ShareAvailability av = availability();
        if (!av.isAvailable()) {
            handle.error(ShareException.unavailable(av.reason()));
            return;
        }
        final SharePackage pkg = request.pkg();
        if (pkg == null || !pkg.isUsable()) {
            handle.error(ShareException.invalid("要导出的归档文件不可用"));
            return;
        }
        final ShareLimits limits = limits();
        long size = pkg.sizeBytes();
        if (!limits.accepts(size)) {
            handle.error(new ShareException(ShareErrorCode.TOO_LARGE,
                    "归档 " + humanSize(size) + " 超过平台单文件上限 " + humanSize(limits.maxFileBytes())));
            return;
        }
        handle.progress(ShareProgress.of(ShareProgress.Phase.PREPARING, "正在准备上传"));

        final long op = beginOperation(handle);
        // 有效期:请求给了就用请求的,否则用设置里的,再按平台区间钳制
        int wanted = request.expiryDays() > 0 ? request.expiryDays() : StorageToConfig.expiryDays();
        final int expiryDays = limits.clampExpiryDays(wanted);
        final int maxDownloads = request.maxDownloads();

        final StorageToApi api = newApi();
        currentApi = api;
        ShareExecutors.io().execute(new Runnable() {
            @Override
            public void run() {
                uploadFlow(op, handle, api, pkg, expiryDays, maxDownloads);
            }
        });
    }

    @Override
    public void pull(@NonNull String shareRef, @NonNull ShareCallback<SharePackage> callback) {
        final ShareCallbackHandle<SharePackage> handle = ShareCallbackHandle.of(callback);

        ShareAvailability av = availability();
        if (!av.isAvailable()) {
            handle.error(ShareException.unavailable(av.reason()));
            return;
        }
        handle.progress(ShareProgress.of(ShareProgress.Phase.DOWNLOADING, "正在解析分享链接"));

        final long op = beginOperation(handle);
        final StorageToApi api = newApi();
        currentApi = api;
        ShareExecutors.io().execute(new Runnable() {
            @Override
            public void run() {
                pullFlow(op, handle, api, shareRef);
            }
        });
    }

    /**
     * 在线平台没有"等对端推送"这条链路(对端不会主动连到本机),所以不支持。
     * 门面已按能力拦截;这里再以 {@code UNSUPPORTED} 失败一次,避免直接调用时静默无响应。
     */
    @Override
    public void listen(@Nullable ShareImportListener listener) {
        if (listener != null) {
            listener.onError(ShareException.unsupported(
                    "在线平台不支持接收推送;请把分享链接填进导入,或改用局域网互传"));
        }
    }

    @Override
    protected void onCancelRequested() {
        StorageToApi api = currentApi;
        if (api != null) api.cancelInFlight();
        final String uploadId = pendingUploadId;
        final String ownerToken = pendingUploadOwnerToken;
        pendingUploadId = null;
        pendingUploadOwnerToken = null;
        if (uploadId != null && !uploadId.isEmpty()) {
            // 释放分片会话:不释放会一直占着服务端的并发分片额度
            ShareExecutors.io().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        newApi().abortUpload(uploadId, ownerToken);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // 导出流程
    // ------------------------------------------------------------------

    private void uploadFlow(long op, @NonNull ShareCallbackHandle<ShareLink> handle,
                            @NonNull StorageToApi api, @NonNull SharePackage pkg,
                            int expiryDays, int maxDownloads) {
        final long size = pkg.sizeBytes();
        final String name = pkg.fileName();
        final String mime = pkg.mimeType();

        String collectionId = "";
        String ownerToken = "";
        String initOwnerToken = "";
        String fileId = "";
        String uploadId = null;
        boolean confirmed = false;
        try {
            // 1) 集合:让导入有机器可读入口(见类注释)
            checkActive(op, handle);
            CollectionResponse collection = api.createCollection(1);
            collectionId = collection.collection.id;
            if (collection.ownerToken != null && !collection.ownerToken.isEmpty()) {
                ownerToken = collection.ownerToken;
            }

            // 2) init
            checkActive(op, handle);
            InitUploadResponse init = api.initUpload(name, mime, size);
            // 分片分支会返回 owner_token:分片相关的 parts/complete/abort 都是 "Owner only",
            // 一路带着它才能在换网/换 IP 后仍然完成或释放会话
            if (init.ownerToken != null && !init.ownerToken.isEmpty()) {
                initOwnerToken = init.ownerToken;
            }

            // 3) 上传字节
            if (init.isMultipart()) {
                uploadId = init.uploadId;
                pendingUploadId = uploadId;
                pendingUploadOwnerToken = initOwnerToken;
                uploadMultipart(op, handle, api, pkg, init, initOwnerToken);
                pendingUploadId = null;
                pendingUploadOwnerToken = null;
                uploadId = null;
            } else {
                if (init.uploadUrl == null || init.uploadUrl.isEmpty()) {
                    throw new ShareException(ShareErrorCode.PARSE, "服务端未返回上传地址");
                }
                handle.progress(new ShareProgress(ShareProgress.Phase.UPLOADING, 0L, size, "正在上传"));
                // 照抄服务端给的签名头(见 StorageToApi.applySignedHeaders):少带就是 403
                api.putFileSection(init.uploadUrl, pkg.file(), 0L, size, mime, init.headers,
                        new StorageToApi.ProgressSink() {
                            @Override
                            public void onBytes(long done, long total) {
                                if (!isCurrent(op)) return;
                                handle.progress(new ShareProgress(ShareProgress.Phase.UPLOADING,
                                        done, size, "正在上传"));
                            }
                        });
            }

            // 4) confirm:到这一步才会有文件记录与分享地址
            checkActive(op, handle);
            handle.progress(ShareProgress.of(ShareProgress.Phase.FINALIZING, "正在确认上传"));
            ConfirmUploadResponse confirm =
                    api.confirmUpload(name, size, mime, init.r2Key, collectionId, expiryDays);
            confirmed = true;
            if (confirm.file != null && confirm.file.id != null) fileId = confirm.file.id;
            if (confirm.ownerToken != null && !confirm.ownerToken.isEmpty()) {
                ownerToken = confirm.ownerToken;
            }

            // 5) 下载次数上限:设置失败就整体回滚,不给"以为阅后即焚其实没有"的链接
            if (maxDownloads > 0 && !fileId.isEmpty()) {
                try {
                    api.setFileMaxDownloads(fileId, ownerToken, maxDownloads);
                } catch (ShareException e) {
                    api.deleteFileQuietly(fileId, ownerToken);
                    throw new ShareException(e.code(),
                            "无法设置下载次数上限,已取消本次分享:" + e.getMessage(), e);
                }
            }

            // 6) 标记集合就绪(清单才可用)。失败不致命:分享页链接仍可用,
            //    只是"对方用链接导入"这条路会走不通,所以在链接备注里如实说明。
            boolean collectionReady = true;
            if (!collectionId.isEmpty()) {
                try {
                    api.markCollectionReady(collectionId);
                } catch (ShareException e) {
                    collectionReady = false;
                    LogStore.log(Category.SYSTEM, "分享: 集合标记就绪失败(页面分享仍可用) " + e.getMessage());
                }
            }

            checkActive(op, handle);

            StorageFileInfo file = confirm.file;
            String pageUrl = file != null && file.url != null && !file.url.isEmpty()
                    ? file.url
                    : (collection.collection.url == null ? "" : collection.collection.url);

            StringBuilder note = new StringBuilder();
            note.append("storage.to 已下线直链:请让对方打开链接下载,或让对方用集合链接导入");
            if (!collectionReady) note.append("(本次集合未就绪,链接导入可能不可用)");
            if (init.isMultipart()) note.append(";大文件已按分片上传");

            ShareLink link = ShareLink.builder(SharePlatform.STORAGE_TO)
                    .pageUrl(pageUrl)
                    // 直链留空:官方已下线 /r/,不要伪造一个自己都不确定能用的地址
                    .directUrl("")
                    .fileId(fileId)
                    .collectionId(collectionId)
                    .ownerToken(ownerToken)
                    .expiresAtMillis(parseIsoMillis(file == null ? null : file.expiresAt))
                    .maxDownloads(maxDownloads)
                    .sizeBytes(size)
                    .fileName(name)
                    .note(note.toString())
                    .build();

            LogStore.success(Category.SYSTEM, "分享: storage.to 上传成功 " + name + " (" + humanSize(size)
                    + ", 有效期 " + expiryDays + "天" + (maxDownloads > 0 ? ", 限 " + maxDownloads + " 次" : "") + ")");
            handle.success(link);
        } catch (Throwable t) {
            // 回滚:分片会话 + 已建的文件记录
            if (uploadId != null) api.abortUpload(uploadId, initOwnerToken);
            pendingUploadId = null;
            pendingUploadOwnerToken = null;
            if (confirmed && !fileId.isEmpty()) {
                api.deleteFileQuietly(fileId, ownerToken);
            }
            ShareException err = t instanceof ShareException
                    ? (ShareException) t
                    : new ShareException(ShareErrorCode.UNKNOWN, "上传失败:" + t.getMessage(), t);
            if (!isCurrent(op)) {
                // 已被新操作取代/已取消:不覆盖"取消"这个更准确的结论(handle 通常也已终止)
                err = new ShareException(ShareErrorCode.CANCELLED);
            } else {
                LogStore.fail(Category.SYSTEM, "分享: storage.to 上传失败 " + err.code() + " " + err.getMessage());
            }
            handle.error(err);
        } finally {
            finishOperation(op);
        }
    }

    /**
     * 分片上传:{@code partSize} 切片依次 PUT,收 ETag,最后 complete-multipart。
     *
     * <p>与官方 CLI 的差别:这里是<b>顺序</b>上传而不是并发。原因很直接 ——
     * 导出的归档是"设置 + Room + 主题"的量级(几 MB),正常走不到分片路径;
     * 真走到了(超大库),顺序上传更省内存、失败点更清楚,而并发带来的复杂度
     * (共享计数、部分失败)换不来用户可感知的收益。
     */
    private void uploadMultipart(long op, @NonNull ShareCallbackHandle<ShareLink> handle,
                                 @NonNull StorageToApi api, @NonNull SharePackage pkg,
                                 @NonNull InitUploadResponse init, @NonNull String ownerToken)
            throws ShareException {
        final long size = pkg.sizeBytes();
        final int totalParts = init.totalParts;
        final long partSize = init.partSize;
        if (totalParts <= 0 || partSize <= 0) {
            throw new ShareException(ShareErrorCode.PARSE, "服务端未返回分片参数");
        }
        Map<String, String> urls = init.initialUrls == null
                ? new HashMap<String, String>()
                : new HashMap<>(init.initialUrls);
        List<UploadPart> parts = new ArrayList<>(totalParts);
        long uploaded = 0L;

        for (int num = 1; num <= totalParts; num++) {
            checkActive(op, handle);
            final String numStr = String.valueOf(num);
            String url = urls.get(numStr);
            if (url == null || url.isEmpty()) {
                List<Integer> batch = new ArrayList<>(PART_URL_BATCH);
                for (int k = num; k <= Math.min(num + PART_URL_BATCH - 1, totalParts); k++) {
                    batch.add(k);
                }
                // normalizedUrls():服务端实际返回 {urls:{partNum:url}},文档写的是 part_urls 数组,
                // 两种形状都收(见 StorageToDto 里 GetPartUrlsResponse 的说明)
                GetPartUrlsResponse more = api.getPartUrls(init.uploadId, batch, ownerToken);
                urls.putAll(more.normalizedUrls());
                url = urls.get(numStr);
            }
            if (url == null || url.isEmpty()) {
                throw new ShareException(ShareErrorCode.HTTP, "服务端未返回第 " + num + " 个分片地址");
            }
            long offset = (long) (num - 1) * partSize;
            long len = (num == totalParts) ? (size - offset) : partSize;
            final long base = uploaded;
            // 分片 PUT 不能带 Content-Type(签名只覆盖服务端列出的头,见 StorageToApi);
            // 分片的 init 响应也没有 headers 字段,所以这里不传签名头
            String etag = api.putFileSection(url, pkg.file(), offset, len, null, null,
                    new StorageToApi.ProgressSink() {
                        @Override
                        public void onBytes(long done, long total) {
                            if (!isCurrent(op)) return;
                            handle.progress(new ShareProgress(ShareProgress.Phase.UPLOADING,
                                    base + done, size, "正在上传(分片)"));
                        }
                    });
            if (etag.isEmpty()) {
                throw new ShareException(ShareErrorCode.HTTP, "分片上传未返回 ETag");
            }
            parts.add(new UploadPart(num, etag));
            uploaded += len;
        }
        api.completeMultipart(init.uploadId, parts, ownerToken);
    }

    // ------------------------------------------------------------------
    // 导入流程
    // ------------------------------------------------------------------

    private void pullFlow(long op, @NonNull ShareCallbackHandle<SharePackage> handle,
                          @NonNull StorageToApi api, @NonNull String shareRef) {
        File partial = null;
        try {
            StorageToLinkResolver resolver = new StorageToCollectionResolver(api);
            ResolvedDownload resolved = resolver.resolve(shareRef);
            checkActive(op, handle);

            File dir = importTempDir();
            if (dir == null) {
                throw new ShareException(ShareErrorCode.IO, "本地缓存目录不可用,无法保存导入文件");
            }
            pruneImportDir(dir);
            final File dest = resolved.targetFileIn(dir);
            partial = dest;

            handle.progress(new ShareProgress(ShareProgress.Phase.DOWNLOADING, 0L,
                    resolved.sizeBytes(), "正在下载"));
            final long declared = resolved.sizeBytes();
            api.downloadToFile(resolved.directUrl(), dest, new StorageToApi.ProgressSink() {
                @Override
                public void onBytes(long done, long total) {
                    if (!isCurrent(op)) return;
                    handle.progress(new ShareProgress(ShareProgress.Phase.DOWNLOADING,
                            done, total > 0 ? total : declared, "正在下载"));
                }
            });
            checkActive(op, handle);

            handle.progress(ShareProgress.of(ShareProgress.Phase.VERIFYING, "正在校验归档"));
            ShareManifest manifest = ShareArchive.readManifest(dest);
            ShareArchive.check(dest, manifest);
            if (manifest == null) {
                // 清单缺失不是致命(部分旧包/对端可能没带),但要在日志里留痕便于排查
                LogStore.log(Category.SYSTEM, "分享: 导入包无清单(跳过 schema/校验和检查) "
                        + resolved.suggestedLocalName());
            }

            SharePackage pkg = SharePackage.of(dest, resolved.suggestedLocalName(), null, manifest);
            LogStore.success(Category.SYSTEM, "分享: 在线导入下载完成 " + pkg.fileName()
                    + " (" + humanSize(pkg.sizeBytes()) + ")");
            handle.success(pkg);
        } catch (Throwable t) {
            // 失败/取消都不留半截文件:下次导入同名会覆盖,但残留会占空间且让人困惑
            if (partial != null) {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    partial.delete();
                } catch (Throwable ignored) {
                }
            }
            ShareException err = t instanceof ShareException
                    ? (ShareException) t
                    : new ShareException(ShareErrorCode.UNKNOWN, "导入失败:" + t.getMessage(), t);
            if (!isCurrent(op)) err = new ShareException(ShareErrorCode.CANCELLED);
            else LogStore.fail(Category.SYSTEM, "分享: 在线导入失败 " + err.code() + " " + err.getMessage());
            handle.error(err);
        } finally {
            finishOperation(op);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 本次操作是否仍然有效;无效即抛 CANCELLED 让流程立刻退出(并触发回滚) */
    private void checkActive(long op, @NonNull ShareCallbackHandle<?> handle) throws ShareException {
        if (!isCurrent(op) || handle.isTerminal()) {
            throw new ShareException(ShareErrorCode.CANCELLED);
        }
    }

    @NonNull
    private StorageToApi newApi() {
        return new StorageToApi(network, StorageToConfig.baseUrl());
    }

    /** 导入临时目录(应用缓存;外部缓存优先,便于用户手动取走) */
    @Nullable
    private File importTempDir() {
        try {
            File base = appContext.getExternalCacheDir();
            if (base == null) base = appContext.getCacheDir();
            if (base == null) return null;
            File dir = new File(base, "share_import");
            if (!dir.exists() && !dir.mkdirs()) return null;
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只留最近 {@link #KEEP_IMPORT_FILES} 份导入文件 */
    private static void pruneImportDir(@NonNull File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length <= KEEP_IMPORT_FILES) return;
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            for (int i = KEEP_IMPORT_FILES; i < files.length; i++) {
                //noinspection ResultOfMethodCallIgnored
                files[i].delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 解析 ISO-8601 到期时间。
     * 用 {@link SimpleDateFormat} 而不是 {@code java.time}:本模块 minSdk 24,
     * 而 {@code java.time} 要到 API 26(不想为此开 desugaring)。
     */
    static long parseIsoMillis(@Nullable String iso) {
        if (iso == null) return 0L;
        String s = iso.trim();
        if (s.isEmpty()) return 0L;
        // 带 Z / 带偏移 / 不带时区各试一遍;时区统一按 UTC(服务端返回的是 UTC 时刻)
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
        };
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                java.util.Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    @NonNull
    static String humanSize(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    @NonNull
    @Override
    public String toString() {
        return "StorageToTransport{" + platform().id() + ", base=" + StorageToConfig.baseUrl() + "}";
    }
}

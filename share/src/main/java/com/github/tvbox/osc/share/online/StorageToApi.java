package com.github.tvbox.osc.share.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.net.NetworkProvider;
import com.github.tvbox.osc.share.ShareErrorCode;
import com.github.tvbox.osc.share.ShareException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

/**
 * storage.to 接口客户端(HTTP 细节全部关在这里,{@link StorageToTransport} 只表达流程)。
 *
 * <p>客户端的来源:{@link NetworkProvider}(:core-network 的契约)。
 * <b>不自行 new OkHttpClient.Builder</b> —— 业务模块自建客户端会绕过 OkGoHelper 统一装配的
 * DNS/DoH/超时/日志策略(AGENTS §二/:core-network 行),而且重复建连接池。
 *
 * <p>预签名 PUT 的两个硬约束(照官方 CLI 实现,写错就是 403):
 * <ol>
 *   <li><b>方法必须是 PUT</b>(不是 POST),{@code Content-Length} 必须等于本次要传的字节数;</li>
 *   <li><b>单文件上传要带 {@code Content-Type}</b>(init 时申报的那个),
 *       而<b>分片上传不能带</b> {@code Content-Type} —— 签名只覆盖服务端当时列出的头
 *       ({@link InitUploadResponse#headers}),多带一个头就会签名不匹配。</li>
 * </ol>
 *
 * <p>取消:OkHttp 的阻塞读无法靠线程中断打断,只能 {@link Call#cancel()}
 * ({@link #cancelInFlight()})。在跑的 call 记在集合里,取消时逐个 cancel。
 */
final class StorageToApi {

    /** 普通接口的 JSON 媒体类型 */
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final Gson GSON = new Gson();

    /** 清单/JSON 读取上限:集合清单是元数据,给 1 MB 足够,防被超大响应拖爆内存 */
    private static final long MAX_TEXT_BYTES = 1024L * 1024L;

    private final NetworkProvider network;
    private final String baseUrl;

    /** 在跑的 call(用于取消);cancel 后从集合移除 */
    private final Set<Call> inFlight = Collections.newSetFromMap(new ConcurrentHashMap<Call, Boolean>());

    StorageToApi(@NonNull NetworkProvider network, @NonNull String baseUrl) {
        this.network = network;
        this.baseUrl = normalizeBase(baseUrl);
    }

    /**
     * 归一化基址:去掉结尾斜杠,并去掉用户可能多写的 {@code /api} 后缀。
     *
     * <p>为什么必须去掉:文档把基础 URL 定义为 {@code https://storage.to/api},
     * 所有端点都相对它书写;而本类内部统一按 {@code 基址 + "/api/xxx"} 拼。
     * 用户若照文档把设置项填成 {@code https://storage.to/api},不归一化就会拼出
     * {@code .../api/api/upload/init} —— 而这种错法很难从 404 里看出来。
     */
    @NonNull
    private static String normalizeBase(@NonNull String raw) {
        String b = raw.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/api")) b = b.substring(0, b.length() - 4);
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    @NonNull
    String baseUrl() {
        return baseUrl;
    }

    /** 传输进度观察者(字节级);实现方自行做节流,别每个读循环都回调 */
    interface ProgressSink {
        void onBytes(long done, long total);
    }

    /** 取消所有在跑的请求 */
    void cancelInFlight() {
        for (Call c : new ArrayList<>(inFlight)) {
            try {
                c.cancel();
            } catch (Throwable ignored) {
            }
        }
        inFlight.clear();
    }

    // ------------------------------------------------------------------
    // 上传协商
    // ------------------------------------------------------------------

    @NonNull
    InitUploadResponse initUpload(@NonNull String filename, @NonNull String contentType, long size)
            throws ShareException {
        JsonObject body = new JsonObject();
        body.addProperty("filename", filename);
        body.addProperty("content_type", contentType);
        body.addProperty("size", size);
        InitUploadResponse resp = postJson("/api/upload/init", body, null, InitUploadResponse.class);
        requireSuccess(resp.success, resp.error);
        return resp;
    }

    @NonNull
    GetPartUrlsResponse getPartUrls(@NonNull String uploadId, @NonNull List<Integer> partNumbers,
                                    @Nullable String ownerToken) throws ShareException {
        JsonObject body = new JsonObject();
        body.addProperty("upload_id", uploadId);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (Integer n : partNumbers) arr.add(n);
        body.add("part_numbers", arr);
        // 文档把 /upload/parts 标为 "Owner only":带上 init 给的 owner_token,
        // 免得只靠"访客令牌 + 同 IP"兜底(移动网络换网就换 IP,会中途 403)
        GetPartUrlsResponse resp =
                postJson("/api/upload/parts", body, ownerToken, GetPartUrlsResponse.class);
        requireSuccess(resp.success, resp.error);
        return resp;
    }

    void completeMultipart(@NonNull String uploadId, @NonNull List<UploadPart> parts,
                           @Nullable String ownerToken) throws ShareException {
        JsonObject body = new JsonObject();
        body.addProperty("upload_id", uploadId);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (UploadPart p : parts) {
            JsonObject o = new JsonObject();
            o.addProperty("partNumber", p.partNumber);
            o.addProperty("etag", p.etag);
            arr.add(o);
        }
        body.add("parts", arr);
        SimpleResponse resp = postJson("/api/upload/complete-multipart", body, ownerToken,
                SimpleResponse.class);
        requireSuccess(resp.success, resp.error);
    }

    /** 释放分片会话(尽力而为:失败留给服务端 24h 回收,不再向用户报错) */
    void abortUpload(@Nullable String uploadId, @Nullable String ownerToken) {
        if (uploadId == null || uploadId.isEmpty()) return;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("upload_id", uploadId);
            SimpleResponse resp = postJson("/api/upload/abort", body, ownerToken, SimpleResponse.class);
            if (!resp.success) {
                LogStore.log(Category.SYSTEM, "分享: 分片会话释放未确认 " + resp.error);
            }
        } catch (Throwable t) {
            LogStore.log(Category.SYSTEM, "分享: 分片会话释放失败(留给服务端回收) " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 只读查询(导入侧与配额展示)
    // ------------------------------------------------------------------

    /**
     * {@code GET /collection/{id}/status} —— 文档化的集合成员读取入口,<b>不需要所有权</b>
     * (文档只给 {@code /ready} 与 {@code DELETE} 标了 "Owner only")。
     * <p>导入侧用它列出集合里的文件,比去猜 {@code /c/{id}.json} 清单的结构可靠得多。
     *
     * @return 集合状态;集合不存在/已过期返回 {@code null}
     */
    @Nullable
    CollectionStatusResponse collectionStatus(@NonNull String collectionId) throws ShareException {
        return requestJson("GET", "/api/collection/" + collectionId + "/status", null, null,
                CollectionStatusResponse.class, true);
    }

    /**
     * {@code GET /bandwidth/status} —— 滚动 24 小时<b>上传</b>配额余量(下载不计入)。
     * 用于导出前提示"今天还能传多少"。取不到返回 {@code null}(不影响主流程)。
     */
    @Nullable
    BandwidthStatusResponse bandwidthStatus() {
        try {
            return requestJson("GET", "/api/bandwidth/status", null, null,
                    BandwidthStatusResponse.class, true);
        } catch (Throwable t) {
            return null;
        }
    }

    @NonNull
    ConfirmUploadResponse confirmUpload(@NonNull String filename, long size,
                                        @NonNull String contentType, @NonNull String r2Key,
                                        @Nullable String collectionId, int expiryDays)
            throws ShareException {
        JsonObject body = new JsonObject();
        body.addProperty("filename", filename);
        body.addProperty("size", size);
        body.addProperty("content_type", contentType);
        body.addProperty("r2_key", r2Key);
        if (collectionId != null && !collectionId.isEmpty()) {
            body.addProperty("collection_id", collectionId);
        }
        // 0 表示"不指定",此时省略字段让服务端用它自己的默认值(官方 CLI 同样做法)
        if (expiryDays > 0) {
            body.addProperty("expiry_days", expiryDays);
        }
        ConfirmUploadResponse resp =
                postJson("/api/upload/confirm", body, null, ConfirmUploadResponse.class);
        requireSuccess(resp.success, resp.error);
        return resp;
    }

    // ------------------------------------------------------------------
    // 集合(导出恒建集合,导入才有稳定的机器可读入口,见 StorageCollectionManifest)
    // ------------------------------------------------------------------

    @NonNull
    CollectionResponse createCollection(int expectedFileCount) throws ShareException {
        JsonObject body = new JsonObject();
        if (expectedFileCount > 0) {
            body.addProperty("expected_file_count", expectedFileCount);
        }
        CollectionResponse resp = postJson("/api/collection", body, null, CollectionResponse.class);
        requireSuccess(resp.success, resp.error);
        if (resp.collection == null || resp.collection.id == null || resp.collection.id.isEmpty()) {
            throw new ShareException(ShareErrorCode.PARSE, "服务端未返回集合信息");
        }
        return resp;
    }

    void markCollectionReady(@NonNull String collectionId) throws ShareException {
        CollectionResponse resp = postJson("/api/collection/" + collectionId + "/ready",
                new JsonObject(), null, CollectionResponse.class);
        requireSuccess(resp.success, resp.error);
    }

    void setCollectionMaxDownloads(@NonNull String collectionId, @Nullable String ownerToken, int max)
            throws ShareException {
        JsonObject body = new JsonObject();
        body.addProperty("max_downloads", max);
        SimpleResponse resp = postJson("/api/collection/" + collectionId + "/max-downloads",
                body, ownerToken, SimpleResponse.class);
        requireSuccess(resp.success, resp.error);
    }

    // ------------------------------------------------------------------
    // 文件管理(需要 owner token)
    // ------------------------------------------------------------------

    void setFileMaxDownloads(@NonNull String fileId, @Nullable String ownerToken, int max)
            throws ShareException {
        JsonObject body = new JsonObject();
        body.addProperty("max_downloads", max);
        SimpleResponse resp = postJson("/api/file/" + fileId + "/max-downloads",
                body, ownerToken, SimpleResponse.class);
        requireSuccess(resp.success, resp.error);
    }

    /**
     * 删除已上传文件(用于"设置没应用成功就整体回滚")。
     * 尽力而为:失败只记日志 —— 此时用户已经拿到失败提示,再抛一个删除失败只会更混乱,
     * 文件本身也会按有效期自然过期。
     */
    void deleteFileQuietly(@Nullable String fileId, @Nullable String ownerToken) {
        if (fileId == null || fileId.isEmpty()) return;
        try {
            SimpleResponse resp = requestJson("DELETE", "/api/file/" + fileId, null,
                    ownerToken, SimpleResponse.class, false);
            if (!resp.success) {
                LogStore.log(Category.SYSTEM, "分享: 回滚删除未确认 " + resp.error);
            }
        } catch (Throwable t) {
            LogStore.log(Category.SYSTEM, "分享: 回滚删除失败(文件将按有效期过期) " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 预签名 PUT
    // ------------------------------------------------------------------

    /**
     * 上传一段文件到预签名地址。
     *
     * @param contentType   单文件上传传申报的类型;分片上传传 {@code null}
     *                      (分片的签名不含 Content-Type,带了就是签名不匹配)
     * @param signedHeaders {@code /upload/init} 返回的 {@code headers}(签名覆盖的头),
     *                      由 {@link #applySignedHeaders} 过滤后照抄
     * @param offset        起始偏移
     * @param length        本次字节数
     * @return 响应 ETag(去引号);无 ETag 返回空串(单文件上传不需要它)
     */
    @NonNull
    String putFileSection(@NonNull String uploadUrl, @NonNull File file,
                          long offset, long length, @Nullable String contentType,
                          @Nullable Map<String, List<String>> signedHeaders,
                          @Nullable ProgressSink sink) throws ShareException {
        RequestBody body = new FileSectionBody(file, offset, length,
                contentType == null ? null : MediaType.parse(contentType), sink);
        Request.Builder rb = new Request.Builder()
                .url(uploadUrl)
                .put(body)
                .header("User-Agent", StorageToConfig.userAgent());
        applySignedHeaders(rb, signedHeaders);
        Request request = rb.build();
        OkHttpClient client = network.general();
        Call call = client.newCall(request);
        inFlight.add(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String detail = safeBody(response);
                throw new ShareException(ShareErrorCode.HTTP,
                        "上传失败(HTTP " + response.code() + ")" + (detail.isEmpty() ? "" : ":" + detail));
            }
            String etag = response.header("ETag");
            if (etag == null) return "";
            return etag.replace("\"", "").trim();
        } catch (ShareException e) {
            throw e;
        } catch (IOException e) {
            throw cancelledOrNetwork(e);
        } finally {
            inFlight.remove(call);
        }
    }

    /**
     * 把 {@code /upload/init} 返回的签名头加到预签名 PUT 上。
     *
     * <p>为什么必须照抄:预签名 URL 的签名覆盖了服务端列出的这些头,
     * <b>少带一个就 403</b>(官方文档的单文件响应里就给了 {@code "headers": {"Host": ["..."]}})。
     * 官方 CLI 其实忽略了这个字段 —— 它能跑通只是因为签名里通常只有 Host,
     * 而 Host 由 HTTP 客户端按 URL 自动填。这里按文档补上,顺带把"只有 Host 被签名"这个
     * 隐含前提去掉。
     *
     * <p>跳过由客户端自管的头:Host / Content-Length / Content-Type / User-Agent。
     * 它们由 OkHttp 依据连接与请求体自行决定,手工覆盖轻则无效重则把请求写坏
     * (Content-Length 写错会被 R2 判成签名或长度不匹配)。
     */
    private static void applySignedHeaders(@NonNull Request.Builder rb,
                                          @Nullable Map<String, List<String>> signedHeaders) {
        if (signedHeaders == null || signedHeaders.isEmpty()) return;
        for (Map.Entry<String, List<String>> e : signedHeaders.entrySet()) {
            String name = e.getKey();
            if (name == null) continue;
            String lower = name.toLowerCase(java.util.Locale.US);
            if (lower.equals("host") || lower.equals("content-length")
                    || lower.equals("content-type") || lower.equals("user-agent")) {
                continue;
            }
            List<String> values = e.getValue();
            if (values == null || values.isEmpty()) continue;
            for (String v : values) {
                if (v != null) rb.addHeader(name, v);
            }
        }
    }

    // ------------------------------------------------------------------
    // 下载(导入)
    // ------------------------------------------------------------------

    /** 取文本(集合清单等)。返回 null 表示 404(调用方按"清单不存在"处理) */
    @Nullable
    String getText(@Nullable String url) throws ShareException {
        if (url == null || url.trim().isEmpty()) {
            throw ShareException.invalid("下载地址为空");
        }
        Request request = new Request.Builder()
                .url(url.trim())
                .get()
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", StorageToConfig.userAgent())
                .build();
        OkHttpClient client = network.general();
        Call call = client.newCall(request);
        inFlight.add(call);
        try (Response response = call.execute()) {
            if (response.code() == 404) return null;
            if (!response.isSuccessful()) {
                throw new ShareException(ShareErrorCode.HTTP,
                        "读取失败(HTTP " + response.code() + ")");
            }
            ResponseBody rb = response.body();
            if (rb == null) return "";
            return readCappedText(rb);
        } catch (ShareException e) {
            throw e;
        } catch (IOException e) {
            throw cancelledOrNetwork(e);
        } finally {
            inFlight.remove(call);
        }
    }

    /**
     * 流式下载到本地文件(不整包读进内存)。
     *
     * @return 实际写入字节数
     */
    long downloadToFile(@NonNull String url, @NonNull File dest, @Nullable ProgressSink sink)
            throws ShareException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", StorageToConfig.userAgent())
                .build();
        OkHttpClient client = network.general();
        Call call = client.newCall(request);
        inFlight.add(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new ShareException(ShareErrorCode.HTTP,
                        "下载失败(HTTP " + response.code() + ")");
            }
            ResponseBody rb = response.body();
            if (rb == null) {
                throw new ShareException(ShareErrorCode.PARSE, "响应体为空");
            }
            long total = rb.contentLength();
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new ShareException(ShareErrorCode.IO, "无法创建下载目录");
            }
            long written = 0L;
            byte[] buf = new byte[16384];
            try (InputStream in = rb.byteStream();
                 OutputStream os = new FileOutputStream(dest)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    written += n;
                    if (sink != null && (written % (256L * 1024L) < n)) {
                        sink.onBytes(written, total);
                    }
                }
                os.flush();
            }
            if (sink != null) sink.onBytes(written, total);
            return written;
        } catch (ShareException e) {
            throw e;
        } catch (IOException e) {
            throw cancelledOrNetwork(e);
        } finally {
            inFlight.remove(call);
        }
    }

    // ------------------------------------------------------------------
    // HTTP 基础设施
    // ------------------------------------------------------------------

    @NonNull
    private <T> T postJson(@NonNull String path, @NonNull Object body, @Nullable String ownerToken,
                           @NonNull Class<T> type) throws ShareException {
        return requestJson("POST", path, body, ownerToken, type, false);
    }

    /**
     * 统一 JSON 请求。
     *
     * @param nullOn404 true 时 404 返回 {@code null} 而不是抛异常(只读查询用:
     *                  "集合不存在/已过期"是正常结果,不是错误)
     */
    @Nullable
    private <T> T requestJson(@NonNull String method, @NonNull String path,
                              @Nullable Object body, @Nullable String ownerToken,
                              @NonNull Class<T> type, boolean nullOn404) throws ShareException {
        Request.Builder rb = new Request.Builder()
                .url(baseUrl + path)
                .header("Accept", "application/json")
                .header("User-Agent", StorageToConfig.userAgent());
        if ("GET".equals(method)) {
            // OkHttp 不允许 GET 带 body:必须用 .get(),不能走 .method("GET", body)
            rb.get();
        } else {
            // 参数序用 (String, MediaType):okhttp4 里带 (MediaType, String) 的那一版是
            // 为了兼容 Java 保留的过时重载,新代码用 (String, MediaType) 才不会吃 deprecation 警告。
            // 无 body 时发 "{}" 而不是空串:官方 CLI 对无参接口(如 abort/ready/DELETE)也是
            // json.Marshal(struct{}{}) → "{}",空串配 application/json 可能被服务端判成非法 JSON。
            RequestBody reqBody = body == null
                    ? RequestBody.create("{}", JSON)
                    : RequestBody.create(GSON.toJson(body), JSON);
            rb.method(method, reqBody).header("Content-Type", "application/json");
        }
        // 匿名标识:与官方 CLI 的默认行为一致 —— 每次上传都带,首次调用时惰性生成并持久化。
        // 它只是"把同一台设备的上传归到一起"的随机串(不是账号、不是鉴权凭据),
        // 用户可在隐私设置里经 StorageToConfig.resetVisitorToken() 清掉换新。
        rb.header("X-Visitor-Token", StorageToConfig.visitorToken());
        if (ownerToken != null && !ownerToken.isEmpty()) {
            // 文档给了两种带法:独立使用时 Authorization: Owner <token>;
            // 已有 Bearer 会话时用 X-Owner-Token。本模块不用 Bearer,所以两种都发 ——
            // 同一个令牌,谁被服务端认下来都行,省得纠结走哪条分支。
            rb.header("Authorization", "Owner " + ownerToken);
            rb.header("X-Owner-Token", ownerToken);
        }
        OkHttpClient client = network.general();
        Call call = client.newCall(rb.build());
        inFlight.add(call);
        try (Response response = call.execute()) {
            int code = response.code();
            String text = safeBody(response);
            if (code == 429) {
                throw rateLimited(text, response);
            }
            if (code == 404 && nullOn404) {
                return null;
            }
            if (!response.isSuccessful()) {
                throw httpError(code, text);
            }
            try {
                T parsed = GSON.fromJson(text, type);
                if (parsed == null) throw new ShareException(ShareErrorCode.PARSE, "响应为空");
                return parsed;
            } catch (ShareException e) {
                throw e;
            } catch (Throwable t) {
                throw new ShareException(ShareErrorCode.PARSE, "响应解析失败", t);
            }
        } catch (ShareException e) {
            throw e;
        } catch (IOException e) {
            throw cancelledOrNetwork(e);
        } finally {
            inFlight.remove(call);
        }
    }

    /**
     * 按文档「错误」节的语义把 HTTP 状态码翻成本模块的错误码。
     * <p>不这么分的话,界面只能说"服务端返回异常",而用户真正需要知道的是
     * "这个分享要密码" / "链接过期了" / "今天额度用完了" 这三种完全不同的处置方式。
     */
    @NonNull
    private static ShareException httpError(int code, @Nullable String body) {
        String detail = errorMessage(body, "");
        switch (code) {
            case 401:
                return new ShareException(ShareErrorCode.AUTH,
                        detail.isEmpty() ? "该分享需要密码或密码错误" : detail);
            case 403:
                return new ShareException(ShareErrorCode.AUTH,
                        detail.isEmpty() ? "没有权限操作该分享(所有权凭据不匹配)" : detail);
            case 404:
                return new ShareException(ShareErrorCode.PARSE,
                        detail.isEmpty() ? "分享不存在或已过期" : detail);
            case 422:
                return new ShareException(ShareErrorCode.INVALID_INPUT,
                        detail.isEmpty() ? "校验失败或已触及套餐/配额限制" : detail);
            default:
                return new ShareException(ShareErrorCode.HTTP,
                        detail.isEmpty() ? "服务端返回异常(HTTP " + code + ")" : detail);
        }
    }

    /** 空响应体也有兜底:失败路径里读 body 再抛异常会掩盖原始错误 */
    @NonNull
    private static String safeBody(@NonNull Response response) {
        try {
            ResponseBody rb = response.body();
            if (rb == null) return "";
            return readCappedText(rb);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 带上限读取响应体文本。
     *
     * <p>为什么不用现成的 {@code response.body().string()}:它把整个响应读进内存,
     * 一个被劫持/配置错误的地址回了几个 GB 就是直接 OOM。清单这类元数据几百 KB 足够,
     * 超限按"读到的部分"截断即可(调用方是解析 JSON,截断会解析失败并报 PARSE,是可接受的失败)。
     */
    @NonNull
    private static String readCappedText(@NonNull ResponseBody body) throws IOException {
        long declared = body.contentLength();
        if (declared > MAX_TEXT_BYTES) {
            throw new IOException("响应过大(" + declared + "B)");
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        long total = 0L;
        try (InputStream in = body.byteStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_TEXT_BYTES) throw new IOException("响应过大");
                bos.write(buf, 0, n);
            }
        }
        return new String(bos.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
    }

    /**
     * 429 的文案。
     *
     * <p>文档把两种 429 分开:每分钟<b>速率限制</b>带 {@code Retry-After} /
     * {@code X-RateLimit-Limit} / {@code X-RateLimit-Remaining} 头;而匿名客户的
     * <b>上传配额</b>429 带 JSON 详情 —— 文件数上限是 {@code {error, limit, used}},
     * 带宽上限是 {@code {error, limit_gb, used_gb}}。
     * 两者对用户的含义完全不同("等一会儿再试" vs "今天别传了"),所以都要读出来。
     */
    @NonNull
    private static ShareException rateLimited(@Nullable String body, @NonNull Response response) {
        String retryAfter = response.header("Retry-After");
        String msg = null;
        try {
            if (body != null && !body.trim().isEmpty()) {
                JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                String err = optString(o, "error");
                String serverMessage = optString(o, "message");
                if (err.isEmpty() && !serverMessage.isEmpty()) {
                    // 速率限制那种:{message: "Too Many Requests"} + Retry-After
                    msg = serverMessage;
                } else if (!err.isEmpty()) {
                    StringBuilder sb = new StringBuilder(err);
                    double limitGb = optDouble(o, "limit_gb", -1);
                    double usedGb = optDouble(o, "used_gb", -1);
                    int limit = optInt(o, "limit", 0);
                    int used = optInt(o, "used", 0);
                    int resets = optInt(o, "resets_in_seconds", 0);
                    if (limitGb >= 0) {
                        sb.append("(已用 ").append(fmtGb(usedGb)).append(" / ").append(fmtGb(limitGb)).append(")");
                    } else if (limit > 0) {
                        sb.append("(已用 ").append(used).append("/").append(limit).append(" 个文件)");
                    }
                    if (resets > 0) sb.append(",").append(formatDuration(resets)).append("后恢复");
                    msg = sb.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        if (msg == null || msg.trim().isEmpty()) {
            msg = "已达平台限额,请稍后再试";
        }
        if (retryAfter != null && !retryAfter.trim().isEmpty()) {
            try {
                int secs = (int) Double.parseDouble(retryAfter.trim());
                if (secs > 0) msg = msg + "(约 " + formatDuration(secs) + "后可重试)";
            } catch (Throwable ignored) {
            }
        }
        return new ShareException(ShareErrorCode.RATE_LIMITED, msg);
    }

    private static double optDouble(@NonNull JsonObject o, @NonNull String key, double def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    @NonNull
    private static String fmtGb(double gb) {
        if (gb < 1.0) return String.format(java.util.Locale.US, "%.2f GB", gb);
        return String.format(java.util.Locale.US, "%.1f GB", gb);
    }

    /** 从错误响应里挖一句可读原因({@code error} 优先,其次 {@code message}) */
    @NonNull
    private static String errorMessage(@Nullable String body, @NonNull String fallback) {
        try {
            if (body != null && !body.trim().isEmpty()) {
                JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                String err = optString(o, "error");
                if (!err.isEmpty()) return err;
                String message = optString(o, "message");
                if (!message.isEmpty()) return message;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 把 OkHttp 的 IOException 归类:被我们 cancel 掉的是"已取消",其余是网络错误 */
    @NonNull
    private static ShareException cancelledOrNetwork(@NonNull IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.US);
        if (m.contains("cancel") || m.contains("closed")) {
            // "Socket closed" 是 OkHttp cancel 后的典型表现,归到 CANCELLED 而不是网络错误:
            // 否则用户点取消会看到一句"网络请求失败",像是出了问题。
            return new ShareException(ShareErrorCode.CANCELLED);
        }
        return ShareException.network(e.getMessage(), e);
    }

    private static void requireSuccess(boolean success, @Nullable String error) throws ShareException {
        if (success) return;
        String msg = error == null || error.trim().isEmpty() ? "服务端拒绝了本次请求" : error;
        // 服务端用 error 字段回业务错误(如"Unauthorized"、"file too large"),归类为 HTTP 而不是 UNKNOWN:
        // 界面据此可以说"服务端拒绝了"而不是"未知错误"。
        throw new ShareException(ShareErrorCode.HTTP, msg);
    }

    @NonNull
    private static String optString(@NonNull JsonObject o, @NonNull String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static int optInt(@NonNull JsonObject o, @NonNull String key, int def) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    @NonNull
    private static String formatDuration(int seconds) {
        if (seconds < 60) return seconds + " 秒";
        if (seconds < 3600) return (seconds / 60) + " 分钟";
        return (seconds / 3600) + " 小时";
    }

    // ------------------------------------------------------------------
    // 请求体:文件的一段(可上报进度,支持重试时反复读同一段)
    // ------------------------------------------------------------------

    /**
     * 文件的一段作为 PUT 体。
     *
     * <p>为什么每次都重新 {@code RandomAccessFile.seek} 而不是复用流:
     * 上传失败要重试,而 OkHttp 可能已经在失败前消费掉了一部分流 —— 复用会把
     * "从断点继续"变成"少传一截"(官方 CLI 也专门处理了这件事:重试前 rewind)。
     * 这里更进一步:每次 {@code writeTo} 都从头 seek 到 {@code offset},天然可重放。
     */
    private static final class FileSectionBody extends RequestBody {

        private final File file;
        private final long offset;
        private final long length;
        private final MediaType contentType;
        private final ProgressSink sink;

        FileSectionBody(@NonNull File file, long offset, long length,
                        @Nullable MediaType contentType, @Nullable ProgressSink sink) {
            this.file = file;
            this.offset = offset;
            this.length = length;
            this.contentType = contentType;
            this.sink = sink;
        }

        @Nullable
        @Override
        public MediaType contentType() {
            return contentType;
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public void writeTo(@NonNull BufferedSink sink) throws IOException {
            byte[] buf = new byte[16384];
            long remaining = length;
            long written = 0L;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(offset);
                while (remaining > 0) {
                    int want = (int) Math.min(buf.length, remaining);
                    int n = raf.read(buf, 0, want);
                    if (n <= 0) break;
                    sink.write(buf, 0, n);
                    remaining -= n;
                    written += n;
                    if (this.sink != null) this.sink.onBytes(written, length);
                }
            }
        }
    }

    /** 供日志:接口地址(不含令牌/凭据) */
    @NonNull
    @Override
    public String toString() {
        return "StorageToApi{" + baseUrl + "}";
    }

    /** 便于调试:当前在跑的请求数 */
    int inFlightCount() {
        return inFlight.size();
    }
}

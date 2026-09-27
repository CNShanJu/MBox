package com.github.tvbox.osc.share.online;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Map;

/**
 * storage.to 接口的数据传输对象(DTO)。
 *
 * <p>为什么全塞在一个文件里(而不是一文件一 DTO):这些类<b>只是线上 JSON 的形状映射</b>,
 * 没有任何行为、不被本模块之外引用,拆成十几个文件只会让"接口长什么样"这件事更难一眼看完。
 * Java 允许一个文件里放多个<b>非 public</b> 顶层类,这里正好用上:它们都是包内可见,
 * 只有 {@link StorageToApi} 与 {@link StorageToTransport} 用。
 *
 * <p>字段名一律显式 {@link SerializedName}:线上是 snake_case
 * ({@code upload_url} / {@code part_size} / {@code owner_token}),<b>不靠命名策略猜</b> ——
 * 猜错一个字段名就是"上传成功但拿不到链接"。
 *
 * <p><b>接口清单以官方文档为准</b>(https://storage.to/zh/docs/api,2026-09 核对):
 * 文档把基础路径定义为 {@code https://storage.to/api},下面列的是相对该基础路径的路径,
 * 即 {@code POST /upload/init} 实际是 {@code POST https://storage.to/api/upload/init}。
 * <pre>
 * POST /upload/init               {filename, content_type, size} → type/upload_url/r2_key/…
 * POST /upload/parts              {upload_id, part_numbers[]}   → urls{partNum: url}
 * POST /upload/complete-multipart {upload_id, parts[]}          → success
 * POST /upload/abort              {upload_id}                   → success
 * POST /upload/confirm            {filename,size,content_type,r2_key,collection_id?,crc32?,file_id?}
 *                                                               → file{…} + owner_token
 * POST /file/reserve              {}                            → file{id,url} + owner_token
 * POST /collection                {expected_file_count?}        → collection{…} + owner_token
 * GET  /collection/{id}/status    —                             → files[] + is_uploading/…
 * POST /collection/{id}/ready     {}                            → success
 * DELETE /collection/{id}
 * POST|DELETE /collection/{id}/password
 * POST /collection/{id}/verify-password   {password}
 * POST /collection/{id}/expiry            {days}          (1–7;null=永久,仅高级版)
 * POST /collection/{id}/max-downloads     {max_downloads} (1–1000;null=移除上限)
 * POST /file/{id}/thumbnail               (仅图片/视频,≤2MB)
 * GET  /file/{id}/status                  → {pending}
 * DELETE /file/{id}
 * POST|DELETE /file/{id}/password, POST /file/{id}/verify-password
 * POST /file/{id}/expiry, POST /file/{id}/max-downloads
 * POST /sharex/upload                     (multipart 一次性,≤25MB,20/天)
 * GET  /user, POST /auth/logout           (Bearer)
 * GET  /files(访客令牌)/ GET /user/files(Bearer)  → {files:[…]} 本客户端自己的上传
 * GET  /bandwidth/status                  → 上传配额余量
 * GET  /health, GET /activity
 * POST /app-analytics, POST /app-errors
 * </pre>
 *
 * <p>鉴权(文档「身份验证」节):
 * <ul>
 *   <li>匿名上传无需密钥,但每设备/IP 滚动 24 小时最多 <b>50 个文件</b>,另受带宽配额限制,
 *       文件 3 天后过期;</li>
 *   <li>{@code X-Visitor-Token: <random>} —— 客户端自己生成一次的随机串(可为空的匿名身份);</li>
 *   <li>{@code Authorization: Bearer <token>} —— 账号 API 令牌(本项目不使用);</li>
 *   <li><b>owner_token</b> —— 每个创建资源的端点({@code /upload/init} 的分片分支、
 *       {@code /upload/confirm}、{@code /file/reserve}、{@code /collection})都会返回它,
 *       是与该资源绑定的签名所有权证明,<b>不依赖 IP 或访客令牌</b>。
 *       变更操作(删除/改密码/改过期/改下载上限,以及分片相关的 parts/complete/abort)
 *       都要它:独立使用时发 {@code Authorization: Owner <token>},
 *       已有 Bearer 会话时改用 {@code X-Owner-Token: <token>}(见 {@link StorageToApi})。</li>
 * </ul>
 */

/** {@code POST /api/upload/init} 响应 */
class InitUploadResponse {
    @SerializedName("success") boolean success;
    @SerializedName("error") String error;
    /** {@code "single"} 或 {@code "multipart"} */
    @SerializedName("type") String type;
    /** single:预签名上传地址(method = PUT) */
    @SerializedName("upload_url") String uploadUrl;
    /** multipart:分片会话 id */
    @SerializedName("upload_id") String uploadId;
    /** 服务端侧对象键,confirm 时必须原样带回 */
    @SerializedName("r2_key") String r2Key;
    @SerializedName("part_size") long partSize;
    @SerializedName("total_parts") int totalParts;
    /** 首批预签名分片地址:{@code "1" -> url} */
    @SerializedName("initial_urls") Map<String, String> initialUrls;
    /**
     * 预签名 PUT 需要额外携带的请求头({@code 名字 -> 值列表})。
     * 签名只覆盖服务端指定的头,<b>多带/少带都会 403</b>,所以要照抄
     * (客户端自管的 Host/Content-Length/Content-Type/User-Agent 除外,见 StorageToApi)。
     */
    @SerializedName("headers") Map<String, List<String>> headers;

    /**
     * 分片分支会返回 owner_token(文档「所有者令牌」节明确列入 {@code /upload/init multipart})。
     * 分片相关的 {@code /upload/parts}、{@code /upload/complete-multipart}、{@code /upload/abort}
     * 都是 "Owner only",所以拿到它就要一路带着 —— 否则只能靠"访客令牌 + 同 IP"兜底,
     * 而移动网络换网就换 IP,分片上传会中途变成 403。
     */
    @SerializedName("owner_token") String ownerToken;

    boolean isMultipart() {
        return "multipart".equalsIgnoreCase(type);
    }
}

/**
 * {@code POST /upload/parts} 响应。
 *
 * <p><b>这里文档与实际实现不一致,所以两种形状都收</b>:
 * <ul>
 *   <li>官方 API 文档(2026-09)写的是数组 {@code part_urls:[{partNumber,url}]};</li>
 *   <li>但桌面端源码里有一条明确的事故记录(desktop {@code src-tauri/src/upload.rs} 的
 *       {@code get_more_parts_v2}):桌面端当年读 {@code part_urls} 是错的,服务端实际返回
 *       {@code {success, urls}},改成读 {@code urls} 才对;Go CLI 一直发/收的就是
 *       {@code part_numbers} + {@code urls}。</li>
 * </ul>
 * 结论:以服务端实际行为({@code urls} 映射)为主,文档那版数组顺带兼容 —— 谁对都不影响,
 * 解析不出来才真会断(而断在"第 251 个分片"这种地方极难排查)。
 */
class GetPartUrlsResponse {
    @SerializedName("success") boolean success;
    @SerializedName("error") String error;
    /** 实际形状:{@code "3" -> presigned url} */
    @SerializedName("urls") Map<String, String> urls;
    /** 文档形状:{@code [{partNumber, url}]} */
    @SerializedName("part_urls") List<PartUrlEntry> partUrls;

    /** 归一化成 {@code 分片号 -> url};两种形状都为空时返回空表 */
    Map<String, String> normalizedUrls() {
        Map<String, String> out = new java.util.HashMap<>();
        if (urls != null) out.putAll(urls);
        if (partUrls != null) {
            for (PartUrlEntry e : partUrls) {
                if (e == null || e.url == null || e.url.isEmpty()) continue;
                out.put(String.valueOf(e.partNumber), e.url);
            }
        }
        return out;
    }

    static class PartUrlEntry {
        @SerializedName("partNumber") int partNumber;
        @SerializedName("url") String url;
    }
}

/** multipart 完成 / abort / ready 等只要 success 的响应 */
class SimpleResponse {
    @SerializedName("success") boolean success;
    @SerializedName("error") String error;
}

/** 一个已上传分片:{@code partNumber} 从 1 开始,{@code etag} 取自 PUT 响应的 ETag 头(去引号) */
class UploadPart {
    @SerializedName("partNumber") int partNumber;
    @SerializedName("etag") String etag;

    UploadPart(int partNumber, String etag) {
        this.partNumber = partNumber;
        this.etag = etag;
    }
}

/** {@code POST /api/upload/confirm} 响应 */
class ConfirmUploadResponse {
    @SerializedName("success") boolean success;
    @SerializedName("error") String error;
    @SerializedName("file") StorageFileInfo file;
    /**
     * 管理凭据:后续"改有效期/改下载上限/删除"要靠它。
     * 匿名上传(不带 visitor token)时这是<b>唯一</b>的所有权证明,丢了就再也管不了这个文件。
     */
    @SerializedName("owner_token") String ownerToken;
}

/** 文件信息 */
class StorageFileInfo {
    @SerializedName("id") String id;
    /** 人类可读的下载页地址(形如 {@code https://storage.to/FQxyz1234});不是直链 */
    @SerializedName("url") String url;
    @SerializedName("filename") String filename;
    @SerializedName("size") long size;
    @SerializedName("human_size") String humanSize;
    /** ISO-8601 到期时间 */
    @SerializedName("expires_at") String expiresAt;
    @SerializedName("max_downloads") int maxDownloads;
}

/** {@code POST /api/collection} / {@code /ready} 响应 */
class CollectionResponse {
    @SerializedName("success") boolean success;
    @SerializedName("error") String error;
    @SerializedName("collection") StorageCollectionInfo collection;
    @SerializedName("owner_token") String ownerToken;
}

/** 集合信息(一次导出多个文件时把它们归到一起) */
class StorageCollectionInfo {
    @SerializedName("id") String id;
    /** 集合页地址(形如 {@code https://storage.to/c/FQabc5678}) */
    @SerializedName("url") String url;
    @SerializedName("expires_at") String expiresAt;
    @SerializedName("max_downloads") int maxDownloads;
}

/**
 * {@code GET /collection/{id}/status} 响应 —— <b>导入侧最重要的一条:它是文档化的集合成员读取入口</b>。
 *
 * <p>有了它,"导入"不必去猜 {@code /c/{id}.json} 清单的结构(那条路只是 README 里提过一句,
 * 字段从未文档化),而是走正式 API:拿到集合里每个文件的
 * {@code id / url / filename / size / expires_at}。文档里这个端点<b>没有</b>标 "Owner only"
 * (对比 {@code /ready} 与 {@code DELETE} 都标了),所以它对分享接收方也可用。
 *
 * <p>注意 {@link StorageFileInfo#url} 仍然是<b>下载页地址</b>而不是直链 ——
 * 文档的「限制一览」只说"下载直连 R2 签名 URL 提供",并没有给出取直链的公开端点。
 * 所以取字节这一步仍要解析下载页,见 {@link StorageToLinkResolver}。
 */
class CollectionStatusResponse {
    @SerializedName("success") boolean success;
    @SerializedName("error") String error;
    @SerializedName("files") List<StorageFileInfo> files;
    @SerializedName("is_uploading") boolean isUploading;
    @SerializedName("file_count") int fileCount;
    @SerializedName("expected_file_count") int expectedFileCount;
    @SerializedName("total_size") long totalSize;
    @SerializedName("human_total_size") String humanTotalSize;
}

/**
 * {@code GET /bandwidth/status} 响应 —— 滚动 24 小时上传配额余量(下载不计入)。
 * 用于在导出前提示"今天还能传多少",以及 429 之后解释原因。
 */
class BandwidthStatusResponse {
    @SerializedName("success") boolean success;
    @SerializedName("error") String error;
    @SerializedName("authenticated") boolean authenticated;
    @SerializedName("has_token") boolean hasToken;
    @SerializedName("limit_bytes") long limitBytes;
    @SerializedName("limit_gb") double limitGb;
    @SerializedName("used_bytes") long usedBytes;
    @SerializedName("used_gb") double usedGb;
    @SerializedName("remaining_bytes") long remainingBytes;
    @SerializedName("remaining_gb") double remainingGb;
    @SerializedName("window_hours") int windowHours;
    @SerializedName("plan") String plan;
}

/**
 * 集合清单({@code GET /c/<id>.json},官方 README 明确给出的读回入口)。
 *
 * <p>这个端点是"在线导入"的立足点:{@code /r/} 直链已被 storage.to 下线,
 * 分享页是 HTML(给浏览器看的),而集合清单是<b>机器可读的 JSON</b>。
 * 因此本模块导出时一律建集合(即使只有一个文件),导入时按 `<c/{id}>.json` 取清单 ——
 * 全程不爬 HTML。
 *
 * <p>字段容错解析:清单里每个文件的下载地址字段名官方未在 README 里固定,
 * 这里按可能的几种命名都收一遍({@code url} / {@code download_url} / {@code raw_url}),
 * 取第一个看起来像直链的。所以字段都标了多种 {@code SerializedName} 的备选 ——
 * Gson 只认最后一个注解,故备选字段另用 {@link #pickUrl} 在原始 JSON 上兜。
 */
class StorageCollectionManifest {
    @SerializedName("id") String id;
    @SerializedName("url") String url;
    @SerializedName("expires_at") String expiresAt;
    @SerializedName("files") List<Entry> files;

    static class Entry {
        @SerializedName("url") String url;
        @SerializedName("download_url") String downloadUrl;
        @SerializedName("raw_url") String rawUrl;
        @SerializedName("filename") String filename;
        @SerializedName("name") String name;
        @SerializedName("size") long size;

        /** 取该条目可用的直链(优先 raw/download,最后才是 page url) */
        String pickUrl() {
            if (rawUrl != null && !rawUrl.trim().isEmpty()) return rawUrl.trim();
            if (downloadUrl != null && !downloadUrl.trim().isEmpty()) return downloadUrl.trim();
            if (url != null && !url.trim().isEmpty()) return url.trim();
            return "";
        }

        String pickName() {
            if (filename != null && !filename.trim().isEmpty()) return filename.trim();
            if (name != null && !name.trim().isEmpty()) return name.trim();
            return "";
        }
    }
}

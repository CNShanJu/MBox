package com.github.tvbox.osc.share.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.share.ShareErrorCode;
import com.github.tvbox.osc.share.ShareException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认解析策略:优先走<b>官方 README 明确给出</b>的集合清单端点,单文件分享页只作尽力而为的兜底。
 *
 * <p>两条路径的性质完全不同,所以 {@link ResolvedDownload#isExperimental()} 把它们区分开:
 * <ol>
 *   <li><b>集合清单(稳)</b>:{@code GET <base>/c/<id>.json} —— 官方 README 就写着
 *       {@code curl https://storage.to/c/FQabc5678.json} "Download collection as JSON manifest"。
 *       这是机器可读的正式入口,不依赖页面结构。<b>因此本模块导出时一律建集合</b>
 *       (即使只传一个文件,见 {@link StorageToTransport#export}),保证"自己导出的东西自己能导入";</li>
 *   <li><b>单文件分享页(不稳,标 experimental)</b>:页面是给浏览器渲染的,地址藏在 HTML/JS 里。
 *       官方 CLI 干脆不提供下载,原因就在这里。这里只按几种常见形态试一把
 *       (JSON 字段 / 直链 anchor),抠不到就明确报"请自行下载后从本地导入",
 *       而不是假装能行、让用户看着一个转圈界面。</li>
 * </ol>
 *
 * <p><b>⚠ 待联网实测</b>:本文件里的正则与字段名(尤其第 2 条路径,以及清单里文件条目的字段名)
 * 是按官方 README 与 CLI 源码推断的,开发环境访问 storage.to 会被 Cloudflare 拦截,
 * 无法现场验证真实响应结构。首次联网自测时应重点核对:
 * 清单 JSON 顶层字段、文件条目里的下载地址字段名、以及单文件页里直链的形态。
 * 对不上的话只需改本类(其余代码不受影响)—— 这正是把它单独抽成策略接口的原因。
 */
public final class StorageToCollectionResolver implements StorageToLinkResolver {

    private static final Gson GSON = new Gson();

    /** 集合清单路径片段 */
    private static final String COLLECTION_SEGMENT = "/c/";

    /**
     * 单文件页兜底:找 JSON 里像"下载地址"的字段。
     * 键名覆盖几种可能的写法(不同版本/不同端点可能不一样)。
     */
    private static final Pattern JSON_URL = Pattern.compile(
            "\"(?:download_url|downloadUrl|raw_url|rawUrl|file_url|fileUrl|direct_url|url)\"\\s*:\\s*\"([^\"]{8,2048})\"");

    /** 单文件页兜底:找指向压缩包/清单的 anchor */
    private static final Pattern ANCHOR_ARCHIVE = Pattern.compile(
            "href\\s*=\\s*[\"']([^\"']{8,2048}\\.(?:zip|json|db|bin)(?:\\?[^\"']*)?)[\"']",
            Pattern.CASE_INSENSITIVE);

    /** 单文件页兜底:老式 {@code /r/<id>} 直链(官方已下线,但镜像站可能还在用) */
    private static final Pattern LEGACY_HOTLINK = Pattern.compile(
            "[\"'](https?://[^\"']{0,256}/r/[A-Za-z0-9_-]{4,64})[\"']");

    private final StorageToApi api;
    private final String baseUrl;

    StorageToCollectionResolver(@NonNull StorageToApi api) {
        this.api = api;
        this.baseUrl = api.baseUrl();
    }

    @NonNull
    @Override
    public ResolvedDownload resolve(@Nullable String shareRef) throws ShareException {
        String ref = normalize(shareRef);
        if (ref.isEmpty()) {
            throw ShareException.invalid("请填写分享链接");
        }

        if (isCollectionRef(ref)) {
            // 集合链接:先找出"要导入哪一个文件",再解析它的下载页
            StorageFileInfo file = firstFileOfCollection(ref);
            if (file == null) {
                throw new ShareException(ShareErrorCode.PARSE,
                        "集合里没有可下载的文件(可能尚未就绪或已过期),请让分享方重新导出");
            }
            String page = file.url;
            if (page == null || page.trim().isEmpty()) {
                throw new ShareException(ShareErrorCode.PARSE, "集合成员缺少下载地址");
            }
            // 文件对象给的是下载页地址而不是直链,所以还要再走一次页面解析
            ResolvedDownload resolved = fromSharePage(page);
            return new ResolvedDownload(
                    resolved.directUrl(),
                    file.filename != null && !file.filename.trim().isEmpty()
                            ? file.filename.trim() : resolved.fileName(),
                    file.size > 0 ? file.size : resolved.sizeBytes(),
                    ref,
                    resolved.isExperimental());
        }

        // 单文件分享页:尽力而为
        return fromSharePage(ref);
    }

    // ------------------------------------------------------------------
    // 路径 1:集合成员(两个来源,按可靠性排序)
    // ------------------------------------------------------------------

    /**
     * 取出集合里"最值得导入"的那个文件。
     *
     * <p>两个来源,依次尝试:
     * <ol>
     *   <li><b>{@code GET /api/collection/{id}/status}(首选)</b> —— 官方文档的正式端点,
     *       返回完整文件对象({@code id/url/filename/size/expires_at}),<b>不需要所有权</b>,
     *       字段有文档可依;</li>
     *   <li><b>{@code GET /c/{id}.json} 清单(兜底)</b> —— 官方 README 提过一句、字段从未文档化,
     *       所以只当老式/镜像站点的退路,解析全程容错。</li>
     * </ol>
     *
     * <p>网络类错误直接往上抛(否则用户会看到"链接不对"而实际是断网);
     * 404/权限类问题才退到下一个来源。
     */
    @Nullable
    private StorageFileInfo firstFileOfCollection(@NonNull String ref) throws ShareException {
        String collectionId = collectionIdOf(ref);
        if (!collectionId.isEmpty()) {
            try {
                CollectionStatusResponse status = api.collectionStatus(collectionId);
                if (status != null && status.files != null && !status.files.isEmpty()) {
                    StorageFileInfo best = null;
                    for (StorageFileInfo f : status.files) {
                        if (f == null || f.url == null || f.url.trim().isEmpty()) continue;
                        if (best == null) {
                            best = f;
                            continue;
                        }
                        // 优先挑本应用导出的归档包:同一集合里可能混着别的文件
                        if (looksLikeAppArchive(f) && !looksLikeAppArchive(best)) best = f;
                    }
                    if (best != null) return best;
                }
            } catch (ShareException e) {
                if (e.code() == ShareErrorCode.NETWORK) throw e;
                LogStore.log(Category.SYSTEM, "分享: 集合状态读取失败,改用清单兜底 " + e.getMessage());
            }
        }

        // 兜底:README 里提过的机器可读清单
        String json = api.getText(manifestUrlOf(ref));
        if (json == null || json.trim().isEmpty()) return null;
        return firstFileOfManifest(json, ref);
    }

    /** 清单(兜底来源)→ 文件对象;结构不认识时退化成"捞第一个像下载地址的串" */
    @Nullable
    private StorageFileInfo firstFileOfManifest(@NonNull String json, @NonNull String ref) {
        StorageCollectionManifest manifest = null;
        try {
            manifest = GSON.fromJson(json, StorageCollectionManifest.class);
        } catch (Throwable ignored) {
        }
        if (manifest == null || manifest.files == null || manifest.files.isEmpty()) {
            String loose = firstUrlInJson(json);
            if (loose.isEmpty()) return null;
            StorageFileInfo looseFile = new StorageFileInfo();
            looseFile.url = loose;
            return looseFile;
        }
        StorageCollectionManifest.Entry best = null;
        for (StorageCollectionManifest.Entry e : manifest.files) {
            if (e == null || e.pickUrl().isEmpty()) continue;
            if (best == null) {
                best = e;
                continue;
            }
            if (looksLikeAppArchive(e) && !looksLikeAppArchive(best)) best = e;
        }
        if (best == null) return null;
        StorageFileInfo out = new StorageFileInfo();
        out.url = best.pickUrl();
        out.filename = best.pickName();
        out.size = best.size;
        return out;
    }

    /** 名字像本应用导出的归档包(.zip / .mbox) */
    private static boolean looksLikeAppArchive(@Nullable StorageFileInfo f) {
        if (f == null) return false;
        return isArchiveName(f.filename) || isArchiveName(f.url);
    }

    private static boolean isArchiveName(@Nullable String n) {
        if (n == null) return false;
        String s = n.toLowerCase(Locale.US);
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        return s.endsWith(".zip") || s.endsWith(".mbox");
    }

    /** 名字像本应用导出的归档包(清单兜底来源用) */
    private static boolean looksLikeAppArchive(@Nullable StorageCollectionManifest.Entry e) {
        if (e == null) return false;
        return isArchiveName(e.pickName()) || isArchiveName(e.pickUrl());
    }

    // ------------------------------------------------------------------
    // 路径 2:单文件分享页(尽力而为,experimental)
    // ------------------------------------------------------------------

    @NonNull
    private ResolvedDownload fromSharePage(@NonNull String ref) throws ShareException {
        String html = api.getText(ref);
        if (html == null || html.trim().isEmpty()) {
            throw new ShareException(ShareErrorCode.PARSE, "分享页没有内容(可能已过期)");
        }

        String direct = firstMatch(JSON_URL, html, ref);
        if (direct.isEmpty()) direct = firstMatch(ANCHOR_ARCHIVE, html, ref);
        if (direct.isEmpty()) direct = firstMatch(LEGACY_HOTLINK, html, ref);
        if (direct.isEmpty()) {
            // 页面结构没对上:如实说清,并给出可用的替代做法
            LogStore.log(Category.SYSTEM, "分享: storage.to 分享页未解析出直链 " + ref);
            throw new ShareException(ShareErrorCode.PARSE,
                    "无法从该分享页直接取文件(平台可能已改版)。"
                            + "请在浏览器打开链接自行下载,再从本地文件导入;"
                            + "或让分享方改发集合链接");
        }
        return new ResolvedDownload(absolutize(direct), "", 0L, ref, true);
    }

    // ------------------------------------------------------------------
    // 引用归一化
    // ------------------------------------------------------------------

    /** 用户可能粘的是纯 id、`/c/xxx`、或完整地址:统一成绝对地址 */
    @NonNull
    private String normalize(@Nullable String raw) {
        if (raw == null) return "";
        String ref = raw.trim();
        if (ref.isEmpty()) return "";
        // 去掉复制粘贴常见的包裹字符与空白
        ref = ref.replace("\n", "").replace("\r", "").replace(" ", "");
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        if (ref.startsWith("/")) return baseUrl + ref;
        return baseUrl + "/" + ref;
    }

    /** 是否是集合引用(路径里带 {@code /c/} 段;文档:集合分享地址形如 {@code /c/{id}}) */
    private static boolean isCollectionRef(@NonNull String ref) {
        return !collectionIdOf(ref).isEmpty();
    }

    /** 从集合引用里取出集合 id;不是集合引用返回空串 */
    @NonNull
    private static String collectionIdOf(@NonNull String ref) {
        int scheme = ref.indexOf("://");
        String path = scheme >= 0 ? ref.substring(scheme + 3) : ref;
        int slash = path.indexOf('/');
        if (slash < 0) return "";
        String p = path.substring(slash);
        if (!p.startsWith(COLLECTION_SEGMENT)) return "";
        String id = p.substring(COLLECTION_SEGMENT.length());
        int q = id.indexOf('?');
        if (q >= 0) id = id.substring(0, q);
        if (id.endsWith(".json")) id = id.substring(0, id.length() - 5);
        while (id.endsWith("/")) id = id.substring(0, id.length() - 1);
        return id.trim();
    }

    /** 集合页地址补上 {@code .json};已是 {@code .json} 则原样返回 */
    @NonNull
    private static String manifestUrlOf(@NonNull String ref) {
        String u = ref;
        int q = u.indexOf('?');
        String query = "";
        if (q >= 0) {
            query = u.substring(q);
            u = u.substring(0, q);
        }
        if (u.endsWith(".json")) return u + query;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u + ".json" + query;
    }

    /** 相对地址补成绝对(基于配置的基址) */
    @NonNull
    private String absolutize(@NonNull String url) {
        String u = url.trim();
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("/")) return baseUrl + u;
        return baseUrl + "/" + u;
    }

    // ------------------------------------------------------------------
    // 容错工具
    // ------------------------------------------------------------------

    /** 取第一个匹配且"看起来是文件地址而非分享页本身"的捕获组 */
    @NonNull
    private String firstMatch(@NonNull Pattern p, @NonNull String text, @NonNull String ref) {
        try {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String candidate = m.group(1);
                if (candidate == null) continue;
                String c = candidate.replace("\\/", "/").trim();
                if (c.isEmpty()) continue;
                // 排除指回分享页自身的地址(否则会拿 HTML 当 zip 下载)
                if (c.equals(ref)) continue;
                if (c.endsWith(".html") || c.endsWith(".htm")) continue;
                if (!c.startsWith("http") && !c.startsWith("/") && !c.startsWith("//")) continue;
                return c;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 结构不认识时的最后兜底:在 JSON 里找第一个像文件下载地址的字符串。
     * 只接受带明显文件后缀或明显 CDN 路径的值,避免把页面地址当文件。
     */
    @NonNull
    private String firstUrlInJson(@NonNull String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            String found = walkForUrl(root, 0);
            return found == null ? "" : found;
        } catch (Throwable t) {
            return "";
        }
    }

    @Nullable
    private String walkForUrl(@Nullable JsonElement el, int depth) {
        if (el == null || depth > 6) return null;
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            for (String key : new String[]{"download_url", "downloadUrl", "raw_url", "file_url", "url"}) {
                if (o.has(key) && o.get(key).isJsonPrimitive()) {
                    String v = o.get(key).getAsString();
                    if (looksLikeFileUrl(v)) return v;
                }
            }
            for (java.util.Map.Entry<String, JsonElement> e : o.entrySet()) {
                String r = walkForUrl(e.getValue(), depth + 1);
                if (r != null) return r;
            }
            return null;
        }
        if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            for (JsonElement e : a) {
                String r = walkForUrl(e, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean looksLikeFileUrl(@Nullable String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.US);
        if (!s.startsWith("http")) return false;
        return s.endsWith(".zip") || s.endsWith(".json") || s.endsWith(".bin")
                || s.contains("/r/") || s.contains("/d/") || s.contains("/download/");
    }

    @NonNull
    @Override
    public String toString() {
        return "StorageToCollectionResolver{" + baseUrl + "}";
    }
}

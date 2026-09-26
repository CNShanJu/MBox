package com.github.catvod.crawler;

import android.text.TextUtils;
import android.util.Log;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.ParseBeanUrls;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 播放地址解析器（归属爬虫模块）：批量下载前，把源站返回的"集标识/内部ID"（如 4|527743|62032）
 * 解析成真实可下载的 HTTP 地址，解析逻辑与播放流程（getPlay → playerContent/解析）保持一致。
 * <p>
 * 支持：直接 HTTP 地址、爬虫（playerContent）返回 parse=0 的直链、
 * json:/parse: 类型的解析接口；需要 WebView 嗅探的地址无法批量解析，返回 null 由调用方跳过。
 * <p>
 * 解析结果除地址外还携带源要求的请求头（{@link ResolveResult#headers}，如 User-Agent/Referer，
 * 与播放端取值一致，值带前导空格）。防盗链源的分片/文件校验这些头，下载必须携带。
 * 外部统一经 {@link SpiderApi#resolvePlayUrl(String, String, String)} 调用（串行 + 超时）。
 */
public class PlayUrlResolver {

    private static final String TAG = "TVBox-Download";

    /** 解析结果：真实地址 + 源要求的请求头（可为 null） */
    public static class ResolveResult {
        public final String url;
        public Map<String, String> headers;

        public ResolveResult(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    /**
     * 解析单集真实播放地址；解析失败返回 null。
     *
     * @param sourceKey 来源 key
     * @param playFlag  线路名（如 线路1）
     * @param url       源站返回的地址/内部标识
     */
    public static String resolve(String sourceKey, String playFlag, String url) {
        ResolveResult rr = resolveWithHeader(sourceKey, playFlag, url);
        return rr == null ? null : rr.url;
    }

    /** 同 {@link #resolve(String, String, String)}，同时返回请求头（下载防盗链源必须使用） */
    public static ResolveResult resolveWithHeader(String sourceKey, String playFlag, String url) {
        if (TextUtils.isEmpty(url)) return null;
        // 已是有效 HTTP 地址，直接使用（无额外请求头）
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return new ResolveResult(url, null);
        }
        try {
            SourceBean sb = ApiConfig.get().getSource(sourceKey);
            if (sb == null) return null;
            int type = sb.getType();
            if (type == 3) {
                // 爬虫源：playerContent 返回播放信息
                Spider sp = ApiConfig.get().getCSP(sb);
                String json = sp.playerContent(playFlag, url, ApiConfig.get().getVipParseFlags());
                JSONObject result = new JSONObject(json);
                return handleResult(result, playFlag, url);
            }
            if (type == 0 || type == 1) {
                // XML/JSON 接口源：本身是视频格式则直下，否则走源站的解析接口
                if (DefaultConfig.isVideoFormat(url)) return new ResolveResult(url, null);
                String playerUrl = sb.getPlayerUrl() == null ? "" : sb.getPlayerUrl().trim();
                return parseJson(playerUrl, url);
            }
            if (type == 4) {
                // HTTP 接口源：play 参数拿播放信息
                String api = sb.getApi();
                if (TextUtils.isEmpty(api)) return null;
                String sep = api.contains("?") ? "&" : "?";
                String req = api + sep + "play=" + encode(url) + "&flag=" + encode(playFlag);
                JSONObject result = new JSONObject(HttpClient.getSync(req, null));
                return handleResult(result, playFlag, url);
            }
        } catch (Throwable th) {
            Log.i(TAG, "resolve 失败: " + url + " -> " + th.getMessage());
        }
        return null;
    }

    /**
     * 当前播放集下载解析（防盗链代理特例）:
     * 1. 解析成功但结果无请求头 → 补播放器请求头(UA/Referer)——部分代理(如 jx.91by.top)
     *    对无头下载请求返回 ASCII art 提示页而非 m3u8(播放带同一头正常);
     * 2. 解析失败 → 回退播放器已嗅探到的地址 + 播放器请求头(WebView 嗅探类源无法批量解析)。
     * <p>仅"当前播放集"可用(播放器才知道 header); 其他 m3u8 走 {@link #resolveWithHeader} 不受影响。</p>
     *
     * @param playbackHeaders 播放器当前请求头(可为 null)
     * @param fallbackUrl     播放器最终播放地址(解析失败时回退, 可为 null)
     */
    public static ResolveResult resolveCurrentWithPlaybackHeaders(String sourceKey, String playFlag, String url,
                                                                  Map<String, String> playbackHeaders, String fallbackUrl) {
        ResolveResult rr = resolveWithHeader(sourceKey, playFlag, url);
        if (rr == null || TextUtils.isEmpty(rr.url)) {
            return new ResolveResult(fallbackUrl, playbackHeaders);
        }
        // 合并请求头: 解析结果为底, 播放器请求头(UA/Referer 等)补充缺失键——
        // 防盗链代理(如 jx.91by.top)校验 Referer/UA, 解析结果往往只带 UA 缺 Referer,
        // 仅"headers 为空才补"会漏, 必须逐键合并
        if (playbackHeaders != null && !playbackHeaders.isEmpty()) {
            Map<String, String> merged = rr.headers != null ? new HashMap<>(rr.headers) : new HashMap<>();
            boolean changed = false;
            for (Map.Entry<String, String> e : playbackHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !merged.containsKey(e.getKey())) {
                    merged.put(e.getKey(), e.getValue());
                    changed = true;
                }
            }
            if (changed || rr.headers == null || rr.headers.isEmpty()) {
                return new ResolveResult(rr.url, merged);
            }
        }
        return rr;
    }

    /** 处理播放信息结果：parse=0 直链；parse=1 尝试 json 解析 */
    private static ResolveResult handleResult(JSONObject result, String playFlag, String url) throws JSONException {
        boolean parse = result.optString("parse", "1").equals("1");
        boolean jx = result.optString("jx", "0").equals("1");
        String playUrl = result.optString("playUrl", "");
        String realUrl = result.optString("url", "");
        Map<String, String> headers = extractHeaders(result);
        if (!parse && !jx) {
            String direct = playUrl + realUrl;
            return direct.startsWith("http://") || direct.startsWith("https://")
                    ? new ResolveResult(direct, headers) : null;
        }
        // 需要解析
        if (jx) {
            // 自定义解析(jx=1):与播放端 initParse/doParse 同款(默认解析器),
            // 批量支持 json 接口/jsonExt/jsonExtMix, WebView 嗅探型不支持返回 null
            return resolveJx(playFlag, realUrl);
        }
        ResolveResult rr = parseJson(playUrl, realUrl);
        if (rr == null) return null;
        // json 解析结果未给 header 时，继承爬虫结果自带的 header
        if (rr.headers == null) rr.headers = headers;
        return rr;
    }

    /** 自定义解析(jx=1):复用播放端 doParse 的解析路径(默认解析器), 批量下载可用 */
    private static ResolveResult resolveJx(String flag, String input) {
        try {
            com.github.tvbox.osc.bean.ParseBean pb = ApiConfig.get().getDefaultParse();
            Log.i(TAG, "jx解析: 解析器=" + (pb == null ? "null" : (pb.getName() + " type=" + pb.getType()))
                    + " input=" + input);
            if (pb == null) return null;
            int type = pb.getType();
            if (type == 0) {
                Log.i(TAG, "jx解析: 解析器为 WebView 嗅探型(type0), 批量不支持, 跳过: " + input);
                return null;
            }
            if (type == 1) {
                // json 解析接口(带解析器 ext 的 header)
                Map<String, String> reqHeaders = new HashMap<>();
                try {
                    JSONObject ext = new JSONObject(pb.getExt());
                    if (ext.has("header")) {
                        JSONObject hd = ext.getJSONObject("header");
                        Iterator<String> keys = hd.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            reqHeaders.put(k, hd.optString(k, ""));
                        }
                    }
                } catch (Throwable ignored) {
                }
                String json = HttpClient.getSync(ParseBeanUrls.url(pb) + encode(input), reqHeaders);
                Log.i(TAG, "jx解析(json): resp=" + (json == null ? "null"
                        : json.substring(0, Math.min(200, json.length()))));
                JSONObject rs = parseJsonResult(input, json);
                String real = rs == null ? null : rs.optString("url", "");
                if (TextUtils.isEmpty(real)) return null;
                return new ResolveResult(real, extractHeaders(rs));
            }
            if (type == 2) {
                // json 扩展(同播放端 type2: 收集 type1 解析器)
                java.util.LinkedHashMap<String, String> jxs = new java.util.LinkedHashMap<>();
                for (com.github.tvbox.osc.bean.ParseBean p : ApiConfig.get().getParseBeanList()) {
                    if (p.getType() == 1) jxs.put(p.getName(), ParseBeanUrls.mixUrl(p));
                }
                JSONObject rs = ApiConfig.get().jsonExt(ParseBeanUrls.url(pb), jxs, input);
                if (rs == null || !rs.has("url") || TextUtils.isEmpty(rs.optString("url"))) return null;
                if (rs.optInt("parse", 0) == 1) return null; // 需二次嗅探, 不支持
                return new ResolveResult(rs.optString("url"), extractHeaders(rs));
            }
            if (type == 3) {
                // json 聚合(同播放端 type3)
                java.util.LinkedHashMap<String, HashMap<String, String>> jxs = new java.util.LinkedHashMap<>();
                String extendName = "";
                for (com.github.tvbox.osc.bean.ParseBean p : ApiConfig.get().getParseBeanList()) {
                    HashMap<String, String> data = new HashMap<>();
                    data.put("url", ParseBeanUrls.url(p));
                    if (ParseBeanUrls.url(p).equals(ParseBeanUrls.url(pb))) extendName = p.getName();
                    data.put("type", p.getType() + "");
                    data.put("ext", p.getExt());
                    jxs.put(p.getName(), data);
                }
                JSONObject rs = ApiConfig.get().jsonExtMix(flag + "111", ParseBeanUrls.url(pb), extendName, jxs, input);
                if (rs == null || !rs.has("url") || TextUtils.isEmpty(rs.optString("url"))) return null;
                if (rs.has("parse") && rs.optInt("parse", 0) == 1) return null; // 需二次嗅探, 不支持
                return new ResolveResult(rs.optString("url"), extractHeaders(rs));
            }
        } catch (Throwable th) {
            Log.i(TAG, "jx解析失败: " + input + " -> " + th.getMessage());
        }
        return null;
    }

    /** json 解析（json:/parse: 接口），失败返回 null */
    private static ResolveResult parseJson(String playUrl, String videoUrl) {
        try {
            if (TextUtils.isEmpty(playUrl)) return null;
            if (playUrl.startsWith("json:")) {
                playUrl = playUrl.substring(5);
            } else if (playUrl.startsWith("parse:")) {
                // 按名称找解析器
                String name = playUrl.substring(6);
                boolean found = false;
                for (ParseBean pb : ApiConfig.get().getParseBeanList()) {
                    if (pb.getName().equals(name) && pb.getType() == 1) {
                        playUrl = ParseBeanUrls.mixUrl(pb);
                        found = true;
                        break;
                    }
                }
                if (!found) return null;
            }
            if (TextUtils.isEmpty(playUrl)) return null;
            String json = HttpClient.getSync(playUrl + encode(videoUrl), null);
            JSONObject rs = parseJsonResult(videoUrl, json);
            String real = rs == null ? null : rs.optString("url", "");
            if (TextUtils.isEmpty(real)) return null;
            // 解析接口返回的 user-agent/referer（与播放端 jsonParse 一致，防盗链源必须携带）
            return new ResolveResult(real, extractHeaders(rs));
        } catch (Throwable th) {
            Log.i(TAG, "json解析失败: " + playUrl + " -> " + th.getMessage());
            return null;
        }
    }

    /** 解析 json 接口返回（兼容 {url:..} / {data:{url:..}}） */
    private static JSONObject parseJsonResult(String input, String json) throws JSONException {
        JSONObject jsonPlayData = new JSONObject(json);
        String url;
        if (jsonPlayData.has("data")) {
            JSONObject data = jsonPlayData.getJSONObject("data");
            url = data.has("url") ? data.getString("url") : "";
        } else {
            url = jsonPlayData.getString("url");
        }
        if (url.startsWith("//")) url = "http:" + url;
        if (!url.startsWith("http")) return null;
        JSONObject taskResult = new JSONObject();
        taskResult.put("url", url);
        return taskResult;
    }

    /** 提取播放信息里的请求头：header 对象（爬虫）+ user-agent/referer 兜底（解析接口） */
    private static Map<String, String> extractHeaders(JSONObject result) {
        if (result == null) return null;
        Map<String, String> headers = null;
        try {
            if (result.has("header")) {
                Object h = result.get("header");
                JSONObject hds = h instanceof JSONObject ? (JSONObject) h : new JSONObject(h.toString());
                Iterator<String> keys = hds.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (headers == null) headers = new HashMap<>();
                    headers.put(key, hds.getString(key));
                }
            }
        } catch (Throwable ignored) {
        }
        // user-agent / referer 兜底（与播放端 jsonParse 一致，值带前导空格，部分源校验该空格）
        try {
            String ua = result.optString("user-agent", "");
            if (ua.trim().length() > 0) {
                if (headers == null) headers = new HashMap<>();
                headers.put("User-Agent", " " + ua);
            }
            String referer = result.optString("referer", "");
            if (referer.trim().length() > 0) {
                if (headers == null) headers = new HashMap<>();
                headers.put("Referer", " " + referer);
            }
        } catch (Throwable ignored) {
        }
        return headers;
    }

    private static String encode(String url) {
        try {
            return URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }
}

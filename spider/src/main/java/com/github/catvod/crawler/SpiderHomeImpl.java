package com.github.catvod.crawler;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.spiderapi.AbsXmlParser;
import com.github.tvbox.osc.spiderapi.SortParser;
import com.github.tvbox.osc.spiderapi.SpiderHomeApi;

import java.util.HashMap;
import java.util.Map;

/**
 * 强类型首页/分类实现(type=3 JS/JAR):内容经爬虫契约(spiderapi 包)取得后解析为 AbsSortXml/AbsXml。
 * 解析与 VM 共用同一权威实现:homeContent→SortParser.parseSortJson;分类/首页推荐→
 * AbsXmlParser.parseJson(与 SourceViewModel.json() 同源,消除双实现漂移)。
 */
public final class SpiderHomeImpl implements SpiderHomeApi {

    private static final SpiderHomeImpl INSTANCE = new SpiderHomeImpl();

    public static SpiderHomeImpl get() {
        return INSTANCE;
    }

    private AbsXml parse(String sourceKey, String content, String tag) {
        if (content == null || content.isEmpty()) {
            android.util.Log.w("SpiderBridge", tag + " 内容为空 key=" + sourceKey);
            return null;
        }
        try {
            // 与 VM json() 同一权威解析(AbsJson → AbsXml + normalize 回填 sourceKey/拆 beanList)
            AbsXml xml = AbsXmlParser.parseJson(content, sourceKey);
            if (xml == null || xml.movie == null) {
                android.util.Log.w("SpiderBridge", tag + " 解析为空 key=" + sourceKey);
                return null;
            }
            return xml;
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", tag + " 异常 key=" + sourceKey, th);
            return null;
        }
    }

    @Override
    public AbsSortXml homeContent(String sourceKey, boolean filter) {
        if (sourceKey == null) {
            android.util.Log.w("SpiderBridge", "home(typed): 入参缺失 key=" + sourceKey);
            return null;
        }
        try {
            SourceBean sb = ApiConfig.get().getSource(sourceKey);
            if (sb == null || sb.getType() != 3) {
                return null; // 仅 type=3 走强类型
            }
            String content = SpiderContentImpl.get().homeContent(sourceKey, filter);
            if (content == null || content.isEmpty()) {
                android.util.Log.w("SpiderBridge", "home(typed): 内容为空 key=" + sourceKey + " filter=" + filter);
                return null;
            }
            // 解析与 app sortJson() 一致;若响应内嵌 list(首页视频),一并解析到 sort.videoList
            AbsSortXml data = SortParser.parseSortJson(content);
            if (data == null || data.classes == null || data.classes.sortList == null
                    || data.classes.sortList.isEmpty()) {
                android.util.Log.w("SpiderBridge", "home(typed): 无分类/解析为空 key=" + sourceKey);
                return null;
            }
            // 同响应内嵌首页视频(list 字段,与旧 json() 语义一致):带上供 VM 直接发布
            if (data.list != null && data.list.videoList != null && !data.list.videoList.isEmpty()) {
                data.videoList = data.list.videoList;
            }
            android.util.Log.d("SpiderBridge", "home(typed) 成功: key=" + sourceKey
                    + " filter=" + filter + " classes=" + data.classes.sortList.size()
                    + " embedded=" + (data.videoList == null ? 0 : data.videoList.size()));
            return data;
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "home(typed) 异常: key=" + sourceKey, th);
            return null;
        }
    }

    @Override
    public AbsXml category(String sourceKey, String tid, String pg, boolean filter, Map<String, String> extend) {
        SourceBean sb = ApiConfig.get().getSource(sourceKey);
        if (sb == null) return null;
        int type = sb.getType();
        if (type == 3) {
            Map<String, String> ext = extend == null ? new HashMap<>() : new HashMap<>(extend);
            String content = SpiderContentImpl.get().categoryContent(sourceKey, tid, pg, filter, ext);
            AbsXml xml = parse(sourceKey, content, "category(typed)");
            if (xml != null) {
                android.util.Log.d("SpiderBridge", "category(typed) 成功: key=" + sourceKey + " tid=" + tid + " pg=" + pg);
            }
            return xml;
        }
        if (type == 0 || type == 1 || type == 4) {
            return categoryFromHttp(sb, type, tid, pg, extend);
        }
        return null; // 其余类型不支持 typed,回退旧路径
    }

    /** type0/1/4 HTTP 接口:按类型拼参(与 VM getList 旧逻辑同 API 等值)拉取后按 XML/JSON 解析 */
    private AbsXml categoryFromHttp(SourceBean sb, int type, String tid, String pg, Map<String, String> filterSelect) {
        try {
            String content;
            if (type == 4) {
                String ext;
                if (filterSelect != null && !filterSelect.isEmpty()) {
                    ext = android.util.Base64.encodeToString(
                            new org.json.JSONObject(filterSelect).toString().getBytes("UTF-8"),
                            android.util.Base64.DEFAULT | android.util.Base64.NO_WRAP);
                } else {
                    ext = android.util.Base64.encodeToString("{}".getBytes("UTF-8"),
                            android.util.Base64.DEFAULT | android.util.Base64.NO_WRAP);
                }
                Map<String, String> params = new HashMap<>();
                params.put("ac", "detail");
                params.put("filter", "true");
                params.put("t", tid);
                params.put("pg", pg);
                params.put("ext", ext);
                content = com.github.tvbox.osc.util.HttpClient.getSync(sb.getApi(), params, null);
            } else {
                Map<String, String> params = new HashMap<>();
                params.put("ac", type == 0 ? "videolist" : "detail");
                params.put("t", tid);
                params.put("pg", pg);
                if (filterSelect != null) {
                    for (Map.Entry<String, String> entry : filterSelect.entrySet()) {
                        params.put(entry.getKey(), entry.getValue());
                    }
                }
                params.put("f", (filterSelect == null || filterSelect.isEmpty())
                        ? "" : new org.json.JSONObject(filterSelect).toString());
                content = com.github.tvbox.osc.util.HttpClient.getSync(sb.getApi(), params, null);
            }
            if (content == null || content.isEmpty()) {
                android.util.Log.w("SpiderBridge", "category(typed/http): 内容为空 key=" + sb.getKey()
                        + " tid=" + tid + " pg=" + pg);
                return null;
            }
            AbsXml xml = type == 0
                    ? com.github.tvbox.osc.spiderapi.AbsXmlParser.parseXml(content, sb.getKey())
                    : com.github.tvbox.osc.spiderapi.AbsXmlParser.parseJson(content, sb.getKey());
            if (xml == null || xml.movie == null) {
                android.util.Log.w("SpiderBridge", "category(typed/http): 解析为空 key=" + sb.getKey()
                        + " tid=" + tid + " pg=" + pg);
                return null;
            }
            android.util.Log.d("SpiderBridge", "category(typed/http) 成功: key=" + sb.getKey()
                    + " tid=" + tid + " pg=" + pg + " type=" + type);
            return xml;
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "category(typed/http) 异常: key=" + sb.getKey()
                    + " tid=" + tid + " pg=" + pg, th);
            return null;
        }
    }

    @Override
    public AbsXml homeVideoContent(String sourceKey) {
        SourceBean sb = ApiConfig.get().getSource(sourceKey);
        if (sb == null || sb.getType() != 3) return null;
        String content = SpiderContentImpl.get().homeVideoContent(sourceKey);
        AbsXml xml = parse(sourceKey, content, "homeVideo(typed)");
        if (xml != null) {
            android.util.Log.d("SpiderBridge", "homeVideo(typed) 成功: key=" + sourceKey);
        }
        return xml;
    }
}

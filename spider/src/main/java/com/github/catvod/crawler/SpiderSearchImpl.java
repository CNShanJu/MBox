package com.github.catvod.crawler;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.spiderapi.AbsXmlParser;
import com.github.tvbox.osc.spiderapi.HttpSourceParams;
import com.github.tvbox.osc.spiderapi.SpiderSearchApi;
import com.github.tvbox.osc.util.HttpClient;

import java.util.Map;

/**
 * 强类型搜索实现:按源类型分发——
 * type3(JS/JAR)经 SpiderContentImpl 取 Spider 内容;type0/1/4(HTTP 接口)按类型拼参
 * 同步拉取后再解析。解析与 VM 共用同一权威实现 AbsXmlParser(parseXml/parseJson)。
 */
public final class SpiderSearchImpl implements SpiderSearchApi {

    private static final SpiderSearchImpl INSTANCE = new SpiderSearchImpl();

    public static SpiderSearchImpl get() {
        return INSTANCE;
    }

    @Override
    public AbsXml search(String sourceKey, String word, boolean quick) {
        if (sourceKey == null || word == null) {
            android.util.Log.w("SpiderBridge", "search(typed): 入参缺失 key=" + sourceKey + " word=" + word);
            return null;
        }
        try {
            SourceBean sb = ApiConfig.get().getSource(sourceKey);
            if (sb == null) {
                return null;
            }
            int type = sb.getType();
            if (type == 3) {
                return searchFromSpider(sourceKey, word, quick);
            }
            if (type == 0 || type == 1 || type == 4) {
                return searchFromHttp(sb, word, quick);
            }
            return null; // 其余类型不支持 typed,回退字符串通道
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "search(typed) 异常: key=" + sourceKey + " word=" + word, th);
            return null;
        }
    }

    /** type=3 JS/JAR:走 Spider 内容通道 */
    private AbsXml searchFromSpider(String sourceKey, String word, boolean quick) {
        try {
            String content = SpiderContentImpl.get().searchContent(sourceKey, word, quick);
            if (content == null || content.isEmpty()) {
                android.util.Log.w("SpiderBridge", "search(typed): 内容为空 key=" + sourceKey
                        + " word=" + word + " quick=" + quick);
                return null;
            }
            AbsXml xml = AbsXmlParser.parseJson(content, sourceKey);
            if (xml == null || xml.movie == null || xml.movie.videoList == null || xml.movie.videoList.isEmpty()) {
                android.util.Log.w("SpiderBridge", "search(typed): 解析为空/无列表 key=" + sourceKey
                        + " word=" + word + " quick=" + quick);
                return null;
            }
            android.util.Log.d("SpiderBridge", "search(typed/spider) 成功: key=" + sourceKey + " word=" + word
                    + " quick=" + quick + " hits=" + xml.movie.videoList.size());
            return xml;
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "search(typed/spider) 异常: key=" + sourceKey + " word=" + word, th);
            return null;
        }
    }

    /** type0/1/4 HTTP 接口:按类型拼参(与 VM 旧逻辑一致)拉取后按 XML/JSON 解析 */
    private AbsXml searchFromHttp(SourceBean sb, String word, boolean quick) {
        int type = sb.getType();
        try {
            Map<String, String> params = HttpSourceParams.search(type, word, quick);
            if (params == null) {
                return null;
            }
            String content = HttpClient.getSync(sb.getApi(), params, null);
            if (content == null || content.isEmpty()) {
                android.util.Log.w("SpiderBridge", "search(typed/http): 内容为空 key=" + sb.getKey()
                        + " word=" + word + " quick=" + quick);
                return null;
            }
            AbsXml xml = type == 0
                    ? AbsXmlParser.parseXml(content, sb.getKey())
                    : AbsXmlParser.parseJson(content, sb.getKey());
            if (xml == null || xml.movie == null || xml.movie.videoList == null || xml.movie.videoList.isEmpty()) {
                android.util.Log.w("SpiderBridge", "search(typed/http): 解析为空/无列表 key=" + sb.getKey()
                        + " word=" + word + " quick=" + quick);
                return null;
            }
            android.util.Log.d("SpiderBridge", "search(typed/http) 成功: key=" + sb.getKey()
                    + " word=" + word + " quick=" + quick + " hits=" + xml.movie.videoList.size()
                    + " type=" + type);
            return xml;
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "search(typed/http) 异常: key=" + sb.getKey() + " word=" + word, th);
            return null;
        }
    }
}

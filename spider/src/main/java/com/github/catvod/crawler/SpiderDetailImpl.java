package com.github.catvod.crawler;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.spiderapi.AbsXmlParser;
import com.github.tvbox.osc.spiderapi.SpiderDetailApi;
import com.github.tvbox.osc.util.HttpClient;

import java.util.Collections;
import java.util.Map;

/**
 * 强类型详情实现:按源类型分发——
 * type3(JS/JAR)经 SpiderContentImpl 取 Spider 内容;type0/1/4(HTTP 接口)按类型拼参
 * 同步拉取后再解析。解析与 VM 共用同一权威实现 AbsXmlParser(parseXml/parseJson),
 * 消除双实现漂移;app 只做 enrichment+post。
 */
public final class SpiderDetailImpl implements SpiderDetailApi {

    private static final SpiderDetailImpl INSTANCE = new SpiderDetailImpl();

    public static SpiderDetailImpl get() {
        return INSTANCE;
    }

    @Override
    public AbsXml detail(String sourceKey, String vodId) {
        if (sourceKey == null || vodId == null) {
            android.util.Log.w("SpiderBridge", "detail(typed): 入参缺失 key=" + sourceKey + " id=" + vodId);
            return null;
        }
        try {
            SourceBean sb = ApiConfig.get().getSource(sourceKey);
            if (sb == null) {
                return null;
            }
            int type = sb.getType();
            if (type == 3) {
                return detailFromSpider(sourceKey, vodId);
            }
            if (type == 0 || type == 1 || type == 4) {
                return detailFromHttp(sb, vodId);
            }
            return null; // 其余类型不支持 typed,回退字符串通道
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "detail(typed) 异常: key=" + sourceKey + " id=" + vodId, th);
            return null;
        }
    }

    /** type=3 JS/JAR:走 Spider 内容通道 */
    private AbsXml detailFromSpider(String sourceKey, String vodId) {
        try {
            String content = SpiderContentImpl.get().detailContent(sourceKey, Collections.singletonList(vodId));
            if (content == null || content.isEmpty()) {
                android.util.Log.w("SpiderBridge", "detail(typed): 内容为空 key=" + sourceKey + " id=" + vodId);
                return null;
            }
            AbsXml xml = AbsXmlParser.parseJson(content, sourceKey);
            if (xml == null || xml.movie == null) {
                android.util.Log.w("SpiderBridge", "detail(typed): 解析为空 key=" + sourceKey + " id=" + vodId);
                return null;
            }
            android.util.Log.d("SpiderBridge", "detail(typed/spider) 成功: key=" + sourceKey + " id=" + vodId);
            return xml;
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "detail(typed/spider) 异常: key=" + sourceKey + " id=" + vodId, th);
            return null;
        }
    }

    /** type0/1/4 HTTP 接口:按类型拼参(与 VM 旧逻辑一致)拉取后按 XML/JSON 解析 */
    private AbsXml detailFromHttp(SourceBean sb, String vodId) {
        int type = sb.getType();
        try {
            Map<String, String> params =
                    com.github.tvbox.osc.spiderapi.HttpSourceParams.detail(type, vodId);
            if (params == null) {
                return null;
            }
            String content = HttpClient.getSync(sb.getApi(), params, null);
            if (content == null || content.isEmpty()) {
                android.util.Log.w("SpiderBridge", "detail(typed/http): 内容为空 key=" + sb.getKey() + " id=" + vodId);
                return null;
            }
            AbsXml xml = type == 0
                    ? AbsXmlParser.parseXml(content, sb.getKey())
                    : AbsXmlParser.parseJson(content, sb.getKey());
            if (xml == null || xml.movie == null) {
                android.util.Log.w("SpiderBridge", "detail(typed/http): 解析为空 key=" + sb.getKey() + " id=" + vodId);
                return null;
            }
            android.util.Log.d("SpiderBridge", "detail(typed/http) 成功: key=" + sb.getKey()
                    + " id=" + vodId + " type=" + type);
            return xml;
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "detail(typed/http) 异常: key=" + sb.getKey() + " id=" + vodId, th);
            return null;
        }
    }
}

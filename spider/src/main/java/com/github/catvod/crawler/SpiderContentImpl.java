package com.github.catvod.crawler;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.spiderapi.SpiderContentApi;

import java.util.List;
import java.util.Map;

/**
 * :spider 侧对 SpiderContentApi 的实现:桥接 ApiConfig.getCSP(JS/JAR 源)。
 * App 组合根启动时注入 SpiderContentProviders。
 */
public final class SpiderContentImpl implements SpiderContentApi {

    private static final SpiderContentImpl INSTANCE = new SpiderContentImpl();

    public static SpiderContentImpl get() {
        return INSTANCE;
    }

    private static Spider spiderOf(SourceBean sb) {
        return sb == null ? null : ApiConfig.get().getCSP(sb);
    }

    @Override
    public String homeContent(String sourceKey, boolean filter) {
        try {
            Spider sp = spiderOf(ApiConfig.get().getSource(sourceKey));
            return sp == null ? null : sp.homeContent(filter);
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "SpiderContent 调用异常", th);
            return null;
        }
    }

    @Override
    public String homeVideoContent(String sourceKey) {
        try {
            Spider sp = spiderOf(ApiConfig.get().getSource(sourceKey));
            return sp == null ? null : sp.homeVideoContent();
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "SpiderContent 调用异常", th);
            return null;
        }
    }

    @Override
    public String categoryContent(String sourceKey, String tid, String pg, boolean filter, Map<String, String> extend) {
        try {
            Spider sp = spiderOf(ApiConfig.get().getSource(sourceKey));
            if (sp == null) return null;
            java.util.HashMap<String, String> ext = extend == null ? new java.util.HashMap<>() : new java.util.HashMap<>(extend);
            return sp.categoryContent(tid, pg, filter, ext);
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "SpiderContent 调用异常", th);
            return null;
        }
    }

    @Override
    public String detailContent(String sourceKey, List<String> ids) {
        try {
            Spider sp = spiderOf(ApiConfig.get().getSource(sourceKey));
            return sp == null ? null : sp.detailContent(ids);
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "SpiderContent 调用异常", th);
            return null;
        }
    }

    @Override
    public String searchContent(String sourceKey, String word, boolean quick) {
        try {
            Spider sp = spiderOf(ApiConfig.get().getSource(sourceKey));
            return sp == null ? null : sp.searchContent(word, quick);
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "SpiderContent 调用异常", th);
            return null;
        }
    }

    @Override
    public String playerContent(String sourceKey, String flag, String id, List<String> vipFlags) {
        try {
            Spider sp = spiderOf(ApiConfig.get().getSource(sourceKey));
            return sp == null ? null : sp.playerContent(flag, id, vipFlags);
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "SpiderContent 调用异常", th);
            return null;
        }
    }

    /** 带页码搜索(聚合搜索翻页):JS/JAR 源按 Spider.searchContent(key, quick, pg) 取下一页 */
    @Override
    public String searchContent(String sourceKey, String word, boolean quick, String pg) {
        try {
            Spider sp = spiderOf(ApiConfig.get().getSource(sourceKey));
            if (sp == null) return null;
            if (pg == null || pg.isEmpty()) return sp.searchContent(word, quick);
            return sp.searchContent(word, quick, pg);
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "SpiderContent 调用异常(翻页搜索)", th);
            return null;
        }
    }
}

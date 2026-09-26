package com.github.catvod.crawler;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.spiderapi.SpiderManualCheckApi;

/** :spider 侧手动视频判定实现:桥接 ApiConfig.getCSP(UI/下载侧不再直接拿具体 Spider) */
public final class SpiderManualCheckImpl implements SpiderManualCheckApi {

    private static final SpiderManualCheckImpl INSTANCE = new SpiderManualCheckImpl();

    public static SpiderManualCheckImpl get() {
        return INSTANCE;
    }

    @Override
    public Boolean manualVideoCheck(String sourceKey, String url) {
        try {
            SourceBean sb = ApiConfig.get().getSource(sourceKey);
            Spider sp = sb == null ? null : ApiConfig.get().getCSP(sb);
            if (sp == null) {
                android.util.Log.w("SpiderBridge", "manualVideoCheck: 源缺失或无 Spider key=" + sourceKey);
                return null;
            }
            if (!sp.manualVideoCheck()) return null;
            Boolean r = sp.isVideoFormat(url);
            android.util.Log.d("SpiderBridge", "manualVideoCheck: key=" + sourceKey + " url=" + url + " -> " + r);
            return r;
        } catch (Throwable th) {
            android.util.Log.w("SpiderBridge", "manualVideoCheck 异常: " + sourceKey, th);
            return null;
        }
    }
}

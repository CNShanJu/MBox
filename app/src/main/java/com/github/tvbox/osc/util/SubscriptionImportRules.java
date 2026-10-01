package com.github.tvbox.osc.util;

import com.github.tvbox.osc.spiderapi.CmsApiRules;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.Locale;

import okhttp3.HttpUrl;

/** 订阅的手动、文件与 JSON 导入共用的内容和地址判定。 */
public final class SubscriptionImportRules {
    private SubscriptionImportRules() {
    }

    public static boolean isSupportedAddress(String address) {
        if (address == null) return false;
        String value = address.trim();
        if (value.isEmpty()) return false;
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return HttpUrl.parse(value) != null;
        }
        try {
            URI uri = new URI(value);
            if (!"clan".equalsIgnoreCase(uri.getScheme()) || !"localhost".equalsIgnoreCase(uri.getHost())) {
                return false;
            }
            String path = uri.getPath();
            if (path == null || path.length() <= 1) return false;
            for (String part : path.split("/")) {
                if ("..".equals(part) || ".".equals(part)) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 常见的访问拒绝响应不是站点页面，不应继续探测采集接口。 */
    public static boolean isAccessDeniedResponse(String body) {
        if (body == null) return false;
        String text = body.trim().toLowerCase(Locale.ROOT);
        if (text.length() > 2048) return false;
        return text.equals("request forbidden") || text.equals("access denied")
                || text.equals("forbidden") || text.equals("403 forbidden")
                || text.matches("(?s).*<title>\\s*(?:403\\s+)?forbidden\\s*</title>.*");
    }

    /** 排除把「阅读」书源或完整配置中的 name/url 误当订阅清单条目。 */
    public static boolean mayBeListEntry(JsonObject entry) {
        if (entry == null || CmsApiRules.looksLikeBookSource(entry.toString())) return false;
        for (String key : entry.keySet()) {
            if (key.startsWith("rule") || "sites".equals(key) || "lives".equals(key)
                    || "spider".equals(key) || "parses".equals(key)
                    || "storeHouse".equals(key) || "rules".equals(key)) {
                return false;
            }
        }
        return true;
    }

    /** 内嵌文件内容按普通本地/JSON 配置的规则识别，裸站点先补 sites 外壳。 */
    public static String normalizedContent(String content) {
        if (content == null) return null;
        String text = content.trim();
        if (text.isEmpty()) return null;
        String wrapped = CmsApiRules.wrapSiteJson(text);
        if (wrapped != null) return wrapped;
        int shape = CmsApiRules.subscriptionShape(text);
        return shape == CmsApiRules.SHAPE_CONFIG || shape == CmsApiRules.SHAPE_ENCRYPTED ? text : null;
    }
}

package com.github.tvbox.osc.update;

import java.net.URI;
import java.util.Locale;

/** 将下载器当前使用的候选 URL 转成面板中易读的链路名称。 */
final class UpdateDownloadRoute {

    private UpdateDownloadRoute() {
    }

    static String describe(String url) {
        if (url == null || url.trim().isEmpty()) return "未知";
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return "未知";
            host = host.toLowerCase(Locale.ROOT);
            String path = uri.getRawPath();
            String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
            if (isHost(host, "gitee.com")) return "Gitee（" + host + "）";
            // 代理 URL 会把 GitHub 原地址放在路径内，不能只搜索整个 URL 中的 github.com。
            if (lowerPath.contains("https://github.com/")) return "代理（" + host + "）";
            if (isHost(host, "github.com")) return "GitHub（" + host + "）";
            return "其他（" + host + "）";
        } catch (IllegalArgumentException e) {
            return "未知";
        }
    }

    private static boolean isHost(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}

package com.github.tvbox.osc.download.internal;

import java.net.URI;
import com.github.tvbox.osc.bean.DownloadTask;
import java.util.List;

/** 特殊站点共用两个下载槽，通用站点沿用用户配置；主清单及原集页都参与判断。 */
public final class DownloadConcurrency {
    private DownloadConcurrency() {}
    public static boolean isChaoxing(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.equals("chaoxing.com") || host.endsWith(".chaoxing.com")
                    || host.equals("chaoxing.cn") || host.endsWith(".chaoxing.cn");
        } catch (Exception e) { return false; }
    }
    static boolean scoped(DownloadTask t) { return isChaoxing(t.url) || isChaoxing(t.episodeRawUrl); }
    static boolean canStart(DownloadTask candidate, List<DownloadTask> tasks) {
        if (!scoped(candidate)) return true;
        int active = 0;
        for (DownloadTask t : tasks) if (t.state == DownloadTask.STATE_DOWNLOADING && scoped(t)) active++;
        return active < 2;
    }
}

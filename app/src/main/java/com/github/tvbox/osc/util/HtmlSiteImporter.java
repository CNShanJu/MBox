package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.github.tvbox.osc.spiderapi.CmsApiRules;
import com.github.tvbox.osc.spiderapi.HtmlSiteRules;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 影视站"抓页面"接入:站点采集接口关闭/不开放时,按苹果CMS(MacCMS)页面结构实探一遍
 * (首页分类 → 分类页 → 详情页 → 播放页),探通了就生成一份指向 {@code assets://js/lib/maccms.js}
 * 通用抓取运行时的单源配置,由调用方以 clan:// 方式加入订阅。
 * <p>
 * 与 {@link CmsSiteImporter} 的分工:后者找"采集接口"(有接口优先,分类/搜索更全);本类只抓页面,
 * 作为接口不可用时的兜底。页面结构识别在 {@link HtmlSiteRules}(纯字符串逻辑,可 JVM 单测),
 * 本类只负责网络实探与落盘,后台任务走 {@link HeavyTaskUtil} 共享执行器,回调统一切回主线程。
 * <p>
 * 探不通(不是苹果CMS 站 / 结构不支持 / 播放页拿不到地址)一律 {@link Callback#onNotFound()}:
 * 宁可不加,也不生成一个"能选但打不开"的坏订阅(参见"导入内容不是订阅配置"那类线上投诉)。
 */
public final class HtmlSiteImporter {

    /** 抓取源运行时(App 资源里的通用模板) */
    public static final String RUNTIME_API = "assets://js/lib/maccms.js";

    /** 探测结果:站点名 + 生成的配置文件 + 命中的播放页地址(便于日志核对) */
    public interface Callback {
        void onFound(String siteName, File configFile, String samplePlayUrl);

        /** 没探通:调用方据此提示用户 */
        void onNotFound();
    }

    /** 探测进度(主线程回调):多步探测要几秒,调用方据此更新加载框文案 */
    public interface Progress {
        void onStep(String hint);
    }

    /** 总时长上限:首页/分类/详情/播放/搜索多步相加,不能让用户等太久 */
    private static final long BUDGET_MS = 30000;

    /** 最多试几个子目录候选(子目录猜错会白跑好几步) */
    private static final int MAX_PREFIX_CANDIDATES = 4;

    /** 分类页最多试几个分类(有的分类是空的) */
    private static final int MAX_CLASS_TRY = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Map<String, String> PROBE_HEADERS = Collections.unmodifiableMap(buildHeaders());

    private HtmlSiteImporter() {
    }

    /**
     * 实探站点并生成抓取源配置。
     *
     * @param inputUrl  用户填入/书源里写的站点地址(可带二级路径)
     * @param knownText 已知内容(书源 JSON 文本或已抓到的页面):用于推断站点子目录(如 /jiejie),可为 null
     * @param outDir    生成的配置落盘目录(应用专属外部存储:clan 本地服务器可读,无需存储权限)
     * @param progress  可为 null;已在主线程回调
     */
    public static void probe(final String inputUrl, final String knownText, final File outDir,
                             final Callback callback, final Progress progress) {
        if (callback == null) return;
        if (inputUrl == null || inputUrl.trim().isEmpty()) {
            callback.onNotFound();
            return;
        }
        HeavyTaskUtil.getBigTaskExecutorService().execute(new Runnable() {
            @Override
            public void run() {
                Result result = null;
                try {
                    result = scan(inputUrl.trim(), knownText, outDir, progress);
                } catch (Throwable th) {
                    com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.SUBSCRIPTION, "订阅导入: 抓页面探测异常 " + inputUrl + " " + th);
                }
                final Result r = result;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (r == null) callback.onNotFound();
                        else callback.onFound(r.siteName, r.file, r.samplePlayUrl);
                    }
                });
            }
        });
    }

    private static Result scan(String inputUrl, String knownText, File outDir, Progress progress) {
        long deadline = SystemClock.uptimeMillis() + BUDGET_MS;
        String host = hostOf(inputUrl);
        if (host == null) return null;

        // 站点可能挂在子目录下(用户/书源常只写到域名),根目录还可能是无关落地页:
        // 先抓根目录,再从书源文本、输入地址、根页面链接里收集子目录候选
        String rootHtml = fetch(HtmlSiteRules.homeUrl(host, ""));
        LinkedHashSet<String> prefixes = new LinkedHashSet<>();
        addPrefix(prefixes, HtmlSiteRules.subdirHint(knownText == null ? "" : knownText));
        addPrefix(prefixes, HtmlSiteRules.subdirHint(inputUrl));
        addPrefix(prefixes, HtmlSiteRules.subdirHint(rootHtml));
        addPrefix(prefixes, "");    // 根目录兜底

        int tried = 0;
        for (String prefix : prefixes) {
            if (tried++ >= MAX_PREFIX_CANDIDATES || expired(deadline)) break;
            reportProgress(progress, "正在识别站点结构 " + host + prefix);
            String home = prefix.isEmpty() ? rootHtml : fetch(HtmlSiteRules.homeUrl(host, prefix));
            if (home == null || home.isEmpty()) continue;
            LinkedHashMap<String, String> classes = HtmlSiteRules.classes(home);
            if (classes.isEmpty()) continue;

            // 分类页 → 详情页 → 播放页 整条链路都要探通,才算"能抓";
            // 列表路由按候选逐个试(v10 默认 / show 写法 / 伪静态),探通哪个就把哪个模板写进配置
            String categoryUrl = null;
            HtmlSiteRules.Route listRoute = null;
            String detailUrl = null;
            List<HtmlSiteRules.Probe> plan = HtmlSiteRules.listProbePlan(prefix, new ArrayList<>(classes.keySet()),
                    MAX_CLASS_TRY);
            for (HtmlSiteRules.Probe probe : plan) {
                if (expired(deadline)) break;
                String url = HtmlSiteRules.siteUrl(host, prefix, probe.url);
                reportProgress(progress, "正在读取分类页 " + CmsApiRules.displayHost(url));
                List<String> details = HtmlSiteRules.detailHrefs(fetch(url), 1);
                if (details.isEmpty()) continue;
                categoryUrl = url;
                listRoute = probe.route;
                detailUrl = absolute(host, prefix, details.get(0));
                break;
            }
            if (detailUrl == null || listRoute == null) continue;

            reportProgress(progress, "正在读取详情页 " + CmsApiRules.displayHost(detailUrl));
            String detailHtml = fetch(detailUrl);
            String vodId = HtmlSiteRules.vodIdOfDetailUrl(detailUrl);
            List<String> plays = HtmlSiteRules.playHrefs(detailHtml, vodId);
            if (plays.isEmpty()) continue;
            String playPageUrl = absolute(host, prefix, plays.get(0));

            reportProgress(progress, "正在读取播放页 " + CmsApiRules.displayHost(playPageUrl));
            String media = HtmlSiteRules.playerUrl(fetch(playPageUrl));
            if (media == null || media.isEmpty()) continue;

            // 搜索:站点名/分类名前两个字当关键词实搜一次,搜得出来才把搜索地址与模板写进配置
            String keyword = searchKeyword(HtmlSiteRules.siteName(home, host), classes);
            HtmlSiteRules.Route searchRoute = null;
            reportProgress(progress, "正在验证站点搜索");
            for (HtmlSiteRules.Probe probe : HtmlSiteRules.searchProbePlan(prefix, encode(keyword))) {
                if (expired(deadline)) break;
                String url = HtmlSiteRules.siteUrl(host, prefix, probe.url);
                if (HtmlSiteRules.detailHrefs(fetch(url), 1).isEmpty()) continue;
                searchRoute = probe.route;
                break;
            }

            String siteName = HtmlSiteRules.siteName(home, host);
            String key = "maccms_" + CmsApiRules.siteKey(host);
            String extJson = HtmlSiteRules.buildExtJson(siteName, host, prefix, limit(classes, 30),
                    listRoute.listUrl, listRoute.listPageUrl,
                    searchRoute == null ? null : searchRoute.listUrl,
                    searchRoute == null ? null : searchRoute.listPageUrl);
            String json = CmsApiRules.buildSubscriptionJson(key, siteName, 3, RUNTIME_API, extJson,
                    searchRoute != null ? 1 : 0, 1, 0);
            File dest = write(outDir, key, json);
            if (dest == null) return null;
            com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SUBSCRIPTION, "订阅导入: 抓页面接入成功 " + siteName + " " + host + prefix
                    + " 分类=" + classes.size() + " 列表路由=" + listRoute
                    + " 搜索路由=" + (searchRoute == null ? "无" : searchRoute.listUrl)
                    + " 示例播放页=" + playPageUrl + " 分类页=" + categoryUrl
                    + " -> " + dest.getAbsolutePath());
            return new Result(siteName, dest, playPageUrl);
        }
        return null;
    }

    /** 生成的配置落盘(UTF-8) */
    private static File write(File outDir, String key, String json) {
        File dest = new File(outDir, key + ".json");
        try {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new java.io.IOException("mkdirs failed");
            }
            if (dest.exists() && !dest.canWrite()) dest.setWritable(true);
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(dest), "UTF-8");
            try {
                writer.write(json);
            } finally {
                writer.close();
            }
        } catch (Throwable th) {
            com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.SUBSCRIPTION, "订阅导入: 抓取源配置写入失败 " + dest.getAbsolutePath() + " " + th);
            return null;
        }
        return dest;
    }

    /** 页面里的链接绝对化(站点根相对 / 相对路径两种写法都收) */
    private static String absolute(String host, String prefix, String href) {
        if (href == null || href.isEmpty()) return null;
        if (href.regionMatches(true, 0, "http", 0, 4)) return href;
        if (href.startsWith("/")) return host + href;
        return host + (prefix == null ? "" : prefix) + "/" + href;
    }

    /** 搜索验证用的关键词:站点名前 2 个字,取不到用首个分类名前 2 个字 */
    private static String searchKeyword(String siteName, LinkedHashMap<String, String> classes) {
        String k = shortKeyword(siteName);
        if (!k.isEmpty()) return k;
        for (String name : classes.values()) {
            k = shortKeyword(name);
            if (!k.isEmpty()) return k;
        }
        return "电影";
    }

    private static String shortKeyword(String s) {
        if (s == null) return "";
        String v = s.trim().replaceAll("[\\s\\-_|—·,，、/]", "");
        if (v.length() < 2) return "";
        return v.substring(0, 2);
    }

    private static LinkedHashMap<String, String> limit(LinkedHashMap<String, String> classes, int max) {
        if (classes.size() <= max) return classes;
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : classes.entrySet()) {
            if (out.size() >= max) break;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static void addPrefix(LinkedHashSet<String> prefixes, String prefix) {
        if (prefix == null) return;
        String v = prefix.trim();
        if (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        if (v.isEmpty() || "/".equals(v)) {
            prefixes.add("");
            return;
        }
        prefixes.add(v);
    }

    private static String encode(String keyword) {
        try {
            return java.net.URLEncoder.encode(keyword, "UTF-8");
        } catch (Throwable th) {
            return keyword;
        }
    }

    private static String fetch(String url) {
        if (url == null || url.isEmpty()) return "";
        String html = HttpClient.getQuietly(url, PROBE_HEADERS);
        return html == null ? "" : html;
    }

    private static boolean expired(long deadline) {
        return SystemClock.uptimeMillis() > deadline;
    }

    private static void reportProgress(final Progress progress, final String hint) {
        if (progress == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                progress.onStep(hint);
            }
        });
    }

    private static String hostOf(String url) {
        int scheme = url.indexOf("//");
        if (scheme < 0) return null;
        int start = scheme + 2;
        int end = start;
        while (end < url.length() && url.charAt(end) != '/' && url.charAt(end) != '?'
                && url.charAt(end) != '#') {
            end++;
        }
        return end > start ? url.substring(0, end) : null;
    }

    private static Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", HtmlSiteRules.browserUserAgent());
        return headers;
    }

    private static final class Result {
        final String siteName;
        final File file;
        final String samplePlayUrl;

        Result(String siteName, File file, String samplePlayUrl) {
            this.siteName = siteName;
            this.file = file;
            this.samplePlayUrl = samplePlayUrl;
        }
    }
}

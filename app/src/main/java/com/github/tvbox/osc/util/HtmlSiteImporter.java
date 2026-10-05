package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.github.tvbox.osc.spiderapi.CmsApiRules;
import com.github.tvbox.osc.spiderapi.HtmlSiteRules;
import com.github.tvbox.osc.transfer.SubscriptionImportFiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /** 探测结果:站点名 + 生成的配置文件 + 命中的播放页地址 */
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
    private static final int MAX_DECLARED_CLASSES = 30;
    private static final int MAX_HINT_PROBES = 8;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Map<String, String> PROBE_HEADERS = Collections.unmodifiableMap(buildHeaders());

    private HtmlSiteImporter() {
    }

    /**
     * 实探站点并生成抓取源配置。
     *
     * @param inputUrl  用户填入/书源里写的站点地址(可带二级路径)
     * @param knownText 已知内容(书源 JSON 文本或已抓到的页面):推断子目录;
     *                  书源 sortUrl 还提供分类与搜索地址,可为 null
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
                } catch (Throwable ignored) {
                    com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.SUBSCRIPTION, "订阅导入: 抓页面探测异常");
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

        BookSource bookSource = bookSource(knownText, host);
        // 书源已明确列出分类路径时，先走这些地址；无需等可能无关或不可达的站点根首页。
        String rootHtml = bookSource != null && !bookSource.sortUrl.isEmpty()
                ? "" : fetch(HtmlSiteRules.homeUrl(host, ""));
        List<String> prefixes = HtmlSiteRules.prefixCandidates(inputUrl, knownText, rootHtml,
                MAX_PREFIX_CANDIDATES);
        boolean readHome = rootHtml != null && !rootHtml.isEmpty();
        boolean foundClasses = false;
        boolean foundDetail = false;
        boolean foundPlayPage = false;

        int tried = 0;
        for (String prefix : prefixes) {
            if (tried++ >= MAX_PREFIX_CANDIDATES || expired(deadline)) break;
            reportProgress(progress, "正在识别站点结构 " + host + prefix);
            HtmlSiteRules.SortUrlHints hints = HtmlSiteRules.sortUrlHints(
                    bookSource == null ? null : bookSource.sortUrl, host, prefix, MAX_DECLARED_CLASSES);
            String home = hints.classes.isEmpty()
                    ? (prefix.isEmpty() ? rootHtml : fetch(HtmlSiteRules.homeUrl(host, prefix))) : "";
            if (home != null && !home.isEmpty()) readHome = true;
            LinkedHashMap<String, String> classes = new LinkedHashMap<>(hints.classes);
            for (Map.Entry<String, String> entry : HtmlSiteRules.classes(home).entrySet()) {
                if (!classes.containsKey(entry.getKey())) classes.put(entry.getKey(), entry.getValue());
            }
            if (classes.isEmpty()) continue;
            foundClasses = true;

            // 分类页 → 详情页 → 播放页 整条链路都要探通,才算"能抓";
            // 列表路由按候选逐个试(v10 默认 / show 写法 / 伪静态),探通哪个就把哪个模板写进配置
            HtmlSiteRules.Route listRoute = null;
            String detailUrl = null;
            List<HtmlSiteRules.Probe> plan = new ArrayList<>(hints.classProbes.subList(0,
                    Math.min(hints.classProbes.size(), MAX_HINT_PROBES)));
            for (HtmlSiteRules.Probe probe : HtmlSiteRules.listProbePlan(prefix,
                    new ArrayList<>(classes.keySet()), MAX_CLASS_TRY)) {
                boolean duplicate = false;
                for (HtmlSiteRules.Probe existing : plan) {
                    if (existing.url.equals(probe.url)) { duplicate = true; break; }
                }
                if (!duplicate) plan.add(probe);
            }
            for (HtmlSiteRules.Probe probe : plan) {
                if (expired(deadline)) break;
                String url = HtmlSiteRules.siteUrl(host, prefix, probe.url);
                reportProgress(progress, "正在读取分类页 " + CmsApiRules.displayHost(url));
                List<String> details = HtmlSiteRules.detailHrefs(fetch(url), 1);
                if (details.isEmpty()) continue;
                listRoute = probe.route;
                detailUrl = absolute(host, prefix, details.get(0));
                break;
            }
            if (detailUrl == null || listRoute == null) continue;
            foundDetail = true;

            reportProgress(progress, "正在读取详情页 " + CmsApiRules.displayHost(detailUrl));
            String detailHtml = fetch(detailUrl);
            String vodId = HtmlSiteRules.vodIdOfDetailUrl(detailUrl);
            List<String> plays = HtmlSiteRules.playHrefs(detailHtml, vodId);
            if (plays.isEmpty()) continue;
            foundPlayPage = true;
            String playPageUrl = absolute(host, prefix, plays.get(0));

            reportProgress(progress, "正在读取播放页 " + CmsApiRules.displayHost(playPageUrl));
            String media = HtmlSiteRules.playerUrl(fetch(playPageUrl));
            if (media == null || media.isEmpty()) continue;

            // 搜索:站点名/分类名前两个字当关键词实搜一次,搜得出来才把搜索地址与模板写进配置
            String siteName = bookSource != null && !bookSource.name.isEmpty()
                    ? bookSource.name : HtmlSiteRules.siteName(home, host);
            String keyword = searchKeyword(HtmlSiteRules.siteName(detailHtml, host), classes);
            HtmlSiteRules.Route searchRoute = null;
            reportProgress(progress, "正在验证站点搜索");
            if (hints.searchRoute != null && !expired(deadline)) {
                String url = HtmlSiteRules.siteUrl(host, prefix,
                        HtmlSiteRules.fill(hints.searchRoute.listUrl, "{key}", encode(keyword)));
                if (!HtmlSiteRules.detailHrefs(fetch(url), 1).isEmpty()) searchRoute = hints.searchRoute;
            }
            for (HtmlSiteRules.Probe probe : HtmlSiteRules.searchProbePlan(prefix, encode(keyword))) {
                if (searchRoute != null) break;
                if (expired(deadline)) break;
                String url = HtmlSiteRules.siteUrl(host, prefix, probe.url);
                if (HtmlSiteRules.detailHrefs(fetch(url), 1).isEmpty()) continue;
                searchRoute = probe.route;
                break;
            }

            String key = "maccms_" + CmsApiRules.siteKey(host);
            String extJson = HtmlSiteRules.buildExtJson(siteName, host, prefix, limit(classes, 30),
                    listRoute.listUrl, listRoute.listPageUrl,
                    searchRoute == null ? null : searchRoute.listUrl,
                    searchRoute == null ? null : searchRoute.listPageUrl, hints.classRoutes);
            String json = CmsApiRules.buildSubscriptionJson(key, siteName, 3, RUNTIME_API, extJson,
                    searchRoute != null ? 1 : 0, 1, 0);
            File dest = write(outDir, key, json);
            if (dest == null) return null;
            com.github.tvbox.osc.log.LogStore.success(com.github.tvbox.osc.log.Category.SUBSCRIPTION,
                    "订阅导入: 抓页面接入成功 分类=" + classes.size()
                            + " 搜索=" + (searchRoute == null ? "未探通" : "已验证"));
            return new Result(siteName, dest, playPageUrl);
        }
        String stage = !foundClasses ? (readHome ? "首页未识别到分类" : "首页均未读到内容")
                : !foundDetail ? "分类页未识别到详情"
                : !foundPlayPage ? "详情页未识别到播放链接"
                : "播放页未取得媒体地址";
        com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.SUBSCRIPTION,
                "订阅导入: 抓页面未探通 阶段=" + stage);
        return null;
    }

    private static BookSource bookSource(String text, String host) {
        if (text == null || !CmsApiRules.looksLikeBookSource(text)) return null;
        try {
            JsonElement root = JsonParser.parseString(text);
            JsonObject obj = root.isJsonObject() ? root.getAsJsonObject()
                    : root.isJsonArray() && root.getAsJsonArray().size() > 0
                    && root.getAsJsonArray().get(0).isJsonObject()
                    ? root.getAsJsonArray().get(0).getAsJsonObject() : null;
            if (obj == null) return null;
            String sourceUrl = stringField(obj, "sourceUrl");
            if (sourceUrl.isEmpty()) sourceUrl = stringField(obj, "bookSourceUrl");
            if (!host.equalsIgnoreCase(hostOf(sourceUrl))) return null;
            return new BookSource(stringField(obj, "sourceName"), stringField(obj, "sortUrl"));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String stringField(JsonObject obj, String key) {
        JsonElement field = obj.get(key);
        return field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isString()
                ? field.getAsString().trim() : "";
    }

    private static final class BookSource {
        final String name;
        final String sortUrl;

        BookSource(String name, String sortUrl) {
            this.name = name;
            this.sortUrl = sortUrl;
        }
    }

    /** 生成的配置落盘(UTF-8) */
    private static File write(File outDir, String key, String json) {
        File dest = new File(outDir, key + ".json");
        try {
            SubscriptionImportFiles.runLocked(() -> {
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
                return null;
            });
        } catch (Throwable ignored) {
            com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.SUBSCRIPTION, "订阅导入: 抓取源配置写入失败");
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

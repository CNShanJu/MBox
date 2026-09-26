package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.github.tvbox.osc.spiderapi.CmsApiRules;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 资源站(苹果CMS/MacCMS 系)采集接口嗅探:用户只填了站点地址时,从页面(必要时翻其"帮助中心")
 * 扒出采集地址并逐个实探,命中后在本地生成一份只含该源的最小订阅配置,由调用方以 clan:// 方式加入订阅。
 * <p>
 * 识别规则集中在 {@link CmsApiRules}(纯字符串逻辑,可 JVM 单测);本类只负责网络试探与落盘,
 * 后台任务走 {@link HeavyTaskUtil} 共享执行器,回调统一切回主线程。
 */
public final class CmsSiteImporter {

    /** 嗅探结果:建议站点名 + 生成的配置文件 + 命中的采集接口地址 */
    public interface Callback {
        void onFound(String siteName, File configFile, String api);

        /** 没找到能用的采集接口:调用方据此提示用户,不要再把站点地址当订阅配置存进去 */
        void onNotFound();
    }

    /** 输入形态:决定要不要再拉一次页面、以及候选地址怎么排 */
    public enum InputKind {
        /** 用户粘的就是采集接口地址(如 .../api.php/provide/vod/):只需实探一次 */
        API,
        /** 站点首页/栏目页/说明页,或页面响应不可用但地址像站点:按候选路径逐个实探 */
        SITE
    }

    /**
     * 探测进度(主线程回调):嗅探要逐个实探候选接口,可能十几秒,调用方据此更新加载框文案,
     * 避免长时间无反馈被误认为"点了没反应"。
     */
    public interface Progress {
        /** @param done 已试个数(从 1 起) @param total 候选总数 @param url 正在试的地址 */
        void onProbe(int done, int total, String url);
    }

    /**
     * 按输入形态嗅探(通用入口:用户丢进来的可能是站点首页、栏目页,也可能是采集接口)。
     *
     * @param inputUrl 用户填入的地址(站点或接口)
     * @param pageHtml 已知的该地址响应内容;为 null 时本方法会自己拉一次(网络失败则直接按站点候选试探)
     * @param outDir   生成的订阅配置落盘目录(应用专属外部存储:clan 本地服务器可读,无需存储权限)
     */
    public static void probeInput(final String inputUrl, final String pageHtml, final InputKind kind,
                                  final File outDir, final Callback callback) {
        probeInput(inputUrl, pageHtml, kind, outDir, callback, null);
    }

    /**
     * 同上,额外支持进度回调。
     *
     * @param progress 可为 null;已在主线程回调
     */
    public static void probeInput(final String inputUrl, final String pageHtml, final InputKind kind,
                                  final File outDir, final Callback callback, final Progress progress) {
        HeavyTaskUtil.getBigTaskExecutorService().execute(new Runnable() {
            @Override
            public void run() {
                Result result = null;
                try {
                    if (kind == InputKind.API) {
                        result = probeApi(inputUrl, outDir);
                    } else {
                        result = scan(inputUrl, pageHtml, outDir, progress);
                    }
                } catch (Throwable th) {
                    com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.SUBSCRIPTION, "订阅导入: 资源站嗅探异常 " + inputUrl + " " + th);
                }
                final Result r = result;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback == null) return;
                        if (r == null) callback.onNotFound();
                        else callback.onFound(r.siteName, r.file, r.api);
                    }
                });
            }
        });
    }

    /** 用户直接粘采集接口:实探一次(必要时补 ac 参数),通了就生成单源配置 */
    private static Result probeApi(String apiUrl, File outDir) {
        if (apiUrl == null || apiUrl.trim().isEmpty()) return null;
        String api = apiUrl.trim();
        int kind = CmsApiRules.detectKind(get(api));
        if (kind < 0) kind = CmsApiRules.detectKind(get(withAcParam(api)));
        if (kind < 0) return null;
        return write(CmsApiRules.displayHost(api), api, kind, outDir);
    }

    /**
     * 总时长上限。单次请求已由 HttpClient 的探测客户端收紧到秒级,
     * 这里只兜住"候选地址多又都慢"的极端站点。
     */
    private static final long BUDGET_MS = 25000;

    /** 部分资源站挂在 CDN 后按 UA 拦 okhttp 字样,探测一律用浏览器 UA */
    private static final String PROBE_UA = "Mozilla/5.0 (Linux; Android 12.0; Mobile) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final Map<String, String> PROBE_HEADERS =
            Collections.unmodifiableMap(buildHeaders());

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CmsSiteImporter() {
    }

    private static Result scan(String siteUrl, String pageHtml, File outDir, final Progress progress) {
        long deadline = SystemClock.uptimeMillis() + BUDGET_MS;
        // 调用方没给页面内容(如"添加订阅"初次拉取失败)时自己补一次,拿不到也不影响按默认候选继续探;
        // 已给内容就绝不重复拉取(那会白白多耗一个网络往返,用户看到的就是"点了没反应")
        String html = pageHtml == null ? get(siteUrl) : pageHtml;
        if (html == null) html = "";

        LinkedHashSet<String> declared =
                new LinkedHashSet<>(CmsApiRules.apiUrlsFromPage(html, siteUrl));
        if (declared.isEmpty()) {
            // 首页一般不写接口,只在"帮助中心/采集说明"里公布(红牛即如此),故二级页再扒一次
            for (String doc : CmsApiRules.docPageUrls(siteUrl, html)) {
                if (expired(deadline)) break;
                declared.addAll(CmsApiRules.apiUrlsFromPage(get(doc), siteUrl));
            }
        }
        List<String> candidates = CmsApiRules.candidates(siteUrl, new ArrayList<>(declared));
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SUBSCRIPTION, "订阅导入: 嗅探 " + siteUrl + " 候选接口数=" + candidates.size());
        reportProgress(progress, 0, candidates.size(), siteUrl);

        for (int idx = 0; idx < candidates.size(); idx++) {
            if (expired(deadline)) break;
            String api = candidates.get(idx);
            reportProgress(progress, idx + 1, candidates.size(), api);
            int kind = CmsApiRules.detectKind(get(api));
            if (kind < 0 && !expired(deadline)) {
                // 有些站点空参只回分类表甚至空列表,补上 ac=videolist 才出数据
                kind = CmsApiRules.detectKind(get(withAcParam(api)));
            }
            if (kind < 0) continue;
            String name = CmsApiRules.siteName(html);
            if (name == null) name = CmsApiRules.displayHost(api);
            return write(name, api, kind, outDir);
        }
        return null;
    }

    /** 进度回调切回主线程(探测本身在后台执行器上,UI 更新必须回主线程) */
    private static void reportProgress(final Progress progress, final int done, final int total,
                                       final String url) {
        if (progress == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                progress.onProbe(done, total, url);
            }
        });
    }

    private static Result write(String name, String api, int kind, File outDir) {
        String key = CmsApiRules.siteKey(api);
        File dest = new File(outDir, "cms_" + key + ".json");
        String json = CmsApiRules.buildSubscriptionJson(key, name, kind, api);
        try {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new java.io.IOException("mkdirs failed");
            }
            OutputStreamWriter writer =
                    new OutputStreamWriter(new FileOutputStream(dest), "UTF-8");
            try {
                writer.write(json);
            } finally {
                writer.close();
            }
        } catch (Throwable th) {
            com.github.tvbox.osc.log.LogStore.fail(com.github.tvbox.osc.log.Category.SUBSCRIPTION, "订阅导入: 生成订阅配置写入失败 " + dest.getAbsolutePath() + " " + th);
            return null;
        }
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SUBSCRIPTION, "订阅导入: 识别到采集接口 type=" + kind + " " + api + " -> " + dest.getAbsolutePath());
        return new Result(name, dest, api);
    }

    private static String get(String url) {
        return HttpClient.getQuietly(url, PROBE_HEADERS);
    }

    /** 采集接口补列表参数(MacCMS 统一识别 ac=videolist:XML 型出 video 列表,JSON 型出 list) */
    private static String withAcParam(String api) {
        if (api.contains("ac=")) return api;
        return api + (api.indexOf('?') >= 0 ? "&" : "?") + "ac=videolist";
    }

    private static boolean expired(long deadline) {
        return SystemClock.uptimeMillis() > deadline;
    }

    private static Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", PROBE_UA);
        return headers;
    }

    private static final class Result {
        final String siteName;
        final File file;
        final String api;

        Result(String siteName, File file, String api) {
            this.siteName = siteName;
            this.file = file;
            this.api = api;
        }
    }
}

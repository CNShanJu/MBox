package com.github.catvod.crawler;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

/**
 * 爬虫模块统一门面：播放/搜索/首页/分类/详情/解析/代理全能力，
 * 外部（UI 的 SourceViewModel、播放器、下载 reResolve、RemoteServer）只认这一个入口，
 * 不再直连 ApiConfig.getSpider() / JsLoader / JarLoader。
 * <p>
 * 所有方法统一：取 Spider 实例 → 按源分道执行（同源串行、异源并行，见 {@link SpiderExecutor}）
 * → 超时 → 结果归一（失败返回 null/空，不向调用方抛裸异常）。
 */
public final class SpiderApi {

    private static final String TRACE_TAG = "SpiderTrace";
    private static final long DEFAULT_TIMEOUT = 20_000;

    private static final SpiderExecutor EXECUTOR = new SpiderExecutor();

    private SpiderApi() {
    }

    /** 0 号道（兼容入口：无 sourceKey 上下文的既有调用方；新代码一律走 {@link #executeSerial(String, Runnable)}） */
    public static ExecutorService serialExecutor() {
        return EXECUTOR.asExecutorService();
    }

    // ------------------------------------------------------------------
    // 播放地址解析（播放器 / 下载 reResolve 共用）
    // ------------------------------------------------------------------

    /** 解析单集真实播放地址 + 请求头（按源分道 + 20s 超时） */
    public static PlayUrlResolver.ResolveResult resolvePlayUrl(String sourceKey, String playFlag, String rawUrl) {
        return EXECUTOR.call(sourceKey, DEFAULT_TIMEOUT,
                () -> PlayUrlResolver.resolveWithHeader(sourceKey, playFlag, rawUrl));
    }

    /** 解析单集真实播放地址（只取 url） */
    public static String resolvePlayUrlString(String sourceKey, String playFlag, String rawUrl) {
        PlayUrlResolver.ResolveResult rr = resolvePlayUrl(sourceKey, playFlag, rawUrl);
        return rr == null ? null : rr.url;
    }

    // ------------------------------------------------------------------
    // 内容爬取（首页/分类/详情/搜索/播放，UI 层用）
    // ------------------------------------------------------------------

    public static String home(String sourceKey, boolean filter) {
        return callSpider(sourceKey, "homeContent", sp -> sp.homeContent(filter));
    }

    public static String category(String sourceKey, String tid, String pg, boolean filter, Map<String, String> extend) {
        return callSpider(sourceKey, "categoryContent", sp -> sp.categoryContent(tid, pg, filter, toHashMap(extend)));
    }

    public static String detail(String sourceKey, List<String> ids) {
        return callSpider(sourceKey, "detailContent", sp -> sp.detailContent(ids));
    }

    public static String search(String sourceKey, String key, boolean quick) {
        return callSpider(sourceKey, "searchContent", sp -> sp.searchContent(key, quick));
    }

    public static String search(String sourceKey, String key, boolean quick, String pg) {
        return callSpider(sourceKey, "searchContent", sp -> sp.searchContent(key, quick, pg));
    }

    /** 播放信息 JSON（播放器用） */
    public static String play(String sourceKey, String flag, String id, List<String> vipFlags) {
        return callSpider(sourceKey, "playerContent", sp -> sp.playerContent(flag, id, vipFlags));
    }

    // ------------------------------------------------------------------
    // 源代理 / 控制 / 串行执行
    // ------------------------------------------------------------------

    /** 源代理（RemoteServer /proxy 用） */
    public static Object[] proxyLocal(Map<String, String> params) {
        return ApiConfig.get().proxyLocal(params);
    }

    /** 取消该源进行中的请求（JS okhttp tag） */
    public static void cancelByTag(String sourceKey) {
        SourceBean sb = ApiConfig.get().getSource(sourceKey);
        if (sb == null) return;
        Spider sp = ApiConfig.get().getCSP(sb);
        if (sp != null) {
            try {
                sp.cancelByTag();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 源/爬虫更新后重载（钩子，具体重建逻辑由 ApiConfig 加载流程触发） */
    public static void reloadSources() {
        // 预留：源更新后由 ApiConfig 重建 Spider 实例
    }

    /** 串行执行任意爬虫任务（带超时，按 key 分道）；超时/异常返回 null */
    public static <T> T submitSerial(String key, Callable<T> task, long timeoutMs) {
        return EXECUTOR.call(key, timeoutMs, task);
    }

    /** 串行执行任意爬虫任务（带超时，无 key 落 0 号道）；超时/异常返回 null */
    public static <T> T submitSerial(Callable<T> task, long timeoutMs) {
        return EXECUTOR.call(timeoutMs, task);
    }

    /** 异步提交（不阻塞，按 key 分道，回调自行处理） */
    public static void executeSerial(String key, Runnable task) {
        EXECUTOR.execute(key, task);
    }

    /** 异步提交（不阻塞，无 key 落 0 号道） */
    public static void executeSerial(Runnable task) {
        EXECUTOR.execute(task);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private interface SpiderCall {
        String call(Spider sp) throws Exception;
    }

    private static String callSpider(String sourceKey, String method, SpiderCall fn) {
        SourceBean sb = ApiConfig.get().getSource(sourceKey);
        if (sb == null) return null;
        long start = System.currentTimeMillis();
        try {
            String result = EXECUTOR.call(sourceKey, DEFAULT_TIMEOUT, () -> fn.call(ApiConfig.get().getCSP(sb)));
            android.util.Log.i(TRACE_TAG, "[spider] " + sb.getName() + " " + method + " 完成 耗时="
                    + (System.currentTimeMillis() - start) + "ms");
            return result;
        } catch (Throwable th) {
            android.util.Log.i(TRACE_TAG, "[spider] " + sb.getName() + " " + method + " 异常 耗时="
                    + (System.currentTimeMillis() - start) + "ms " + th.getMessage());
            throw th;
        }
    }

    private static java.util.HashMap<String, String> toHashMap(Map<String, String> map) {
        if (map == null) return null;
        return new java.util.HashMap<>(map);
    }
}

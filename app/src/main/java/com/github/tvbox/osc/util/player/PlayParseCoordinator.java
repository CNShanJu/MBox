package com.github.tvbox.osc.util.player;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.spiderapi.ParseConfigProviders;
import com.github.tvbox.osc.spiderapi.SpiderManualCheckProviders;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HCallBack;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.ParseBeanUrls;
import com.github.tvbox.osc.util.VideoParseRuler;
import xyz.doikki.videoplayer.player.PlaybackErrorReporter;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import me.jessyan.autosize.AutoSize;
import me.jessyan.autosize.internal.CustomAdapt;

/**
 * 播放页"解析/嗅探执行"引擎(自 PlayFragment 抽取,行为等价搬迁)。
 * <p>
 * 职责:
 * 1. 解析编排:initParse(默认/内联 json/parse: 重定向解析源选择)与 doParse
 *    (type0 嗅探 / type1 json 解析 / type2 json 扩展 / type3 json 聚合);
 * 2. 无头 WebView 嗅探:创建/配置/加载/停止/销毁,资源拦截、广告过滤、视频地址判定
 *    与首发回调,已发现地址队列(自动重试用);
 * 3. 解析上下文(parseFlag/webUrl/UA/headers)与 20s 嗅探超时;
 * 4. WebView SSL 策略:默认拒绝,仅当用户开启"忽略证书错误"才放行;
 * 5. 解析线程:type2/3 提交应用级共享执行器 + epoch 自检(过期即丢弃),
 *    不再每轮自建线程池。
 * <p>
 * 宿主(PlayFragment)经 {@link Callback} 接收 提示/播放/错误重试/解析源展示切换/主线程投递;
 * sourceBean 经 {@link #setSourceBean} 注入(点击选择器/手动视频判定用)。
 */
public class PlayParseCoordinator {

    /** 宿主回调(全部由 PlayFragment 实现,与抽取前调用点一一对应) */
    public interface Callback {
        /** 播放页状态提示(= PlayFragment.setTip) */
        void onShowTip(String msg, boolean loading, boolean err);

        /** 播放一个解析/嗅探到的地址(= PlayFragment.playUrl) */
        void onPlayUrl(String url, HashMap<String, String> headers);

        /** 解析出错,走播放页错误重试(= PlayFragment.errorWithRetry) */
        void onErrorRetry(String err, boolean finish);

        /** 需要展示/隐藏解析源选择(= PlayFragment mController.showParse(useParse)) */
        void onShowParseRoot(boolean show);

        /**
         * 宿主仍处于 attach 状态则把任务投递到主线程执行。
         *
         * @return true=已投递;false=宿主已不可用(等同抽取前 isAdded() 判断失败)
         */
        boolean postOnUiThread(Runnable r);
    }

    // ---------- 宿主注入 ----------
    private final Activity activity;
    private final CustomAdapt customAdapt;
    private final Callback callback;
    /** 嗅探页所属数据源(setData 时注入,WebView 点击选择器/手动视频判定用) */
    private SourceBean sourceBean;

    // ---------- 解析上下文(原 PlayFragment.parseFlag/webUrl/webUserAgent/webHeaderMap) ----------
    private String parseFlag;
    private String webUrl;
    private String webUserAgent;
    private Map<String, String> webHeaderMap;

    // ---------- 嗅探超时(替代原 Fragment mHandler 的 message 100) ----------
    private final Handler parseHandler = new Handler(Looper.getMainLooper());
    private final Runnable sniffTimeoutTask = new Runnable() {
        @Override
        public void run() {
            stopParse();
            callback.onErrorRetry("嗅探错误", false);
        }
    };

    // ---------- 解析任务代数(替代原 parseThreadPool.shutdown;共享执行器不可 shutdown) ----------
    private final AtomicInteger parseTaskEpoch = new AtomicInteger();

    // ---------- 无头 WebView 嗅探状态(原 PlayFragment 私有字段) ----------
    private WebView mSysWebView;
    private final Map<String, Boolean> loadedUrls = new HashMap<>();
    private LinkedList<String> loadFoundVideoUrls = new LinkedList<>();
    private HashMap<String, HashMap<String, String>> loadFoundVideoUrlsHeader = new HashMap<>();
    private final AtomicInteger loadFoundCount = new AtomicInteger(0);

    public PlayParseCoordinator(Activity activity, CustomAdapt customAdapt, Callback callback) {
        this.activity = activity;
        this.customAdapt = customAdapt;
        this.callback = callback;
    }

    /** 数据源注入(host.setData 时调用;sourceBean 原为 PlayFragment 字段) */
    public void setSourceBean(SourceBean sourceBean) {
        this.sourceBean = sourceBean;
    }

    // ================= 解析上下文(host 侧写入入口) =================

    /** 新一轮播放结果到达、header 尚未解析前的复位(原 mObserverPlayResult 开头 webUserAgent/webHeaderMap = null) */
    public void resetWebRequestContext() {
        webUserAgent = null;
        webHeaderMap = null;
    }

    /** 播放结果 header 解析完成后写入(原 mObserverPlayResult:webHeaderMap=headers;含 UA 时置 webUserAgent) */
    public void setWebRequestContext(Map<String, String> headers, String userAgent) {
        if (userAgent != null && !userAgent.isEmpty()) {
            webUserAgent = userAgent;
        }
        webHeaderMap = headers;
    }

    // ================= 队列(host 自动重试用,原 loadFoundVideoUrls 等) =================

    /** 新一轮解析前清空已发现地址队列(原 initParseLoadFound) */
    public void resetFoundQueue() {
        loadFoundCount.set(0);
        loadFoundVideoUrls = new LinkedList<>();
        loadFoundVideoUrlsHeader = new HashMap<>();
    }

    /** 是否还有可重试的已发现地址(原 autoRetry 判空 loadFoundVideoUrls) */
    public boolean hasFoundVideo() {
        return loadFoundVideoUrls != null && !loadFoundVideoUrls.isEmpty();
    }

    /** 取出下一个待重试的已发现地址(原 autoRetryFromLoadFoundVideoUrls poll) */
    public String pollFoundVideoUrl() {
        return loadFoundVideoUrls.poll();
    }

    /** 取某已发现地址的请求头(原 loadFoundVideoUrlsHeader.get(url)) */
    public HashMap<String, String> getFoundVideoHeaders(String url) {
        return loadFoundVideoUrlsHeader.get(url);
    }

    /**
     * 当前播放所用请求头(原 PlayFragment.getPlayHeaders,下载回退播放地址时使用):
     * 优先完整 header,缺失时用爬虫返回的 UA。
     */
    public Map<String, String> getPlayHeaders() {
        if (webHeaderMap != null && !webHeaderMap.isEmpty()) return webHeaderMap;
        if (webUserAgent != null && !webUserAgent.isEmpty()) {
            java.util.HashMap<String, String> h = new java.util.HashMap<>();
            h.put("User-Agent", webUserAgent);
            return h;
        }
        return null;
    }

    // ================= 解析编排(原 initParse/doParse/stopParse) =================

    /** 新一轮播放结果进入解析分支(原 PlayFragment.initParse):记录上下文、展示解析源选择并执行 */
    public void initParse(String flag, boolean useParse, String playUrl, final String url) {
        parseFlag = flag;
        webUrl = url;
        ParseBean parseBean = null;
        callback.onShowParseRoot(useParse);
        if (useParse) {
            parseBean = ParseConfigProviders.get().getDefaultParse();
        } else {
            if (playUrl.startsWith("json:")) {
                parseBean = new ParseBean();
                parseBean.setType(1);
                parseBean.setUrl(playUrl.substring(5));
            } else if (playUrl.startsWith("parse:")) {
                String parseRedirect = playUrl.substring(6);
                for (ParseBean pb : ParseConfigProviders.get().getParseBeanList()) {
                    if (pb.getName().equals(parseRedirect)) {
                        parseBean = pb;
                        break;
                    }
                }
            }
            if (parseBean == null) {
                parseBean = new ParseBean();
                parseBean.setType(0);
                parseBean.setUrl(playUrl);
            }
        }
        doParse(parseBean);
    }

    /** 按解析源执行(原 PlayFragment.doParse);切换解析源时由宿主直接调用 */
    public void doParse(ParseBean pb) {
        stopParse();
        resetFoundQueue();
        if (pb.getType() == 0) {
            callback.onShowTip("正在嗅探播放地址", true, false);
            scheduleSniffTimeout();
            if (pb.getExt() != null) {
                // 解析ext
                try {
                    HashMap<String, String> reqHeaders = new HashMap<>();
                    JSONObject jsonObject = new JSONObject(pb.getExt());
                    if (jsonObject.has("header")) {
                        JSONObject headerJson = jsonObject.optJSONObject("header");
                        Iterator<String> keys = headerJson.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            if (key.equalsIgnoreCase("user-agent")) {
                                webUserAgent = headerJson.getString(key).trim();
                            } else {
                                reqHeaders.put(key, headerJson.optString(key, ""));
                            }
                        }
                        if (reqHeaders.size() > 0) webHeaderMap = reqHeaders;
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
            loadWebView(ParseBeanUrls.url(pb) + webUrl);

        } else if (pb.getType() == 1) { // json 解析
            callback.onShowTip("正在解析播放地址", true, false);
            // 解析ext
            Map<String, String> reqHeaders = new HashMap<>();
            try {
                JSONObject jsonObject = new JSONObject(pb.getExt());
                if (jsonObject.has("header")) {
                    JSONObject headerJson = jsonObject.optJSONObject("header");
                    Iterator<String> keys = headerJson.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        reqHeaders.put(key, headerJson.optString(key, ""));
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
            HttpClient.get(ParseBeanUrls.url(pb) + encodeUrl(webUrl), reqHeaders, "json_jx", new HCallBack() {
                        @Override
                        public void onSuccess(String json) {
                            try {
                                JSONObject rs = jsonParse(webUrl, json);
                                HashMap<String, String> headers = null;
                                if (rs.has("header")) {
                                    try {
                                        JSONObject hds = rs.getJSONObject("header");
                                        Iterator<String> keys = hds.keys();
                                        while (keys.hasNext()) {
                                            String key = keys.next();
                                            if (headers == null) {
                                                headers = new HashMap<>();
                                            }
                                            headers.put(key, hds.getString(key));
                                        }
                                    } catch (Throwable th) {

                                    }
                                }
                                callback.onPlayUrl(rs.getString("url"), headers);
                            } catch (Throwable e) {
                                e.printStackTrace();
                                callback.onErrorRetry("解析错误", false);
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            callback.onErrorRetry("解析错误", false);
                        }
                    });
        } else if (pb.getType() == 2) { // json 扩展
            callback.onShowTip("正在解析播放地址", true, false);
            final long parseEpoch = parseTaskEpoch.get();
            LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
            for (ParseBean p : ParseConfigProviders.get().getParseBeanList()) {
                if (p.getType() == 1) {
                    jxs.put(p.getName(), ParseBeanUrls.mixUrl(p));
                }
            }
            com.github.tvbox.osc.util.HeavyTaskUtil.getBigTaskExecutorService().execute(new Runnable() {
                @Override
                public void run() {
                    // 已被新一轮解析/停止取代:直接丢弃(共享线程池不可 shutdown,epoch 自检)
                    if (parseEpoch != parseTaskEpoch.get()) return;
                    JSONObject rs = ParseConfigProviders.get().jsonExt(ParseBeanUrls.url(pb), jxs, webUrl);
                    if (parseEpoch != parseTaskEpoch.get()) return;
                    if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
                        callback.onShowTip("解析错误", false, true);
                    } else {
                        HashMap<String, String> headers = null;
                        if (rs.has("header")) {
                            try {
                                JSONObject hds = rs.getJSONObject("header");
                                Iterator<String> keys = hds.keys();
                                while (keys.hasNext()) {
                                    String key = keys.next();
                                    if (headers == null) {
                                        headers = new HashMap<>();
                                    }
                                    headers.put(key, hds.getString(key));
                                }
                            } catch (Throwable th) {

                            }
                        }
                        if (parseEpoch != parseTaskEpoch.get()) return;
                        if (rs.has("jxFrom")) {
                            logParseOrigin(rs.optString("jxFrom"));
                        }
                        boolean parseWV = rs.optInt("parse", 0) == 1;
                        if (parseWV) {
                            String wvUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                            loadUrl(wvUrl);
                        } else {
                            callback.onPlayUrl(rs.optString("url", ""), headers);
                        }
                    }
                }
            });
        } else if (pb.getType() == 3) { // json 聚合
            callback.onShowTip("正在解析播放地址", true, false);
            final long parseEpoch = parseTaskEpoch.get();
            LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
            String extendName = "";
            for (ParseBean p : ParseConfigProviders.get().getParseBeanList()) {
                HashMap data = new HashMap<String, String>();
                data.put("url", ParseBeanUrls.url(p));
                if (ParseBeanUrls.url(p).equals(ParseBeanUrls.url(pb))) {
                    extendName = p.getName();
                }
                data.put("type", p.getType() + "");
                data.put("ext", p.getExt());
                jxs.put(p.getName(), data);
            }
            String finalExtendName = extendName;
            com.github.tvbox.osc.util.HeavyTaskUtil.getBigTaskExecutorService().execute(new Runnable() {
                @Override
                public void run() {
                    if (parseEpoch != parseTaskEpoch.get()) return;
                    JSONObject rs = ParseConfigProviders.get().jsonExtMix(parseFlag + "111", ParseBeanUrls.url(pb), finalExtendName, jxs, webUrl);
                    if (parseEpoch != parseTaskEpoch.get()) return;
                    if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
                        callback.onShowTip("解析错误", false, true);
                    } else {
                        if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                            if (rs.has("ua")) {
                                webUserAgent = rs.optString("ua").trim();
                            }
                            if (parseEpoch != parseTaskEpoch.get()) return;
                            if (!callback.postOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    String mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                                    stopParse();
                                    callback.onShowTip("正在嗅探播放地址", true, false);
                                    scheduleSniffTimeout();
                                    loadWebView(mixParseUrl);
                                }
                            })) return;
                        } else {
                            HashMap<String, String> headers = null;
                            if (rs.has("header")) {
                                try {
                                    JSONObject hds = rs.getJSONObject("header");
                                    Iterator<String> keys = hds.keys();
                                    while (keys.hasNext()) {
                                        String key = keys.next();
                                        if (headers == null) {
                                            headers = new HashMap<>();
                                        }
                                        headers.put(key, hds.getString(key));
                                    }
                                } catch (Throwable th) {
                                    th.printStackTrace();
                                }
                            }
                            if (parseEpoch != parseTaskEpoch.get()) return;
                            if (rs.has("jxFrom")) {
                                logParseOrigin(rs.optString("jxFrom"));
                            }
                            callback.onPlayUrl(rs.optString("url", ""), headers);
                        }
                    }
                }
            });
        }
    }

    private void logParseOrigin(String origin) {
        String detail = "播放解析来源: " + PlaybackErrorReporter.safeDiagnosticText(origin);
        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.PLAYER, detail);
        android.util.Log.i("MBoxPlayer", detail);
    }

    /** 停止当前解析/嗅探(原 stopParse):作废任务代数、停止 WebView、取消 json 请求、取消超时 */
    public void stopParse() {
        parseTaskEpoch.incrementAndGet(); // 作废排队/在途解析任务(替代原 parseThreadPool.shutdown)
        cancelSniffTimeout();
        stopLoadWebView(false);
        HttpClient.cancel("json_jx");
    }

    /** 宿主销毁时全量释放(原 onDestroyView 的 stopLoadWebView(true) + stopParse) */
    public void destroy() {
        parseTaskEpoch.incrementAndGet();
        cancelSniffTimeout();
        HttpClient.cancel("json_jx");
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyWebViewInternal();
        } else {
            parseHandler.post(new Runnable() {
                @Override
                public void run() {
                    destroyWebViewInternal();
                }
            });
        }
    }

    private void destroyWebViewInternal() {
        if (mSysWebView == null) return;
        try {
            mSysWebView.stopLoading();
            mSysWebView.loadUrl("about:blank");
            // 先摘除父容器,再 destroy,避免 "WebView.destroy() called while still attached" 警告与渲染进程崩溃
            ViewParent parent = mSysWebView.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(mSysWebView);
            }
            mSysWebView.removeAllViews();
            mSysWebView.destroy();
        } catch (Throwable ignored) {
        }
        mSysWebView = null;
    }

    private void scheduleSniffTimeout() {
        cancelSniffTimeout();
        parseHandler.postDelayed(sniffTimeoutTask, 20 * 1000);
    }

    private void cancelSniffTimeout() {
        parseHandler.removeCallbacks(sniffTimeoutTask);
    }

    // ================= WebView 嗅探执行 =================

    private void loadWebView(String url) {
        if (mSysWebView == null) {
            mSysWebView = new MyWebView(activity, activity, customAdapt);
            configWebViewSys(mSysWebView);
            loadUrl(url);
        } else {
            loadUrl(url);
        }
    }

    private void loadUrl(String url) {
        if (!callback.postOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mSysWebView != null) {
                    mSysWebView.stopLoading();
                    if (webUserAgent != null) {
                        mSysWebView.getSettings().setUserAgentString(webUserAgent);
                    }
                    //mSysWebView.clearCache(true);
                    if (webHeaderMap != null) {
                        mSysWebView.loadUrl(url, webHeaderMap);
                    } else {
                        mSysWebView.loadUrl(url);
                    }
                }
            }
        })) return;
    }

    private void stopLoadWebView(boolean destroy) {
        if (!callback.postOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mSysWebView != null) {
                    mSysWebView.stopLoading();
                    mSysWebView.loadUrl("about:blank");
                    if (destroy) {
                        destroyWebViewInternal();
                    }
                }
            }
        })) return;
    }

    JSONObject jsonParse(String input, String json) throws JSONException {
        JSONObject jsonPlayData = new JSONObject(json);
        //小窗版解析方法改到这了  之前那个位置data解析无效
        String url;
        if (jsonPlayData.has("data")) {
            url = jsonPlayData.getJSONObject("data").getString("url");
        } else {
            url = jsonPlayData.getString("url");
        }
        if (url.startsWith("//")) {
            url = "http:" + url;
        }
        if (!url.startsWith("http")) {
            return null;
        }
        JSONObject headers = new JSONObject();
        String ua = jsonPlayData.optString("user-agent", "");
        if (ua.trim().length() > 0) {
            headers.put("User-Agent", " " + ua);
        }
        String referer = jsonPlayData.optString("referer", "");
        if (referer.trim().length() > 0) {
            headers.put("Referer", " " + referer);
        }
        JSONObject taskResult = new JSONObject();
        taskResult.put("header", headers);
        taskResult.put("url", url);
        return taskResult;
    }

    private String encodeUrl(String url) {
        try {
            return URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }

    private boolean checkVideoFormat(String url) {
        try {
            if (url.contains("url=http") || url.contains(".html")) {
                return false;
            }
            if (sourceBean != null && sourceBean.getType() == 3) {
                // 手动视频判定经爬虫契约(:spider),不直接拿具体 Spider
                Boolean r = SpiderManualCheckProviders.get()
                        .manualVideoCheck(sourceBean.getKey(), url);
                if (r != null) {
                    return r;
                }
            }
            return VideoParseRuler.checkIsVideoForParse(webUrl, url);
        } catch (Exception e) {
            return false;
        }
    }

    private static class MyWebView extends WebView {
        private final Activity hostActivity;
        private final CustomAdapt adapt;

        public MyWebView(@NonNull Context context, Activity hostActivity, CustomAdapt adapt) {
            super(context);
            this.hostActivity = hostActivity;
            this.adapt = adapt;
        }

        @Override
        public void setOverScrollMode(int mode) {
            super.setOverScrollMode(mode);
            if (hostActivity != null && adapt != null)
                AutoSize.autoConvertDensityOfCustomAdapt(hostActivity, adapt);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return false;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configWebViewSys(WebView webView) {
        if (webView == null) {
            return;
        }
        ViewGroup.LayoutParams layoutParams = SystemConfig.isDebugOpen()
                ? new ViewGroup.LayoutParams(800, 400) :
                new ViewGroup.LayoutParams(1, 1);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.clearFocus();
        webView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        activity.addContentView(webView, layoutParams);
        /* 添加webView配置 */
        final WebSettings settings = webView.getSettings();
        settings.setNeedInitialFocus(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        if (SystemConfig.isDebugOpen()) {
            settings.setBlockNetworkImage(false);
        } else {
            settings.setBlockNetworkImage(true);
        }
        settings.setUseWideViewPort(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
//        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        /* 添加webView配置 */
        //设置编码
        settings.setDefaultTextEncodingName("utf-8");
        settings.setUserAgentString(webView.getSettings().getUserAgentString());
//         settings.setUserAgentString(ANDROID_UA);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return false;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                return true;
            }
        });
        SysWebClient mSysWebClient = new SysWebClient();
        webView.setWebViewClient(mSysWebClient);
        webView.setBackgroundColor(Color.BLACK);
    }

    private class SysWebClient extends WebViewClient {

        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override
        public void onReceivedSslError(WebView webView, SslErrorHandler sslErrorHandler, SslError sslError) {
            if (sslError != null && SystemConfig.isSslExceptionAllowedForUrl(sslError.getUrl())) {
                sslErrorHandler.proceed();
            } else {
                sslErrorHandler.cancel();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // 防御:sourceBean 可能为空(源切换/重试期间),避免取点击选择器 NPE
            String click = sourceBean == null ? null : sourceBean.getClickSelector();
            LOG.i("onPageFinished url:" + url);

            if (click != null && !click.isEmpty()) {
                String selector;
                if (click.contains(";")) {
                    if (!url.contains(click.split(";")[0])) return;
                    selector = click.split(";")[1];
                } else {
                    selector = click.trim();
                }
                String js = "$(\"" + selector + "\").click();";
                LOG.i("javascript:" + js);
                mSysWebView.loadUrl("javascript:" + js);
            }
        }

        WebResourceResponse checkIsVideo(String url, HashMap<String, String> headers) {
            if (url.endsWith("/favicon.ico")) {
                if (url.startsWith("http://127.0.0.1")) {
                    return new WebResourceResponse("image/x-icon", "UTF-8", null);
                }
                return null;
            }

            boolean isFilter = VideoParseRuler.isFilter(webUrl, url);
            if (isFilter) {
                LOG.i("shouldInterceptLoadRequest filter:" + url);
                return null;
            }

            boolean ad;
            if (!loadedUrls.containsKey(url)) {
                ad = AdBlocker.isAd(url);
                loadedUrls.put(url, ad);
            } else {
                ad = Boolean.TRUE.equals(loadedUrls.get(url));
            }

            if (!ad) {
                if (checkVideoFormat(url)) {
                    loadFoundVideoUrls.add(url);
                    loadFoundVideoUrlsHeader.put(url, headers);
                    LOG.i("loadFoundVideoUrl:" + url);
                    if (loadFoundCount.incrementAndGet() == 1) {
                        url = loadFoundVideoUrls.poll();
                        cancelSniffTimeout();
                        String cookie = CookieManager.getInstance().getCookie(url);
                        if (!TextUtils.isEmpty(cookie))
                            headers.put("Cookie", " " + cookie);//携带cookie
                        callback.onPlayUrl(url, headers);
                        stopLoadWebView(false);
                    }
                }
            }

            return ad || loadFoundCount.get() > 0 ?
                    AdBlocker.createEmptyResource() :
                    null;
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
//            WebResourceResponse response = checkIsVideo(url, new HashMap<>());
            return null;
        }

        @Nullable
        @Override
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            LOG.i("shouldInterceptRequest url:" + url);
            HashMap<String, String> webHeaders = new HashMap<>();
            Map<String, String> hds = request.getRequestHeaders();
            if (hds != null && hds.keySet().size() > 0) {
                for (String k : hds.keySet()) {
                    if (k.equalsIgnoreCase("user-agent")
                            || k.equalsIgnoreCase("referer")
                            || k.equalsIgnoreCase("origin")) {
                        webHeaders.put(k, " " + hds.get(k));
                    }
                }
            }
            return checkIsVideo(url, webHeaders);
        }

        @Override
        public void onLoadResource(WebView webView, String url) {
            super.onLoadResource(webView, url);
        }
    }
}

package com.github.tvbox.osc.util;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
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

import androidx.annotation.Nullable;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.download.DownloadUrlSniffer;
import com.github.tvbox.osc.config.SystemConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 方案 A：单例无头 WebView，串行复用（下载侧）。
 * <p>
 * 对嗅探型源（type 0，播放靠 WebView 页面嗅探）的每个剧集页：加载后经
 * shouldInterceptRequest 拦截真实视频地址，复用同一个 WebView 上下文/Cookie/登录会话，
 * 逐集串行消费，零多 WebView 炸机风险。App 启动时注册到
 * {@link com.github.tvbox.osc.util.DownloadManager}，DownloadScheduler 在任务
 * 启动前/地址过期重解析时调用 {@link #sniff}。
 * <p>
 * 线程模型：{@link #sniff} 任意线程可调（内部转主线程执行），调用线程以 CountDownLatch
 * 阻塞等待最多 timeoutMs+3s；主线程负责 WebView 创建/加载/拦截。
 */
public class WebSniffResolver implements DownloadUrlSniffer {

    private static final WebSniffResolver instance = new WebSniffResolver();

    public static WebSniffResolver get() {
        return instance;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 单例复用的无头 WebView（不 destroy，保留会话） */
    private WebView webView;
    private SourceBean sourceBean;
    /** 当前嗅探的剧集页地址（VideoParseRuler 过滤用） */
    private String sniffWebUrl;

    // 单次嗅探状态
    private final Map<String, Boolean> loadedUrls = new HashMap<>();
    private final AtomicInteger loadFoundCount = new AtomicInteger(0);
    private volatile String foundUrl;
    private volatile Map<String, String> foundHeaders;
    private Runnable timeoutRunnable;
    private CountDownLatch latch;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private WebSniffResolver() {
    }

    @Override
    public SniffResult sniff(String sourceKey, String playFlag, String episodeRawUrl, long timeoutMs) {
        if (episodeRawUrl == null || episodeRawUrl.isEmpty()) return null;
        // 串行:等待前一嗅探结束再开始(并发下载任务可能同时触发,避免抢同一 WebView)
        long deadline = System.currentTimeMillis() + Math.max(5000L, timeoutMs) + 5000L;
        while (!busy.compareAndSet(false, true)) {
            if (System.currentTimeMillis() > deadline) return null;
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                return null;
            }
        }
        foundUrl = null;
        foundHeaders = null;
        try {
            final CountDownLatch done = new CountDownLatch(1);
            latch = done;
            final long tmo = Math.max(5000L, timeoutMs);
            mainHandler.post(() -> runSniff(sourceKey, episodeRawUrl, tmo, done));
            boolean ok = done.await(tmo + 3000L, TimeUnit.MILLISECONDS);
            return ok && foundUrl != null ? new SniffResult(foundUrl, foundHeaders) : null;
        } catch (InterruptedException e) {
            return null;
        } finally {
            busy.set(false);
        }
    }

    /** 主线程:重置状态 → 复用/创建 WebView → 加载剧集页,命中或超时放行 latch */
    @SuppressLint("SetJavaScriptEnabled")
    private void runSniff(String sourceKey, String rawUrl, long timeoutMs, CountDownLatch done) {
        try {
            resetState();
            sourceBean = com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getSource(sourceKey);
            sniffWebUrl = rawUrl;
            ensureWebView();

            timeoutRunnable = () -> {
                if (done.getCount() > 0) done.countDown();
            };
            mainHandler.postDelayed(timeoutRunnable, timeoutMs);

            webView.stopLoading();
            webView.loadUrl(rawUrl);
        } catch (Throwable th) {
            th.printStackTrace();
            if (done.getCount() > 0) done.countDown();
        }
    }

    /** 复用单例 WebView:首次创建(优先附着到当前 Activity 1x1,与播放侧一致;无 Activity 时应用上下文 detached),之后只重置不销毁 */
    @SuppressLint("SetJavaScriptEnabled")
    private void ensureWebView() {
        if (webView != null) return;
        Context ctx = App.getInstance();
        Activity act = null;
        try {
            if (AppManager.getInstance().isActivity()) {
                Activity cur = AppManager.getInstance().currentActivity();
                if (cur != null && !cur.isFinishing()) {
                    ctx = cur;
                    act = cur;
                }
            }
        } catch (Throwable ignored) {
        }
        webView = new WebView(ctx);
        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBlockNetworkImage(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.setBackgroundColor(android.graphics.Color.BLACK);
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
        webView.setWebViewClient(new SniffWebClient());
        // 附着到当前 Activity(1x1,同播放侧),保证页面 JS 正常执行;无 Activity 时 detached 也能加载
        if (act != null) {
            try {
                act.addContentView(webView, new ViewGroup.LayoutParams(1, 1));
            } catch (Throwable ignored) {
            }
        }
    }

    /** 新一轮嗅探前清理上一轮状态(不销毁 WebView,保留 Cookie/会话;导航历史必须清,否则随集数滚动增长) */
    private void resetState() {
        loadedUrls.clear();
        loadFoundCount.set(0);
        if (webView != null) {
            try {
                webView.stopLoading();        // 打断上一页残留加载(超时/未命中的页)
                webView.loadUrl("about:blank"); // 卸载上一页 JS/资源,避免残留状态影响下一集
                webView.clearHistory();       // 清导航历史:几百集串行 loadUrl 会不断累积,必须每集清
            } catch (Throwable ignored) {
            }
        }
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private class SniffWebClient extends WebViewClient {

        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // 默认拒绝(取消加载):只有用户显式开启"忽略证书错误"调试选项时才放行,防止中间人篡改
            if (SystemConfig.isIgnoreSslError()) {
                handler.proceed();
            } else {
                handler.cancel();
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
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // 站点配置的播放按钮选择器:部分页面需点击才出流(与播放侧一致)
            try {
                if (sourceBean != null) {
                    String click = sourceBean.getClickSelector();
                    if (click != null && !click.isEmpty()) {
                        String selector;
                        if (click.contains(";")) {
                            if (!url.contains(click.split(";")[0])) return;
                            selector = click.split(";")[1];
                        } else {
                            selector = click.trim();
                        }
                        String js = "$(\"" + selector + "\").click();";
                        view.loadUrl("javascript:" + js);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            HashMap<String, String> webHeaders = new HashMap<>();
            Map<String, String> hds = request.getRequestHeaders();
            if (hds != null) {
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
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return null;
        }
    }

    private WebResourceResponse checkIsVideo(String url, HashMap<String, String> headers) {
        try {
            if (url.endsWith("/favicon.ico")) {
                return null;
            }
            if (VideoParseRuler.isFilter(sniffWebUrl, url)) {
                return null;
            }
            boolean ad;
            if (!loadedUrls.containsKey(url)) {
                ad = AdBlocker.isAd(url);
                loadedUrls.put(url, ad);
            } else {
                ad = Boolean.TRUE.equals(loadedUrls.get(url));
            }
            if (ad) {
                return AdBlocker.createEmptyResource();
            }
            if (checkVideoFormat(url)) {
                if (loadFoundCount.incrementAndGet() == 1) {
                    foundUrl = url;
                    Map<String, String> h = new HashMap<>(headers);
                    try {
                        String cookie = CookieManager.getInstance().getCookie(url);
                        if (cookie != null && !cookie.isEmpty()) h.put("Cookie", " " + cookie);
                    } catch (Throwable ignored) {
                    }
                    foundHeaders = h;
                    // 命中:停止加载,放行等待线程(下一轮复用前由 resetState 再清理)
                    try {
                        webView.stopLoading();
                        webView.loadUrl("about:blank");
                    } catch (Throwable ignored) {
                    }
                    if (timeoutRunnable != null) mainHandler.removeCallbacks(timeoutRunnable);
                    if (latch != null && latch.getCount() > 0) latch.countDown();
                }
                return AdBlocker.createEmptyResource();
            }
            return null;
        } catch (Throwable th) {
            return null;
        }
    }

    private boolean checkVideoFormat(String url) {
        try {
            if (url.contains("url=http") || url.contains(".html")) {
                return false;
            }
            if (sourceBean != null && sourceBean.getType() == 3) {
                // 手动视频判定经爬虫契约(:spider),不直接拿具体 Spider
                Boolean r = com.github.tvbox.osc.spiderapi.SpiderManualCheckProviders.get()
                        .manualVideoCheck(sourceBean.getKey(), url);
                if (r != null) {
                    return r;
                }
            }
            return VideoParseRuler.checkIsVideoForParse(sniffWebUrl, url);
        } catch (Exception e) {
            return false;
        }
    }
}

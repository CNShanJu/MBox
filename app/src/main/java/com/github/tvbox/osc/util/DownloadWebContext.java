package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import com.github.tvbox.osc.download.DownloadRequestContextProvider;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** CookieManager 由宿主主线程读取；源解析提供 UA/Referer，避免伪造学习通身份。 */
public final class DownloadWebContext implements DownloadRequestContextProvider {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String defaultUserAgent;

    public DownloadWebContext(String defaultUserAgent) { this.defaultUserAgent = defaultUserAgent; }

    @Override public Map<String, String> refresh(String source, String flag, String raw,
                                                String url, Map<String, String> headers) throws Exception {
        AtomicReference<String> cookie = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> failed = new AtomicReference<>();
        Runnable read = () -> {
            try { cookie.set(CookieManager.getInstance().getCookie(url)); }
            catch (Exception e) { failed.set(e); }
            finally { done.countDown(); }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) read.run();
        else {
            main.post(read);
            if (!done.await(3, TimeUnit.SECONDS)) throw new java.io.IOException("读取登录会话超时");
        }
        if (failed.get() != null) throw new java.io.IOException("登录会话读取失败", failed.get());
        String value = cookie.get();
        Map<String, String> defaults = new java.util.HashMap<>();
        defaults.put("User-Agent", defaultUserAgent);
        if (raw != null && raw.startsWith("http") && !raw.equals(url)
                && !com.github.tvbox.osc.spiderapi.MediaUrlUtil.isVideoFormat(raw)) defaults.put("Referer", raw);
        return DownloadHeaders.merge(defaults, headers, value == null || value.isEmpty() ? null
                : Collections.singletonMap("Cookie", value));
    }
}

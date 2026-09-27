package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 轻量网络请求封装(替代 OkGo,基于 OkHttp 4.x)
 * <ul>
 *     <li>异步 GET / POST JSON / 文件下载,回调统一切回主线程</li>
 *     <li>同步 GET / 下载,调用方需自行确保不在主线程执行</li>
 *     <li>支持 tag 取消请求(替代 OkGo.getInstance().cancelTag)</li>
 * </ul>
 */
public class HttpClient {
    public static final long DEFAULT_MILLISECONDS = 10000;

    /** 单次响应体读取上限(见 {@link HttpBodyReader#MAX_TEXT_BYTES}) */
    static long maxBodyBytes() {
        return HttpBodyReader.MAX_TEXT_BYTES;
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile OkHttpClient client;

    private HttpClient() {
    }

    /** 读响应体文本,带大小上限(实现见 {@link HttpBodyReader#readText}) */
    static String readBodyCapped(okhttp3.ResponseBody body) throws IOException {
        return HttpBodyReader.readText(body);
    }

    public static OkHttpClient getClient() {
        OkHttpClient c = OkGoHelper.getDefaultClient();
        if (c != null) return c;
        if (client == null) {
            synchronized (HttpClient.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                            .readTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                            .writeTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                            .addInterceptor(new UserAgentInterceptor())
                            .build();
                }
            }
        }
        return client;
    }

    /** 为没有显式设置 User-Agent 的请求补充默认 UA(还原 OkGo 全局 UA 行为,保持与旧版一致的 okhttp/3.x 字符串) */
    static class UserAgentInterceptor implements okhttp3.Interceptor {
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            okhttp3.Request request = chain.request();
            if (request.header("User-Agent") == null) {
                request = request.newBuilder().header("User-Agent", "okhttp/3.12.11").build();
            }
            return chain.proceed(request);
        }
    }

    // ---------------------------------------------------------------------
    // 异步 GET
    // ---------------------------------------------------------------------

    public static void get(String url, Object tag, HCallBack callback) {
        get(url, null, null, tag, callback);
    }

    public static void get(String url, Map<String, String> headers, Object tag, HCallBack callback) {
        get(url, null, headers, tag, callback);
    }

    public static void get(String url, Map<String, String> params, Map<String, String> headers, Object tag, final HCallBack callback) {
        try {
            final String fullUrl = HttpUrls.normalizeUrl(HttpUrls.buildUrl(url, params));
            Request.Builder builder = new Request.Builder().url(fullUrl).tag(tag);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
            }
            getClient().newCall(builder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.body() == null) {
                            postSuccess(callback, "");
                            return;
                        }
                        postSuccess(callback, readBodyCapped(response.body()));
                    } catch (Throwable th) {
                        postError(callback, th);
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Throwable th) {
            // 请求构造异常(如非法 URL)不能崩溃调用线程,走回调
            postError(callback, th);
        }
    }

    // ---------------------------------------------------------------------
    // 同步 GET(返回完整 Response,调用方负责关闭)
    // ---------------------------------------------------------------------

    public static Response getResponseSync(String url, Map<String, String> headers) throws IOException {
        try {
            Request.Builder builder = new Request.Builder().url(HttpUrls.normalizeUrl(url));
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
            }
            return getClient().newCall(builder.build()).execute();
        } catch (IOException e) {
            throw e;
        } catch (Throwable th) {
            // 非法 URL 等构造异常统一转 IOException,避免逃逸成运行时异常
            throw new IOException("request build failed: " + th.getMessage(), th);
        }
    }

    /** 同步 GET,成功返回字符串,失败抛出 IOException */
    public static String getSync(String url, Map<String, String> headers) throws IOException {
        Response response = getResponseSync(url, headers);
        try {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("response not successful: " + response.code());
            }
            return readBodyCapped(response.body());
        } finally {
            response.close();
        }
    }

    // ---------------------------------------------------------------------
    // 嗅探式同步 GET(快速失败、不抛异常)
    // ---------------------------------------------------------------------

    /**
     * 静默同步 GET:超时短、失败或非 2xx 一律返回 null,不抛异常、不打日志。
     * 用于"轮询多个候选地址直到命中"的嗅探场景(如订阅导入探测资源站接口)。
     * 复用全局客户端的连接池与拦截器,仅收紧超时;调用方自行确保不在主线程执行。
     */
    public static String getQuietly(String url, Map<String, String> headers) {
        try {
            Request.Builder builder = new Request.Builder().url(HttpUrls.normalizeUrl(url));
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
            }
            Response response = getProbeClient().newCall(builder.build()).execute();
            try {
                if (!response.isSuccessful() || response.body() == null) return null;
                return readBodyCapped(response.body());
            } finally {
                response.close();
            }
        } catch (Throwable th) {
            return null;
        }
    }

    private static volatile OkHttpClient probeClient;

    private static OkHttpClient getProbeClient() {
        OkHttpClient c = probeClient;
        if (c == null) {
            synchronized (HttpClient.class) {
                if (probeClient == null) {
                    probeClient = getClient().newBuilder()
                            .connectTimeout(3000, TimeUnit.MILLISECONDS)
                            .readTimeout(5000, TimeUnit.MILLISECONDS)
                            .callTimeout(6000, TimeUnit.MILLISECONDS)
                            .retryOnConnectionFailure(false)
                            .build();
                }
                c = probeClient;
            }
        }
        return c;
    }

    // ---------------------------------------------------------------------
    // 文件下载
    // ---------------------------------------------------------------------

    /** 同步下载到目标文件,返回目标文件 */
    public static File downloadSync(String url, File dest) throws IOException {
        return downloadSync(url, dest, null);
    }

    public static File downloadSync(String url, File dest, Map<String, String> headers) throws IOException {
        Response response = getResponseSync(url, headers);
        try {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("download failed, code=" + response.code());
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            // 先写同目录临时文件、成功后再整体替换:直接写目标文件时,中途失败/被并发读到就是
            // 半个 jar(而 jar 会被当成有效缓存留着,DexClassLoader 加载失败后每次调用都重下)
            File tmp = new File(dest.getAbsolutePath() + ".tmp");
            if (tmp.exists() && !tmp.delete()) {
                throw new IOException("cannot remove stale temp file: " + tmp);
            }
            // 文件可能被置为只读(防止 dex 校验),覆盖前恢复可写
            if (dest.exists() && !dest.canWrite()) {
                dest.setWritable(true);
            }
            InputStream is = response.body().byteStream();
            OutputStream os = new FileOutputStream(tmp);
            try {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
            } finally {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
                try {
                    os.close();
                } catch (IOException ignored) {
                }
            }
            if (tmp.length() <= 0) {
                tmp.delete();
                throw new IOException("download empty body: " + url);
            }
            if (!tmp.renameTo(dest)) {
                // 极端情况(rename 跨文件系统等):退化为复制,失败则保留 tmp 报错
                try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(dest)) {
                    byte[] buffer = new byte[8192];
                    int length;
                    while ((length = in.read(buffer)) > 0) {
                        out.write(buffer, 0, length);
                    }
                } finally {
                    tmp.delete();
                }
            }
            return dest;
        } finally {
            response.close();
        }
    }

    /**
     * 异步下载到目标文件,成功/失败回调切主线程。
     * <p>
     * 与 {@link #downloadSync} 同一套写法:先写同目录 .tmp、下载完整后再整体替换目标文件。
     * 原来直接写目标文件,断流/中途失败会把<b>上一份可用文件</b>截断成半个包
     * (订阅爬虫 jar 就是这么坏的:半截 jar 头仍是 "PK"、仍被当成缓存,加载失败后源全部不可用)。
     */
    public static void download(final String url, final File dest, Map<String, String> headers, final Object tag, final FCallBack callback) {
        try {
            Request.Builder builder = new Request.Builder().url(HttpUrls.normalizeUrl(url)).tag(tag);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
            }
            getClient().newCall(builder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    File tmp = new File(dest.getAbsolutePath() + ".tmp");
                    try {
                        if (!response.isSuccessful() || response.body() == null) {
                            throw new IOException("download failed, code=" + response.code());
                        }
                        File parent = dest.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        if (tmp.exists() && !tmp.delete()) {
                            throw new IOException("cannot remove stale temp file: " + tmp);
                        }
                        // 文件可能被置为只读(防止 dex 校验),覆盖前恢复可写
                        if (dest.exists() && !dest.canWrite()) {
                            dest.setWritable(true);
                        }
                        InputStream is = response.body().byteStream();
                        OutputStream os = new FileOutputStream(tmp);
                        try {
                            byte[] buffer = new byte[8192];
                            int length;
                            while ((length = is.read(buffer)) > 0) {
                                os.write(buffer, 0, length);
                            }
                        } finally {
                            try {
                                is.close();
                            } catch (IOException ignored) {
                            }
                            try {
                                os.close();
                            } catch (IOException ignored) {
                            }
                        }
                        if (tmp.length() <= 0) {
                            throw new IOException("download empty body: " + url);
                        }
                        if (!tmp.renameTo(dest)) {
                            // 极端情况(rename 跨文件系统等):退化为复制,失败则保留 tmp 报错
                            try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(dest)) {
                                byte[] buffer = new byte[8192];
                                int length;
                                while ((length = in.read(buffer)) > 0) {
                                    out.write(buffer, 0, length);
                                }
                            }
                        }
                        postSuccess(callback, dest);
                    } catch (Throwable th) {
                        postError(callback, th);
                    } finally {
                        if (tmp.exists()) tmp.delete();
                        response.close();
                    }
                }
            });
        } catch (Throwable th) {
            postError(callback, th);
        }
    }

    // ---------------------------------------------------------------------
    // 取消
    // ---------------------------------------------------------------------

    public static void cancel(Object tag) {
        if (tag == null) return;
        OkHttpClient c = getClient();
        for (Call call : c.dispatcher().queuedCalls()) {
            if (tag.equals(call.request().tag())) call.cancel();
        }
        for (Call call : c.dispatcher().runningCalls()) {
            if (tag.equals(call.request().tag())) call.cancel();
        }
    }

    // ---------------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------------

    // ---------------------------------------------------------------------
    // 同步 GET(带参:供 HTTP 型源取数复用 HttpUrls 拼装语义;调用方自行保证非主线程)
    // ---------------------------------------------------------------------

    /** 同步 GET:参数按 {@link HttpUrls#buildUrl} 拼装;成功返回字符串,失败抛 IOException */
    public static String getSync(String url, Map<String, String> params, Map<String, String> headers) throws IOException {
        return getSync(HttpUrls.normalizeUrl(HttpUrls.buildUrl(url, params)), headers);
    }

    /** 中文域名转 punycode(语义见 {@link HttpUrls#normalizeUrl};保留兼容外部调用) */
    public static String normalizeUrl(String url) {
        return HttpUrls.normalizeUrl(url);
    }

    private static void postSuccess(final HCallBack callback, final String content) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(content);
            }
        });
    }

    private static void postError(final HCallBack callback, final Throwable e) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(e);
            }
        });
    }

    private static void postSuccess(final FCallBack callback, final File file) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(file);
            }
        });
    }

    private static void postError(final FCallBack callback, final Throwable e) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(e);
            }
        });
    }
}

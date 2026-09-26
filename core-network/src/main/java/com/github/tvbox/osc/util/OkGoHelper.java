package com.github.tvbox.osc.util;

import static okhttp3.ConnectionSpec.CLEARTEXT;
import static okhttp3.ConnectionSpec.COMPATIBLE_TLS;
import static okhttp3.ConnectionSpec.MODERN_TLS;
import static okhttp3.ConnectionSpec.RESTRICTED_TLS;

import android.content.Context;

import com.github.catvod.net.SSLCompat;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.util.urlhttp.BrotliInterceptor;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

import okhttp3.Cache;
import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * 全局 OkHttpClient 初始化（独立模块 :common）。
 * Context 由 {@link #init(Context)} 注入（不依赖 app 类）；
 * Exo 播放内核与 Picasso 的初始化已拆回 app 侧（依赖 :player / picasso）。
 */
public class OkGoHelper {
    public static final long DEFAULT_MILLISECONDS = 10000;      //默认的超时时间

    private static Context appContext;

    public static DnsOverHttps dnsOverHttps = null;

    public static ArrayList<String> dnsHttpsList = new ArrayList<>();

    public static List<ConnectionSpec> getConnectionSpec() {
        return Collections.unmodifiableList(Arrays.asList(RESTRICTED_TLS, MODERN_TLS, COMPATIBLE_TLS, CLEARTEXT));
    }

    public static String getDohUrl(int type) {
        switch (type) {
            case 1: {
                return "https://doh.pub/dns-query";
            }
            case 2: {
                return "https://dns.alidns.com/dns-query";
            }
            case 3: {
                return "https://doh.360.cn/dns-query";
            }
        }
        return "";
    }

    static void initDnsOverHttps() {
        if (dnsHttpsList.isEmpty()) {
            dnsHttpsList.add("关闭");
            dnsHttpsList.add("腾讯");
            dnsHttpsList.add("阿里");
            dnsHttpsList.add("360");
        }
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        if (SystemConfig.isDebugOpen()) {
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        } else {
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.NONE);
        }
        builder.addInterceptor(loggingInterceptor);
        builder.addInterceptor(new BrotliInterceptor());
        try {
            setOkHttpSsl(builder);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        builder.connectionSpecs(getConnectionSpec());
        if (appContext != null) {
            builder.cache(new Cache(new File(appContext.getCacheDir().getAbsolutePath(), "dohcache"), 10 * 1024 * 1024));
        }
        OkHttpClient dohClient = builder.build();
        String dohUrl = getDohUrl(SystemConfig.getDohUrl());
        if (dohUrl.isEmpty()) {
            dnsOverHttps = null;
        } else {
            dnsOverHttps = new DnsOverHttps.Builder().client(dohClient).url(HttpUrl.get(dohUrl)).build();
        }
    }

    static OkHttpClient defaultClient = null;
    static OkHttpClient noRedirectClient = null;
    /** 图片专用客户端(带磁盘缓存):仅给 Picasso 等图片加载用,与 API/搜索流量隔离 */
    private static volatile OkHttpClient imageClient = null;

    /** 图片磁盘缓存上限(字节)。海报多为几十~几百 KB,100MB 可长期覆盖各页面海报回看 */
    private static final long IMAGE_CACHE_MAX_BYTES = 100L * 1024 * 1024;

    /**
     * 根据当前 Hawk 配置重建 DnsOverHttps(替代原 DnsOverHttps.setUrl 原地修改)
     * 注意:已构建的 OkHttpClient 不会立即生效,重启应用或下次重建客户端时生效
     */
    public static void refreshDnsOverHttps() {
        initDnsOverHttps();
    }

    public static OkHttpClient getDefaultClient() {
        return defaultClient;
    }

    public static OkHttpClient getNoRedirectClient() {
        return noRedirectClient;
    }

    /**
     * 图片专用 OkHttpClient(懒建,与默认客户端共享连接池/UA/日志等基础配置):
     * <ul>
     *   <li>挂 100MB 磁盘缓存——滑走再滑回/重进页面时,海报即使被 Picasso 内存 LRU 挤出,
     *       也能命中本地磁盘,不再回源重新下载(修:搜索结果页"已加载图滑回又加载");</li>
     *   <li>缓存头兜底拦截器:多数图床响应不带 Cache-Control/Expires,OkHttp 默认不会落盘;
     *       仅对图片客户端把"无缓存头的成功 GET"补成可缓存,使磁盘缓存真正生效;</li>
     *   <li>仅图片客户端生效,API/搜索/下载等流量不受影响(那些请求仍走各自无磁盘缓存的客户端)。</li>
     * </ul>
     */
    public static OkHttpClient getImageClient() {
        OkHttpClient c = imageClient;
        if (c == null) {
            synchronized (OkGoHelper.class) {
                c = imageClient;
                if (c == null) {
                    c = buildImageClient();
                    imageClient = c;
                }
            }
        }
        return c;
    }

    private static OkHttpClient buildImageClient() {
        if (defaultClient == null || appContext == null) return defaultClient;
        try {
            File dir = new File(appContext.getCacheDir(), "image_http_cache");
            if (!dir.exists() && !dir.mkdirs()) {
                return defaultClient; // 缓存目录创建失败:退回默认客户端(无磁盘缓存,功能不受影响)
            }
            return defaultClient.newBuilder()
                    .cache(new Cache(dir, IMAGE_CACHE_MAX_BYTES))
                    // 兜底缓存头:仅本客户端(图片)生效——OkHttp 依据响应缓存头决定是否落盘,
                    // 很多图床不带缓存头,补一个公共 max-age 使其可被磁盘缓存
                    .addNetworkInterceptor(chain -> {
                        okhttp3.Request req = chain.request();
                        okhttp3.Response resp = chain.proceed(req);
                        if (!"GET".equals(req.method()) || !resp.isSuccessful()) return resp;
                        if (resp.header("Cache-Control") != null || resp.header("Expires") != null) return resp;
                        return resp.newBuilder()
                                .header("Cache-Control", "public, max-age=86400")
                                .removeHeader("Pragma")
                                .build();
                    })
                    .build();
        } catch (Throwable th) {
            return defaultClient; // 构建失败:退回默认客户端,不阻塞图片加载
        }
    }

    /**
     * 公共根 Builder:默认客户端、免重定向客户端与播放器客户端共用的基础配置
     * (日志/UA/Brotli/连接规格/超时/安全DNS/SSL)。
     * 注意:OkHttpClient.newBuilder() 派生的客户端共享连接池属正常设计,
     * 这里合并的是"从不同根 Builder 各自重复初始化"的公共部分,避免重复创建配置。
     */
    public static OkHttpClient.Builder newBaseBuilder() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();

        if (SystemConfig.isDebugOpen()) {
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        } else {
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.NONE);
        }
        builder.addInterceptor(loggingInterceptor);
        // 默认 User-Agent:还原 OkGo 的全局 UA 行为,部分源接口无 UA 会拒绝请求
        builder.addInterceptor(new HttpClient.UserAgentInterceptor());
        builder.connectionSpecs(getConnectionSpec());
        builder.addInterceptor(new BrotliInterceptor());
        builder.readTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                .writeTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                .connectTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS);
        if (dnsOverHttps != null) {
            builder.dns(dnsOverHttps);
        }
        try {
            setOkHttpSsl(builder);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return builder;
    }

    /** App 启动时调用一次（context 注入；Exo/Picasso 初始化由 app 侧在 init 后自行完成） */
    public static void init(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
        initDnsOverHttps();

        OkHttpClient.Builder builder = newBaseBuilder();

        defaultClient = builder.build();

        builder.followRedirects(false);
        builder.followSslRedirects(false);
        noRedirectClient = builder.build();
    }

    /**
     * SSL 装配(安全红线):默认走 OkHttp 系统证书校验(校验证书链 + 默认主机名校验);
     * 仅当用户显式开启"忽略证书错误"(SystemConfig.isIgnoreSslError,默认关)时,
     * 才为个别自签名/证书错误站点挂载 SSLCompat(信任任意证书)放行。
     * 放行覆盖 WebView(即时生效)与 OkHttp 网络请求(重启应用后按新值重建客户端生效)。
     */
    private static synchronized void setOkHttpSsl(OkHttpClient.Builder builder) {
        try {
            if (SystemConfig.isIgnoreSslError()) {
                final SSLSocketFactory sslSocketFactory = new SSLCompat();
                builder.sslSocketFactory(sslSocketFactory, SSLCompat.TM);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

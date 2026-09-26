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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /** 当前生效的安全 DNS 解析器(null=关闭)。{@link #refreshDnsOverHttps()} 整体替换,读方只取一次引用 */
    private static volatile DnsOverHttps dnsOverHttps = null;

    /** 当前生效的 DoH 地址(空串=关闭):变更比对用字符串,避免拿 DnsOverHttps 解析反推 */
    private static volatile String dohUrl = "";

    /** 安全 DNS 选项文案(事实源在本模块 DohOptions;unmodifiable,防止外部原地改坏共享表) */
    public static final List<String> dnsHttpsList = DohOptions.LABELS;

    /**
     * 安全 DNS 变更监听:模块初始化时注册,DoH 变化时回调<b>重建自己的客户端</b>。
     * <p>
     * 为什么要有它::core-network 只负责建自己那几个客户端,spider/download 等模块各自持有
     * 按 DoH 构建的 client(这些 client 的 DNS 在 build 时就固定了,改字段不会生效)。
     * 让每个模块各自在设置页被调用一遍会形成反向依赖(基础模块不得认识业务模块),
     * 故改由<span>变更方发信号、持有方订阅</span> —— 方向仍是业务模块 → :core-network。
     */
    public interface DohChangeListener {
        void onDohChanged(String dohUrl);
    }

    private static final CopyOnWriteArrayList<DohChangeListener> dohChangeListeners = new CopyOnWriteArrayList<>();

    /** 注册 DoH 变更监听(幂等由调用方保证;回调在触发变更的线程上同步执行) */
    public static void addDohChangeListener(DohChangeListener listener) {
        if (listener != null) dohChangeListeners.addIfAbsent(listener);
    }

    public static List<ConnectionSpec> getConnectionSpec() {
        return Collections.unmodifiableList(Arrays.asList(RESTRICTED_TLS, MODERN_TLS, COMPATIBLE_TLS, CLEARTEXT));
    }

    /** 安全 DNS 下标 → DoH 地址(空串=关闭);映射唯一事实源在 {@link DohOptions#url(int)} */
    public static String getDohUrl(int type) {
        return DohOptions.url(type);
    }

    /** 安全 DNS 选项数(UI 取下标前必须先过这里/{{@link #dohLabel}}) */
    public static int dohCount() {
        return DohOptions.count();
    }

    /**
     * 安全 DNS 的显示文案(下标越界自动收进范围)。
     * <p>
     * 为什么必须夹取:历史版本/老备份里的 {@code doh_url} 可能是 4/5/6(那时列表有 7 项),
     * 而当前列表只有 4 项——直接 {@code dnsHttpsList[getDohUrl()]} 会 IndexOutOfBounds 崩在设置页。
     * 夹到最后一个有效项(而不是"关闭"),是为了保住用户"我要用 DoH"的意图。
     */
    public static String dohLabel(int index) {
        return DohOptions.label(index);
    }

    static void initDnsOverHttps() {
        String url = getDohUrl(SystemConfig.getDohUrl());
        dnsOverHttps = buildDohResolver(url);
        dohUrl = url;
    }

    /**
     * 按给定 DoH 地址构建解析器(关闭时返回 null)。
     * <p>
     * 这里的 dohClient 只用于解析 DoH 域名本身,与业务客户端<b>独立</b>:它存在的意义是
     * 解析时先走系统 DNS,避免"用 DoH 解析 DoH 域名"的自锁。
     */
    private static DnsOverHttps buildDohResolver(String dohUrl) {
        if (dohUrl == null || dohUrl.isEmpty()) return null;
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
        return new DnsOverHttps.Builder().client(dohClient).url(HttpUrl.get(dohUrl)).build();
    }

    /** 默认客户端(可被 DoH 变更整体替换,故 volatile;读方取一次引用用到底,不会中途换池) */
    static volatile OkHttpClient defaultClient = null;
    static volatile OkHttpClient noRedirectClient = null;
    /** 图片专用客户端(带磁盘缓存):仅给 Picasso 等图片加载用,与 API/搜索流量隔离 */
    private static volatile OkHttpClient imageClient = null;

    /** 图片磁盘缓存上限(字节)。海报多为几十~几百 KB,100MB 可长期覆盖各页面海报回看 */
    private static final long IMAGE_CACHE_MAX_BYTES = 100L * 1024 * 1024;

    /**
     * 安全 DNS 改动后<b>立即</b>换用新解析器:重建 DoH 解析器 + 本模块按 DoH 构建的客户端
     * ({@code defaultClient}/{@code noRedirectClient},图片客户端置空下次懒建),
     * 再广播给外部模块(spider/download/app 各自重建自己的 client)。
     * <p>
     * 为什么必须重建而不是改字段:OkHttp 的 DNS 是 build 时拷进 client 的,已建好的 client
     * 改静态字段不会生效(原实现的"要重启"根因)。旧 client 一律<b>不 shutdown</b>:
     * 它可能正在被在跑的请求(下载分片/正在播的流)持有,主动关闭会打断这些请求;
     * 让它们自然跑完、随引用释放交给 GC —— 代价是极短期的连接池重复,换来"换 DNS 不打断业务"。
     */
    public static void refreshDnsOverHttps() {
        if (isSameDohUrl(getDohUrl(SystemConfig.getDohUrl()))) return; // 早退:值没变,不该动连接池
        initDnsOverHttps();
        rebuildDohClients();
        notifyDohChanged(dohUrl);
    }

    /** 当前生效的 DoH 地址(空串=关闭);供变更比对与外部模块读取 */
    public static String currentDohUrl() {
        return dohUrl;
    }

    /** 当前生效的安全 DNS 解析器(null=关闭);供合并版爬虫/回环 DNS 转发读取 */
    public static DnsOverHttps getDnsOverHttps() {
        return dnsOverHttps;
    }

    /**
     * 当前生效的 DNS 策略(关闭时为系统 DNS)。
     * <p>
     * 为什么不直接返回 {@code getDnsOverHttps()}:okhttp4 的 {@code Builder.dns()} 参数非空,
     * 关设置时传 null 会抛 {@code NPE: Parameter specified as non-null is null}(合并版爬虫曾因此崩在初始化)。
     */
    public static okhttp3.Dns currentDns() {
        DnsOverHttps current = dnsOverHttps;
        return current != null ? current : okhttp3.Dns.SYSTEM;
    }

    private static boolean isSameDohUrl(String candidate) {
        return dohUrl.equals(candidate == null ? "" : candidate);
    }

    private static void notifyDohChanged(String dohUrl) {
        for (DohChangeListener listener : dohChangeListeners) {
            try {
                listener.onDohChanged(dohUrl);
            } catch (Throwable th) {
                // 单个订阅方重建失败不得影响其它模块(例如图片客户端没建好不该拖垮下载客户端)
                th.printStackTrace();
            }
        }
    }

    private static synchronized void rebuildDohClients() {
        OkHttpClient.Builder builder = newBaseBuilder();
        defaultClient = builder.build();
        builder.followRedirects(false);
        builder.followSslRedirects(false);
        noRedirectClient = builder.build();
        // 图片客户端派生自 defaultClient(共享连接池),置空即可:下次取用时按新根重建
        imageClient = null;
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
        // 首次构建与"换 DoH 后重建"同一段代码:两处各自 build 迟早漂移(超时/重定向配置漏改一边)
        rebuildDohClients();
    }

    /**
     * SSL 装配(安全红线):默认走 OkHttp 系统证书校验(校验证书链 + 默认主机名校验);
     * 仅当用户显式开启"忽略证书错误"(SystemConfig.isIgnoreSslError,默认关)时,
     * 才为个别自签名/证书错误站点挂载 SSLCompat(信任任意证书)放行。
     * 放行覆盖 WebView(即时生效)与 OkHttp 网络请求(本模块客户端在下次"换 DoH/重启"重建时生效)。
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

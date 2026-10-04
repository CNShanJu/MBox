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
import java.io.IOException;
import java.net.Proxy;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
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

    /**
     * 网络问题通知(供 app 侧弹"网络不可用"页)。
     * <p>
     * 触发条件是<b>"断网 + 真的发起了网络请求"</b>,覆盖两类:
     * ① 请求发出前就被判定没网({@link NetworkGuardInterceptor} 快速失败);
     * ② 请求发到一半断网(域名解析失败 / 连接建立失败 / 连接被重置)。
     * <p>
     * 为什么不直接监听系统网络状态:没做任何网络操作时断网不该弹页(用户可能正在看本地视频/本地文件),
     * 所以"要不要弹"由网络层在真实失败点决定;页面侧的"有网自动返回"才走系统状态单点
     * ({@code SystemStateMonitor})——那个单点下载侧已经在用,这里不新增第二条监听链路。
     */
    public interface NetworkIssueListener {
        void onNetworkIssue(String reason);
    }

    private static final CopyOnWriteArrayList<NetworkIssueListener> networkIssueListeners = new CopyOnWriteArrayList<>();

    /** 同一波失败的节流窗口:整屏内容失败时会有几十个请求同时抛,只该弹一次 */
    private static final long NETWORK_ISSUE_THROTTLE_MS = 3000L;
    private static volatile long lastNetworkIssueAt = 0L;

    /** 注册网络问题监听(app 组合根调用一次;回调在触发请求的线程上同步执行) */
    public static void addNetworkIssueListener(NetworkIssueListener listener) {
        if (listener != null) networkIssueListeners.addIfAbsent(listener);
    }

    /**
     * 由网络层调用(节流统一在这里做):reason 仅用于日志/排查展示。
     * 监听方自己的异常一律吞掉 —— 通知失败绝不能影响请求本身的失败语义。
     */
    static void notifyNetworkIssue(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastNetworkIssueAt < NETWORK_ISSUE_THROTTLE_MS) return;
        lastNetworkIssueAt = now;
        for (NetworkIssueListener listener : networkIssueListeners) {
            try {
                listener.onNetworkIssue(reason);
            } catch (Throwable ignored) {
            }
        }
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
    /** 局域网视频中转专用:流式读取媒体,不能让 BODY 日志预读完整响应。 */
    private static volatile OkHttpClient mediaRelayClient = null;
    /** 图片专用客户端(带磁盘缓存):仅给 Picasso 等图片加载用,与 API/搜索流量隔离 */
    private static volatile OkHttpClient imageClient = null;
    /** DoH 切换只换客户端；在途旧客户端与新客户端必须共用这一份磁盘缓存索引。 */
    private static volatile Cache imageDiskCache = null;

    /** 图片磁盘缓存上限(字节)。海报多为几十~几百 KB,100MB 可长期覆盖各页面海报回看 */
    private static final long IMAGE_CACHE_MAX_BYTES = 100L * 1024 * 1024;

    /**
     * 安全 DNS 改动后<b>立即</b>换用新解析器:重建 DoH 解析器 + 本模块按 DoH 构建的客户端
     * ({@code defaultClient}/{@code noRedirectClient}/{@code mediaRelayClient},图片客户端置空下次懒建),
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
     * 诊断用:当前网络实际生效的解析环境(系统下发的 DNS 服务器 + 私人 DNS)。
     * <p>
     * 用于"App 解析不到域名、浏览器却能打开"这类问题:此时必须知道手机当时用的是哪台 DNS ——
     * 是路由器下发的、还是被【私人 DNS】(DoT 主机名)或 VPN 接管了。只读不写,任何异常一律返回空串,
     * 绝不影响请求本身(纯诊断,调用方把结果拼进失败日志即可)。
     * <p>
     * 放在 :core-network 而不是 :spider:这类网络环境信息属网络基础设施的职责,且本模块已持有
     * 注入的 application context(见 {@link #init(Context)})。
     */
    public static String dnsEnvHint() {
        try {
            Context ctx = appContext;
            if (ctx == null) return "网络状态未初始化";
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "无 ConnectivityManager";
            android.net.Network net = cm.getActiveNetwork();
            // 关键分支:断网时这里就是 null —— 必须显式写出来,否则日志只剩一句
            // UnknownHostException,会把"手机没网"误判成"域名/DNS 有问题"
            if (net == null) return "无活动网络";
            android.net.LinkProperties lp = cm.getLinkProperties(net);
            if (lp == null) return "无 LinkProperties";
            StringBuilder sb = new StringBuilder();
            java.util.List<java.net.InetAddress> servers = lp.getDnsServers();
            if (servers != null && !servers.isEmpty()) sb.append("下发DNS=").append(servers);
            else sb.append("下发DNS=空");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                String privateDns = lp.getPrivateDnsServerName();
                if (privateDns != null) sb.append(" 私人DNS=").append(privateDns);
            }
            return sb.toString();
        } catch (Throwable th) {
            // 权限/系统异常也要能看出来(原实现静默返回空串,等于这条诊断白加)
            return "网络状态读不到:" + th.getClass().getSimpleName();
        }
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
        OkHttpClient.Builder mediaBuilder = noRedirectClient.newBuilder();
        for (Iterator<Interceptor> it = mediaBuilder.interceptors().iterator(); it.hasNext(); ) {
            if (it.next() instanceof HttpLoggingInterceptor) it.remove();
        }
        // API 的 10 秒读超时会截断慢速媒体分片;保留连接/DNS/TLS/UA/网络守卫策略。
        mediaRelayClient = mediaBuilder.readTimeout(30, TimeUnit.SECONDS).build();
        // 图片客户端派生自 defaultClient(共享连接池),置空即可:下次取用时按新根重建
        imageClient = null;
    }

    public static OkHttpClient getDefaultClient() {
        return defaultClient;
    }

    public static OkHttpClient getNoRedirectClient() {
        return noRedirectClient;
    }

    /** 局域网视频中转共享客户端:禁用响应体日志,避免视频下载完毕后才开始向浏览器输出。 */
    public static OkHttpClient getMediaRelayClient() {
        return mediaRelayClient;
    }

    /**
     * One cast session's DNS-pinned media client. A fresh pool prevents a connection made by
     * another client from bypassing this session's DNS policy; direct routes ensure the policy
     * checks the media host itself instead of only a system HTTP proxy.
     */
    public static OkHttpClient newScopedMediaRelayClient(Dns pinnedDns) {
        OkHttpClient current = mediaRelayClient;
        if (current == null || pinnedDns == null) throw new IllegalStateException("Media client unavailable");
        return current.newBuilder().dns(pinnedDns).connectionPool(new ConnectionPool())
                .proxy(Proxy.NO_PROXY).build();
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

    /** 清理所有图片客户端共用的磁盘缓存；必须由后台线程调用。 */
    public static void evictImageDiskCache() throws IOException {
        getImageClient(); // 首次清理时懒建缓存；已有 DoH 旧客户端仍引用同一 Cache。
        Cache cache = imageDiskCache;
        if (cache == null) throw new IOException("图片磁盘缓存不可用");
        cache.evictAll();
    }

    private static OkHttpClient buildImageClient() {
        if (appContext == null) return defaultClient;
        try {
            File dir = new File(appContext.getCacheDir(), "image_http_cache");
            if (!dir.exists() && !dir.mkdirs()) {
                return defaultClient; // 缓存目录创建失败:退回默认客户端(无磁盘缓存,功能不受影响)
            }
            // 从"静默"根起手:图片是后台链路,一个海报加载失败不该把用户弹到"网络不可用"整屏页
            // (原来从 defaultClient.newBuilder() 派生,顺带继承了"失败即上报"的守卫拦截器)
            Cache cache = imageDiskCache;
            if (cache == null) {
                cache = new Cache(dir, IMAGE_CACHE_MAX_BYTES);
                imageDiskCache = cache;
            }
            return newBaseBuilder(false)
                    .cache(cache)
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
    /**
     * 当前是否有可用网络（供 {@link NetworkGuardInterceptor} 做"无网络快速失败"）。
     * <p>
     * 只看 App 的默认网络：其它网络（例如蓝牙或局域网链路）不能承载普通请求。
     * 默认网络需带 {@code NET_CAPABILITY_INTERNET}；不要求 VALIDATED，避免系统探测尚未完成时拦掉可用请求。
     * 读不到（context 未注入、权限异常、系统实现差异）一律返回 true：守卫只是优化，
     * 绝不能因为它自己出问题就把请求拦死。
     * <p>
     * 判定口径与 {@code SystemStateMonitor.hasUsableNetwork()}（:common，页面侧"有网/没网"的唯一判定）
     * <b>必须逐字一致</b>：两处结论相反就会出现"页面以为有网、守卫以为没网"这类诡异现象。
     * 为什么不合成一处：模块门禁 {@code checkModuleDependencies} 明令 <b>:core-network 禁止依赖 :common</b>，
     * 只能各留一份 —— 改判定口径时<b>两处一起改</b>（VPN/以太网/蓝牙共享算不算有网就是这条口径）。
     */
    public static boolean hasNetwork() {
        try {
            Context ctx = appContext;
            if (ctx == null) return true;
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            android.net.Network active = cm.getActiveNetwork();
            if (active == null) return false;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            // 切网瞬间能力快照可能暂时读不到；此时让实际请求判断，避免误拦。
            return caps == null || caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable th) {
            return true;
        }
    }

    /** 常规根 Builder：失败会经网络层上报（用户可见的页面/接口/播放/爬虫链路用这个） */
    public static OkHttpClient.Builder newBaseBuilder() {
        return newBaseBuilder(true);
    }

    /**
     * 根 Builder。
     *
     * @param notifyOnIssue 请求因无网失败时是否上报（{@code OkGoHelper.notifyNetworkIssue} → "网络不可用"整屏页）。
     *                      后台链路（图片预取/下载续传/自动检查更新）传 false：它们不是<b>用户主动发起</b>的操作，
     *                      失败却把用户从正在看的内容上弹走，违背"没做网络操作时不弹页"的设计意图。
     *                      传 false 仍然会快速失败（省掉一次白等的超时），只是不上报。
     */
    public static OkHttpClient.Builder newBaseBuilder(boolean notifyOnIssue) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        // 无网络快速失败:必须放在最前面(拦截器按加入顺序执行),断网时连日志拦截器都不进,
        // 直接抛出 "当前无网络,请检查网络连接"(见 NetworkGuardInterceptor 上的说明)
        builder.addInterceptor(new NetworkGuardInterceptor(notifyOnIssue));
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

    /**
     * DoH 解析专用客户端 Builder。
     * <p>
     * 与业务客户端共用日志/SSL/压缩/连接规格配置(一处维护),但 DNS 固定为系统 DNS ——
     * 它要用来解析 DoH 服务自己的域名,装上 DoH 解析器会形成"用 DoH 解析 DoH 域名"的自锁。
     */
    public static OkHttpClient.Builder newDohClientBuilder() {
        OkHttpClient.Builder builder = newBaseBuilder();
        builder.dns(Dns.SYSTEM);
        return builder;
    }

    /** App 启动时调用一次（context 注入；Exo/Picasso 初始化由 app 侧在 init 后自行完成） */
    public static void init(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
        initDnsOverHttps();
        // 首次构建与"换 DoH 后重建"同一段代码:两处各自 build 迟早漂移(超时/重定向配置漏改一边)
        rebuildDohClients();
    }

    /** 仅对用户指定的精确主机放行异常证书，其他主机继续验证系统信任链与主机名。 */
    private static synchronized void setOkHttpSsl(OkHttpClient.Builder builder) {
        try {
            if (SystemConfig.isIgnoreSslError()) {
                final X509TrustManager systemTrust = systemTrustManager();
                final SSLSocketFactory sslSocketFactory = new SSLCompat();
                builder.sslSocketFactory(sslSocketFactory, SSLCompat.TM);
                builder.hostnameVerifier((host, session) -> {
                    if (SystemConfig.isSslExceptionAllowedForHost(host)) return true;
                    try {
                        Certificate[] peers = session.getPeerCertificates();
                        X509Certificate[] chain = new X509Certificate[peers.length];
                        for (int i = 0; i < peers.length; i++) {
                            if (!(peers[i] instanceof X509Certificate)) return false;
                            chain[i] = (X509Certificate) peers[i];
                        }
                        if (chain.length == 0) return false;
                        systemTrust.checkServerTrusted(chain, chain[0].getPublicKey().getAlgorithm());
                        return HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session);
                    } catch (Exception error) {
                        return false;
                    }
                });
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static X509TrustManager systemTrustManager() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        }
        throw new IllegalStateException("No system X509 trust manager");
    }
}

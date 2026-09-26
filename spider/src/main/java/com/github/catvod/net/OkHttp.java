package com.github.catvod.net;

import android.net.Uri;

import java.util.HashMap;

import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.github.tvbox.osc.bean.Doh;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.dnsoverhttps.DnsOverHttps;

public class OkHttp {

    private static final int TIMEOUT = 30 * 1000;
    private static final int CACHE = 100 * 1024 * 1024;

    /** 解析器:写方在设置线程/静态初始化,读方在爬虫请求线程,故 volatile 保证可见性 */
    private volatile DnsOverHttps dns;
    private volatile OkHttpClient client;
    private ProxySelector selector;

    /** 当前生效的 DoH 地址(空串=关闭):仅用于日志/排查,行为以 {@link #dns} 为准 */
    private volatile String dohUrl = "";

    private static class Loader {
        static volatile OkHttp INSTANCE = new OkHttp();
    }

    /**
     * 订阅 :core-network 的安全 DNS 变更(设置页改"安全 DNS"后立即重建本模块 client)。
     * <p>
     * 方向:业务模块 → 基础设施,合法;反过来(core-network 直接调 :spider)会形成反向依赖。
     * 放在静态初始化而非 app 侧显式调用:app/UI 一律不许直连 spider 实现(AGENTS §二),
     * 爬虫自身订阅后,合并 jar 内的爬虫也能在首次触碰本类时自动跟上设置。
     */
    static {
        initDoh(com.github.tvbox.osc.util.OkGoHelper.currentDohUrl());
        com.github.tvbox.osc.util.OkGoHelper.addDohChangeListener(OkHttp::initDoh);
    }

    /**
     * 按当前设置重建 DoH 解析器(空串=关闭)。
     * <p>
     * 必须换掉 {@code client}:OkHttp 的 DNS 在 build 时固化,只改 dns 字段对已建 client 无效 ——
     * 这正是"爬虫侧从不跟随安全 DNS 设置"的原因(原 setDoh 全仓无调用点)。
     */
    private static synchronized void initDoh(String url) {
        String want = url == null ? "" : url;
        try {
            get().setDoh(new Doh().name("").url(want));
        } catch (Throwable th) {
            // 安全 DNS 是可选增强,它失败绝不能让本类**静态初始化**失败:
            // <clinit> 抛异常会变成 ExceptionInInitializerError 并永久毒化本类
            // (之后每次 OkHttp.client() 都是 NoClassDefFoundError → 该进程内所有 JS/JAR 源
            //  永远取不到页面,线上表现就是"[maccms] 取页面为空"却看不出原因)。
            // 这里退化成"按关闭处理":记日志 + 让期望值仍然生效(免得每次建 client 都白试一遍)。
            LOG.e("catvod-http", "安全 DNS 初始化失败,本次按关闭处理(下次改设置或重启可重试): " + th);
            get().disableDoh(want);
        }
    }

    public static OkHttp get() {
        return Loader.INSTANCE;
    }

    /** 当前生效的 DoH 地址(空串=关闭);排查"爬虫到底走没走安全 DNS"时读它 */
    public static String dohUrl() {
        return get().dohUrl;
    }

    /**
     * 当前解析器的人类可读名字,用于失败日志。
     * <p>
     * {@code UnknownHostException} 这类"App 解析不了、浏览器能打开"的问题只可能是安全 DNS
     * (腾讯/阿里/360 的 DoH 会对部分域名返回空)或系统 DNS,日志里不写出来就只能猜。
     */
    public static String dnsName() {
        String url = dohUrl();
        StringBuilder sb = new StringBuilder(url == null || url.isEmpty() ? "系统DNS" : "安全DNS=" + url);
        // 网络状态(SystemStateMonitor 单点):断网时所有解析都会以 "Unable to resolve host" 的面目出现 ——
        // 不写出来就会把"手机没网"误判成"域名被拦/DoH 有问题"(实测踩过,绕了一大圈)
        String net = networkState();
        if (!net.isEmpty()) sb.append(";网络=").append(net);
        // 再补上"手机当时的下发 DNS + 私人 DNS"(见 OkGoHelper.dnsEnvHint)
        String env = com.github.tvbox.osc.util.OkGoHelper.dnsEnvHint();
        if (env != null && !env.isEmpty()) sb.append(';').append(env);
        return sb.toString();
    }

    /** 当前网络状态(WIFI/CELLULAR/NONE → "无网络");未初始化或读不到时返回空串 */
    private static String networkState() {
        try {
            com.github.tvbox.osc.state.SystemState state =
                    com.github.tvbox.osc.state.SystemStateMonitor.get().getCurrentState();
            if (state == null || state.network == null || state.network.isEmpty()) return "";
            return com.github.tvbox.osc.state.SystemStateMonitor.VAL_NONE.equals(state.network) ? "无网络" : state.network;
        } catch (Throwable th) {
            return "";
        }
    }

    public static Dns dns() {
        return get().dns != null ? get().dns : Dns.SYSTEM;
    }

    public void setDoh(Doh doh) {
        String url = doh == null || doh.getUrl() == null ? "" : doh.getUrl();
        if (url.isEmpty()) {
            // 关闭安全 DNS(默认就是关):原来这里仍会 new Cache(Path.doh(), 100MB) 建缓存目录、
            // 建一个用不上的 DoH client —— 既白碰磁盘,又依赖已注入的 context(静态初始化里直接
            // NPE,整类被毒化)。关闭时什么都不做。
            disableDoh("");
            return;
        }
        // 安全红线:DoH 客户端默认走系统证书校验;仅用户显式开启"忽略证书错误"才信任任意证书
        // 用 :core-network 的 DoH 专用 Builder:它与业务客户端共享日志/SSL/压缩/连接规格配置,
        // 又不会把 DoH 自己的 DNS 装进去(否则"用 DoH 解析 DoH 域名"会自锁)
        OkHttpClient dohClient = OkGoHelper.newDohClientBuilder().cache(new Cache(Path.doh(), CACHE)).build();
        dohUrl = url;
        dns = new DnsOverHttps.Builder().client(dohClient).url(HttpUrl.get(dohUrl)).bootstrapDnsHosts(doh.getHosts()).build();
        // 置空即"下次懒建":正在跑的请求仍持有旧 client(不打断),不再有请求方来取时随之被 GC
        client = null;
    }

    /** 不启用安全 DNS(未配置或初始化失败):只记录期望值供排查/比对,不持有 DoH 客户端 */
    void disableDoh(String url) {
        dohUrl = url == null ? "" : url;
        dns = null;
        client = null;
    }

    public void setProxy(String proxy) {
        ProxySelector.setDefault(selector());
        selector().setProxy(proxy);
        client = null;
    }

    public static ProxySelector selector() {
        if (get().selector != null) return get().selector;
        return get().selector = new ProxySelector();
    }

    /** 懒建根 client(双重检查):原实现无同步,并发首次取用时可能建出两套连接池 */
    public static OkHttpClient client() {
        OkHttpClient c = get().client;
        if (c != null) return c;
        synchronized (OkHttp.class) {
            c = get().client;
            if (c == null) {
                c = getBuilder().build();
                get().client = c;
            }
            return c;
        }
    }

    /** timeout<=0 一律按默认超时处理:0 在 OkHttp 里等于"永不超时",正是"源卡死拖死整条道"的来源 */
    private static int safeTimeout(int timeout) {
        return timeout <= 0 ? TIMEOUT : timeout;
    }

    public static OkHttpClient client(int timeout) {
        int ms = safeTimeout(timeout);
        return client().newBuilder().connectTimeout(ms, TimeUnit.MILLISECONDS).readTimeout(ms, TimeUnit.MILLISECONDS).writeTimeout(ms, TimeUnit.MILLISECONDS).build();
    }

    public static OkHttpClient noRedirect(int timeout) {
        int ms = safeTimeout(timeout);
        return client().newBuilder().connectTimeout(ms, TimeUnit.MILLISECONDS).readTimeout(ms, TimeUnit.MILLISECONDS).writeTimeout(ms, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build();
    }

    public static OkHttpClient client(boolean redirect, int timeout) {
        return redirect ? client(timeout) : noRedirect(timeout);
    }

    public static String string(String url) {
        // try-with-resources + null 体判断:原实现 body() 为 null 时 NPE、且异常一律吞成 "",
        // 拿不到内容时完全看不出是网络失败还是响应为空
        if (url == null || !url.startsWith("http")) return "";
        try (Response response = newCall(url).execute()) {
            return response.body() == null ? "" : response.body().string();
        } catch (Exception e) {
            LOG.e("catvod-http", url + " 请求失败(" + dnsName() + "): " + e);
            return "";
        }
    }

    public static Call newCall(String url) {
        Uri uri = Uri.parse(url);
        if (uri.getUserInfo() != null) return newCall(url, Headers.of("Authorization", Util.basic(uri)));
        return client().newCall(new Request.Builder().url(url).build());
    }

    public static Call newCall(OkHttpClient client, String url) {
        return client.newCall(new Request.Builder().url(url).build());
    }

    public static Call newCall(OkHttpClient client, String url, Headers headers) {
        return client.newCall(new Request.Builder().url(url).headers(headers).build());
    }

    public static Call newCall(String url, Headers headers) {
        return client().newCall(new Request.Builder().url(url).headers(headers).build());
    }

    public static Call newCall(String url, Headers headers, HashMap<String, String> params) {
        return client().newCall(new Request.Builder().url(buildUrl(url, params)).headers(headers).build());
    }

    public static Call newCall(String url, Headers headers, RequestBody body) {
        return client().newCall(new Request.Builder().url(url).headers(headers).post(body).build());
    }

    public static Call newCall(OkHttpClient client, String url, RequestBody body) {
        return client.newCall(new Request.Builder().url(url).post(body).build());
    }

    public static FormBody toBody(HashMap<String, String> params) {
        FormBody.Builder body = new FormBody.Builder();
        for (Map.Entry<String, String> entry : params.entrySet()) body.add(entry.getKey(), entry.getValue());
        return body.build();
    }

    private static HttpUrl buildUrl(String url, HashMap<String, String> params) {
        HttpUrl.Builder builder = Objects.requireNonNull(HttpUrl.parse(url)).newBuilder();
        // 同 HttpUrls.buildUrl:重复键要覆盖而不是追加(api 自带 ac/t 的 CMS 源否则会拼出两组参数)
        for (Map.Entry<String, String> entry : params.entrySet()) builder.setQueryParameter(entry.getKey(), entry.getValue());
        return builder.build();
    }

    private static OkHttpClient.Builder getBuilder() {
        // 自愈:本类若在 :core-network 初始化之前就被触碰(合并 jar 早跑 / 启动顺序变化),
        // 静态初始化那次会拿到"未启用";建 client 前比对一次,保证爬到的一定是当前设置
        String configured = com.github.tvbox.osc.util.OkGoHelper.currentDohUrl();
        if (!configured.equals(get().dohUrl)) initDoh(configured);
        // 从 :core-network 的公共根 Builder 起手(日志/UA/Brotli/连接规格/安全 DNS/SSL 一处维护),
        // 满足 AGENTS §二"网络客户端收口 core-network,业务模块不得自行 new OkHttpClient.Builder()"
        OkHttpClient.Builder builder = OkGoHelper.newBaseBuilder()
                .addInterceptor(new OkhttpInterceptor())
                .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .dns(dns());
        builder.proxySelector(selector());
        return builder;
    }
}

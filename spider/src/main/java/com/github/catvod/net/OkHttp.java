package com.github.catvod.net;

import android.net.Uri;

import java.util.HashMap;

import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.github.tvbox.osc.bean.Doh;
import com.github.tvbox.osc.config.SystemConfig;

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
        get().setDoh(new Doh().name("").url(url == null ? "" : url));
    }

    public static OkHttp get() {
        return Loader.INSTANCE;
    }

    /** 当前生效的 DoH 地址(空串=关闭);排查"爬虫到底走没走安全 DNS"时读它 */
    public static String dohUrl() {
        return get().dohUrl;
    }

    public static Dns dns() {
        return get().dns != null ? get().dns : Dns.SYSTEM;
    }

    public void setDoh(Doh doh) {
        // 安全红线:DoH 客户端默认走系统证书校验;仅用户显式开启"忽略证书错误"才信任任意证书
        OkHttpClient.Builder dohBuilder = new OkHttpClient.Builder().cache(new Cache(Path.doh(), CACHE));
        if (SystemConfig.isIgnoreSslError()) {
            dohBuilder.sslSocketFactory(new SSLCompat(), SSLCompat.TM);
        }
        OkHttpClient dohClient = dohBuilder.build();
        dohUrl = doh.getUrl();
        dns = dohUrl.isEmpty() ? null : new DnsOverHttps.Builder().client(dohClient).url(HttpUrl.get(dohUrl)).bootstrapDnsHosts(doh.getHosts()).build();
        // 置空即"下次懒建":正在跑的请求仍持有旧 client(不打断),不再有请求方来取时随之被 GC
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

    public static OkHttpClient client() {
        if (get().client != null) return get().client;
        return get().client = getBuilder().build();
    }

    public static OkHttpClient client(int timeout) {
        return client().newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS).build();
    }

    public static OkHttpClient noRedirect(int timeout) {
        return client().newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build();
    }

    public static OkHttpClient client(boolean redirect, int timeout) {
        return redirect ? client(timeout) : noRedirect(timeout);
    }

    public static String string(String url) {
        try {
            return url.startsWith("http") ? newCall(url).execute().body().string() : "";
        } catch (Exception e) {
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
        for (Map.Entry<String, String> entry : params.entrySet()) builder.addQueryParameter(entry.getKey(), entry.getValue());
        return builder.build();
    }

    private static OkHttpClient.Builder getBuilder() {
        // 自愈:本类若在 :core-network 初始化之前就被触碰(合并 jar 早跑 / 启动顺序变化),
        // 静态初始化那次会拿到"未启用";建 client 前比对一次,保证爬到的一定是当前设置
        String configured = com.github.tvbox.osc.util.OkGoHelper.currentDohUrl();
        if (!configured.equals(get().dohUrl)) initDoh(configured);
        OkHttpClient.Builder builder = new OkHttpClient.Builder().addInterceptor(new OkhttpInterceptor()).connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS).readTimeout(TIMEOUT, TimeUnit.MILLISECONDS).writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS).dns(dns());
        // 安全红线:默认系统证书校验(证书链 + 主机名校验);仅用户显式开启"忽略证书错误"(默认关)
        // 才为个别自签名源站点挂载 SSLCompat(信任任意证书)放行,禁止无条件全局关闭 TLS 校验。
        if (SystemConfig.isIgnoreSslError()) {
            builder.sslSocketFactory(new SSLCompat(), SSLCompat.TM);
        }
        builder.proxySelector(selector());
        return builder;
    }
}

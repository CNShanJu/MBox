package com.github.catvod.crawler;

import android.content.Context;

import com.github.tvbox.osc.util.OkGoHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;

public abstract class Spider {

    public void init(Context context) throws Exception {}

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    public String homeContent(boolean filter) throws Exception {
        return "";
    }

    public String homeVideoContent() throws Exception {
        return "";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap < String, String > extend) throws Exception {
        return "";
    }

    public String detailContent(List < String > ids) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return "";
    }

    public String playerContent(String flag, String id, List < String > vipFlags) throws Exception {
        return "";
    }

    public boolean manualVideoCheck() throws Exception {
        return false;
    }

    public boolean isVideoFormat(String url) throws Exception {
        return false;
    }

    public Object[] proxyLocal(Map < String, String > params) throws Exception {
        return null;
    }

    public void cancelByTag() {

    }

    public void destroy() {}

    /**
     * 供爬虫(含合并版 jar)取用自定义 DNS。
     * 注意:okhttp4 的 {@code OkHttpClient.Builder.dns()} 参数非空,而"安全DNS"关闭时
     * {@link OkGoHelper#dnsOverHttps} 为 null——直接返回 null 会导致合并 jar 初始化时抛
     * {@code NPE: Parameter specified as non-null is null ... dns},进而 ExceptionInInitializerError 崩掉应用。
     * 因此 DoH 未启用时回退系统 DNS(Dns.SYSTEM),保证调用方永远拿到非空值。
     */
    public static Dns safeDns() {
        Dns dns = OkGoHelper.dnsOverHttps;
        return dns != null ? dns : Dns.SYSTEM;
    }
}

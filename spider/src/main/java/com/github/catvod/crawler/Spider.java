package com.github.catvod.crawler;

import android.content.Context;

import com.github.tvbox.osc.util.OkGoHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;

public abstract class Spider {

    /**
     * 当前站点 key（订阅里的唯一标识）。
     * <p>
     * 部分 jar 会把它拼进本地代理地址（{@code /proxy?siteKey=...}）用来标识"这个请求属于哪个站点"。
     * MBox 的 {@code proxyInvoke} 正是按这个参数反查 jar 的，但此前从没给 jar 赋过值——
     * jar 拼出来的地址里始终是 null，反查只能落到"最近用过的 jar"这条兜底路径上，
     * 多站点并发时就会串到别的 jar。由 {@code JarLoader.getSpider} 在 init 前赋值。
     */
    public String siteKey;

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

    /**
     * 通用命令通道（对齐 catvod / AVBox 的 {@code Spider.action}）：jar 可用它挂
     * {@code logout}、{@code clearCookie} 这类「非播放动作」。App 侧清理网盘凭据时，
     * 除了删本地文件，还会尝试调 {@code action("logout")} 让源服务端的 token 失效——
     * 服务端设备绑定型源（本地不落 token）只有这一条路能真正退出登录。
     * <p>默认返回 null：没实现该动作的 jar 直接回落 null，调用方静默跳过、不报错。
     */
    public String action(String action) {
        return null;
    }

    public void cancelByTag() {

    }

    public void destroy() {}

    /**
     * 供爬虫(含合并版 jar)取用自定义 DNS。
     * 注意:okhttp4 的 {@code OkHttpClient.Builder.dns()} 参数非空,而"安全DNS"关闭时
     * {@link OkGoHelper#getDnsOverHttps()} 返回 null——直接返回 null 会导致合并 jar 初始化时抛
     * {@code NPE: Parameter specified as non-null is null ... dns},进而 ExceptionInInitializerError 崩掉应用。
     * 因此 DoH 未启用时回退系统 DNS(Dns.SYSTEM),保证调用方永远拿到非空值。
     */
    public static Dns safeDns() {
        Dns dns = OkGoHelper.getDnsOverHttps();
        return dns != null ? dns : Dns.SYSTEM;
    }
}

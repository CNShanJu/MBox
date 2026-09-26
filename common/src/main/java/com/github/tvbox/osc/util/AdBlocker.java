package com.github.tvbox.osc.util;

import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 广告域名名单(极简 {@code url.contains(host)} 匹配)。
 *
 * <p>名单分两层,各自的生命周期不同:
 * <ul>
 *   <li><b>默认名单</b>(应用内置 {@code ads},见 ApiConfig 的 defaultIJKADS):进程内只装一次,任何源都生效;</li>
 *   <li><b>当前源名单</b>(源配置里的 {@code ads}):<b>每加载一次源配置就整体替换</b>。</li>
 * </ul>
 *
 * <p>为什么必须分两层:旧实现只有一份名单,且用 {@code AdBlocker.isEmpty()} 当"只初始化一次"的开关 ——
 * 于是<b>第一个源的 ads 会永远粘着后面所有源</b>(切源后新源的 ads 加不进来,旧源的 ads 一直在拦),
 * 而 {@code clear()} 全仓没有任何调用点,名单一旦装上就无法刷新。
 *
 * <p>域名一律小写归一后保存(匹配时待查 URL 也转小写),大小写不再影响命中;
 * 读取可能发生在 WebView 拦截线程,写入发生在配置加载线程,故默认名单用写时复制列表、
 * 源名单用 volatile + 不可变列表,避免并发读写 ArrayList。
 */
public class AdBlocker {

    /** 应用内置广告域名(只加一次;写时复制,读线程无需加锁) */
    private static final List<String> DEFAULT_HOSTS = new CopyOnWriteArrayList<>();

    /** 当前源的广告域名(整体替换,故 volatile + 不可变列表) */
    private static volatile List<String> SOURCE_HOSTS = Collections.emptyList();

    private AdBlocker() {
    }

    /**
     * 装入应用内置广告域名。幂等:重复调用只补齐缺失项(可安全地在每次加载配置时调用)。
     */
    public static void ensureDefaultHosts(Collection<String> hosts) {
        if (hosts == null || hosts.isEmpty()) return;
        for (String host : hosts) {
            String h = normalize(host);
            if (h != null && !DEFAULT_HOSTS.contains(h)) DEFAULT_HOSTS.add(h);
        }
    }

    /**
     * 设置<b>当前源</b>的广告域名(整体替换,传 null/空即清空)。
     * 每次解析订阅配置后调用,保证切源后不残留上一个源的拦截名单。
     */
    public static void setSourceHosts(Collection<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            SOURCE_HOSTS = Collections.emptyList();
            return;
        }
        Set<String> set = new LinkedHashSet<>();
        for (String host : hosts) {
            String h = normalize(host);
            if (h != null) set.add(h);
        }
        SOURCE_HOSTS = Collections.unmodifiableList(new ArrayList<>(set));
    }

    /** 名单是否为空(默认 + 当前源) */
    public static boolean isEmpty() {
        return DEFAULT_HOSTS.isEmpty() && SOURCE_HOSTS.isEmpty();
    }

    /** 清空全部名单(默认 + 源)。仅供测试/重置使用,业务侧请用 ensureDefaultHosts/setSourceHosts */
    public static void clear() {
        DEFAULT_HOSTS.clear();
        SOURCE_HOSTS = Collections.emptyList();
    }

    /** 该地址是否命中广告域名(大小写不敏感;url/host 为空时一律不命中) */
    public static boolean isAd(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        for (String host : DEFAULT_HOSTS) {
            if (lower.contains(host)) return true;
        }
        for (String host : SOURCE_HOSTS) {
            if (lower.contains(host)) return true;
        }
        return false;
    }

    /** 空响应体(拦掉广告/统计资源时回给 WebView 的占位响应) */
    public static WebResourceResponse createEmptyResource() {
        return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
    }

    /** trim + 小写;"https://Host/ad.js" 这类整条地址也按小写子串匹配,不做 URL 解析 */
    private static String normalize(String host) {
        if (host == null) return null;
        String h = host.trim().toLowerCase();
        return h.isEmpty() ? null : h;
    }
}

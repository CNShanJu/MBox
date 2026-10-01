package com.github.tvbox.osc.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 订阅管理「直播源」页的展示模型与纯逻辑(不碰 Android,便于 JVM 单测)。
 * <p>
 * 两类条目:
 * <ul>
 *   <li><b>订阅导入</b>({@link Entry#fromSubscription} 非空):视频订阅里 {@code lives} 自带的直播源。
 *       跟着订阅走 —— 换订阅自动清掉旧的、自动补上新的,页面<b>不允许删除</b>(要删就删那条订阅);</li>
 *   <li><b>用户自建</b>({@code fromSubscription == null}):用户自己加的直播源(存
 *       {@link LiveConfig#liveHistory()}),可加可删。</li>
 * </ul>
 * 勾选语义:<b>单选</b>;勾中的地址就是 {@code SystemConfig} 里的"当前直播源"(直播页据此拉取);
 * 一条都没勾 = 用订阅自带的直播(与"用户直播源为空"时的既有回落一致)。
 */
public final class LiveSourceEntries {

    /** 订阅里 lives 解析出来的一条(由 LiveChannelConfigApi.SubscribeLiveSource 映射而来) */
    public static final class Imported {
        public final String name;
        public final String url;

        public Imported(String name, String url) {
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
        }
    }

    /** 列表里的一条直播源 */
    public static final class Entry {
        /** 展示名(已兜底推导,不会为空) */
        public final String name;
        /** 直播源地址;空 = 订阅里的内嵌频道分组(没有可单独指定的地址) */
        public final String url;
        /** 来源订阅名;null = 用户自建 */
        public final String fromSubscription;
        /** 是否当前生效的直播源 */
        public final boolean checked;

        Entry(String name, String url, String fromSubscription, boolean checked) {
            this.name = name;
            this.url = url;
            this.fromSubscription = fromSubscription;
            this.checked = checked;
        }

        /** 用户自建的才能删;订阅导入的跟着订阅走 */
        public boolean removable() {
            return fromSubscription == null;
        }

        /** 订阅里的内嵌频道分组:不可单独指定为直播源,页面只作说明 */
        public boolean embedded() {
            return url.isEmpty();
        }
    }

    private LiveSourceEntries() {
    }

    /**
     * 组装列表:订阅导入的在前(保持订阅里的顺序),用户自建的在后(保持用户自己的顺序)。
     * 同一个地址只出现一次(订阅里先到先得),免得"订阅给的"和"自己加过的"重复两行。
     *
     * @param subscriptionName 当前订阅名(用于标注「来自:&lt;订阅名&gt;」;空则显示"订阅")
     * @param imported         订阅自带的直播源(见 {@code LiveChannelConfigApi.getSubscribeLiveSources()})
     * @param userUrls         用户自建的直播源地址(见 {@link LiveConfig#liveHistory()})
     * @param checkedUrl       当前生效的直播源地址(见 {@code SystemConfig.getLiveUrl()};空 = 用订阅自带的直播)
     */
    public static List<Entry> build(String subscriptionName,
                                    List<Imported> imported,
                                    List<String> userUrls,
                                    String checkedUrl) {
        String checked = checkedUrl == null ? "" : checkedUrl.trim();
        String sub = subscriptionName == null || subscriptionName.trim().isEmpty()
                ? "订阅" : subscriptionName.trim();
        List<Entry> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (imported != null) {
            for (Imported it : imported) {
                if (it == null) continue;
                String url = it.url == null ? "" : it.url.trim();
                String name = it.name == null ? "" : it.name.trim();
                // 去重键:有地址按地址,内嵌分组按名字(它们没有地址,只能靠名字区分)
                String key = url.isEmpty() ? "\u0000embed:" + name + "@" + out.size() : url;
                if (!seen.add(key)) continue;
                if (name.isEmpty()) name = displayName(url);
                if (name.isEmpty()) name = "订阅自带分组";
                out.add(new Entry(name, url, sub, !url.isEmpty() && url.equals(checked)));
            }
        }
        if (userUrls != null) {
            for (String raw : userUrls) {
                if (raw == null) continue;
                String url = raw.trim();
                if (url.isEmpty() || seen.contains(url)) continue;
                seen.add(url);
                String name = displayName(url);
                out.add(new Entry(name.isEmpty() ? url : name, url, null, url.equals(checked)));
            }
        }
        // 当前生效的直播源不在清单里(首次装机内置的默认源就是这种:在 SystemConfig 里、但不在用户历史里)
        // → 补一行出来,否则"实际在用的那条"看不见勾选,用户会以为没在用它
        if (!checked.isEmpty() && !seen.contains(checked)) {
            String name = displayName(checked);
            out.add(new Entry(name.isEmpty() ? checked : name, checked, null, true));
        }
        return out;
    }

    /**
     * 没有名字时的展示名:<b>主机名 + 最后一段路径</b>(如 {@code 192.168.1.5:8080/iptv.m3u})。
     * 太长按 32 字符截断加省略号;解析不出来就原样返回(再截断)。
     */
    public static String displayName(String url) {
        String s = url == null ? "" : url.trim();
        if (s.isEmpty()) return "";
        String host = "";
        String tail = "";
        try {
            URI uri = URI.create(s.replace(" ", ""));
            host = uri.getHost() == null ? "" : uri.getHost();
            if (!host.isEmpty() && uri.getPort() > 0) host = host + ":" + uri.getPort();
            String path = uri.getPath() == null ? "" : uri.getPath();
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash < path.length() - 1) tail = path.substring(slash + 1);
        } catch (Throwable ignore) {
            // 不是合法 URI(如带中文域名/奇怪字符):退回整串截断,不抛给页面
        }
        String out = host.isEmpty() ? s : (tail.isEmpty() ? host : host + "/" + tail);
        return out.length() <= MAX_NAME ? out : out.substring(0, MAX_NAME - 1) + "…";
    }

    /** 展示名上限(字符) */
    private static final int MAX_NAME = 32;
}

package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.google.gson.JsonArray;

import java.util.List;

/**
 * 直播频道配置契约（改进.txt 收口：直播页读取频道分组 / 注入直播源数据）。
 * <p>
 * 具体实现由 AppCompositionRoot 注入（桥接 :spider ApiConfig），直播 UI 不直读其实现类。
 */
public interface LiveChannelConfigApi {

    /**
     * 主直播分组:{@code live[]} 里配置的直播源(单个"待拉取"的代理分组);
     * 没配直播源时才回落到订阅源自带的直播。空 = 没有直播可用。
     */
    List<LiveChannelGroup> getChannelGroupList();

    /**
     * 兜底直播分组 = <b>订阅源自带</b>的直播(内嵌频道分组,或订阅源里的直播地址包成的代理分组)。
     * 只在主直播源没内容/加载失败时才用;没有则为空列表。
     */
    List<LiveChannelGroup> getFallbackChannelGroupList();

    /**
     * <b>订阅源自带的直播源清单</b>(名字 + 地址),顺序与订阅配置里 {@code lives} 一致。
     * <p>
     * 订阅管理页的「直播源」标签用它列出"跟着订阅走、不可删除"的条目(标注「来自:&lt;订阅名&gt;」)。
     * 与 {@link #getFallbackChannelGroupList()} 的区别:后者在"用户已配直播源"时才是兜底,
     * 且在"没配用户直播源"时会被清空(那份就是主列表本身);本清单<b>始终保留</b>解析结果,
     * 与用户是否配置了直播源无关,页面不需要关心这些内部分支。
     */
    List<SubscribeLiveSource> getSubscribeLiveSources();

    /** 当前已解析的订阅直播源所属地址；切源后的旧数据不得归属到新订阅。 */
    String getLoadedSubscriptionUrl();

    /** 订阅源自带的单个直播源;{@link #url} 为空 = 订阅里的<b>内嵌频道分组</b>(不是单个直播源地址) */
    final class SubscribeLiveSource {
        /** 展示名(订阅里 lives 的分组名;订阅没给名字时为空串,由页面按地址推导) */
        public final String name;
        /** 直播源地址;空串 = 内嵌频道分组,没有可单独拉取的地址 */
        public final String url;

        public SubscribeLiveSource(String name, String url) {
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
        }

        /** 是否是"可单独指定为直播源"的地址(内嵌分组返回 false) */
        public boolean hasUrl() {
            return !url.isEmpty();
        }

        @Override
        public String toString() {
            return "SubscribeLiveSource{name='" + name + "', url='" + url + "'}";
        }
    }

    /** 用直播源 json(lives 数组)重建频道分组 */
    void loadLives(JsonArray livesArray);
}

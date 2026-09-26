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

    /** 用直播源 json(lives 数组)重建频道分组 */
    void loadLives(JsonArray livesArray);
}

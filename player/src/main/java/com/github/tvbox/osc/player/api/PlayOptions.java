package com.github.tvbox.osc.player.api;

import java.util.Map;

/**
 * 播放参数（引擎无关）。
 */
public class PlayOptions {

    /** 播放地址（经 SpiderApi.resolvePlayUrl 解析后的真实地址） */
    public String url;

    /** 请求头（防盗链源必须携带） */
    public Map<String, String> headers;

    /** 片名（外部播放器/通知用） */
    public String title;

    /** 字幕地址（可空） */
    public String subtitle;

    /** 起始播放位置（毫秒） */
    public long startPositionMs = 0;

    /** 内核选择（-1=默认，与 PlayerHelper 的 playType 对应） */
    public int playType = -1;

    /** 是否循环播放 */
    public boolean looping = false;

    public static PlayOptions of(String url) {
        PlayOptions o = new PlayOptions();
        o.url = url;
        return o;
    }
}

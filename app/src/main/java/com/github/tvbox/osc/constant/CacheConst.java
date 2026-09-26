package com.github.tvbox.osc.constant;


public class CacheConst {

    /**
     * 存储视频总时长的SP库,key为对应视频的path
     */
    public static final String VIDEO_DURATION_SP = "video_duration_sp";
    /**
     * 存储视频播放进度的SP库,key为对应视频的path
     */
    public static final String VIDEO_PROGRESS_SP = "video_progress_sp";

    /**
     * 存储"播放过的剧集"的SP库:key=sourceKey|vodId,value=已播放过的集索引(StringSet,元素为 playIndex 字符串)。
     * 下载完成列表据此把播放过的剧集标题置灰。
     */
    public static final String VIDEO_PLAYED_SP = "video_played_sp";

}
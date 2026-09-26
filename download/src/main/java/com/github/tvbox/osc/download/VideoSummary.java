package com.github.tvbox.osc.download;

/**
 * 视频级下载聚合（展示模型，UI 直接渲染）：已下载 N / 下载中 M / 已暂停 / 失败 / 未下载。
 */
public class VideoSummary {

    /** 已下载（档案存在且文件在） */
    public int downloaded;

    /** 下载中（含排队/调度暂停/网络暂停/校验/合并） */
    public int downloading;

    /** 用户手动暂停 */
    public int paused;

    /** 失败（可重试） */
    public int failed;

    /** 未下载 */
    public int notDownloaded;

    /** 总集数 */
    public int total;
}

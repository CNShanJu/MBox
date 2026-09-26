package com.github.tvbox.osc.download;

/**
 * 已下载档案条目（长期保留，详情页"已下载"判断依据；删文件联动删档案）。
 */
public class ArchiveItem {

    /** 统一剧集标识 sourceKey|vodId|playFlag|playIndex */
    public String episodeId;

    /** 视频级标识 sourceKey|vodId */
    public String videoId;

    public String sourceKey;

    /** 来源名称（下载管理页按 来源+剧名 分组展示用） */
    public String sourceName;

    public String vodName;

    public String episodeName;

    /** 最终文件路径 */
    public String savePath;

    public long size;

    public long downloadTime;

    public String pic;
}

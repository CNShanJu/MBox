package com.github.tvbox.osc.bean;

import java.util.List;


public class VideoFolder {
    public VideoFolder(String name, List<VideoInfo> videoList) {
        this.name = name;
        this.videoList = videoList;
    }

    String name;
    /** 来源名(下载页分组用,本地视频不用) */
    String sourceName;
    List<VideoInfo> videoList;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public List<VideoInfo> getVideoList() {
        return videoList;
    }

    public void setVideoList(List<VideoInfo> videoList) {
        this.videoList = videoList;
    }
}
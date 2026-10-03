package com.github.tvbox.osc.bean;

/** 投屏媒体的标题与播放地址。 */
public class CastVideo {

    private final String name;
    private final String url;
    private final long positionMs;

    public CastVideo(String name, String url) {
        this(name, url, 0);
    }

    public CastVideo(String name, String url, long positionMs) {
        this.name = name;
        this.url = url;
        this.positionMs = Math.max(0, positionMs);
    }

    public String getName() {
        return name;
    }

    public String getUri() {
        return url;
    }

    public long getPositionMs() {
        return positionMs;
    }

    public String getId() {
        return "";
    }
}

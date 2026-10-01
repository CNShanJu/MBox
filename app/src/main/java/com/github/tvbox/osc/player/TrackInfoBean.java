package com.github.tvbox.osc.player;

public class TrackInfoBean {
    public String name;
    public String language;
    public int trackId;
    public boolean selected;

    // 旧 Exo 适配器的渲染器 ID；Media3 直接按 Tracks.Group 选择，不再使用。
    public int renderId;
    // Media3 当前 Tracks 中的分组索引
    public int trackGroupId;

}

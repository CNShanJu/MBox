package com.github.tvbox.osc.bean;

/**
 * 投屏数据类(桩实现)
 * <p>
 * DLNA 投屏功能暂不可用:原依赖 com.github.devin1014.DLNA-Cast:dlna-dmc:V1.0.0
 * 已从 JitPack 消失,原始实现备份于项目根目录 _backup_dlna/ 下。
 */
public class CastVideo {

    private final String name;
    private final String url;

    public CastVideo(String name, String url) {
        this.name = name;
        this.url = url;
    }

    public String getName() {
        return name;
    }

    public String getUri() {
        return url;
    }

    public String getId() {
        return "";
    }
}

package com.github.tvbox.osc.bean;

/**
 * 解析器配置(纯模型,跨模块共享)。
 * <p>
 * 从 :spider 迁入 :common(原 :core-model)时按改进.txt 规则纯化:
 * <ul>
 *   <li>只承载数据(name/url/ext/type/isDefault),无 Android 依赖;</li>
 *   <li>proxy:// 前缀替换、ext 的 Base64 拼接等"基础设施行为"移出到调用侧
 *       (spider 内 ParseBeanUrls 工具,见 com.github.tvbox.osc.util.ParseBeanUrls)。</li>
 * </ul>
 */
public class ParseBean {

    private String name;
    private String url;
    private String ext;
    private int type;   // 0 普通嗅探 1 json 2 Json扩展 3 聚合

    private boolean isDefault = false;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** 原始解析地址(proxy:// 等前缀不做处理;需要时用 spider 侧 ParseBeanUrls.url(...)) */
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean b) {
        isDefault = b;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getExt() {
        return ext;
    }

    public void setExt(String ext) {
        this.ext = ext;
    }
}

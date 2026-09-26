package com.github.tvbox.osc.bean;


public class Subscription {

    /** 添加方式(导出清单里的 origin):直接导入=输入名称+地址;本地导入=选本地文件;JSON 导入=粘贴 JSON 文本 */
    public static final String ORIGIN_DIRECT = "direct";
    public static final String ORIGIN_LOCAL = "local";
    public static final String ORIGIN_JSON = "json";

    public Subscription() {
    }

    public Subscription(String name, String url) {
        this(name, url, ORIGIN_DIRECT);
    }

    public Subscription(String name, String url, String origin) {
        this.name = name;
        this.url = url;
        this.origin = origin;
    }

    String name;
    String url;
    /** 当初怎么加进来的({@link #ORIGIN_DIRECT}/{@link #ORIGIN_LOCAL}/{@link #ORIGIN_JSON};旧数据为 null) */
    String origin;
    //选择状态
    boolean isChecked;
    //置顶
    private boolean top;

    /** 添加方式;旧数据(无该字段)按"直接导入"算 */
    public String getOrigin() {
        return origin == null || origin.isEmpty() ? ORIGIN_DIRECT : origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public boolean isTop() {
        return top;
    }

    public void setTop(boolean top) {
        this.top = top;
    }

    public boolean isChecked() {
        return isChecked;
    }

    public Subscription setChecked(boolean checked) {
        isChecked = checked;
        return this;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

}
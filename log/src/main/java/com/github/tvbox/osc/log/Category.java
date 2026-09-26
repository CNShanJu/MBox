package com.github.tvbox.osc.log;

/**
 * 日志大类型（category）：稳定、数量少，日志页第一级筛选。
 * 定义在 log 模块内，业务模块引用常量；log 模块不反向依赖任何业务模块。
 */
public enum Category {
    DOWNLOAD("下载"),
    PLAYER("播放"),
    SUBSCRIPTION("订阅"),
    SYSTEM("系统"),
    OTHER("其他");

    private final String label;

    Category(String label) {
        this.label = label;
    }

    /** 日志页展示名（中文） */
    public String label() {
        return label;
    }
}

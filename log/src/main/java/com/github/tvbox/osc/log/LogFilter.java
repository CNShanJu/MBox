package com.github.tvbox.osc.log;

/**
 * 日志查询过滤条件（全部可空；分页）。
 */
public class LogFilter {

    /** 大类型：Category.name() */
    public String category;

    /** 小类型 code */
    public String subType;

    /** 最低级别：0=DEBUG 1=INFO 2=WARN 3=ERROR */
    public int minLevel = LogStore.LEVEL_INFO;

    /** 任务维度键（episodeId） */
    public String taskKey;

    /** 起始时间戳（含） */
    public Long fromTs;

    /** 结束时间戳（含） */
    public Long toTs;

    /** 关键词（匹配 detail/reason 子串） */
    public String keyword;

    public int limit = 200;

    public int offset = 0;
}

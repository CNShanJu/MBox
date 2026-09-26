package com.github.tvbox.osc.spiderapi;

import java.util.List;
import java.util.Map;

/**
 * 爬虫内容契约(JS/JAR 源,type=3):首页/分类/首页推荐/详情/搜索/播放解析。
 * 具体 Spider 实现留在 :spider,app 只经本接口取内容字符串。
 */
public interface SpiderContentApi {

    /** 首页分类/视频 JSON/XML 源串;失败返回 null */
    String homeContent(String sourceKey, boolean filter);

    /** 首页推荐视频内容 */
    String homeVideoContent(String sourceKey);

    /** 分类列表内容(tid/pg/filter/extend 与 Spider.categoryContent 对齐) */
    String categoryContent(String sourceKey, String tid, String pg, boolean filter, Map<String, String> extend);

    /** 详情内容 */
    String detailContent(String sourceKey, List<String> ids);

    /** 搜索(quick=false 聚合搜索;true 快速搜索) */
    String searchContent(String sourceKey, String word, boolean quick);

    /**
     * 带页码的搜索(pg 从 1 起):聚合搜索"加载更多/翻页"用。
     * 默认忽略页码回落单页语义(老实现不改也能编译);支持翻页的源在实现侧覆写
     * (JS/JAR 源走 {@code Spider.searchContent(key, quick, pg)})。
     */
    default String searchContent(String sourceKey, String word, boolean quick, String pg) {
        return searchContent(sourceKey, word, quick);
    }

    /** 解析播放地址 */
    String playerContent(String sourceKey, String flag, String id, List<String> vipFlags);
}

package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;

import java.util.Map;

/** 强类型首页/分类契约(type3 JS/JAR):返回解析后的领域对象,不再传字符串 */
public interface SpiderHomeApi {

    /**
     * 首页分类+筛选(type3 homeContent):返回解析后的 {@link AbsSortXml}。
     * 若同响应内嵌首页视频(list 字段)一并解析到 {@link AbsSortXml#videoList}。
     *
     * @param sourceKey 源 key
     * @param filter    是否携带筛选条件
     * @return 解析结果;仅支持 type=3,其余类型或失败返回 null
     */
    AbsSortXml homeContent(String sourceKey, boolean filter);

    /** 分类列表(type3) */
    AbsXml category(String sourceKey, String tid, String pg, boolean filter, Map<String, String> extend);

    /** 首页推荐视频(type3,homeVideoContent) */
    AbsXml homeVideoContent(String sourceKey);
}

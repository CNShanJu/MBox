package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.AbsXml;

/** 强类型搜索契约(type3 JS/JAR 试点):返回解析后的 {@link AbsXml},不再传字符串 */
public interface SpiderSearchApi {

    /**
     * 拉取并解析搜索结果。
     *
     * @param sourceKey 源 key
     * @param word      关键词
     * @param quick     true=快速搜索;false=聚合搜索
     * @return 解析后的 AbsXml;仅支持 type=3,其余类型或失败返回 null
     */
    AbsXml search(String sourceKey, String word, boolean quick);
}

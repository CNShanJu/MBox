package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.AbsXml;

/** 强类型详情契约(type3 JS/JAR 源试点):返回解析后的 {@link AbsXml},不再传字符串 */
public interface SpiderDetailApi {

    /**
     * 拉取并解析详情。
     *
     * @param sourceKey 源 key
     * @param vodId     影片 id
     * @return 解析后的 AbsXml;仅支持 type=3(JS/JAR),其余类型或失败返回 null
     */
    AbsXml detail(String sourceKey, String vodId);
}

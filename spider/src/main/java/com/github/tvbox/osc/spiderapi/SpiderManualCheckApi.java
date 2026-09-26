package com.github.tvbox.osc.spiderapi;

/** 嗅探型源的手动视频地址判定契约(替代 UI/下载侧直接拿具体 Spider 对象) */
public interface SpiderManualCheckApi {

    /**
     * 若该源声明需手动判定,给出权威结论;否则返回 null 让调用方回退通用规则。
     *
     * @param sourceKey 源 key
     * @param url       待判定地址
     * @return Boolean.TRUE/FALSE=权威结论;null=无需/无法手动判定
     */
    Boolean manualVideoCheck(String sourceKey, String url);
}

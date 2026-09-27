package com.github.tvbox.osc.spiderapi;

/**
 * 源「插件不可用」故障查询契约：某个源因为插件（jar/JS）缺类或初始化失败而<b>确定不可用</b>时,
 * 给出可展示给用户的原因;源正常 / 未知时返回 null。
 * <p>
 * 为什么需要它（线上实例）:订阅里某个站点声明的类在其 jar 里并不存在
 * （如 {@code "api":"csp_XPathGuard"} 而 jar 里只有一堆别的 *Guard 类）。此时
 * {@code JarLoader.getSpider} 只会返回 {@code SpiderNull}，于是该源的搜索/详情全空、
 * 页面只剩「暂无数据」，日志也只有一行「源初始化失败：类名」（连站点 key 都没有）——
 * 用户与开发者都分不清是源坏了还是 App 坏了。
 * <p>
 * 实现见 :spider 的 {@code com.github.catvod.crawler.SpiderFaults}（由组合根注入）。
 * 默认实现恒返回 null（无故障信息），调用方只需把它当"可选提示"用。
 */
public interface SpiderFaultApi {

    /**
     * @param sourceKey 源 key（订阅里的 site key）
     * @return 该源不可用的用户可读原因;源可用 / 没有故障记录时返回 null
     */
    String unavailableReason(String sourceKey);
}

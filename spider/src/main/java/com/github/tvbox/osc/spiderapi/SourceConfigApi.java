package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.SourceBean;

import java.util.List;

/**
 * 源配置元信息契约（改进.txt 一阶段：SourceViewModel 不再直读 :spider 的 ApiConfig）。
 * <p>
 * 提供源注册表/首页源/vip 解析旗标等只读元信息；具体实现留在 :spider（ApiConfig），
 * 由 AppCompositionRoot 在应用启动时注入，app 侧（ViewModel）只依赖本接口，便于 Fake 单测。
 */
public interface SourceConfigApi {

    /** 按 key 取源；不存在返回 null */
    SourceBean getSource(String sourceKey);

    /** 设为首页源（同步持久化） */
    void setSourceBean(SourceBean sourceBean);

    /** 首页源（未设置时 ApiConfig 返回空占位对象） */
    SourceBean getHomeSourceBean();

    /** 全部已加载源（副本） */
    List<SourceBean> getSourceBeanList();

    /** vip 解析旗标列表 */
    List<String> getVipParseFlags();
}

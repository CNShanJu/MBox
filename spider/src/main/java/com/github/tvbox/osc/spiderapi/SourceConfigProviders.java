package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.SourceBean;

import java.util.Collections;
import java.util.List;

/**
 * 源配置元信息服务持有者（App 组合根注入 :spider 实现；默认安全降级：getSource/getHomeSourceBean 返回
 * null、getVipParseFlags 返回空表，避免注入前误用直接崩溃——SourceViewModel 调用点与原语义一致地判空/空转）。
 */
public final class SourceConfigProviders {

    private static volatile SourceConfigApi impl = new SourceConfigApi() {
        @Override
        public SourceBean getSource(String sourceKey) {
            return null;
        }

        @Override
        public void setSourceBean(SourceBean sourceBean) {
        }

        @Override
        public SourceBean getHomeSourceBean() {
            return null;
        }

        @Override
        public List<SourceBean> getSourceBeanList() {
            return Collections.emptyList();
        }

        @Override
        public List<String> getVipParseFlags() {
            return Collections.emptyList();
        }
    };

    private SourceConfigProviders() {
    }

    public static void set(SourceConfigApi api) {
        if (api != null) {
            impl = api;
        }
    }

    public static SourceConfigApi get() {
        return impl;
    }
}

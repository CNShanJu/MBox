package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.ParseBean;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 解析配置服务持有者（App 组合根注入 :spider 实现；默认安全降级：
 * 无默认解析/空解析列表/jsonExt 返回 null，播放流程据此走"无解析可用"分支）。
 */
public final class ParseConfigProviders {

    private static volatile ParseConfigApi impl = new ParseConfigApi() {
        @Override
        public ParseBean getDefaultParse() {
            return null;
        }

        @Override
        public void setDefaultParse(ParseBean parseBean) {
        }

        @Override
        public List<ParseBean> getParseBeanList() {
            return Collections.emptyList();
        }

        @Override
        public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
            return null;
        }

        @Override
        public JSONObject jsonExtMix(String flag, String key, String name,
                                     LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
            return null;
        }
    };

    private ParseConfigProviders() {
    }

    public static void set(ParseConfigApi api) {
        if (api != null) {
            impl = api;
        }
    }

    public static ParseConfigApi get() {
        return impl;
    }
}

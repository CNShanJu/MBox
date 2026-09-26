package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.ParseBean;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 解析配置与解析执行契约（改进.txt 收口：默认解析/解析列表由播放与详情流程只读，
 * 解析执行 jsonExt/jsonExtMix 委托 :spider 的 jar loader）。
 * <p>
 * 具体实现由 AppCompositionRoot 注入（桥接 :spider ApiConfig），app 侧不直读其实现类。
 */
public interface ParseConfigApi {

    /** 当前默认解析；未设置返回 null */
    ParseBean getDefaultParse();

    /** 设为默认解析（同步持久化默认名；原默认取消选中） */
    void setDefaultParse(ParseBean parseBean);

    /** 全部解析器列表 */
    List<ParseBean> getParseBeanList();

    /** 通用解析执行（parse 前缀:111 等场景）；失败返回 null */
    JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url);

    /** 多线路混流解析执行；失败返回 null */
    JSONObject jsonExtMix(String flag, String key, String name,
                          LinkedHashMap<String, HashMap<String, String>> jxs, String url);
}

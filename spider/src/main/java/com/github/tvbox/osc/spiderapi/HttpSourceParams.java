package com.github.tvbox.osc.spiderapi;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 型源(type0 XML / type1、type4 JSON)请求参数构造(纯逻辑,可 JVM 单测)。
 * <p>
 * 单一权威来源:SourceViewModel(type0/1/4 旧直连路径)与 :spider typed 实现
 * (SpiderDetailImpl 等)共用同一拼参语义,消除双实现漂移(评估 §J 修订方向)。
 */
public final class HttpSourceParams {

    private HttpSourceParams() {
    }

    /**
     * 详情参数:type0 → ac=videolist(旧 XML 源);type1/type4 → ac=detail。
     *
     * @param type 源类型(0/1/4;其余类型返回 null 表示不支持)
     * @param id   影片 id
     */
    public static Map<String, String> detail(int type, String id) {
        if (type != 0 && type != 1 && type != 4) return null;
        if (id == null) return null;
        Map<String, String> params = new HashMap<>();
        params.put("ac", type == 0 ? "videolist" : "detail");
        params.put("ids", id);
        return params;
    }

    /**
     * 搜索参数(与 SourceViewModel getSearch/getQuickSearch 旧拼参逐字一致):
     * type0 → wd;type1 → wd+ac=detail;type4 → wd+ac=detail+quick(主搜 false / 快搜 true)。
     *
     * @param type  源类型(0/1/4;其余返回 null)
     * @param word  关键词
     * @param quick true=快速搜索(detail 页快搜);false=聚合搜索
     */
    public static Map<String, String> search(int type, String word, boolean quick) {
        if (type != 0 && type != 1 && type != 4) return null;
        if (word == null) return null;
        Map<String, String> params = new HashMap<>();
        params.put("wd", word);
        if (type == 1) {
            params.put("ac", "detail");
        } else if (type == 4) {
            params.put("ac", "detail");
            params.put("quick", quick ? "true" : "false");
        }
        return params;
    }

    /**
     * 播放解析参数(type4 HTTP 源;与 SourceViewModel.getPlay type4 旧拼参逐字一致):
     * type4 → play=url + flag=线路。type0/1 无 HTTP(本地拼 JSONObject),返回 null。
     *
     * @param type   源类型
     * @param url    播放地址
     * @param flag   线路 flag
     */
    public static Map<String, String> play(int type, String url, String flag) {
        if (type != 4) return null;
        if (url == null) return null;
        Map<String, String> params = new HashMap<>();
        params.put("play", url);
        params.put("flag", flag);
        return params;
    }
}

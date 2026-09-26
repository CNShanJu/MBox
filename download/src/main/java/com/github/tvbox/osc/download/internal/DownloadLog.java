package com.github.tvbox.osc.download.internal;

import com.github.tvbox.osc.download.DownloadSubType;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.CategoryLogger;
import com.github.tvbox.osc.log.LogStore;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 下载模块日志通道：模块 init 时注册大类型=下载，任务内直接打日志
 * （任务审计日志 = LogStore 同表按 episodeId 的视图，见方案 3.1/6.1⑥）。
 */
public final class DownloadLog {

    public static final CategoryLogger<DownloadSubType> LOG =
            LogStore.get().register(Category.DOWNLOAD, DownloadSubType.class);

    private DownloadLog() {
    }

    /** 构造带 episodeId 的 extras（LogStore 据此写入 taskKey，供"任务详情→查看日志"查询） */
    public static JSONObject extras(String episodeId) {
        if (episodeId == null || episodeId.isEmpty()) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("episodeId", episodeId);
        } catch (JSONException ignored) {
        }
        return o;
    }
}


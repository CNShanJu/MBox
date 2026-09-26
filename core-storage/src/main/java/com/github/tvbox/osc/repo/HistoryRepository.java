package com.github.tvbox.osc.repo;

import com.github.tvbox.osc.bean.VodInfo;

import java.util.List;
import java.util.function.Predicate;

/**
 * 历史记录仓储(改进.txt §3):UI 只依赖本接口,不接触 DAO/AppDataManager。
 * 源是否存在与保留数量由调用方注入(见 getAllVodRecord 语义)。
 */
public interface HistoryRepository {

    /** 保存/更新一条播放历史 */
    void save(String sourceKey, VodInfo vodInfo);

    /** 删除某条历史(sourceKey+vodId) */
    void delete(String sourceKey, String vodId);

    /** 清空历史 */
    void clear();

    /** 查询历史列表;sourceExists 可为 null=不过滤;historyTrimLimit<=0=不裁剪 */
    List<VodInfo> query(int limit, Predicate<String> sourceExists, int historyTrimLimit);

    /** 按 sourceKey+vodId 取单条历史(无则 null;UI 回填观看记录等场景用) */
    VodInfo get(String sourceKey, String vodId);
}

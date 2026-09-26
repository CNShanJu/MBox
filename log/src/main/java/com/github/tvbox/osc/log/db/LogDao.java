package com.github.tvbox.osc.log.db;

import java.util.List;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.github.tvbox.osc.log.LogEntry;

@Dao
public interface LogDao {

    @Insert
    void insertAll(List<LogEntry> entries);

    /**
     * 组合筛选查询（全部条件可空；keyword 匹配 detail/reason 子串）。
     *
     * @param minLevel 最低级别（0=DEBUG 起）
     */
    @Query("SELECT * FROM log_entry WHERE "
            + "(:category IS NULL OR category = :category) "
            + "AND (:subType IS NULL OR subTypeCode = :subType) "
            + "AND (level >= :minLevel) "
            + "AND (:taskKey IS NULL OR taskKey = :taskKey) "
            + "AND (:fromTs IS NULL OR timestamp >= :fromTs) "
            + "AND (:toTs IS NULL OR timestamp <= :toTs) "
            + "AND (:keyword IS NULL OR detail LIKE '%' || :keyword || '%' "
            + "     OR reason LIKE '%' || :keyword || '%') "
            + "ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    List<LogEntry> query(@Nullable String category, @Nullable String subType, int minLevel,
                         @Nullable String taskKey, @Nullable Long fromTs, @Nullable Long toTs,
                         @Nullable String keyword, int limit, int offset);

    /** 任务维度视图（"任务详情→查看日志"） */
    @Query("SELECT * FROM log_entry WHERE taskKey = :taskKey "
            + "ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    List<LogEntry> queryByTask(String taskKey, int limit, int offset);

    /** 删除某时间点之前的日志（按天清理） */
    @Query("DELETE FROM log_entry WHERE timestamp < :before")
    int deleteBefore(long before);

    /** 只保留最近 keep 条（超出删最旧，防总量膨胀） */
    @Query("DELETE FROM log_entry WHERE id IN "
            + "(SELECT id FROM log_entry ORDER BY timestamp DESC LIMIT -1 OFFSET :keep)")
    int trimTo(int keep);

    @Query("SELECT COUNT(*) FROM log_entry")
    int count();

    @Query("DELETE FROM log_entry")
    void clearAll();
}

package com.github.tvbox.osc.log;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 统一日志条目（事件化，所有日志统一结构）：
 * 时间 + 大类型 + 小类型 + 干了啥 + 成功/失败 + 原因 + 附加字段。
 * <ul>
 *   <li>category    大类型（Category.name()，稳定枚举名落库）</li>
 *   <li>subTypeCode 小类型 code（各业务模块枚举的稳定值）</li>
 *   <li>subTypeLabel 小类型展示名（冗余，历史日志不依赖注册映射也能显示）</li>
 *   <li>level       级别（0=DEBUG 1=INFO 2=WARN 3=ERROR，按结果推导）</li>
 *   <li>taskKey     冗余列：episodeId（extras 中同名字段的冗余，便于按任务查询）</li>
 * </ul>
 */
@Entity(tableName = "log_entry",
        indices = {
                @Index(value = {"taskKey", "timestamp"}),
                @Index(value = {"category", "timestamp"})
        })
public class LogEntry {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long timestamp;

    /** 大类型：Category.name() */
    public String category;

    /** 小类型 code（稳定值，永久不变） */
    public String subTypeCode;

    /** 小类型 label（展示名） */
    public String subTypeLabel;

    /** 0=DEBUG 1=INFO 2=WARN 3=ERROR */
    public int level;

    /** 动作详情（截断 4KB） */
    public String detail;

    /** SUCCESS / FAILURE / INFO */
    public String result;

    /** 失败原因（失败必有；成功为空） */
    public String reason;

    /** 附加字段（JSON：episodeId/url 等） */
    public String extras;

    /** 任务维度查询键（episodeId 冗余列） */
    public String taskKey;
}

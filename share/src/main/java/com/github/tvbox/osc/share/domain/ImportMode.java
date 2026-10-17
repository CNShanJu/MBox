package com.github.tvbox.osc.share.domain;

/**
 * 导入语义：合并还是覆盖。
 *
 * <ul>
 *   <li>{@link #MERGE} —— 保留本机现有数据，按域内规则去重/合并（局域网配置导入用）；</li>
 *   <li>{@link #OVERWRITE} —— 先清空本机该域数据再写入，备份里没有的就是没有（本地备份还原用）。</li>
 * </ul>
 */
public enum ImportMode {
    MERGE,
    OVERWRITE
}

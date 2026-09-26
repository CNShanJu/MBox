package com.github.tvbox.osc.util.player;

import com.github.tvbox.osc.repo.HistoryRepositories;
import com.github.tvbox.osc.util.MD5;

/**
 * 播放进度持久化（改进.txt §三 PlayFragment 拆分：PlayHistoryRepository）。
 * <p>
 * 收敛 "业务 key → MD5 → CacheRepository" 的读写与"跳过片头(st)"叠加语义，
 * 宿主（PlayFragment）经 ProgressManager 回调与切集/重置等入口薄转发；
 * 行为与迁出前的 PlayFragment 内嵌逻辑逐行等价（值类型兼容分支/打印保持不变）。
 */
public final class PlayHistoryRepository {

    /** 保存播放进度（毫秒） */
    public void save(String key, long progress) {
        HistoryRepositories.cache().save(MD5.string2MD5(key), progress);
    }

    /**
     * 读取播放进度（毫秒）：缓存进度与跳过片头起点取大者；
     * 缓存缺失/不可解析时返回 skipStartMs。
     */
    public long load(String key, long skipStartMs) {
        Object theCache = HistoryRepositories.cache().get(MD5.string2MD5(key));
        if (theCache == null) {
            return skipStartMs;
        }
        long rec = 0;
        if (theCache instanceof Long) {
            rec = (Long) theCache;
        } else if (theCache instanceof String) {
            try {
                rec = Long.parseLong((String) theCache);
            } catch (NumberFormatException e) {
                System.out.println("String value is not a valid long.");
            }
        } else {
            System.out.println("Value cannot be converted to long.");
        }
        return Math.max(rec, skipStartMs);
    }

    /** 删除播放进度（重置进度/切集清除时） */
    public void delete(String key) {
        HistoryRepositories.cache().delete(MD5.string2MD5(key), 0);
    }
}

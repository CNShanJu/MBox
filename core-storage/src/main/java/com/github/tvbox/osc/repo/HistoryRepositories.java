package com.github.tvbox.osc.repo;

/** 仓储静态访问点(过渡期兼容;后续可换注入) */
public final class HistoryRepositories {

    private static volatile HistoryRepository history = RoomHistoryRepository.get();
    private static volatile CollectRepository collect = RoomCollectRepository.get();
    private static volatile CacheRepository cache = RoomCacheRepository.get();

    private HistoryRepositories() {
    }

    public static HistoryRepository history() {
        return history;
    }

    /** 测试/注入用 */
    public static void setHistory(HistoryRepository repo) {
        if (repo != null) {
            history = repo;
        }
    }

    public static CollectRepository collect() {
        return collect;
    }

    /** 测试/注入用 */
    public static void setCollect(CollectRepository repo) {
        if (repo != null) {
            collect = repo;
        }
    }

    public static CacheRepository cache() {
        return cache;
    }

    /** 测试/注入用 */
    public static void setCache(CacheRepository repo) {
        if (repo != null) {
            cache = repo;
        }
    }
}

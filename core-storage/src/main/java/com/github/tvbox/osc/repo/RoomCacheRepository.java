package com.github.tvbox.osc.repo;

import com.github.tvbox.osc.cache.CacheManager;

/** 基于 CacheManager 的键值缓存仓储默认实现 */
public final class RoomCacheRepository implements CacheRepository {

    private static final CacheRepository INSTANCE = new RoomCacheRepository();

    private RoomCacheRepository() {
    }

    public static CacheRepository get() {
        return INSTANCE;
    }

    @Override
    public Object get(String key) {
        return CacheManager.getCache(key);
    }

    @Override
    public void save(String key, Object value) {
        CacheManager.save(key, value);
    }

    @Override
    public void delete(String key, Object body) {
        CacheManager.delete(key, body);
    }
}

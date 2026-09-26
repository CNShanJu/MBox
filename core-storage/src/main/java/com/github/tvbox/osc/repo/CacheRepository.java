package com.github.tvbox.osc.repo;

/**
 * 键值缓存仓储(播放进度/字幕等本地缓存;UI 只依赖接口,不接触 DAO/CacheManager)。
 */
public interface CacheRepository {

    /** 读取(反序列化);不存在/损坏返回 null */
    Object get(String key);

    /** 写入(序列化) */
    void save(String key, Object value);

    /** 删除 */
    void delete(String key, Object body);
}

package com.github.tvbox.osc.repo;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.VodCollect;

import java.util.List;

/** 收藏仓储(UI 只依赖接口,不接触 DAO) */
public interface CollectRepository {

    /** 加入收藏(已存在则忽略) */
    void save(String sourceKey, VodInfo vodInfo);

    /** 按 sourceKey+vodId 取消收藏 */
    void delete(String sourceKey, String vodId);

    /** 按自增 id 删除 */
    void deleteById(int id);

    /** 清空收藏 */
    void clear();

    /** 是否已收藏 */
    boolean isSaved(String sourceKey, String vodId);

    /** 收藏列表(按加入时间倒序) */
    List<VodCollect> query();
}

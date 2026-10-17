package com.github.tvbox.osc.repo;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.cache.VodCollect;

import java.util.List;

/** 基于 RoomDataManger 的收藏仓储默认实现 */
public final class RoomCollectRepository implements CollectRepository {

    private static final CollectRepository INSTANCE = new RoomCollectRepository();

    private RoomCollectRepository() {
    }

    public static CollectRepository get() {
        return INSTANCE;
    }

    @Override
    public void save(String sourceKey, VodInfo vodInfo) {
        RoomDataManger.insertVodCollect(sourceKey, vodInfo);
    }

    @Override
    public void delete(String sourceKey, String vodId) {
        RoomDataManger.deleteVodCollect(sourceKey, vodId);
    }

    @Override
    public void deleteById(int id) {
        RoomDataManger.deleteVodCollect(id);
    }

    @Override
    public void clear() {
        RoomDataManger.deleteVodCollectAll();
    }

    @Override
    public boolean isSaved(String sourceKey, String vodId) {
        return RoomDataManger.isVodCollect(sourceKey, vodId);
    }

    @Override
    public List<VodCollect> query() {
        return RoomDataManger.getAllVodCollect();
    }
}

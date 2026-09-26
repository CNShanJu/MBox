package com.github.tvbox.osc.repo;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.cache.VodRecord;
import com.github.tvbox.osc.data.AppDataManager;

import java.util.List;
import java.util.function.Predicate;

/** 基于 RoomDataManger/AppDataManager 的默认历史仓储实现(模块内实现,UI 只依赖接口) */
public final class RoomHistoryRepository implements HistoryRepository {

    private static final HistoryRepository INSTANCE = new RoomHistoryRepository();

    private RoomHistoryRepository() {
    }

    public static HistoryRepository get() {
        return INSTANCE;
    }

    @Override
    public void save(String sourceKey, VodInfo vodInfo) {
        RoomDataManger.insertVodRecord(sourceKey, vodInfo);
    }

    @Override
    public void delete(String sourceKey, String vodId) {
        AppDataManager.runOnDb(() -> {
            VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodId);
            if (record != null) {
                AppDataManager.get().getVodRecordDao().delete(record);
            }
        });
    }

    @Override
    public void clear() {
        RoomDataManger.deleteVodRecordAll();
    }

    @Override
    public List<VodInfo> query(int limit, Predicate<String> sourceExists, int historyTrimLimit) {
        return RoomDataManger.getAllVodRecord(limit, sourceExists, historyTrimLimit);
    }

    @Override
    public VodInfo get(String sourceKey, String vodId) {
        return RoomDataManger.getVodInfo(sourceKey, vodId);
    }
}

package com.github.tvbox.osc.cache;

import android.text.TextUtils;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.data.AppDataManager;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 */
public class RoomDataManger {
    static ExclusionStrategy vodInfoStrategy = new ExclusionStrategy() {
        @Override
        public boolean shouldSkipField(FieldAttributes field) {
            if (field.getDeclaringClass() == VodInfo.class && field.getName().equals("seriesFlags")) {
                return true;
            }
            if (field.getDeclaringClass() == VodInfo.class && field.getName().equals("seriesMap")) {
                return true;
            }
            return false;
        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    };

    /** 静态单例:历史记录反复创建 GsonBuilder/TypeToken 会引入大量短生命周期对象,集中复用 */
    private static final Gson VOD_INFO_GSON = new GsonBuilder().addSerializationExclusionStrategy(vodInfoStrategy).create();
    private static final TypeToken<VodInfo> VOD_INFO_TYPE = new TypeToken<VodInfo>() {
    };

    public static void insertVodRecord(String sourceKey, VodInfo vodInfo) {
        AppDataManager.runOnDb(() -> {
            VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodInfo.id);
            if (record == null) {
                record = new VodRecord();
            }
            record.sourceKey = sourceKey;
            record.vodId = vodInfo.id;
            record.updateTime = System.currentTimeMillis();
            record.dataJson = VOD_INFO_GSON.toJson(vodInfo);
            AppDataManager.get().getVodRecordDao().insert(record);
        });
    }

    public static VodInfo getVodInfo(String sourceKey, String vodId) {
        return AppDataManager.runOnDb(() -> {
            VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodId);
            try {
                if (record != null && record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                    VodInfo vodInfo = VOD_INFO_GSON.fromJson(record.dataJson, VOD_INFO_TYPE.getType());
                    if (vodInfo.name == null)
                        return null;
                    return vodInfo;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        });
    }

    public static void deleteVodRecord(String sourceKey, VodInfo vodInfo) {
        AppDataManager.runOnDb(() -> {
            VodRecord record = AppDataManager.get().getVodRecordDao().getVodRecord(sourceKey, vodInfo.id);
            if (record != null) {
                AppDataManager.get().getVodRecordDao().delete(record);
            }
        });
    }

    /**
     * 查询历史列表(纯存储读取)。裁剪数量与“源是否存在”均由业务/UI 层注入,
     * storage 不再依赖 ApiConfig/HistoryHelper/SystemConfig 等业务配置。
     *
     * @param limit            返回条数上限
     * @param sourceExists     源是否仍存在判定(可为 null=不过滤)
     * @param historyTrimLimit 保留上限(<=0 表示不裁剪)
     */
    public static List<VodInfo> getAllVodRecord(int limit) {
        return getAllVodRecord(limit, null, 0);
    }

    public static List<VodInfo> getAllVodRecord(int limit, java.util.function.Predicate<String> sourceExists) {
        return getAllVodRecord(limit, sourceExists, 0);
    }

    public static List<VodInfo> getAllVodRecord(int limit, java.util.function.Predicate<String> sourceExists, int historyTrimLimit) {
        return AppDataManager.runOnDb(() -> {
            int count = AppDataManager.get().getVodRecordDao().getCount();
            if (historyTrimLimit > 0 && count > historyTrimLimit) {
                AppDataManager.get().getVodRecordDao().reserver(historyTrimLimit);
            }
            List<VodRecord> recordList = AppDataManager.get().getVodRecordDao().getAll(limit);
            List<VodInfo> vodInfoList = new ArrayList<>();
            if (recordList != null) {
                for (VodRecord record : recordList) {
                    VodInfo info = null;
                    try {
                        if (record.dataJson != null && !TextUtils.isEmpty(record.dataJson)) {
                            info = VOD_INFO_GSON.fromJson(record.dataJson, VOD_INFO_TYPE.getType());
                            info.sourceKey = record.sourceKey;
                            // 源是否存在由调用方(UI/业务)判定;null 表示不做过滤(原语义=保留)
                            if (sourceExists != null && !sourceExists.test(record.sourceKey)) {
                                info = null;
                            } else if (info.name == null) {
                                info = null;
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (info != null)
                        vodInfoList.add(info);
                }
            }
            return vodInfoList;
        });
    }

    public static void insertVodCollect(String sourceKey, VodInfo vodInfo) {
        AppDataManager.runOnDb(() -> {
            VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodInfo.id);
            if (record != null) {
                return;
            }
            record = new VodCollect();
            record.sourceKey = sourceKey;
            record.vodId = vodInfo.id;
            record.updateTime = System.currentTimeMillis();
            record.name = vodInfo.name;
            record.pic = vodInfo.pic;
            AppDataManager.get().getVodCollectDao().insert(record);
        });
    }

    public static void deleteVodCollect(int id) {
        AppDataManager.runOnDb(() -> {
            AppDataManager.get().getVodCollectDao().delete(id);
        });
    }

    public static void deleteVodCollect(String sourceKey, VodInfo vodInfo) {
        AppDataManager.runOnDb(() -> {
            VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodInfo.id);
            if (record != null) {
                AppDataManager.get().getVodCollectDao().delete(record);
            }
        });
    }

    public static boolean isVodCollect(String sourceKey, String vodId) {
        return AppDataManager.runOnDb(() -> {
            VodCollect record = AppDataManager.get().getVodCollectDao().getVodCollect(sourceKey, vodId);
            return record != null;
        });
    }

    public static List<VodCollect> getAllVodCollect() {
        return AppDataManager.runOnDb(() -> {
            return AppDataManager.get().getVodCollectDao().getAll();
        });
    }

    /**
     * 删除全部收藏
     */
    public static void deleteVodCollectAll() {
        AppDataManager.runOnDb(() -> {
            AppDataManager.get().getVodCollectDao().deleteAll();
        });
    }

    /**
     * 删除全部历史记录
     */
    public static void deleteVodRecordAll() {
        AppDataManager.runOnDb(() -> {
            AppDataManager.get().getVodRecordDao().deleteAll();
        });
    }

}

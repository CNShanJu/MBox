package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class DefaultConfig {

    public static List<MovieSort.SortData> adjustSort(String sourceKey, List<MovieSort.SortData> list, boolean withMy) {
        List<MovieSort.SortData> data = new ArrayList<>();
        if (list == null) list = new ArrayList<>();
        if (sourceKey != null) {
            // 源可能已被删除/订阅刚被换掉:原来直接 sb.getCategories() 会 NPE —— 而本方法是在
            // 主线程的 LiveData 回调里调的(HomeFragment.initViewModel),一点首页就闪退。
            // 取不到源时按"没有自定义分类"处理(下面那支:展示全部),首页仍有内容可用。
            SourceBean sb = ApiConfig.get().getSource(sourceKey);
            ArrayList<String> categories = sb == null ? null : sb.getCategories();
            if (categories != null && !categories.isEmpty()) {
                for (String cate : categories) {
                    for (MovieSort.SortData sortData : list) {
                        // type_name 缺失时 sortData.name 为 null,原写法 sortData.name.equals(cate) 同样 NPE
                        if (sortData == null || sortData.name == null) continue;
                        if (sortData.name.equals(cate)) {
                            if (sortData.filters == null)
                                sortData.filters = new ArrayList<>();
                            data.add(sortData);
                        }
                    }
                }
            } else {
                for (MovieSort.SortData sortData : list) {
                    if (sortData == null) continue;
                    if (sortData.filters == null)
                        sortData.filters = new ArrayList<>();
                    data.add(sortData);
                }
            }
        }
        if (withMy)
            data.add(0, new MovieSort.SortData("my0", "主页"));
        Collections.sort(data);
        return data;
    }

    public static int getAppVersionCode(Context mContext) {
        //包管理操作管理类
        PackageManager pm = mContext.getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(mContext.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return -1;
    }

    public static String getAppVersionName(Context mContext) {
        //包管理操作管理类
        PackageManager pm = mContext.getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(mContext.getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * 后缀
     *
     * @param name
     * @return
     */
    public static String getFileSuffix(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        int endP = name.lastIndexOf(".");
        return endP > -1 ? name.substring(endP) : "";
    }

    /**
     * 获取文件的前缀
     *
     * @param fileName
     * @return
     */
    public static String getFilePrefixName(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "";
        }
        int start = fileName.lastIndexOf(".");
        return start > -1 ? fileName.substring(0, start) : fileName;
    }

    /** 委托 MediaUrlUtil:嗅探正则只保留一份(原双实现见 MediaUrlUtil 头注释) */
    public static boolean isVideoFormat(String url) {
        return com.github.tvbox.osc.spiderapi.MediaUrlUtil.isVideoFormat(url);
    }


    public static String safeJsonString(JsonObject obj, String key, String defaultVal) {
        try {
            if (obj.has(key))
                return obj.getAsJsonPrimitive(key).getAsString().trim();
            else
                return defaultVal;
        } catch (Throwable th) {
        }
        return defaultVal;
    }

    public static int safeJsonInt(JsonObject obj, String key, int defaultVal) {
        try {
            if (obj.has(key))
                return obj.getAsJsonPrimitive(key).getAsInt();
            else
                return defaultVal;
        } catch (Throwable th) {
        }
        return defaultVal;
    }

    public static ArrayList<String> safeJsonStringList(JsonObject obj, String key) {
        ArrayList<String> result = new ArrayList<>();
        try {
            if (obj.has(key)) {
                if (obj.get(key).isJsonObject()) {
                    result.add(obj.get(key).getAsString());
                } else {
                    for (JsonElement opt : obj.getAsJsonArray(key)) {
                        result.add(opt.getAsString());
                    }
                }
            }
        } catch (Throwable th) {
        }
        return result;
    }

    public static String checkReplaceProxy(String urlOri) {
        if (!TextUtils.isEmpty(urlOri) && urlOri.startsWith("proxy://")) {
            return urlOri.replace("proxy://", com.github.tvbox.osc.api.ApiConfig.getLanBase() + "proxy?");
        }else {
            return urlOri;
        }
    }
}
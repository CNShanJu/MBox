package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.util.LOG;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;
import com.thoughtworks.xstream.security.NoTypePermission;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 首页/分类 XML+JSON 解析(自 SourceViewModel 抽出,纯静态、便于单测与 :spider 复用)。
 * 解析不含 UI 状态;VM 只负责结果发布。
 */
public final class SortParser {

    private SortParser() {
    }

    /** JSON 首页/分类解析:AbsSortJson → AbsSortXml,并附加"筛选条件"(filters) */
    public static AbsSortXml parseSortJson(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AbsSortJson sortJson = new Gson().fromJson(obj, new TypeToken<AbsSortJson>() {
            }.getType());
            AbsSortXml data = sortJson.toAbsSortXml();
            try {
                if (obj.has("filters") && data.classes != null && data.classes.sortList != null) {
                    LinkedHashMap<String, ArrayList<MovieSort.SortFilter>> sortFilters = new LinkedHashMap<>();
                    JsonObject filters = obj.getAsJsonObject("filters");
                    for (String key : filters.keySet()) {
                        // 按分类各自兜底:原来整块 catch(Throwable ignored),一个坏 filter 项
                        // 就会让"这个源所有分类"的筛选面板一起变空(而不是只丢坏的那一项)
                        try {
                            ArrayList<MovieSort.SortFilter> sortFilter = new ArrayList<>();
                            JsonElement one = filters.get(key);
                            if (one == null || one.isJsonNull()) continue;
                            if (one.isJsonObject()) {
                                MovieSort.SortFilter filter = sortFilterFrom(one.getAsJsonObject());
                                if (filter != null) sortFilter.add(filter);
                            } else if (one.isJsonArray()) {
                                for (JsonElement ele : one.getAsJsonArray()) {
                                    if (ele == null || !ele.isJsonObject()) continue;
                                    MovieSort.SortFilter filter = sortFilterFrom(ele.getAsJsonObject());
                                    if (filter != null) sortFilter.add(filter);
                                }
                            }
                            if (!sortFilter.isEmpty()) sortFilters.put(key, sortFilter);
                        } catch (Throwable th) {
                            LOG.e("SortParser", "分类 " + key + " 的筛选条件解析失败: " + th);
                        }
                    }
                    for (MovieSort.SortData sort : data.classes.sortList) {
                        if (sort == null) continue;
                        if (sortFilters.containsKey(sort.id) && sortFilters.get(sort.id) != null) {
                            sort.filters = sortFilters.get(sort.id);
                        }
                    }
                }
            } catch (Throwable th) {
                LOG.e("SortParser", "筛选条件解析失败: " + th);
            }
            return data;
        } catch (Throwable th) {
            return null;
        }
    }

    /** XML 首页/分类解析(带 XStream 类型白名单) */
    public static AbsSortXml parseSortXml(String xml) {
        try {
            XStream xstream = new XStream(new DomDriver());
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsSortXml.class);
            xstream.ignoreUnknownElements();
            // XStream 反序列化安全白名单(见 lockDownXStream 语义)
            xstream.addPermission(NoTypePermission.NONE);
            xstream.allowTypeHierarchy(AbsSortXml.class);
            xstream.allowTypesByWildcard(new String[]{
                    "com.github.tvbox.osc.bean.**",
                    "java.lang.**",
                    "java.util.**",
                    "java.time.**",
                    "java.math.**",
                    "java.net.**"
            });
            AbsSortXml data = (AbsSortXml) xstream.fromXML(xml);
            for (MovieSort.SortData sort : data.classes.sortList) {
                if (sort.filters == null) {
                    sort.filters = new ArrayList<>();
                }
            }
            return data;
        } catch (Throwable th) {
            return null;
        }
    }

    /**
     * 单项筛选条件(key/name/value)。
     *
     * @return 字段缺失、类型不对或没有任何可选项时返回 null,由调用方跳过该项 ——
     * 而不是抛异常让整个源的筛选面板全空
     */
    private static MovieSort.SortFilter sortFilterFrom(JsonObject obj) {
        if (obj == null) return null;
        JsonElement keyEl = obj.get("key");
        JsonElement nameEl = obj.get("name");
        if (keyEl == null || !keyEl.isJsonPrimitive() || nameEl == null || !nameEl.isJsonPrimitive()) return null;
        String key = keyEl.getAsString();
        String name = nameEl.getAsString();
        if (key == null || key.isEmpty() || name == null || name.isEmpty()) return null;
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        JsonElement valueEl = obj.get("value");
        if (valueEl != null && valueEl.isJsonArray()) {
            for (JsonElement ele : valueEl.getAsJsonArray()) {
                if (ele == null || !ele.isJsonObject()) continue;
                JsonObject eleObj = ele.getAsJsonObject();
                String valuesKey = eleObj.has("n") && eleObj.get("n").isJsonPrimitive() ? eleObj.get("n").getAsString() : "";
                String valuesValue = eleObj.has("v") && eleObj.get("v").isJsonPrimitive() ? eleObj.get("v").getAsString() : "";
                values.put(valuesKey, valuesValue);
            }
        }
        MovieSort.SortFilter filter = new MovieSort.SortFilter();
        filter.key = key;
        filter.name = name;
        filter.values = values;
        // 一个没有任何可选项的筛选(如 value 类型写错)留着只会渲染出空下拉框,直接作废
        return values.isEmpty() ? null : filter;
    }
}

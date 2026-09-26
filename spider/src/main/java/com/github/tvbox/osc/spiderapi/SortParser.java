package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.MovieSort;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
                if (obj.has("filters")) {
                    LinkedHashMap<String, ArrayList<MovieSort.SortFilter>> sortFilters = new LinkedHashMap<>();
                    JsonObject filters = obj.getAsJsonObject("filters");
                    for (String key : filters.keySet()) {
                        ArrayList<MovieSort.SortFilter> sortFilter = new ArrayList<>();
                        JsonElement one = filters.get(key);
                        if (one.isJsonObject()) {
                            sortFilter.add(sortFilterFrom(one.getAsJsonObject()));
                        } else {
                            for (JsonElement ele : one.getAsJsonArray()) {
                                sortFilter.add(sortFilterFrom(ele.getAsJsonObject()));
                            }
                        }
                        sortFilters.put(key, sortFilter);
                    }
                    for (MovieSort.SortData sort : data.classes.sortList) {
                        if (sortFilters.containsKey(sort.id) && sortFilters.get(sort.id) != null) {
                            sort.filters = sortFilters.get(sort.id);
                        }
                    }
                }
            } catch (Throwable ignored) {
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

    private static MovieSort.SortFilter sortFilterFrom(JsonObject obj) {
        String key = obj.get("key").getAsString();
        String name = obj.get("name").getAsString();
        JsonArray kv = obj.getAsJsonArray("value");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (JsonElement ele : kv) {
            JsonObject eleObj = ele.getAsJsonObject();
            String valuesKey = eleObj.has("n") ? eleObj.get("n").getAsString() : "";
            String valuesValue = eleObj.has("v") ? eleObj.get("v").getAsString() : "";
            values.put(valuesKey, valuesValue);
        }
        MovieSort.SortFilter filter = new MovieSort.SortFilter();
        filter.key = key;
        filter.name = name;
        filter.values = values;
        return filter;
    }
}

package com.github.tvbox.osc.spiderapi;

import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;
import com.thoughtworks.xstream.security.NoTypePermission;

import java.util.ArrayList;
import java.util.List;

/**
 * 点播内容纯解析器（type0 XML / type1 JSON 源;SourceViewModel 纯函数提取,可 JVM 测）。
 * <p>
 * 只做"字符串 → AbsXml"(含线路串解析、sourceKey 回填、XStream 白名单加固),
 * 不接触网络/线程/LiveData/EventBus——发布副作用留在调用方。
 */
public final class AbsXmlParser {

    private AbsXmlParser() {
    }

    /** XStream 反序列化安全白名单:仅放行业务 bean 包与基础 JDK 类型,防 XML gadget */
    private static void lockDownXStream(XStream xstream, Class<?> rootType) {
        try {
            xstream.addPermission(NoTypePermission.NONE);
            if (rootType != null) {
                xstream.allowTypeHierarchy(rootType);
            }
            xstream.allowTypesByWildcard(new String[]{
                    "com.github.tvbox.osc.bean.**",
                    "java.lang.**",
                    "java.util.**",
                    "java.time.**",
                    "java.math.**",
                    "java.net.**"
            });
        } catch (Throwable ignored) {
        }
    }

    /** 解析 XML 点播内容(失败抛异常,由调用方决定发布语义) */
    public static AbsXml parseXml(String xml, String sourceKey) {
        XStream xstream = new XStream(new DomDriver());
        xstream.autodetectAnnotations(true);
        xstream.processAnnotations(AbsXml.class);
        xstream.ignoreUnknownElements();
        if (xml.contains("<year></year>")) {
            xml = xml.replace("<year></year>", "<year>0</year>");
        }
        if (xml.contains("<state></state>")) {
            xml = xml.replace("<state></state>", "<state>0</state>");
        }
        lockDownXStream(xstream, AbsXml.class);
        AbsXml data = (AbsXml) xstream.fromXML(xml);
        normalize(data, sourceKey);
        return data;
    }

    /** 解析 JSON 点播内容(失败抛异常,由调用方决定发布语义) */
    public static AbsXml parseJson(String json, String sourceKey) {
        AbsJson absJson = new Gson().fromJson(json, new TypeToken<AbsJson>() {
        }.getType());
        AbsXml data = absJson.toAbsXml();
        normalize(data, sourceKey);
        return data;
    }

    /** 归一:回填 sourceKey;把线路串(如 a$b#c$d)解析为 beanList。typed 产物亦须过此步(线路可选) */
    public static void normalize(AbsXml data, String sourceKey) {
        if (data == null || data.movie == null || data.movie.videoList == null) {
            return;
        }
        for (Movie.Video video : data.movie.videoList) {
            if (video.urlBean != null && video.urlBean.infoList != null) {
                for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                    String[] str;
                    if (urlInfo.urls != null && urlInfo.urls.contains("#")) {
                        str = urlInfo.urls.split("#");
                    } else {
                        String single = urlInfo.urls;
                        // 空/空白线路文本不产出垃圾剧集条目
                        str = (single == null || single.trim().isEmpty())
                                ? new String[0] : new String[]{single};
                    }
                    List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                    for (String s : str) {
                        String[] ss = s.split("\\$");
                        if (ss.length > 0) {
                            if (ss.length >= 2) {
                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                            } else {
                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(
                                        (infoBeanList.size() + 1) + "", ss[0]));
                            }
                        }
                    }
                    urlInfo.beanList = infoBeanList;
                }
            }
            video.sourceKey = sourceKey;
        }
    }
}

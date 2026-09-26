package com.github.tvbox.osc.util;

import android.text.TextUtils;
import android.util.Base64;

import com.github.tvbox.osc.bean.ParseBean;

/**
 * ParseBean 行为侧工具(:spider):ParseBean 迁入 :common 纯化后,
 * 需要 Android/运行时能力的操作集中在此,供 :spider 与 app 调用:
 * <ul>
 *   <li>{@link #url(ParseBean)}  : 解析器地址,proxy:// 前缀替换为本地代理(原 ParseBean.getUrl 语义);</li>
 *   <li>{@link #mixUrl(ParseBean)} : 带 ext(cat_ext=base64) 的解析请求地址(原 ParseBean.mixUrl 语义)。</li>
 * </ul>
 */
public final class ParseBeanUrls {

    private ParseBeanUrls() {
    }

    /** 解析器请求地址:proxy:// 前缀替换成本地代理入口(原 ParseBean.getUrl 语义) */
    public static String url(ParseBean pb) {
        if (pb == null) return null;
        return DefaultConfig.checkReplaceProxy(pb.getUrl());
    }

    /** 带 ext 的解析请求地址(原 ParseBean.mixUrl 语义:在 url 首个 '?' 后拼 cat_ext=base64url(ext)) */
    public static String mixUrl(ParseBean pb) {
        if (pb == null) return null;
        String url = pb.getUrl();
        String ext = pb.getExt();
        if (url == null) return null;
        if (!TextUtils.isEmpty(ext)) {
            int idx = url.indexOf("?");
            if (idx > 0) {
                return url.substring(0, idx + 1) + "cat_ext="
                        + Base64.encodeToString(ext.getBytes(), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP)
                        + "&" + url.substring(idx + 1);
            }
        }
        return url;
    }
}

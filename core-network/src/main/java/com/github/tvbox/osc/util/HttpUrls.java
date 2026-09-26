package com.github.tvbox.osc.util;

import java.io.UnsupportedEncodingException;
import java.net.IDN;
import java.net.URLEncoder;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

/**
 * URL 拼装/归一纯工具(从 HttpClient 抽出,无 android 依赖,可 JVM 单测)。
 * <p>
 * 语义与旧 HttpClient 内联实现逐字等价:
 * <ul>
 *     <li>{@link #buildUrl}:参数优先走 OkHttp HttpUrl 严格拼接;URL 解析失败时降级为
 *         宽松字符串拼接(兼容旧 OkGo 行为);</li>
 *     <li>{@link #normalizeUrl}:中文域名转 punycode(OkHttp 不接收非 ASCII 域名)。</li>
 * </ul>
 * 供 :spider HTTP 型源(type0/1/4)typed 取数复用同一拼装语义,避免双实现漂移。
 */
public final class HttpUrls {

    private HttpUrls() {
    }

    /** 把参数拼到 URL(与旧 HttpClient.buildUrl 语义一致) */
    public static String buildUrl(String url, Map<String, String> params) {
        if (params == null || params.isEmpty()) return url;
        try {
            HttpUrl.Builder builder = HttpUrl.get(normalizeUrl(url)).newBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getKey() == null) continue;
                if (entry.getValue() == null) continue;
                builder.addQueryParameter(entry.getKey(), entry.getValue());
            }
            return builder.build().toString();
        } catch (Throwable th) {
            // 兼容旧 OkGo 的宽松拼接:HttpUrl 解析失败时降级为字符串拼接,避免抛异常
            StringBuilder sb = new StringBuilder(url);
            boolean first = url.indexOf('?') < 0;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                try {
                    sb.append(first ? '?' : '&');
                    first = false;
                    sb.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(entry.getValue(), "UTF-8"));
                } catch (UnsupportedEncodingException ignored) {
                }
            }
            return sb.toString();
        }
    }

    private static final Pattern URL_HOST_PATTERN = Pattern.compile("^(https?://)([^/?#:]+)(:\\d+)?([/?#].*)?$");

    /** 中文域名转 punycode(OkHttp 的 HttpUrl 不接收非 ASCII 域名),失败时原样返回 */
    public static String normalizeUrl(String url) {
        if (url == null) return null;
        try {
            Matcher m = URL_HOST_PATTERN.matcher(url);
            if (m.matches() && isNonAscii(m.group(2))) {
                String host = IDN.toASCII(m.group(2));
                String port = m.group(3) != null ? m.group(3) : "";
                String rest = m.group(4) != null ? m.group(4) : "";
                return m.group(1) + host + port + rest;
            }
        } catch (Throwable ignored) {
        }
        return url;
    }

    private static boolean isNonAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return true;
        }
        return false;
    }
}

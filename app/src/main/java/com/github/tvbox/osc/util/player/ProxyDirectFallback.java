package com.github.tvbox.osc.util.player;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** 从本机代理播放地址提取一次性直连候选，并保留源提供的请求头。 */
public final class ProxyDirectFallback {
    private ProxyDirectFallback() {
    }

    public static Candidate from(String proxyUrl, Map<String, String> playbackHeaders) {
        if (proxyUrl == null) return null;
        try {
            URI proxy = URI.create(proxyUrl);
            if (!"http".equalsIgnoreCase(proxy.getScheme()) || !"/proxy".equals(proxy.getRawPath())
                    || !isLoopback(proxy.getHost())) return null;
            Map<String, String> query = query(proxy.getRawQuery());
            String targetUrl = query.get("url");
            if (targetUrl == null) return null;
            URI target = URI.create(targetUrl);
            String scheme = target.getScheme();
            String path = target.getPath();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || target.getHost() == null || isLoopback(target.getHost())
                    || path == null || !path.toLowerCase(Locale.ROOT).endsWith(".m3u8")) return null;

            HashMap<String, String> headers = new HashMap<>();
            if (playbackHeaders != null) headers.putAll(playbackHeaders);
            String encodedHeaders = query.get("header");
            if (encodedHeaders != null && !encodedHeaders.isEmpty()) {
                JsonElement parsed = JsonParser.parseString(encodedHeaders);
                if (!parsed.isJsonObject()) return null;
                JsonObject object = parsed.getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value.isJsonPrimitive()) {
                        for (Iterator<String> keys = headers.keySet().iterator(); keys.hasNext(); ) {
                            if (keys.next().equalsIgnoreCase(entry.getKey())) keys.remove();
                        }
                        headers.put(entry.getKey(), value.getAsString());
                    }
                }
            }
            return new Candidate(targetUrl, headers);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, String> query(String rawQuery) throws Exception {
        HashMap<String, String> result = new HashMap<>();
        if (rawQuery == null) return result;
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            if (separator <= 0) continue;
            String key = URLDecoder.decode(part.substring(0, separator), "UTF-8");
            if (result.containsKey(key)) throw new IllegalArgumentException("duplicate proxy parameter");
            result.put(key, URLDecoder.decode(part.substring(separator + 1), "UTF-8"));
        }
        return result;
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equalsIgnoreCase(host) || "localhost".equalsIgnoreCase(host)
                || "::1".equalsIgnoreCase(host);
    }

    public static final class Candidate {
        public final String url;
        public final HashMap<String, String> headers;

        private Candidate(String url, HashMap<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }
}

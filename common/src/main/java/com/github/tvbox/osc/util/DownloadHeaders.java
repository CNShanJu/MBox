package com.github.tvbox.osc.util;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** HTTP 头大小写无关；后面的上下文覆盖旧值，返回独立快照。 */
public final class DownloadHeaders {
    public static Map<String, String> mergeForOrigin(String playbackUrl, String resolvedUrl,
                                                    Map<String, String> playback, Map<String, String> resolved) {
        try {
            java.net.URI a = java.net.URI.create(playbackUrl), b = java.net.URI.create(resolvedUrl);
            if (a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                    && a.getPort() == b.getPort()) return merge(playback, resolved);
        } catch (Exception ignored) { }
        return merge(resolved);
    }
    private DownloadHeaders() {}
    @SafeVarargs
    public static Map<String, String> merge(Map<String, String>... layers) {
        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map<String, String> layer : layers) {
            if (layer == null) continue;
            for (Map.Entry<String, String> e : layer.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) result.put(e.getKey(), e.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }
}

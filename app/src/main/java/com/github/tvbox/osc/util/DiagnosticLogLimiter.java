package com.github.tvbox.osc.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** 限制自动重试期间同一诊断重复写入 Logcat 和业务日志。 */
public final class DiagnosticLogLimiter {
    public static final DiagnosticLogLimiter SHARED = new DiagnosticLogLimiter(30_000, 128);

    private final long windowMs;
    private final Map<String, Long> lastLogged;

    DiagnosticLogLimiter(long windowMs, int maxEntries) {
        this.windowMs = windowMs;
        lastLogged = new LinkedHashMap<String, Long>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public synchronized boolean allow(String detail, long elapsedRealtimeMs) {
        Long last = lastLogged.get(detail);
        if (last != null && elapsedRealtimeMs >= last
                && elapsedRealtimeMs - last < windowMs) return false;
        lastLogged.put(detail, elapsedRealtimeMs);
        return true;
    }
}

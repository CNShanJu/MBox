package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.spiderapi.SourceConfigProviders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 历史记录与创建它的订阅绑定，避免不同订阅里同名 sourceKey 误指向别的影片。 */
public final class HistorySourceBinding {
    private HistorySourceBinding() { }

    public static void stamp(VodInfo info) {
        if (info == null) return;
        stamp(info, SubscriptionConfig.getApiUrl());
    }

    public static boolean isAvailable(VodInfo info) {
        if (info == null || empty(info.sourceKey)
                || SourceConfigProviders.get().getSource(info.sourceKey) == null) return false;
        // 升级前的记录没有订阅身份，保留原来的 sourceKey 匹配行为。
        return matchesSubscription(info, SubscriptionConfig.getApiUrl());
    }

    public static String sourceLabel(VodInfo info) {
        if (!isAvailable(info)) return "源未启用";
        SourceBean source = SourceConfigProviders.get().getSource(info.sourceKey);
        return source == null ? "源未启用" : source.getName();
    }

    static void stamp(VodInfo info, String subscriptionUrl) {
        if (info != null) info.subscriptionFingerprint = fingerprint(subscriptionUrl);
    }

    static boolean matchesSubscription(VodInfo info, String currentUrl) {
        return info != null && (empty(info.subscriptionFingerprint)
                || info.subscriptionFingerprint.equals(fingerprint(currentUrl)));
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }

    private static String fingerprint(String url) {
        if (empty(url)) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[digest.length * 2];
            final char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                hex[i * 2] = digits[value >>> 4];
                hex[i * 2 + 1] = digits[value & 0x0f];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

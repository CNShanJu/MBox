package com.github.tvbox.osc.subtitle;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Keeps remote metadata out of cache paths while retaining a useful subtitle extension. */
final class SubtitleFilePolicy {
    private SubtitleFilePolicy() {
    }

    static String suggestedName(String disposition, String url) {
        String ordinary = null;
        String encoded = null;
        if (disposition != null) {
            for (String part : disposition.split(";")) {
                String value = part.trim();
                int equals = value.indexOf('=');
                if (equals < 0) continue;
                String key = value.substring(0, equals).trim();
                String name = value.substring(equals + 1).trim();
                if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
                if ("filename*".equalsIgnoreCase(key)) {
                    int marker = name.indexOf("''");
                    if (marker >= 0) name = name.substring(marker + 2);
                    encoded = decode(name);
                } else if ("filename".equalsIgnoreCase(key)) {
                    ordinary = decode(name);
                }
            }
        }
        String fallback;
        try {
            fallback = URI.create(url).getPath();
        } catch (IllegalArgumentException e) {
            fallback = null;
        }
        String name = basename(encoded != null ? encoded : ordinary);
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) name = basename(fallback);
        return name.isEmpty() || ".".equals(name) || "..".equals(name) ? "subtitle" : name;
    }

    static String cacheName(String remoteUrl, String suggestedName) {
        String suffix = "";
        int dot = suggestedName == null ? -1 : suggestedName.lastIndexOf('.');
        if (dot >= 0) {
            String ext = suggestedName.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ext.matches("[a-z0-9]{1,8}")) suffix = "." + ext;
        }
        return sha256(remoteUrl) + suffix;
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[bytes.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < bytes.length; i++) {
                hex[i * 2] = digits[(bytes[i] & 0xff) >>> 4];
                hex[i * 2 + 1] = digits[bytes[i] & 0x0f];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }

    private static String decode(String name) {
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return name;
        }
    }

    private static String basename(String value) {
        if (value == null) return "";
        String normalized = value.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        StringBuilder out = new StringBuilder(Math.min(name.length(), 128));
        for (int i = 0; i < name.length() && out.length() < 128; i++) {
            char c = name.charAt(i);
            if (!Character.isISOControl(c)) out.append(c);
        }
        return out.toString().trim();
    }
}

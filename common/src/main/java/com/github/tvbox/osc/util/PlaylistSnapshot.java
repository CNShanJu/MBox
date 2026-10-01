package com.github.tvbox.osc.util;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 短时 URL 仅驻留内存；落盘的是包含序列、范围、密钥及 init 布局的摘要。 */
public final class PlaylistSnapshot {
    public final HlsMediaPlaylist.Result playlist;
    public final long fetchedAt;
    public final List<String> stableKeys;
    public final String layoutSignature;
    public final String requestSignature;

    public PlaylistSnapshot(HlsMediaPlaylist.Result playlist, long fetchedAt) {
        if (!playlist.ok()) throw new IllegalArgumentException(playlist.error);
        this.playlist = playlist;
        this.fetchedAt = fetchedAt;
        List<String> keys = new ArrayList<>();
        StringBuilder layout = new StringBuilder();
        StringBuilder requests = new StringBuilder();
        if (playlist.init != null) {
            layout.append("init:").append(resourceIdentity(playlist.init.url)).append(range(playlist.init.range));
            requests.append(playlist.init.url);
        }
        for (HlsMediaPlaylist.Segment s : playlist.segments) {
            String identity = s.mediaSequence + "|" + resourceIdentity(s.url) + "|" + range(s.range)
                    + "|" + s.discontinuity + "|" + s.duration;
            if (s.key != null) {
                identity += "|" + s.key.method + "|" + keyIdentity(s.key.uri) + "|" + s.key.iv;
                requests.append('\n').append(s.key.uri);
            }
            keys.add(identity);
            layout.append('\n').append(identity);
            requests.append('\n').append(s.url).append(range(s.range));
        }
        stableKeys = Collections.unmodifiableList(keys);
        layoutSignature = "layout-v2:" + digest(layout.toString());
        requestSignature = digest(requests.toString());
    }

    /** 保守映射：域、路径、顺序、范围、IV 或 init 不一致时拒绝复用。 */
    public boolean canReuse(PlaylistSnapshot newer) {
        return newer != null && layoutSignature.equals(newer.layoutSignature);
    }

    public static String resourceIdentity(String url) {
        if (url == null) return "";
        try {
            URI uri = URI.create(url).normalize();
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toASCIIString();
        } catch (Exception e) {
            return url.split("[?#]", 2)[0];
        }
    }

    /** 密钥的 id 等语义参数保留，仅移除常见鉴权参数；不同轮换密钥不能合成一个身份。 */
    public static String keyIdentity(String url) {
        String base = resourceIdentity(url);
        try {
            String query = URI.create(url).getRawQuery();
            if (query == null) return base;
            List<String> stable = new ArrayList<>();
            for (String part : query.split("&")) {
                String name = part.split("=", 2)[0].toLowerCase(java.util.Locale.ROOT);
                if (!name.equals("auth_key") && !name.equals("token") && !name.equals("expires")
                        && !name.equals("expire") && !name.equals("sign") && !name.equals("signature")
                        && !name.equals("wstime") && !name.equals("wssecret")) stable.add(part);
            }
            Collections.sort(stable);
            StringBuilder out = new StringBuilder(base);
            for (int i = 0; i < stable.size(); i++) out.append(i == 0 ? '?' : '&').append(stable.get(i));
            return out.toString();
        } catch (Exception e) { return base; }
    }

    private static String range(HlsMediaPlaylist.ByteRange r) {
        return r == null ? "" : r.offset + ":" + r.length;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}

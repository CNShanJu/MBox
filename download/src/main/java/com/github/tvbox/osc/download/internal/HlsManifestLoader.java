package com.github.tvbox.osc.download.internal;

import com.github.tvbox.osc.util.HlsMasterPlaylist;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 主清单加载及变体回退；未下载分片前选定媒体清单，防止跨清晰度混片。 */
public final class HlsManifestLoader {
    public interface Fetcher { String fetch(String url) throws IOException; }
    public interface Validator { void verify(Manifest manifest) throws IOException; }
    public static final class Manifest {
        public final String url, text;
        Manifest(String url, String text) { this.url = url; this.text = text; }
    }
    public static Manifest load(String url, Fetcher fetcher) throws IOException {
        return load(url, fetcher, manifest -> {}, 0, null);
    }
    public static Manifest load(String url, Fetcher fetcher, Validator validator, int height, String identity) throws IOException {
        return load(url, fetcher, validator, height, identity, new HashSet<>(), 0, new int[1]);
    }
    private static Manifest load(String url, Fetcher fetcher, Validator validator, int height, String identity,
                                 Set<String> path, int depth, int[] budget) throws IOException {
        if (depth >= 8 || ++budget[0] > 32 || !path.add(url)) throw new IOException("HLS 主清单循环、嵌套过深或变体过多");
        try {
            String text = fetcher.fetch(url);
            if (!text.trim().startsWith("#EXTM3U") && !text.trim().startsWith("\ufeff#EXTM3U"))
                throw new IOException("清单返回网页或登录页，无法下载");
            if (!text.contains("#EXT-X-STREAM-INF")) {
                Manifest manifest = new Manifest(url, text);
                validator.verify(manifest);
                return manifest;
            }
            List<HlsMasterPlaylist.Variant> variants = HlsMasterPlaylist.variants(url, text);
            variants.sort(java.util.Comparator.comparingInt((HlsMasterPlaylist.Variant v) ->
                    identity != null && com.github.tvbox.osc.util.PlaylistSnapshot.resourceIdentity(v.url).equals(identity) ? 0 : 1)
                    .thenComparingLong(v -> height > 0 ? Math.abs(v.height - height) : -v.height)
                    .thenComparingLong(v -> -v.bandwidth));
            IOException last = null;
            for (HlsMasterPlaylist.Variant variant : variants) {
                try { return load(variant.url, fetcher, validator, height, identity, path, depth + 1, budget); }
                catch (IOException e) { last = e; }
            }
            throw last == null ? new IOException("主播放列表无可用变体") : last;
        } finally { path.remove(url); }
    }
}

package com.github.tvbox.osc.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 主清单按画质排序，调用方逐个尝试；嵌套清单深度由加载器限制。 */
public final class HlsMasterPlaylist {
    private static final Pattern BANDWIDTH = Pattern.compile("(?:^|,)\\s*(?:AVERAGE-)?BANDWIDTH=(\\d+)");
    private static final Pattern HEIGHT = Pattern.compile("RESOLUTION=\\d+x(\\d+)");
    public static final class Variant {
        public final String url;
        public final long bandwidth;
        public final int height;
        Variant(String url, long bandwidth, int height) { this.url = url; this.bandwidth = bandwidth; this.height = height; }
    }
    private HlsMasterPlaylist() {}
    public static int preferredHeight(String label) {
        if (label == null) return 0;
        Matcher m = Pattern.compile("(?i)(\\d{3,4})p|([248])k").matcher(label);
        int height = 0;
        while (m.find()) height = m.group(1) != null ? Integer.parseInt(m.group(1))
                : m.group(2).equals("4") ? 2160 : m.group(2).equals("8") ? 4320 : 1440;
        return height;
    }
    public static List<Variant> variants(String url, String text) {
        List<Variant> variants = new ArrayList<>();
        String attrs = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) attrs = line.substring(18);
            else if (attrs != null && !line.isEmpty() && !line.startsWith("#")) {
                Matcher bw = BANDWIDTH.matcher(attrs), h = HEIGHT.matcher(attrs);
                variants.add(new Variant(HlsMediaPlaylist.resolveUrl(url, line),
                        bw.find() ? Long.parseLong(bw.group(1)) : 0, h.find() ? Integer.parseInt(h.group(1)) : 0));
                attrs = null;
            }
        }
        variants.sort(Comparator.comparingInt((Variant v) -> v.height).thenComparingLong(v -> v.bandwidth).reversed());
        return variants;
    }
}

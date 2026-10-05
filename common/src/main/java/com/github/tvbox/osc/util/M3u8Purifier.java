package com.github.tvbox.osc.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * m3u8 清单净化(纯逻辑,无 UI/播放器依赖;下沉到 :common 后**播放与下载共用同一份实现**)。
 *
 * <p>做三件事:
 * <ol>
 *   <li><b>剥 BOM</b>:带 BOM 的清单会被 {@code startsWith("#EXTM3U")} 判否 → 净化静默失效;</li>
 *   <li><b>剔除少数派分片</b>(广告/占位):按 URL 去掉最后 4 位扩展名后的前缀分组,出现次数最多的算正常分片,
 *       其余整组剔除。广告片在源站常被删掉(404),播放器因为不看它们所以一路顺,而下载器若照单全下就会
 *       "播放不缺、下载缺" —— 两条链路必须用同一份清单。</li>
 *   <li><b>把清单内的相对地址补成绝对地址</b>(分片、{@code #EXT-X-KEY}、{@code #EXT-X-MAP}、
 *       {@code #EXT-X-MEDIA} 等所有 {@code URI="..."} 属性):净化后的清单在播放链路由本机回环提供,
 *       相对地址会被当成回环地址;</li>
 * </ol>
 *
 * <p>无法判定时返回 null(前缀分组过多/结构异常/不是 m3u8),由调用方按"不做净化"处理
 * —— 播放与下载的这一步口径必须一致,否则又会出现两边清单不一样。
 */
public final class M3u8Purifier {
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]*)\"");

    private M3u8Purifier() {
    }

    /** 去掉开头的 BOM(U+FEFF)与前导空白 */
    public static String stripBom(String content) {
        if (content == null) return "";
        String s = (!content.isEmpty() && content.charAt(0) == '\uFEFF') ? content.substring(1) : content;
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i == 0 ? s : s.substring(i);
    }

    /**
     * Strip a BOM without applying ad filtering, then resolve every playlist URI against its origin.
     * The local loopback endpoint cannot safely serve relative URIs because they would resolve locally.
     * Returns null for malformed URIs so callers can fall back to the origin.
     */
    public static String normalizeForLocalPlayback(String playlistUrl, String playlistText) {
        String content = stripBom(playlistText);
        if (!content.startsWith("#EXTM3U")) return null;
        final URI base;
        try {
            base = URI.create(playlistUrl);
            if (!base.isAbsolute()) return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = content.split("\\r?\\n", -1);
        StringBuilder result = new StringBuilder(content.length() + 128);
        try {
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) result.append(newline);
                String line = lines[i];
                if (line.startsWith("#EXT-X-")) {
                    Matcher matcher = URI_ATTRIBUTE.matcher(line);
                    StringBuffer replacement = new StringBuffer();
                    while (matcher.find()) {
                        String absolute = resolvePlaylistUri(base, matcher.group(1));
                        matcher.appendReplacement(replacement, Matcher.quoteReplacement("URI=\"" + absolute + "\""));
                    }
                    matcher.appendTail(replacement);
                    result.append(replacement);
                } else if (!line.trim().isEmpty() && !line.trim().startsWith("#")) {
                    result.append(resolvePlaylistUri(base, line.trim()));
                } else {
                    result.append(line);
                }
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return result.toString();
    }

    private static String resolvePlaylistUri(URI base, String value) {
        URI reference = URI.create(value);
        if (!reference.isAbsolute() && reference.getRawAuthority() == null
                && (reference.getRawPath() == null || reference.getRawPath().isEmpty())
                && reference.getRawQuery() != null) {
            // java.net.URI.resolve("?segment=1") drops the base filename; a query-only HLS
            // reference instead targets the same playlist path with a different query.
            String text = base.toString();
            int end = text.length();
            int query = text.indexOf('?');
            int fragment = text.indexOf('#');
            if (query >= 0) end = Math.min(end, query);
            if (fragment >= 0) end = Math.min(end, fragment);
            return text.substring(0, end) + reference;
        }
        return base.resolve(reference).toString();
    }

    /**
     * Keep the existing minority-URL filter as the first pass, then remove ad breaks explicitly
     * delimited by CUE-OUT/CUE-IN when the playlist is simple enough to rewrite safely.
     * A missing or ambiguous cue leaves the first pass result unchanged.
     */
    public static String removeAds(String playlistUrl, String m3u8content) {
        if (playlistUrl == null) return null;
        String minorityResult = removeMinorityUrl(playlistDirectory(playlistUrl), m3u8content);
        String cueResult = removeCueSignaledAds(playlistUrl,
                minorityResult == null ? m3u8content : minorityResult);
        return cueResult == null ? minorityResult : cueResult;
    }

    private static String playlistDirectory(String playlistUrl) {
        int end = playlistUrl.length();
        int query = playlistUrl.indexOf('?');
        int fragment = playlistUrl.indexOf('#');
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        int scheme = playlistUrl.indexOf("://");
        int slash = playlistUrl.lastIndexOf('/', end - 1);
        if (scheme >= 0 && slash <= scheme + 2) return playlistUrl.substring(0, end) + "/";
        return slash >= 0 ? playlistUrl.substring(0, slash + 1) : "";
    }

    private static String removeCueSignaledAds(String playlistUrl, String playlist) {
        String content = stripBom(playlist);
        if (!content.startsWith("#EXTM3U")) return null;
        String lineBreak = content.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = content.split("\\r?\\n", -1);
        if (!lines[0].trim().equals("#EXTM3U")) return null;
        boolean ended = false;
        int totalSegments = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.equals("#EXT-X-ENDLIST")) ended = true;
            // Removing segments can change implicit IVs, byte offsets, initialization sections,
            // timing, or rendition alignment. Keep the old filter's result in these cases.
            if (line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-BYTERANGE")
                    || line.startsWith("#EXT-X-MAP") || line.startsWith("#EXT-X-MEDIA-SEQUENCE")
                    || line.startsWith("#EXT-X-DISCONTINUITY")
                    || line.startsWith("#EXT-X-PROGRAM-DATE-TIME")
                    || line.startsWith("#EXT-X-DATERANGE")
                    || line.startsWith("#EXT-X-START:")
                    || line.startsWith("#EXT-X-STREAM-INF:")
                    || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                    || line.startsWith("#EXT-X-MEDIA:")) return null;
            if (!line.isEmpty() && line.charAt(0) != '#') totalSegments++;
        }
        if (!ended || totalSegments < 2) return null;

        List<int[]> adSpans = new ArrayList<>();
        int start = -1;
        int segments = 0;
        int removedSegments = 0;
        boolean pendingExtinf = false;
        boolean seenEndlist = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.equals("#EXTM3U")) {
                if (i != 0) return null;
            } else if (line.startsWith("#EXTINF:")) {
                if (pendingExtinf || seenEndlist) return null;
                pendingExtinf = true;
            } else if (isCueOut(line)) {
                if (start >= 0 || pendingExtinf || seenEndlist) return null;
                start = i;
                segments = 0;
            } else if (isCueIn(line)) {
                if (start < 0 || segments == 0 || pendingExtinf || seenEndlist) return null;
                adSpans.add(new int[]{start, i});
                removedSegments += segments;
                start = -1;
            } else if (isCueOutCont(line) || line.startsWith("#EXT-OATCLS-SCTE35")) {
                if (start < 0 || pendingExtinf || seenEndlist) return null;
            } else if (line.equals("#EXT-X-ENDLIST")) {
                if (start >= 0 || pendingExtinf || seenEndlist) return null;
                seenEndlist = true;
            } else if (line.charAt(0) == '#') {
                // Keep only unambiguous playlist-wide tags outside a cue span. A tag between
                // EXTINF and URI, or within a removed span, may change the next segment.
                if (start >= 0 || pendingExtinf || seenEndlist) return null;
            } else {
                if (!pendingExtinf || seenEndlist) return null;
                pendingExtinf = false;
                if (start >= 0) segments++;
            }
        }
        if (start >= 0 || pendingExtinf || !seenEndlist || adSpans.isEmpty()
                || removedSegments >= totalSegments) return null;

        StringBuilder filtered = new StringBuilder(content.length());
        int spanIndex = 0;
        for (int i = 0; i < lines.length; i++) {
            if (spanIndex < adSpans.size() && i == adSpans.get(spanIndex)[0]) {
                i = adSpans.get(spanIndex)[1];
                spanIndex++;
                continue;
            }
            if (filtered.length() > 0) filtered.append(lineBreak);
            filtered.append(lines[i]);
        }
        // The cleaned list is served from loopback, so all remaining relative URIs must
        // continue to resolve against the original playlist URL (including query-only URIs).
        return normalizeForLocalPlayback(playlistUrl, filtered.toString());
    }

    private static boolean isCueOut(String line) {
        return line.equals("#EXT-X-CUE-OUT") || line.startsWith("#EXT-X-CUE-OUT:");
    }

    private static boolean isCueIn(String line) {
        return line.equals("#EXT-X-CUE-IN") || line.startsWith("#EXT-X-CUE-IN:");
    }

    private static boolean isCueOutCont(String line) {
        return line.equals("#EXT-X-CUE-OUT-CONT") || line.startsWith("#EXT-X-CUE-OUT-CONT:");
    }

    /**
     * 移除 m3u8 中的少数派分片(广告),并把清单内相对地址补成绝对地址。
     *
     * @param tsUrlPre   该 m3u8 所在目录(用于拼接相对路径)
     * @param m3u8content m3u8 文本
     * @return 净化后的 m3u8;无法识别(前缀过多/结构异常/非 m3u8)返回 null
     */
    public static String removeMinorityUrl(String tsUrlPre, String m3u8content) {
        String content = stripBom(m3u8content);
        if (!content.startsWith("#EXTM3U")) return null;
        String linesplit = content.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = content.split(linesplit, -1);

        java.util.HashMap<String, Integer> preUrlMap = new java.util.HashMap<>();
        for (String line : lines) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            int ilast = line.lastIndexOf('.');
            if (ilast <= 4) continue;
            String preUrl = line.substring(0, ilast - 4);
            Integer n = preUrlMap.get(preUrl);
            preUrlMap.put(preUrl, n == null ? 1 : n + 1);
        }
        if (preUrlMap.size() <= 1) return null;
        if (preUrlMap.size() > 5) return null; // 前缀种类太多,无法判定哪个是广告

        int maxTimes = 0;
        String maxTimesPreUrl = "";
        for (java.util.Map.Entry<String, Integer> e : preUrlMap.entrySet()) {
            if (e.getValue() > maxTimes) {
                maxTimesPreUrl = e.getKey();
                maxTimes = e.getValue();
            }
        }
        if (maxTimes == 0) return null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // 所有带 URI="..." 的标签都要补绝对地址(#EXT-X-KEY 的多密钥轮换、#EXT-X-MAP 的 fMP4 init 段、
            // #EXT-X-MEDIA / #EXT-X-I-FRAME-STREAM-INF 等)
            if (line.startsWith("#EXT-X-") && line.contains("URI=\"")) {
                String uri = substringBetween(line, "URI=\"", "\"");
                if (uri != null) {
                    String absUri = absoluteUrl(tsUrlPre, uri);
                    if (!absUri.equals(uri)) {
                        lines[i] = line.replace("URI=\"" + uri + "\"", "URI=\"" + absUri + "\"");
                    }
                }
            }
            if (lines[i].isEmpty() || lines[i].charAt(0) == '#') continue;
            if (lines[i].startsWith(maxTimesPreUrl)) {
                lines[i] = absoluteUrl(tsUrlPre, lines[i]);
            } else {
                if (i > 0 && !lines[i - 1].isEmpty() && lines[i - 1].charAt(0) == '#') {
                    lines[i - 1] = ""; // 连同它的 #EXTINF 一起清掉,避免留下"空时长"条目
                }
                lines[i] = "";
            }
        }
        StringBuilder sb = new StringBuilder(content.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append(linesplit);
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * 把 m3u8 里的相对地址补成绝对地址(纯字符串处理,便于单测)。
     *
     * @param tsUrlPre m3u8 所在目录(如 {@code https://cdn/a/b/})
     * @param url      待补的地址:已是绝对地址原样返回;以 {@code /} 开头按站点根拼;其余按目录拼
     */
    public static String absoluteUrl(String tsUrlPre, String url) {
        if (url == null || url.isEmpty()) return url;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        String pre = tsUrlPre == null ? "" : tsUrlPre;
        if (url.startsWith("/")) {
            // 取站点根(scheme://host[:port]):跳过 https:// 的两个斜杠后再找第一个斜杠。
            // 找不到(如 base 就是 https://host,没有路径)时退化为去掉结尾斜杠 —— 原来直接
            // substring(0, -1) 会抛 StringIndexOutOfBoundsException,整条播放链路异常
            int idx = pre.indexOf('/', 9);
            String origin = idx > 0 ? pre.substring(0, idx) : trimEndSlash(pre);
            return origin + url;
        }
        String dir = pre.endsWith("/") ? pre : pre + "/";
        return dir + url;
    }

    /** 取 {@code open} 与 {@code close} 之间的第一段(取不到返回 null) */
    private static String substringBetween(String text, String open, String close) {
        int a = text.indexOf(open);
        if (a < 0) return null;
        int start = a + open.length();
        int b = text.indexOf(close, start);
        if (b < 0) return null;
        return text.substring(start, b);
    }

    private static String trimEndSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }
}

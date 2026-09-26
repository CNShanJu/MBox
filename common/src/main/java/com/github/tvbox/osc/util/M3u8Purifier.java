package com.github.tvbox.osc.util;

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

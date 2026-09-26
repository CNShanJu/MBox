package com.github.tvbox.osc.spiderapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 影视站(苹果CMS/MacCMS 系)HTML 页面结构识别:纯字符串逻辑,不碰网络也不依赖 Android,可 JVM 单测。
 * <p>
 * 用在"站点采集接口关闭/不开放,只能抓页面"的场景(见 app 侧 HtmlSiteImporter):
 * 子目录提示、分类列表、详情/播放链接、播放页真实地址、总页数——生成给
 * {@code assets://js/lib/maccms.js}(通用抓取源)用的 ext 配置。
 * <p>
 * 与 {@link CmsApiRules} 的分工:后者识别"采集接口"这条路,前者识别"抓页面"这条路;
 * 站点有可用接口时优先走接口(分类/搜索更全),接口不可用再抓页面。
 */
public final class HtmlSiteRules {

    private HtmlSiteRules() {
    }

    /** 详情页链接(苹果CMS 几种常见路由) */
    private static final Pattern DETAIL_HREF = Pattern.compile(
            "href\\s*=\\s*[\"']([^\"']*?(?:vod/detail/id/|voddetail/|/detail/id/)(\\d+)\\.html[^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE);

    /** 播放页链接(vod/play/id/N/sid/S/nid/T.html 或 vodplay/N-S-T.html) */
    private static final Pattern PLAY_HREF = Pattern.compile(
            "href\\s*=\\s*[\"']([^\"']*(?:vod/play/id/|vodplay/)\\d+[^\"']*\\.html[^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE);

    /** 分类链接(带类型 id) */
    private static final Pattern CLASS_HREF = Pattern.compile(
            "<a\\s[^>]*href\\s*=\\s*[\"']([^\"']*?(?:/vod/type/id/|/vodtype/)(\\d+)(?:\\.html)?[^\"']*)[\"'][^>]*>([\\s\\S]{0,60}?)</a>",
            Pattern.CASE_INSENSITIVE);

    /** 分页链接里的页码(总页数取最大值) */
    private static final Pattern PAGE_NUM = Pattern.compile(
            "(?:/page/|vodtype/|/vodshow/\\d+-)(\\d+)", Pattern.CASE_INSENSITIVE);

    /** 站点子目录线索:形如 /jiejie/index.php/vod/… 或 /jiejie/vodshow/… */
    private static final Pattern SUBDIR = Pattern.compile(
            "/([A-Za-z0-9_\\-]{1,24})/(?:index\\.php/)?(?:vod/|vodshow/|vodtype/|voddetail/|vodplay/|vodsearch/)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TITLE = Pattern.compile(
            "<title[^>]*>([\\s\\S]{0,200}?)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 12.0; Mobile) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 探测用浏览器 UA(部分站按 UA 拦截 okhttp 字样) */
    public static String browserUserAgent() {
        return BROWSER_UA;
    }

    // ------------------------------------------------------------------
    // 站点识别
    // ------------------------------------------------------------------

    /**
     * 是否像"可抓页面的苹果CMS 站":页面里有 maccms 变量、或同时出现详情链接与播放链接。
     */
    public static boolean looksLikeMacCmsHtml(String html) {
        if (html == null || html.isEmpty()) return false;
        if (html.contains("var maccms=") || html.contains("maccms = ")) return true;
        return !detailHrefs(html, 1).isEmpty() && html.contains("vod/play/");
    }

    /**
     * 站点子目录提示:书源规则/页面里写着 {@code /jiejie/index.php/vod/…} 时给 {@code "/jiejie"};
     * 站点就在根目录(形如 {@code /index.php/vod/…})给空串。取不到返回空串。
     * <p>
     * 用途:书源/站点地址常只写到域名(如 {@code https://wap.jiejiesp19.xyz}),而站点实际挂在
     * {@code /jiejie/} 子目录下,直接抓根目录只会拿到一张无关的落地页。
     */
    public static String subdirHint(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = SUBDIR.matcher(text);
        while (m.find()) {
            String dir = m.group(1);
            if (dir.isEmpty()) continue;
            // index.php 之类的"文件段"不算目录
            String lower = dir.toLowerCase();
            if (lower.startsWith("index") || lower.equals("vod") || lower.equals("api")) continue;
            return "/" + dir;
        }
        return "";
    }

    /** 页面标题(去标签),站点名兜底用 */
    public static String pageTitle(String html) {
        if (html == null) return "";
        Matcher m = TITLE.matcher(html);
        if (!m.find()) return "";
        String title = TAG.matcher(m.group(1)).replaceAll(" ").replace("&nbsp;", " ").trim();
        return title.isEmpty() ? "" : title;
    }

    /**
     * 站点名:标题按常见分隔符截断取首段,取不到用主机名。
     */
    public static String siteName(String html, String siteUrl) {
        String title = pageTitle(html);
        if (!title.isEmpty()) {
            int cut = title.length();
            for (char sep : new char[]{'-', '_', '|', '—', '·', ',', '，', '、', '/'}) {
                int i = title.indexOf(sep);
                if (i > 1) cut = Math.min(cut, i);
            }
            String name = title.substring(0, cut).trim();
            if (!name.isEmpty() && name.length() <= 24) return name;
        }
        return CmsApiRules.displayHost(siteUrl);
    }

    /**
     * 分类列表(type_id → type_name,保持页面顺序):首页导航里带 {@code /vod/type/id/N.html}
     * 或 {@code /vodtype/N.html} 的链接。
     */
    public static LinkedHashMap<String, String> classes(String html) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (html == null || html.isEmpty()) return out;
        Matcher m = CLASS_HREF.matcher(html);
        while (m.find()) {
            String id = m.group(2);
            String name = stripTags(m.group(3));
            if (id == null || id.isEmpty() || name.isEmpty() || out.containsKey(id)) continue;
            if (name.length() > 12) continue;      // 导航项不会太长,过长的多半不是分类名
            out.put(id, name);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 链接与地址提取
    // ------------------------------------------------------------------

    /** 详情页链接(去重,按页面顺序,最多 limit 条;limit<=0 表示不限) */
    public static List<String> detailHrefs(String html, int limit) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        Matcher m = DETAIL_HREF.matcher(html);
        while (m.find()) {
            String href = m.group(1);
            if (href != null && seen.add(href)) {
                out.add(href);
                if (limit > 0 && out.size() >= limit) break;
            }
        }
        return out;
    }

    /**
     * 播放页链接:只收"与当前片同一个 vod id"的(vodId 为空则只取第一条)。
     * 详情页底部"猜你喜欢"同样是一堆播放链接,不过滤会把别的片当成本片剧集。
     */
    public static List<String> playHrefs(String html, String vodId) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        Matcher m = PLAY_HREF.matcher(html);
        while (m.find()) {
            String href = m.group(1);
            if (href == null) continue;
            if (vodId != null && !vodId.isEmpty()) {
                String id = playVodId(href);
                if (id == null || !id.equals(vodId)) continue;
            }
            if (seen.add(href)) {
                out.add(href);
                if (vodId == null || vodId.isEmpty()) break;   // 不知道本片 id 时只认第一条,避免把推荐位当剧集
            }
        }
        return out;
    }

    /** 从详情页地址取 vod id(标准路由 {@code /vod/detail/id/N.html} 与伪静态 {@code /voddetail/N.html}) */
    public static String vodIdOfDetailUrl(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("vod/detail/id/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("voddetail/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    /** 从播放页地址取 vod id */
    public static String playVodId(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("vod/play/id/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("vodplay/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    /** 总页数:取分页里出现的最大页码;取不到按当前页 */
    public static int pageCount(String html, int currentPage) {
        int max = 0;
        if (html != null && !html.isEmpty()) {
            Matcher m = PAGE_NUM.matcher(html);
            while (m.find()) {
                try {
                    int n = Integer.parseInt(m.group(1));
                    if (n > max) max = n;
                } catch (Throwable ignored) {
                }
            }
            Matcher slash = Pattern.compile(
                    "class\\s*=\\s*[\"'][^\"']*(?:active|num)[^\"']*[\"'][^>]*>[\\s\\S]{0,40}?(\\d+)\\s*/\\s*(\\d+)",
                    Pattern.CASE_INSENSITIVE).matcher(html);
            if (slash.find()) {
                try {
                    int total = Integer.parseInt(slash.group(2));
                    if (total > max && total <= 1000000) max = total;
                } catch (Throwable ignored) {
                }
            }
        }
        return max > 0 ? max : Math.max(currentPage, 1);
    }

    /**
     * 播放页里的真实播放地址:player_aaaa/player_xxx 配置优先(苹果CMS 默认播放器会把地址写在
     * {@code player_aaaa={"url":"…index.m3u8"}} 里),其次 m3u8/mp4 正则,最后 iframe 外链。
     *
     * @return 命中的地址;播放页没有可用地址返回 null
     */
    public static String playerUrl(String html) {
        if (html == null || html.isEmpty()) return null;
        Matcher player = Pattern.compile("player_[a-z0-9]+\\s*=\\s*(\\{[\\s\\S]{0,4000}?\\})\\s*</script>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (player.find()) {
            String json = player.group(1).replace("\\/", "/");
            Matcher url = Pattern.compile("[\"']url[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(json);
            if (url.find()) {
                String u = cleanUrl(url.group(1));
                if (isHttp(u) && !isPageUrl(u)) return u;
            }
        }
        Matcher mac = Pattern.compile("MacPlayer\\s*=\\s*\\{[\\s\\S]{0,2000}?\\}", Pattern.CASE_INSENSITIVE).matcher(html);
        if (mac.find()) {
            Matcher url = Pattern.compile("[\"']?(?:url|Url)[\"']?\\s*:\\s*[\"']([^\"']+)[\"']").matcher(mac.group());
            if (url.find()) {
                String u = cleanUrl(url.group(1));
                if (isHttp(u) && !isPageUrl(u)) return u;
            }
        }
        Matcher direct = Pattern.compile("[\"'](https?:[^\"'<>\\s\\\\]+?\\.(?:m3u8|mp4|flv|mkv|avi|webm)(?:\\?[^\"'<>\\s\\\\]*)?)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (direct.find()) return cleanUrl(direct.group(1));
        Matcher bare = Pattern.compile("(https?://[^\\s\"'<>]+?\\.(?:m3u8|mp4|flv|mkv|avi|webm)(?:\\?[^\\s\"'<>]*)?)",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (bare.find()) return cleanUrl(bare.group(1));
        Matcher frame = Pattern.compile("<iframe\\b[^>]*src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        if (frame.find()) {
            String u = cleanUrl(frame.group(1));
            if (isHttp(u) && u.indexOf("javascript:") != 0) return u;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 生成抓取源配置
    // ------------------------------------------------------------------

    /**
     * 生成 {@code assets://js/lib/maccms.js} 运行时用的 ext 配置(JSON 文本)。
     *
     * @param classes type_id → type_name(可为空)
     */
    public static String buildExtJson(String siteName, String host, String prefix,
                                      Map<String, String> classes, String listUrl, String listPageUrl,
                                      String searchUrl, String searchPageUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"siteName\":\"").append(esc(siteName)).append("\",");
        sb.append("\"host\":\"").append(esc(host)).append("\",");
        sb.append("\"prefix\":\"").append(esc(prefix == null ? "" : prefix)).append("\",");
        sb.append("\"homeUrl\":\"/\",");
        sb.append("\"listUrl\":\"").append(esc(listUrl)).append("\",");
        sb.append("\"listPageUrl\":\"").append(esc(listPageUrl)).append("\",");
        if (searchUrl != null && !searchUrl.isEmpty()) {
            sb.append("\"searchUrl\":\"").append(esc(searchUrl)).append("\",");
            if (searchPageUrl != null && !searchPageUrl.isEmpty()) {
                sb.append("\"searchPageUrl\":\"").append(esc(searchPageUrl)).append("\",");
            }
        }
        sb.append("\"headers\":{\"User-Agent\":\"").append(esc(browserUserAgent())).append("\"},");
        sb.append("\"classes\":[");
        boolean first = true;
        if (classes != null) {
            for (Map.Entry<String, String> e : classes.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"type_id\":\"").append(esc(e.getKey()))
                        .append("\",\"type_name\":\"").append(esc(e.getValue())).append("\"}");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * 列表/搜索路由:同一套苹果CMS 因为有"开 index.php"与"伪静态"两类写法,
     * 加上主题差异,同一份识别代码要能按候选逐个实探,探通哪个就用哪个(结果写进 ext)。
     */
    public static final class Route {
        /** 分类/搜索列表模板({@code {id}}/{@code {pg}}/{@code {key}} 占位) */
        public final String listUrl;
        /** 翻页模板(同上占位);没有独立翻页写法时为 null */
        public final String listPageUrl;

        Route(String listUrl, String listPageUrl) {
            this.listUrl = listUrl;
            this.listPageUrl = listPageUrl;
        }

        @Override
        public String toString() {
            return listUrl + " | " + listPageUrl;
        }
    }

    /** 一条待实探的地址 + 它属于哪个路由(探通后据此把模板写进 ext) */
    public static final class Probe {
        public final String url;
        public final Route route;

        Probe(String url, Route route) {
            this.url = url;
            this.route = route;
        }

        @Override
        public String toString() {
            return url;
        }
    }

    /**
     * 分类列表路由候选(按"命中概率"排序):
     * ① v10 默认 {@code /index.php/vod/type/id/N.html} ② {@code /index.php/vod/show/id/N.html}
     * ③ 伪静态 {@code /vodtype/N.html} ④ 伪静态 {@code /vodshow/N--------P---.html}
     */
    public static List<Route> listRoutes(String prefix) {
        String pp = p(prefix);
        List<Route> out = new ArrayList<>();
        out.add(new Route(pp + "/index.php/vod/type/id/{id}.html",
                pp + "/index.php/vod/type/id/{id}/page/{pg}.html"));
        out.add(new Route(pp + "/index.php/vod/show/id/{id}.html",
                pp + "/index.php/vod/show/id/{id}/page/{pg}.html"));
        out.add(new Route(pp + "/vodtype/{id}.html", pp + "/vodtype/{id}-{pg}.html"));
        out.add(new Route(pp + "/vodshow/{id}--------1---.html",
                pp + "/vodshow/{id}--------{pg}---.html"));
        return out;
    }

    /**
     * 搜索路由候选:① v10 {@code /index.php/vod/search.html?wd=} ② 伪静态 {@code /vodsearch/{key}----------P---.html}
     * ③ 伪静态查询串 {@code /vodsearch.html?wd=}
     */
    public static List<Route> searchRoutes(String prefix) {
        String pp = p(prefix);
        List<Route> out = new ArrayList<>();
        out.add(new Route(pp + "/index.php/vod/search.html?wd={key}",
                pp + "/index.php/vod/search/page/{pg}/wd/{key}.html"));
        out.add(new Route(pp + "/vodsearch/{key}----------1---.html",
                pp + "/vodsearch/{key}----------{pg}---.html"));
        out.add(new Route(pp + "/vodsearch.html?wd={key}", pp + "/vodsearch.html?wd={key}&page={pg}"));
        return out;
    }

    /**
     * 分类页探测计划:路由 × 分类交叉展开(同一路由下先试前几个分类,空的分类很常见),
     * 调用方按顺序实探,拿到详情链接即停。顺序保证"概率最高的路由 + 首个分类"排最前。
     *
     * @param classIds          分类 id(按页面顺序)
     * @param maxClassesPerRoute 每个路由最多带几个分类(<=0 视为 3)
     */
    public static List<Probe> listProbePlan(String prefix, List<String> classIds, int maxClassesPerRoute) {
        List<Probe> out = new ArrayList<>();
        if (classIds == null || classIds.isEmpty()) return out;
        int maxClasses = maxClassesPerRoute > 0 ? maxClassesPerRoute : 3;
        int count = Math.min(maxClasses, classIds.size());
        for (Route route : listRoutes(prefix)) {
            for (int i = 0; i < count; i++) {
                String id = classIds.get(i);
                if (id == null || id.isEmpty()) continue;
                out.add(new Probe(fill(route.listUrl, "{id}", id), route));
            }
        }
        return out;
    }

    /** 搜索探测计划:每个搜索路由一条(关键词已编码) */
    public static List<Probe> searchProbePlan(String prefix, String encodedKeyword) {
        List<Probe> out = new ArrayList<>();
        if (encodedKeyword == null || encodedKeyword.isEmpty()) return out;
        for (Route route : searchRoutes(prefix)) {
            if (route.listPageUrl != null) {
                out.add(new Probe(fill(fill(route.listPageUrl, "{key}", encodedKeyword), "{pg}", "2"), route));
            }
        }
        return out;
    }

    /** 模板占位替换({@code {id}}/{@code {pg}}/{@code {key}};字面量替换,出现多次也全换) */
    public static String fill(String template, String key, String value) {
        if (template == null || key == null || value == null) return template;
        return template.replace(key, value);
    }

    /** 苹果CMS v10 标准路由(抓页面探测的起点;真实可用路径由探测结果决定) */
    public static String defaultListUrl(String prefix) {
        return listRoutes(prefix).get(0).listUrl;
    }

    public static String defaultListPageUrl(String prefix) {
        return listRoutes(prefix).get(0).listPageUrl;
    }

    public static String defaultSearchUrl(String prefix) {
        return searchRoutes(prefix).get(0).listUrl;
    }

    public static String defaultSearchPageUrl(String prefix) {
        return searchRoutes(prefix).get(0).listPageUrl;
    }

    private static String p(String prefix) {
        if (prefix == null || prefix.isEmpty() || "/".equals(prefix)) return "";
        String v = prefix.startsWith("/") ? prefix : "/" + prefix;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    /** 按模板拼站内地址(前缀 + 相对路径) */
    public static String siteUrl(String host, String prefix, String pathOrTemplate) {
        if (pathOrTemplate == null || pathOrTemplate.isEmpty()) return homeUrl(host, prefix);
        if (pathOrTemplate.regionMatches(true, 0, "http", 0, 4)) return pathOrTemplate;
        String pp = p(prefix);
        String path = pathOrTemplate.startsWith("/") ? pathOrTemplate : "/" + pathOrTemplate;
        if (!pp.isEmpty() && !path.startsWith(pp + "/") && !path.equals(pp)) path = pp + path;
        return trimHost(host) + path;
    }

    /** 站点首页地址(host + 子目录 + "/") */
    public static String homeUrl(String host, String prefix) {
        return trimHost(host) + p(prefix) + "/";
    }

    private static String trimHost(String host) {
        String h = host == null ? "" : host.trim();
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        return h;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static boolean isHttp(String u) {
        return u != null && u.regionMatches(true, 0, "http", 0, 4);
    }

    /** 播放页/详情页地址本身不算播放地址(player_aaaa.url 偶尔会被写成当前页) */
    private static boolean isPageUrl(String u) {
        String s = u.toLowerCase();
        return s.contains("vod/play/") || s.contains("vod/detail/") || s.contains("vodplay/") || s.contains("voddetail/");
    }

    private static String cleanUrl(String u) {
        String s = u == null ? "" : u.replace("\\/", "/").replace("\\", "").trim();
        if (s.endsWith(",")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String stripTags(String html) {
        return html == null ? "" : TAG.matcher(html).replaceAll(" ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'")
                .replace("&quot;", "\"").replaceAll("\\s+", " ").trim();
    }

    /** JSON 字符串转义 */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }
}

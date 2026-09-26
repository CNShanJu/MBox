package com.github.tvbox.osc.spiderapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 资源站(苹果CMS/MacCMS 系)采集接口识别规则:纯字符串逻辑,不碰网络也不依赖 Android,可 JVM 单测。
 * <p>
 * 用于"订阅导入时用户只填了站点地址"的场景:先取站点页面里自己声明的采集地址(资源站常把接口写在
 * 帮助/采集说明页),再按 CMS 默认路径猜(见 {@link #defaultApiUrls});是否真能用由调用方实际探测响应决定
 * (见 {@link #detectKind}),确认可用后用 {@link #buildSubscriptionJson} 生成标准订阅配置。
 * <p>
 * 另含"订阅内容形态"判定({@link #subscriptionShape}):导入(粘贴 JSON / 本地文件 / 订阅地址响应)前判断
 * 这份内容能不能当订阅配置用、要不要补 {@code sites} 外壳,以及它是不是「阅读」(Legado)App 的书源
 * (见 {@link #looksLikeBookSource});不能用的内容不再落盘——否则必然在加载阶段报"解析配置失败",
 * 还会顺手把当前可用订阅挤掉。
 */
public final class CmsApiRules {

    private CmsApiRules() {
    }

    /**
     * 顺序试探的候选上限(再多会让用户等太久)。
     * 站点根默认路径({@link #defaultApiUrls} 前 7 条)总是排在候选最前,故不会被这里的截断挤掉。
     */
    public static final int MAX_CANDIDATES = 10;

    /** 说明页(帮助中心/采集教程)最多翻几个:每个都是一次网络请求 */
    public static final int MAX_DOC_PAGES = 2;

    /**
     * CMS 默认采集接口相对路径:JSON 优先(字段全、与 type1 详情参数 ac=detail 直接兼容),XML 兜底。
     * {@code at/josn} 不是笔误修正而是现实兼容——不少站点就这么写,MacCMS 对未知 at 值回落默认 JSON 输出。
     */
    private static final String[] DEFAULT_API_PATHS = {
            "api.php/provide/vod/at/json/",
            "api.php/provide/vod/at/josn/",
            "api.php/provide/vod/",
            "api.php/provide/vod/at/xml/",
            "api.php/provide/vod/from/m3u8/at/json/",
            "api.php/provide/vod/from/m3u8/at/xml/",
            "provide/vod/",
    };

    private static final String HTTP = "http";
    private static final String HTTPS = "https";

    /** 页面文本里的接口地址:必须以 http(s) 开头且明显是采集接口(provide/vod 或 api.php + vod) */
    private static final Pattern PAGE_API = Pattern.compile(
            "https?://[A-Za-z0-9._\\-]+(?::\\d+)?/[^\\s\"'<>()\\[\\]{}，。、；：）】《》]*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TITLE = Pattern.compile(
            "<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    /** 资源站标题里的 SEO 分隔符 */
    private static final char[] NAME_SEPARATORS = {'-', '_', '|', '—', '·', ',', '，', '、', '/'};

    private static final int MAX_NAME_LEN = 24;

    /** 首页里的锚点:href 与可见文字都要看(说明页常写作"帮助中心"而链接是 /index.php/help) */
    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\s[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 可能写着采集接口的说明页关键词 */
    private static final Pattern DOC_HINT = Pattern.compile(
            "help|caiji|采集|接口|教程|线路", Pattern.CASE_INSENSITIVE);

    /** 内容形态:已是可直接加载的订阅配置({@code sites} 为数组) */
    public static final int SHAPE_CONFIG = 0;

    /**
     * 内容形态:裸站点条目/数组,补 {@code {"sites":[…]} 外壳} 后可加载(见 {@link #wrapSiteJson})。
     * 判定与补壳同规则:判为 SITE 即代表 {@link #wrapSiteJson} 能补出壳来
     */
    public static final int SHAPE_SITE = 1;

    /** 内容形态:加密配置套路(图片+base64 / AES 包头),内容无法就地判定,交加载阶段解码 */
    public static final int SHAPE_ENCRYPTED = 2;

    /** 内容形态:「阅读」(Legado)App 的书源:带 {@code sourceUrl} 与 {@code rule*} 规则,不是 TVBox 源 */
    public static final int SHAPE_BOOK_SOURCE = 3;

    /** 内容形态:只有直播源({@code lives})的清单:不是订阅配置(直播源另有填写入口) */
    public static final int SHAPE_LIVES = 4;

    /** 内容形态:其它内容:存成订阅必然在加载阶段报"解析配置失败" */
    public static final int SHAPE_UNUSABLE = 5;

    /** 「阅读」书源的规则字段:顶层出现任意一个即认定是书源(书源版本间字段名有差异,故列常用几个) */
    private static final String[] BOOK_SOURCE_RULE_KEYS = {
            "ruleSearch", "ruleExplore", "ruleBookInfo", "ruleToc", "ruleContent",
            "ruleHomeList", "ruleDetailTitle", "rulePlayUrl", "ruleSearchUrl"
    };

    /** 加密配置标记:8 位字母/0 + {@code **}(图片+base64 套路);另有一种以 {@code 2423} 开头的 AES 包头 */
    private static final Pattern ENCRYPTED_MARK = Pattern.compile("[A-Za-z0]{8}\\*\\*");

    // ------------------------------------------------------------------
    // 候选地址
    // ------------------------------------------------------------------

    /**
     * 汇总探测候选(去重、按试探优先级排序、截断到 {@link #MAX_CANDIDATES}):
     * 用户填的地址本身(很多人直接粘采集接口) → 页面里声明的采集地址 → 站点默认路径。
     *
     * @param siteUrl   用户填入的地址
     * @param pageHtml  该地址返回的页面内容(可为 JSON/HTML,可为 null)
     */
    public static List<String> candidates(String siteUrl, String pageHtml) {
        return candidates(siteUrl, apiUrlsFromPage(pageHtml, siteUrl));
    }

    /**
     * 同上,但页面里的采集地址由调用方给出(调用方可能翻过"帮助中心"等二级页才拿到地址,见 {@link #docPageUrls})。
     *
     * @param declaredApis 站点自己声明的采集地址,已按优先级排好
     */
    public static List<String> candidates(String siteUrl, List<String> declaredApis) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        String given = normalize(siteUrl);
        if (given != null && looksLikeApi(given)) set.add(given);
        if (declaredApis != null) set.addAll(declaredApis);
        for (String url : defaultApiUrls(siteUrl)) set.add(url);
        List<String> list = new ArrayList<>(set);
        return list.size() > MAX_CANDIDATES ? list.subList(0, MAX_CANDIDATES) : list;
    }

    /**
     * 首页里指向"帮助中心/采集说明"的链接(绝对化后最多 {@link #MAX_DOC_PAGES} 个)。
     * 很多资源站首页不写接口,只在说明页公布采集地址(红牛即如此),故需要二级页再扒一次。
     */
    public static List<String> docPageUrls(String siteUrl, String html) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty() || normalize(siteUrl) == null) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Matcher m = ANCHOR.matcher(html);
        while (m.find() && seen.size() < MAX_DOC_PAGES) {
            String href = m.group(1);
            if (href == null || !DOC_HINT.matcher(href).find()
                    && !DOC_HINT.matcher(stripTags(m.group(2))).find()) {
                continue;
            }
            String url = resolve(siteUrl, href);
            if (url == null || looksLikeApi(url)) continue; // 本身就是接口就不用再翻页
            seen.add(url);
        }
        out.addAll(seen);
        return out;
    }

    /**
     * 站点默认路径候选(以站点根为基准,再补一份"填入地址所在目录"的候选):
     * CMS 采集接口固定在站点根(api.php/provide/vod/…),用户粘的却常是某个栏目页或说明页,
     * 故根目录候选必须排在前面,避免被 {@link #MAX_CANDIDATES} 截断时把有用路径挤掉。
     */
    public static List<String> defaultApiUrls(String siteUrl) {
        List<String> out = new ArrayList<>();
        String root = rootOf(siteUrl);
        String base = dirOf(siteUrl);
        for (String dir : new String[]{root, base}) {
            if (dir == null) continue;
            for (String path : DEFAULT_API_PATHS) {
                String url = dir + path;
                if (!out.contains(url)) out.add(url);
            }
        }
        return out;
    }

    /**
     * 从页面内容里扒采集接口地址(HTML 源码或纯文本均可):与 {@code siteUrl} 同主站的地址优先,
     * 跨主站的(资源站常把采集接口挂在另一个域名)作为兜底保留。
     */
    public static List<String> apiUrlsFromPage(String html, String siteUrl) {
        List<String> sameHost = new ArrayList<>();
        List<String> otherHost = new ArrayList<>();
        if (html == null || html.isEmpty()) return otherHost;
        String host = hostOf(siteUrl == null ? "" : siteUrl);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Matcher m = PAGE_API.matcher(html);
        while (m.find()) {
            String url = normalize(m.group());
            if (url == null || !looksLikeApi(url) || !seen.add(url)) continue;
            String apiHost = hostOf(url);
            if (host != null && apiHost != null && host.equalsIgnoreCase(apiHost)) sameHost.add(url);
            else otherHost.add(url);
        }
        sameHost.addAll(otherHost);
        return sameHost;
    }

    /** 是否像采集接口:命中 provide/vod 采集路由,或 api.php 且带 vod */
    public static boolean looksLikeApi(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("provide/vod") || (lower.contains("api.php") && lower.contains("vod"));
    }

    /**
     * 是否"资源站地址"(站点首页/栏目页/说明页,而非订阅配置文件、也不是采集接口):
     * 用于"用户只丢一个站点地址进来也要能接上"的通用入口——这类地址不能直接当订阅用
     * (配置解析器要的是 sites 结构),必须先嗅探出采集接口再生成单源配置。
     * <p>
     * 判定:http(s) 且不含采集路由;去掉查询后的路径若以 json/txt/xml 结尾则视为配置文件(返回 false)。
     */
    public static boolean looksLikeSiteUrl(String url) {
        String u = normalize(url);
        if (u == null || looksLikeApi(u)) return false;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int slash = u.lastIndexOf('/');
        int scheme = u.indexOf("//");
        String last = slash < 0 || (scheme >= 0 && slash <= scheme + 1) ? "" : u.substring(slash + 1);
        String lower = last.toLowerCase();
        if (lower.endsWith(".json") || lower.endsWith(".txt") || lower.endsWith(".xml")) return false;
        return true;
    }

    /**
     * 把"单站点/站点数组"内容包成可订阅的最小配置 {@code {"sites":[…]}}:
     * 用户常直接粘 MacCMS 站的站点条目(带 api/name),那份内容本身不是订阅配置,
     * 存成订阅必然解析失败(缺 sites),这里补齐外壳;拿不到有效站点返回 null。
     * <p>
     * 兼容:{@code {…}} 单站点、{@code [{…}]} 数组,以及 {@code {"sites":[…]}} 里 sites 项为单站点的情形。
     */
    public static String wrapSiteJson(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;

        // 1) 顶层就是站点数组:[{api,name,type}…]
        if (t.charAt(0) == '[') {
            int end = t.lastIndexOf(']');
            return end <= 0 ? null : buildSitesJson(siteObjectParts(t.substring(1, end)));
        }
        if (t.charAt(0) != '{') return null;
        String body = objBody(t);
        if (body == null || body.isEmpty()) return null;

        // 2) 多线路/多仓清单:不是站点条目,交调用方原样存盘
        if (indexOfKey(body, "urls") >= 0 || indexOfKey(body, "storeHouse") >= 0) return null;
        // 3) 裸站点条目(带 api/type):补 sites 外壳
        if (indexOfKey(body, "sites") < 0) {
            String single = siteObjectPart(t);
            return single == null ? null : buildSitesJson(Collections.singletonList(single));
        }
        // 4) 已有 sites,但值是"被塞成字符串的数组"([{"sites":"[{…}]"}]):还原成真数组,别再套一层
        String inner = stringValueOf(body, "sites");
        if (inner != null && inner.trim().startsWith("[")) {
            String it = inner.trim();
            int end = it.lastIndexOf(']');
            List<String> items = end <= 0 ? null : siteObjectParts(it.substring(1, end));
            return items == null || items.isEmpty() ? null : buildSitesJson(items);
        }
        return t;   // 5) 正常的完整配置:原样返回
    }

    // ------------------------------------------------------------------
    // 订阅内容形态(导入前判定)
    // ------------------------------------------------------------------

    /**
     * 判定一段内容能不能当订阅配置用(JSON 粘贴导入、本地文件导入、订阅地址响应三处共用):
     * 不能用的内容一旦存成 clan:// 订阅,应用每次启动都会"解析配置失败",还会顺手把当前可用订阅挤掉
     * (线上实例:用户把「阅读」App 的书源粘进 JSON 导入,之后每次启动都报解析失败,怎么重选订阅都恢复不了)。
     *
     * @return {@link #SHAPE_CONFIG} / {@link #SHAPE_SITE} / {@link #SHAPE_ENCRYPTED} /
     *         {@link #SHAPE_BOOK_SOURCE} / {@link #SHAPE_LIVES} / {@link #SHAPE_UNUSABLE}
     */
    public static int subscriptionShape(String text) {
        String t = stripJsonNoise(text);
        if (t.isEmpty()) return SHAPE_UNUSABLE;
        char first = t.charAt(0);
        String body = first == '{' ? objBody(t) : null;
        // 能直接加载的配置最优先(避免被下面的书源/站点判定抢走)
        if (body != null && !body.isEmpty() && sitesIsArray(body)) return SHAPE_CONFIG;
        if (looksLikeBookSource(t)) return SHAPE_BOOK_SOURCE;
        if (first == '[') {
            // 站点数组([{api,type}…]):补 sites 外壳后可当单源订阅用
            int end = t.lastIndexOf(']');
            if (end <= 0) return SHAPE_UNUSABLE;
            return siteObjectParts(t.substring(1, end)).isEmpty() ? SHAPE_UNUSABLE : SHAPE_SITE;
        }
        if (first != '{') {
            // 非 JSON:只有加密套路还能在加载阶段解出配置,其它文本(网页/频道列表等)解析不了
            return ENCRYPTED_MARK.matcher(t).find() || t.startsWith("2423")
                    ? SHAPE_ENCRYPTED : SHAPE_UNUSABLE;
        }
        if (body == null || body.isEmpty()) return SHAPE_UNUSABLE;
        // 多线路/多仓是订阅"清单",不是订阅配置本身(调用方另有展开逻辑)
        if (indexOfKey(body, "urls") >= 0 || indexOfKey(body, "storeHouse") >= 0) return SHAPE_UNUSABLE;
        if (indexOfKey(body, "sites") < 0) {
            if (indexOfKey(body, "lives") >= 0) return SHAPE_LIVES;
            return siteObjectPart(t) == null ? SHAPE_UNUSABLE : SHAPE_SITE;
        }
        // sites 被塞成字符串的数组("sites":"[{…}]"):补壳后可加载;字符串里没有可用站点(如 "[]")则补不了
        String stringified = stringValueOf(body, "sites");
        if (stringified == null) return SHAPE_UNUSABLE;   // sites 存在但既不是数组也不是字符串数组
        String st = stringified.trim();
        if (!st.startsWith("[")) return SHAPE_UNUSABLE;
        int end = st.lastIndexOf(']');
        if (end <= 0 || siteObjectParts(st.substring(1, end)).isEmpty()) return SHAPE_UNUSABLE;
        return SHAPE_SITE;
    }

    /**
     * 是否「阅读」(Legado)App 的书源:带 {@code sourceUrl}/{@code bookSourceUrl} 且带 {@code rule*} 规则字段。
     * 这类内容与 TVBox 单条订阅/多仓条目同形(同样是 {@code {sourceName, sourceUrl}}),
     * 只看名字会被当成订阅条目收下,而它的规则体系 TVBox 完全用不了。
     */
    public static boolean looksLikeBookSource(String text) {
        String t = stripJsonNoise(text);
        if (t.isEmpty()) return false;
        if (t.charAt(0) == '{') {
            String body = objBody(t);
            return body != null && isBookSourceBody(body);
        }
        if (t.charAt(0) == '[') {
            // 书源常成组导出([{…},{…}]):看首个对象
            String body = firstObjectBody(t);
            return body != null && isBookSourceBody(body);
        }
        return false;
    }

    /**
     * 取书源里声明的站点地址({@code sourceUrl}/{@code bookSourceUrl});不是书源或没写地址返回 null。
     * 书源规则 TVBox 用不了,但它通常指向一个资源站(如红牛资源的书源 sourceUrl 就是站点首页),
     * 调用方可据此按站点再嗅探一次采集接口,省得用户再去翻订阅地址。
     */
    public static String bookSourceSiteUrl(String text) {
        String t = stripJsonNoise(text);
        if (t.isEmpty() || !looksLikeBookSource(t)) return null;
        String body;
        if (t.charAt(0) == '{') body = objBody(t);
        else if (t.charAt(0) == '[') body = firstObjectBody(t);
        else return null;
        if (body == null) return null;
        String url = stringValueOf(body, "sourceUrl");
        if (url == null || url.trim().isEmpty()) url = stringValueOf(body, "bookSourceUrl");
        if (url == null) return null;
        return url.trim().isEmpty() ? null : url.trim();
    }

    private static boolean isBookSourceBody(String body) {
        if (indexOfKey(body, "sourceUrl") < 0 && indexOfKey(body, "bookSourceUrl") < 0) return false;
        for (String key : BOOK_SOURCE_RULE_KEYS) {
            if (indexOfKey(body, key) >= 0) return true;
        }
        return false;
    }

    /** {@code body} 里 sites 的值是否为数组(字符串/对象/数字都不算) */
    private static boolean sitesIsArray(String body) {
        int at = indexOfKey(body, "sites");
        if (at < 0) return false;
        int i = body.indexOf(':', at);
        if (i < 0) return false;
        i++;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        return i < body.length() && body.charAt(i) == '[';
    }

    /**
     * 数组文本里首个对象的内容(书源成组导出时判定首个条目);取不到返回 null。
     * 按字符串/转义与括号深度扫描:书源规则里常内嵌大段 HTML/CSS(带花括号),
     * 简单 indexOf('}') 会在字符串里提前截断,导致首个条目判定不到。
     */
    private static String firstObjectBody(String arr) {
        int start = -1, depth = 0;
        boolean inStr = false, escNext = false;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (inStr) {
                if (escNext) escNext = false;
                else if (c == '\\') escNext = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) return arr.substring(start + 1, i);
                if (depth < 0) return null;
            }
        }
        return null;
    }

    private static String buildSitesJson(List<String> items) {
        if (items == null || items.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"sites\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(items.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * 剥掉开头的 BOM 与 {@code //} 注释行后取正文:部分订阅配置带注释,Gson 不认,
     * 加载阶段(FindResult)会先剥一层,这里的形态判定必须同样先剥,否则带注释的真配置会被误判成"不是订阅"。
     */
    private static String stripJsonNoise(String text) {
        if (text == null) return "";
        String t = text;
        if (!t.isEmpty() && t.charAt(0) == '\ufeff') t = t.substring(1);
        t = t.trim();
        while (t.startsWith("//")) {
            int nl = t.indexOf('\n');
            if (nl < 0) return "";
            t = t.substring(nl + 1).trim();
        }
        return t;
    }

    /** 取对象文本的"体内"(首尾花括号之间);不是完整对象返回 null */
    private static String objBody(String obj) {
        if (obj == null || obj.length() < 2 || obj.charAt(0) != '{'
                || obj.charAt(obj.length() - 1) != '}') {
            return null;
        }
        return obj.substring(1, obj.length() - 1).trim();
    }

    /** 形如 {@code "sites":"[{…}]"} 的字符串值(数组被当成字符串塞进来时的站点数组文本);不是字符串值返回 null */
    private static String stringValueOf(String body, String key) {
        int at = indexOfKey(body, key);
        if (at < 0) return null;
        int i = body.indexOf(':', at);
        if (i < 0) return null;
        i++;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        if (i >= body.length() || body.charAt(i) != '"') return null;
        int start = i + 1, j = start;
        boolean escNext = false;
        while (j < body.length()) {
            char c = body.charAt(j);
            if (escNext) escNext = false;
            else if (c == '\\') escNext = true;
            else if (c == '"') break;
            j++;
        }
        if (j >= body.length()) return null;
        return unescape(body.substring(start, j));
    }

    /** 反转义 JSON 字符串里的 {@code \"} 与 {@code \\} */
    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** 判断一个"站点条目"对象片段是否是完整配置({@code {"sites":[…]}} 本身也算,原样保留) */
    private static String siteObjectPart(String obj) {
        if (obj == null || obj.isEmpty() || obj.charAt(0) != '{' || obj.charAt(obj.length() - 1) != '}') {
            return null;
        }
        String body = obj.substring(1, obj.length() - 1).trim();
        if (body.isEmpty()) return null;
        if (indexOfKey(body, "sites") >= 0) return obj;   // 已是带 sites 的完整配置:别再套一层
        if (indexOfKey(body, "urls") >= 0) return null;   // 多线路清单
        if (indexOfKey(body, "storeHouse") >= 0) return null; // 多仓清单
        if (indexOfKey(body, "api") < 0) return null;      // 没有采集接口就不是站点条目
        if (indexOfKey(body, "type") < 0) return null;     // 缺 type 的交给调用方原样存盘兜底
        return obj;
    }

    /** 从数组文本里切出各个顶层对象(处理字符串内的括号与转义) */
    private static List<String> siteObjectParts(String arr) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false, escNext = false;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (inStr) {
                if (escNext) escNext = false;
                else if (c == '\\') escNext = true;
                else if (c == '"') {
                    inStr = false;
                    escNext = false;   // 出字符串复位(与 indexOfKey 同因:否则下段字符串整段被跳过)
                }
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String sub = siteObjectPart(arr.substring(start, i + 1));
                    if (sub != null) out.add(sub);
                    start = -1;
                } else if (depth < 0) break;
            }
        }
        return out;
    }

    /** 对象体内是否存在某个顶层 key(只认对象/数组顶层的 key,避免把 ext 里的同名 key 当外层) */
    private static int indexOfKey(String body, String key) {
        int depth = 0;
        boolean inStr = false, escNext = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inStr) {
                if (escNext) {
                    escNext = false;
                    continue;
                }
                if (c == '\\') {
                    escNext = true;
                    continue;
                }
                if (c == '"') {
                    inStr = false;
                    escNext = false;   // 出字符串必须复位:否则下一段字符串会被当转义而整段跳过
                    // 顶层字符串:判断它是不是 "key"(键文本紧挨在闭引号左边,后跟冒号)
                    if (depth == 0 && i >= key.length()
                            && body.regionMatches(i - key.length(), key, 0, key.length())) {
                        int keyStart = i - key.length();
                        if (keyStart == 0 || body.charAt(keyStart - 1) == '"') {
                            int j = i + 1;
                            while (j < body.length() && Character.isWhitespace(body.charAt(j))) j++;
                            if (j < body.length() && body.charAt(j) == ':') return keyStart;
                        }
                    }
                }
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 响应判定
    // ------------------------------------------------------------------

    /** 采集接口响应类型:1=CMS JSON(作 type1 源),0=CMS XML(作 type0 源),-1=不像采集数据 */
    public static int detectKind(String body) {
        if (body == null) return -1;
        int i = 0;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        String head = body.substring(i, Math.min(body.length(), i + 512)).toLowerCase();
        char first = i >= body.length() ? 0 : body.charAt(i);
        if (first == '{') {
            // MacCMS JSON:{"code":1,"msg":"...","list":[{"vod_id":..}],"class":[..]}
            return body.contains("\"list\"") && (body.contains("\"vod_id\"") || body.contains("\"class\"")) ? 1 : -1;
        }
        if (first == '<') {
            // MacCMS XML:<rss><list><video>…;404/网页同样以 '<' 开头,故必须命中 rss+video
            return head.contains("<rss") && body.toLowerCase().contains("<video") ? 0 : -1;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 生成订阅配置
    // ------------------------------------------------------------------

    /**
     * 生成只含一个站点的最小订阅配置(与 app.json 的 sites 段同构,可直接被 ApiConfig.parseJson 消费)。
     * filterable=1:CMS 的 class 即分类,详情/列表均走标准 ac 参数。
     */
    public static String buildSubscriptionJson(String key, String name, int type, String api) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"sites\":[{")
                .append("\"key\":\"").append(esc(key)).append("\",")
                .append("\"name\":\"").append(esc(name)).append("\",")
                .append("\"type\":").append(type).append(",")
                .append("\"api\":\"").append(esc(api)).append("\",")
                .append("\"searchable\":1,\"quickSearch\":1,\"filterable\":1")
                .append("}]}");
        return sb.toString();
    }

    /**
     * 生成带 {@code ext} 的单源订阅配置(type 3 = 爬虫源:api 指向运行的 JS,ext 是它要的站点配置)。
     * 抓页面类源(如 {@code assets://js/lib/maccms.js})用这个重载;采集接口类源用上面那个。
     *
     * @param extJson ext 内容(JSON 文本,作为字符串字段写入;ApiConfig 会原样交给 JS 的 init)
     */
    public static String buildSubscriptionJson(String key, String name, int type, String api, String extJson,
                                               int searchable, int quickSearch, int filterable) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"sites\":[{")
                .append("\"key\":\"").append(esc(key)).append("\",")
                .append("\"name\":\"").append(esc(name)).append("\",")
                .append("\"type\":").append(type).append(",")
                .append("\"api\":\"").append(esc(api)).append("\",");
        if (extJson != null && !extJson.isEmpty()) {
            sb.append("\"ext\":\"").append(esc(extJson)).append("\",");
        }
        sb.append("\"searchable\":").append(searchable)
                .append(",\"quickSearch\":").append(quickSearch)
                .append(",\"filterable\":").append(filterable)
                .append("}]}");
        return sb.toString();
    }

    /**
     * 源 key:主机名 slug + 主机名短哈希。key 同时用作生成的配置文件名({@code cms_<key>.json})
     * 与配置内 {@code "key"},必须区分得开:只留字母数字会让 {@code hongniu-ziyuan.com} 与
     * {@code hongniuziyuan.com}(以及任何纯中文域名)撞成同一个 key,导致后导入的站点覆盖前一个。
     */
    public static String siteKey(String url) {
        String host = hostOf(url == null ? "" : url.trim());
        if (host == null || host.isEmpty()) return "cms";
        String lowerHost = host.toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (char c : lowerHost.toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') sb.append(c);
            else if (c == '.' || c == '-' || c == '_') sb.append('_');
            else sb.append('_');
        }
        String slug = sb.toString();
        if (slug.isEmpty()) slug = "cms";
        // 短哈希兜底:slug 被规范化后仍可能相同(中文域名、连续分隔符等)
        return slug + "_" + Integer.toHexString(lowerHost.hashCode() & 0xFFFFFF);
    }

    /** 页面标题(去标签/空白),用作站点名兜底;取不到返回 null */
    public static String pageTitle(String html) {
        if (html == null) return null;
        Matcher m = TITLE.matcher(html);
        if (!m.find()) return null;
        String title = TAG.matcher(m.group(1)).replaceAll(" ").replace("&nbsp;", " ").trim();
        return title.isEmpty() ? null : title;
    }

    /**
     * 站点名:取页面标题被 SEO 分隔符截断后的首段(资源站标题常写作"站名-最新电影电视剧");
     * 取不到或过长返回 null,由调用方兜底。
     */
    public static String siteName(String html) {
        String title = pageTitle(html);
        if (title == null) return null;
        int cut = title.length();
        for (char sep : NAME_SEPARATORS) {
            int i = title.indexOf(sep);
            if (i > 0) cut = Math.min(cut, i);
        }
        String name = title.substring(0, cut).trim();
        return name.isEmpty() || name.length() > MAX_NAME_LEN ? null : name;
    }

    /** 站点名兜底:接口主机名(去 www 前缀) */
    public static String displayHost(String url) {
        String host = hostOf(url == null ? "" : url.trim());
        if (host == null || host.isEmpty()) return "资源站";
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 清掉 URL 尾部混进的页面文本标点(采集说明里常见"接口:https://xxx,") */
    private static String normalize(String url) {
        if (url == null) return null;
        String u = url.trim().replace("&amp;", "&");
        while (!u.isEmpty() && ",;.).、。，；）]》".indexOf(u.charAt(u.length() - 1)) >= 0) {
            u = u.substring(0, u.length() - 1);
        }
        return u.isEmpty() || !u.regionMatches(true, 0, HTTP, 0, 4) ? null : u;
    }

    /** 相对链接绝对化(相对站点根或当前目录);脚本/锚点/邮件等返回 null */
    private static String resolve(String base, String href) {
        if (href == null) return null;
        String h = href.trim();
        if (h.isEmpty()) return null;
        String lower = h.toLowerCase();
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:") || lower.startsWith("tel:")
                || h.startsWith("#")) {
            return null;
        }
        if (!lower.startsWith(HTTP)) {
            // 协议相对链接(//host/path,CMS 模板引帮助页很常见):沿用当前页面的协议
            if (h.startsWith("//")) {
                int scheme = base == null ? -1 : base.indexOf("://");
                return scheme > 0 ? normalize(base.substring(0, scheme) + ":" + h) : null;
            }
            String root = rootOf(base);
            if (root == null) return null;
            String dir = dirOf(base);
            if (h.startsWith("/")) h = root + h.substring(1);
            else h = (dir != null ? dir : root) + h;
        }
        return normalize(h);
    }

    private static String stripTags(String html) {
        return html == null ? "" : TAG.matcher(html).replaceAll(" ");
    }

    /** 地址所在目录(含末尾斜杠);非法返回 null */
    private static String dirOf(String url) {        String u = normalize(url);
        if (u == null) return null;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int slash = u.lastIndexOf('/');
        int scheme = u.indexOf("//");
        if (slash < 0 || scheme >= 0 && slash <= scheme + 1) return u + "/";
        return u.substring(0, slash + 1);
    }

    /** 站点根(scheme://host[:port]/) */
    private static String rootOf(String url) {
        String u = normalize(url);
        if (u == null) return null;
        int scheme = u.indexOf("//");
        if (scheme < 0) return null;
        int slash = u.indexOf('/', scheme + 2);
        return slash < 0 ? u + "/" : u.substring(0, slash + 1);
    }

    private static String hostOf(String url) {
        int scheme = url.indexOf("//");
        if (scheme < 0) return null;
        int start = scheme + 2;
        int end = start;
        while (end < url.length() && url.charAt(end) != '/' && url.charAt(end) != ':'
                && url.charAt(end) != '?' && url.charAt(end) != '#') {
            end++;
        }
        return end > start ? url.substring(start, end) : null;
    }

    /** JSON 字符串转义(站点名/地址里的引号与反斜杠) */
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

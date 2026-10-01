package com.github.tvbox.osc.spiderapi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 资源站(MacCMS 系)采集接口识别规则单测。
 * 夹具取自真实站点:红牛资源的首页只写"帮助中心"链接,采集地址挂在另一个域名下。
 */
public class CmsApiRulesTest {

    private static final String HOME = "https://hongniuziyuan.com/";

    private static final String HOME_HTML = "<html><head><title>红牛资源\n-高清影视资源采集站</title></head><body>"
            + "<a target=\"_blank\" href=\"/index.php/help\" class=\"flicker\">采集说明</a>"
            + "<a href=\"/index.php/vodtype/1.html\">电影</a>"
            + "<a href=\"javascript:void(0);\">更多</a>"
            + "</body></html>";

    private static final String HELP_HTML = "<html><body><pre>"
            + "接口地址：https://www.hongniuzy2.com/api.php/provide/vod/at/json/，"
            + "备用 https://www.hongniuzy2.com/api.php/provide/vod/from/hnm3u8/at/xml/ &amp; "
            + "https://www.hongniuzy3.com/api.php/provide/vod/at/xml/"
            + "</pre></body></html>";

    private static final String JSON_API = "https://www.hongniuzy2.com/api.php/provide/vod/at/json/";

    private static final String JSON_BODY = "{\"code\":1,\"msg\":\"数据列表\","
            + "\"list\":[{\"vod_id\":101,\"vod_name\":\"测试\"}],"
            + "\"class\":[{\"type_id\":1,\"type_name\":\"电影\"}]}";

    private static final String XML_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<rss version=\"5.1\"><channel><title>红牛资源</title>"
            + "<list page=\"1\" pagecount=\"1\" recordcount=\"1\" />"
            + "<video><id>101</id><name>测试</name><type>电影</type>"
            + "<dt><dd from=\"红牛\">http://a.com/1.mp4</dd></dt></video></list></channel></rss>";

    // ------------------------------------------------------------------
    // 候选地址
    // ------------------------------------------------------------------

    @Test
    public void pageApis_keepSameHostFirstAndCrossHostAsFallback() {
        List<String> apis = CmsApiRules.apiUrlsFromPage(HELP_HTML, HOME);
        assertEquals(JSON_API, apis.get(0));
        // 站点把采集接口挂在另一个域名:仍然保留(只是排在后面)
        assertTrue(apis.contains("https://www.hongniuzy3.com/api.php/provide/vod/at/xml/"));
    }

    @Test
    public void pageApis_stripTrailingPunctuationAndEntities() {
        for (String api : CmsApiRules.apiUrlsFromPage(HELP_HTML, HOME)) {
            assertFalse("尾部混进了页面标点: " + api, api.endsWith("，") || api.endsWith(","));
            assertFalse("未解码实体: " + api, api.contains("&amp;"));
        }
    }

    @Test
    public void pageApis_ignoreNonApiLinks() {
        assertTrue(CmsApiRules.apiUrlsFromPage(HOME_HTML, HOME).isEmpty());
    }

    @Test
    public void candidates_orderIsDeclaredThenDefaults() {
        List<String> declared = CmsApiRules.apiUrlsFromPage(HELP_HTML, HOME);
        List<String> candidates = CmsApiRules.candidates(HOME, declared);
        assertEquals(declared.get(0), candidates.get(0));
        // 页面没写接口时,标准默认路径要能兜住(多数 MacCMS 站就是这个路径)
        assertTrue(candidates.contains(HOME + "api.php/provide/vod/at/json/"));
        assertTrue(candidates.size() <= CmsApiRules.MAX_CANDIDATES);
    }

    @Test
    public void candidates_userPastedApiGoesFirst() {
        assertEquals(JSON_API, CmsApiRules.candidates(JSON_API, (String) null).get(0));
    }

    @Test
    public void docPageUrls_resolvesRelativeHelpLink() {
        List<String> docs = CmsApiRules.docPageUrls(HOME, HOME_HTML);
        assertEquals(1, docs.size());
        assertEquals("https://hongniuziyuan.com/index.php/help", docs.get(0));
    }

    @Test
    public void docPageUrls_cappedAndEmptyWithoutHtml() {
        assertTrue(CmsApiRules.docPageUrls(HOME, null).isEmpty());
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 5; i++) many.append("<a href=\"/help").append(i).append("\">帮助中心</a>");
        assertEquals(CmsApiRules.MAX_DOC_PAGES, CmsApiRules.docPageUrls(HOME, many.toString()).size());
    }

    @Test
    public void looksLikeApi_onlyForCollectEndpoints() {
        assertTrue(CmsApiRules.looksLikeApi(JSON_API));
        assertTrue(CmsApiRules.looksLikeApi("https://a.com/api.php/vod"));
        assertFalse(CmsApiRules.looksLikeApi(HOME));
        assertFalse(CmsApiRules.looksLikeApi("https://a.com/rss.xml"));
        assertFalse(CmsApiRules.looksLikeApi(null));
    }

    // ------------------------------------------------------------------
    // 响应判定
    // ------------------------------------------------------------------

    @Test
    public void detectKind_jsonIsType1() {
        assertEquals(1, CmsApiRules.detectKind(JSON_BODY));
        assertEquals(1, CmsApiRules.detectKind("  \n" + JSON_BODY));
    }

    @Test
    public void detectKind_xmlIsType0() {
        assertEquals(0, CmsApiRules.detectKind(XML_BODY));
    }

    @Test
    public void detectKind_rejectsWebPagesAndErrors() {
        assertEquals(-1, CmsApiRules.detectKind(HOME_HTML));
        assertEquals(-1, CmsApiRules.detectKind("<html><head><title>404</title></head><body>Not Found</body></html>"));
        assertEquals(-1, CmsApiRules.detectKind("{\"code\":0,\"msg\":\"hacker\"}"));
        assertEquals(-1, CmsApiRules.detectKind(null));
    }

    // ------------------------------------------------------------------
    // 命名与生成配置
    // ------------------------------------------------------------------

    @Test
    public void siteName_cutsSeoSuffix() {
        assertEquals("红牛资源", CmsApiRules.siteName(HOME_HTML));
    }

    @Test
    public void siteName_rejectsTooLongAndMissing() {
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 30; i++) longTitle.append("影");
        assertNull(CmsApiRules.siteName("<title>" + longTitle + "</title>"));
        assertNull(CmsApiRules.siteName("<html>no title</html>"));
    }

    @Test
    public void displayHost_dropsWwwPrefix() {
        assertEquals("hongniuzy2.com", CmsApiRules.displayHost(JSON_API));
    }

    @Test
    public void siteKey_isHostSlug() {
        assertTrue(CmsApiRules.siteKey(JSON_API).startsWith("www_hongniuzy2_com_"));
        assertEquals("cms", CmsApiRules.siteKey("不是地址"));
    }

    /** 仅保留字母数字会把 hongniu-ziyuan.com 与 hongniuziyuan.com(以及中文域名)撞成同一 key,
     *  而 key 同时是生成的配置文件名 → 后导入的站点会覆盖前一个,故必须加哈希区分 */
    @Test
    public void siteKey_doesNotCollideBetweenSimilarHosts() {
        String dash = CmsApiRules.siteKey("https://hongniu-ziyuan.com/api.php/provide/vod/");
        String plain = CmsApiRules.siteKey("https://hongniuziyuan.com/api.php/provide/vod/");
        assertFalse("不同站点生成了同一个 key: " + dash, dash.equals(plain));
        assertFalse(CmsApiRules.siteKey("https://www.中文.com/api.php/provide/vod/")
                .equals(CmsApiRules.siteKey("https://www.测试.com/api.php/provide/vod/")));
        // 同一主机不同路径要稳定同 key(否则重复导入会生成多份配置)
        assertEquals(CmsApiRules.siteKey(JSON_API),
                CmsApiRules.siteKey(JSON_API + "?ac=videolist"));
    }

    /** 协议相对链接(//host/help)要按当前页面协议绝对化,不能拼成 site.com//host/help */
    @Test
    public void docPageUrls_resolvesProtocolRelativeLink() {
        String html = "<a href=\"//help.example.com/help\">采集说明</a>";
        List<String> docs = CmsApiRules.docPageUrls(HOME, html);
        assertEquals(1, docs.size());
        assertEquals("https://help.example.com/help", docs.get(0));
    }

    /** 用户粘的是栏目页/说明页时,站点根的默认采集路径必须留在候选里(不能被截断挤掉) */
    @Test
    public void candidates_keepSiteRootDefaultsForSubPageInput() {
        String page = "https://a.com/index.php/vodtype/1.html";
        List<String> declared = CmsApiRules.apiUrlsFromPage(HELP_HTML, HOME);
        List<String> candidates = CmsApiRules.candidates(page, declared);
        assertTrue("站点根默认路径被挤掉了: " + candidates,
                candidates.contains("https://a.com/api.php/provide/vod/at/json/"));
    }

    @Test
    public void buildSubscriptionJson_isTypeAndApiFaithful() {
        String json = CmsApiRules.buildSubscriptionJson(CmsApiRules.siteKey(JSON_API), "红牛资源", 1, JSON_API);
        assertTrue(json.startsWith("{\"sites\":["));
        assertTrue(json.contains("\"type\":1"));
        assertTrue(json.contains("\"api\":\"" + JSON_API + "\""));
        assertTrue(json.contains("\"searchable\":1"));
    }

    @Test
    public void buildSubscriptionJson_escapesName() {
        String json = CmsApiRules.buildSubscriptionJson("k", "阿\"布", 0, "https://a.com/api.php/provide/vod/");
        assertTrue(json.contains("\"name\":\"阿\\\"布\""));
    }

    // ------------------------------------------------------------------
    // 通用接入:站点地址识别 与 裸站点条目补壳
    // ------------------------------------------------------------------

    /** 用户丢进来的整站地址(带不带尾斜杠、二级栏目页、说明页)都算"站点",该去嗅探 */
    @Test
    public void looksLikeSiteUrl_acceptsSiteAddresses() {
        assertTrue(CmsApiRules.looksLikeSiteUrl("https://hongniuzy4.com/"));
        assertTrue(CmsApiRules.looksLikeSiteUrl("https://hongniuzy4.com"));
        assertTrue(CmsApiRules.looksLikeSiteUrl("  https://hongniuzy4.com/  "));
        assertTrue(CmsApiRules.looksLikeSiteUrl("https://a.com/index.php/vodtype/1.html"));
        assertTrue(CmsApiRules.looksLikeSiteUrl("https://a.com/index.php/help/"));
        assertTrue(CmsApiRules.looksLikeSiteUrl("http://a.com:8080/"));
        // 地址尾部混进中文标点(从采集说明里复制的常见形态)也要认
        assertTrue(CmsApiRules.looksLikeSiteUrl("https://a.com/，"));
    }

    /** 采集接口、订阅配置文件、非 http 输入都不该走"站点嗅探"分支 */
    @Test
    public void looksLikeSiteUrl_rejectsApisAndConfigFiles() {
        assertFalse(CmsApiRules.looksLikeSiteUrl(JSON_API));
        assertFalse(CmsApiRules.looksLikeSiteUrl("https://a.com/api.php/provide/vod/?ac=list"));
        assertFalse(CmsApiRules.looksLikeSiteUrl("https://a.com/box.json"));
        assertFalse(CmsApiRules.looksLikeSiteUrl("https://a.com/tv/x.txt"));
        assertFalse(CmsApiRules.looksLikeSiteUrl("https://a.com/rss.xml"));
        assertFalse(CmsApiRules.looksLikeSiteUrl("https://a.com/config.json?v=2"));
        assertFalse(CmsApiRules.looksLikeSiteUrl("clan://localhost/a/b.json"));
        assertFalse(CmsApiRules.looksLikeSiteUrl("hongniuzy4.com"));
        assertFalse(CmsApiRules.looksLikeSiteUrl(null));
    }

    /** 裸站点条目(用户从别处复制的单站配置)要能补成可订阅的 {"sites":[…]} */
    @Test
    public void wrapSiteJson_wrapsBareSiteObject() {
        String wrapped = CmsApiRules.wrapSiteJson(
                "{\"key\":\"hongniuzy4_com_3188b0\",\"name\":\"红牛资源\",\"type\":1,"
                        + "\"api\":\"https://hongniuzy4.com/api.php/provide/vod/\",\"searchable\":1}");
        assertNotNull(wrapped);
        assertTrue(wrapped.startsWith("{\"sites\":["));
        assertTrue(wrapped.endsWith("]}"));
        assertTrue(wrapped.contains("\"api\":\"https://hongniuzy4.com/api.php/provide/vod/\""));
    }

    /** 站点数组:逐个补壳,顺序与数量保持不变(含 ext 里带花括号的站点) */
    @Test
    public void wrapSiteJson_wrapsSiteArray() {
        String wrapped = CmsApiRules.wrapSiteJson("["
                + "{\"key\":\"a\",\"name\":\"甲\",\"type\":1,\"api\":\"https://a.com/api.php/provide/vod/\"},"
                + "{\"key\":\"b\",\"name\":\"乙\",\"type\":0,\"api\":\"https://b.com/api.php/provide/vod/\","
                + "\"ext\":{\"site\":\"https://b.com\",\"nested\":{\"deep\":1}}}"
                + "]");
        assertNotNull(wrapped);
        assertTrue(wrapped.contains("\"name\":\"甲\""));
        assertTrue(wrapped.contains("\"name\":\"乙\""));
        assertEquals(2, countOccurrences(wrapped, "\"api\":"));
    }

    /** 已经是 {"sites":[…]} 的完整配置原样保留(外层不再套一层) */
    @Test
    public void wrapSiteJson_keepsFullConfigUntouched() {
        String full = "{\"sites\":[{\"key\":\"a\",\"name\":\"甲\",\"type\":1,\"api\":\"https://a.com/vod\"}]}";
        String wrapped = CmsApiRules.wrapSiteJson(full);
        assertEquals(1, countOccurrences(wrapped, "\"sites\""));
        assertTrue(wrapped.startsWith("{\"sites\":[{\"key\":\"a\""));
    }

    /** 列表里混入非站点条目(缺 api/type、urls、storeHouse)时只挑站点条目,且不误伤整体 */
    @Test
    public void wrapSiteJson_dropsNonSiteEntries() {
        String wrapped = CmsApiRules.wrapSiteJson("["
                + "{\"name\":\"只是名字\"},"
                + "{\"key\":\"a\",\"name\":\"甲\",\"api\":\"https://a.com/api.php/provide/vod/\"},"
                + "{\"key\":\"b\",\"name\":\"乙\",\"type\":2,\"api\":\"https://b.com/api.php/provide/vod/\"}"
                + "]");
        assertNotNull(wrapped);
        assertFalse(wrapped.contains("只是名字"));
        assertFalse(wrapped.contains("\"name\":\"甲\"")); // 缺 type 的交调用方原样存盘兜底
        assertTrue(wrapped.contains("\"name\":\"乙\""));
    }

    /** 非站点内容返回 null(调用方据此原样存盘,不改变既有兜底行为) */
    @Test
    public void wrapSiteJson_returnsNullForNonSiteContent() {
        assertNull(CmsApiRules.wrapSiteJson("{\"urls\":[{\"name\":\"甲\",\"url\":\"https://a.com\"}]}"));
        assertNull(CmsApiRules.wrapSiteJson("{\"storeHouse\":[{\"sourceName\":\"甲\",\"sourceUrl\":\"https://a\"}]}"));
        assertNull(CmsApiRules.wrapSiteJson("{\"name\":\"甲\",\"url\":\"https://a.com\"}"));
        assertNull(CmsApiRules.wrapSiteJson("随便一段文本"));
        assertNull(CmsApiRules.wrapSiteJson("{}"));
        assertNull(CmsApiRules.wrapSiteJson("[{\"name\":\"甲\",\"url\":\"https://a.com\"}]"));
        assertNull(CmsApiRules.wrapSiteJson(""));
        assertNull(CmsApiRules.wrapSiteJson(null));
    }

    /** 多段字符串/转义场景:检测曾经因为"出字符串没复位转义标记"而把后续 key 全部跳过 */
    @Test
    public void wrapSiteJson_handlesMultipleStringSegments() {
        // 缺 type 不算站点条目(交调用方原样存盘兜底)
        assertNull(CmsApiRules.wrapSiteJson("{\"api\":\"x\"}"));
        assertNotNull(CmsApiRules.wrapSiteJson("{\"api\":\"x\",\"type\":1}"));
        assertNotNull(CmsApiRules.wrapSiteJson("{\"name\":\"带\\\"引号\\\"的站\",\"type\":1,\"api\":\"https://a.com/vod\"}"));
        assertEquals(1, countOccurrences(
                CmsApiRules.wrapSiteJson("{\"a\":\"1\",\"b\":\"2\",\"type\":1,\"api\":\"https://a/vod\"}"), "\"sites\""));
    }

    /** {"sites":"[{…}]"}:sites 被当成字符串塞进来的整份配置,也要还原成站点数组而不是再套一层 */
    @Test
    public void wrapSiteJson_recoversStringifiedSitesArray() {
        String stringified = "{\"sites\":\"[{\\\"key\\\":\\\"a\\\",\\\"name\\\":\\\"甲\\\",\\\"type\\\":1,"
                + "\\\"api\\\":\\\"https://a.com/api.php/provide/vod/\\\"}]\"}";
        String wrapped = CmsApiRules.wrapSiteJson(stringified);
        assertEquals(1, countOccurrences(wrapped, "\"sites\""));
        assertTrue(wrapped.contains("\"name\":\"甲\""));
        assertTrue(wrapped.contains("\"api\":\"https://a.com/api.php/provide/vod/\""));
    }

    // ------------------------------------------------------------------
    // 订阅内容形态判定(导入前拦截非订阅内容)
    // ------------------------------------------------------------------

    /** 真实夹具:用户从「阅读」App 导出的书源(姐姐视频),字段与线上一致(sourceName/sourceUrl + rule*) */
    private static final String LEGADO_SOURCE = "{"
            + "\"articleStyle\":2,\"customOrder\":-10100157,\"enableJs\":true,\"enabled\":true,"
            + "\"lastUpdateTime\":1775591744706,\"loadWithBaseUrl\":true,"
            + "\"ruleArticles\":\"ul@li\","
            + "\"ruleContent\":\"<!DOCTYPE html>\\n<html><head><title>内容提取</title>"
            + "<style>body { color: red; }</style>"
            + "<script src=\\\"https://code.jquery.com/jquery-3.6.0.min.js\\\"></script>"
            + "</head></html>\","
            + "\"ruleTitle\":\"h1@text\","
            + "\"sourceComment\":\"jiejiesp.xyz\",\"sourceGroup\":\"2222\","
            + "\"sourceName\":\"姐姐视频\",\"sourceUrl\":\"https://wap.jiejiesp19.xyz\""
            + "}";

    /** 真正能当订阅加载的只有"带 sites 数组"的配置 */
    @Test
    public void subscriptionShape_configIsLoadable() {
        assertEquals(CmsApiRules.SHAPE_CONFIG, CmsApiRules.subscriptionShape(
                "{\"sites\":[{\"key\":\"a\",\"name\":\"甲\",\"type\":1,\"api\":\"https://a.com/api.php/provide/vod/\"}]}"));
        assertEquals(CmsApiRules.SHAPE_CONFIG, CmsApiRules.subscriptionShape("  {\"sites\": []}  "));
        assertEquals(CmsApiRules.SHAPE_CONFIG, CmsApiRules.subscriptionShape(
                "{\"spider\":\"https://a.com/jar\",\"wallpaper\":\"\",\"sites\":[],\"lives\":[],\"parses\":[]}"));
        // 带 BOM / 开头 // 注释的配置(部分源就这么写,加载阶段会先剥掉):不能被误判成"不是订阅"
        assertEquals(CmsApiRules.SHAPE_CONFIG, CmsApiRules.subscriptionShape(
                "\ufeff// 我的订阅\n{\"sites\":[{\"key\":\"a\",\"name\":\"甲\",\"type\":1,\"api\":\"https://a.com/vod\"}]}"));
    }

    /** 站点配置里 ext 内嵌了 sourceUrl/ruleContent 之类键时,不能被误判成「阅读」书源 */
    @Test
    public void subscriptionShape_configWithBookSourceLikeExtIsStillConfig() {
        String config = "{\"sites\":[{\"key\":\"a\",\"name\":\"甲\",\"type\":3,\"api\":\"csp_甲\","
                + "\"ext\":{\"sourceUrl\":\"https://a.com\",\"ruleContent\":\"body@html\"}}]}";
        assertEquals(CmsApiRules.SHAPE_CONFIG, CmsApiRules.subscriptionShape(config));
        assertFalse(CmsApiRules.looksLikeBookSource(config));
    }

    /** 裸站点条目/数组:补 sites 外壳后可加载(与 wrapSiteJson 的判定保持一致) */
    @Test
    public void subscriptionShape_siteEntryNeedsWrap() {
        String entry = "{\"key\":\"a\",\"name\":\"甲\",\"type\":1,\"api\":\"https://a.com/api.php/provide/vod/\"}";
        assertEquals(CmsApiRules.SHAPE_SITE, CmsApiRules.subscriptionShape(entry));
        assertEquals(CmsApiRules.SHAPE_CONFIG, CmsApiRules.subscriptionShape(CmsApiRules.wrapSiteJson(entry)));

        String arr = "[" + entry + "]";
        assertEquals(CmsApiRules.SHAPE_SITE, CmsApiRules.subscriptionShape(arr));
        assertEquals(CmsApiRules.SHAPE_CONFIG, CmsApiRules.subscriptionShape(CmsApiRules.wrapSiteJson(arr)));

        // sites 被塞成字符串的数组:同样要补壳(不能算可直接加载)
        String stringified = "{\"sites\":\"[{\\\"key\\\":\\\"a\\\",\\\"name\\\":\\\"甲\\\",\\\"type\\\":1,"
                + "\\\"api\\\":\\\"https://a.com/api.php/provide/vod/\\\"}]\"}";
        assertEquals(CmsApiRules.SHAPE_SITE, CmsApiRules.subscriptionShape(stringified));
        assertEquals(CmsApiRules.SHAPE_CONFIG, CmsApiRules.subscriptionShape(CmsApiRules.wrapSiteJson(stringified)));
        // SHAPE_SITE 的语义=wrapSiteJson 能补出壳:补不出壳的(sites 是普通字符串/空数组文本)不算 SITE
        assertNull(CmsApiRules.wrapSiteJson("{\"sites\":\"[]\"}"));
        assertEquals(CmsApiRules.SHAPE_UNUSABLE, CmsApiRules.subscriptionShape("{\"sites\":\"[]\"}"));
        assertEquals(CmsApiRules.SHAPE_UNUSABLE, CmsApiRules.subscriptionShape("{\"sites\":\"[{}]\"}"));
        assertEquals(CmsApiRules.SHAPE_UNUSABLE, CmsApiRules.subscriptionShape("{\"sites\":\"随便\"}"));
    }

    /**
     * 「阅读」(Legado)书源:与 TVBox 单条订阅同形(同样是 sourceName/sourceUrl),但规则体系不同。
     * 线上实例:用户把它粘进 JSON 导入,存成 clan:// 订阅后每次启动都"解析配置失败"
     * (缺 sites),重选订阅也恢复不了——必须在导入前就认出来。
     */
    @Test
    public void subscriptionShape_legadoBookSourceIsRejected() {
        assertEquals(CmsApiRules.SHAPE_BOOK_SOURCE, CmsApiRules.subscriptionShape(LEGADO_SOURCE));
        assertTrue(CmsApiRules.looksLikeBookSource(LEGADO_SOURCE));
        // 未识别的书源内容不能补成站点条目(它没有 api/type,wrapSiteJson 应放行给调用方兜底)
        assertNull(CmsApiRules.wrapSiteJson(LEGADO_SOURCE));
        // 成组导出的书源数组([{…},{…}])同样要认出来
        assertEquals(CmsApiRules.SHAPE_BOOK_SOURCE, CmsApiRules.subscriptionShape("[" + LEGADO_SOURCE + "]"));
        assertTrue(CmsApiRules.looksLikeBookSource("[" + LEGADO_SOURCE + "," + LEGADO_SOURCE + "]"));
    }

    /** 书源里写的站点地址要能取出来(可据此按站点嗅探采集接口),没写地址/非书源返回 null */
    @Test
    public void bookSourceSiteUrl_readsDeclaredSite() {
        assertEquals("https://wap.jiejiesp19.xyz", CmsApiRules.bookSourceSiteUrl(LEGADO_SOURCE));
        assertEquals("https://wap.jiejiesp19.xyz", CmsApiRules.bookSourceSiteUrl("[" + LEGADO_SOURCE + "]"));
        // 新版书源用 bookSourceUrl/bookSourceName 命名
        assertEquals("https://a.com", CmsApiRules.bookSourceSiteUrl(
                "{\"bookSourceUrl\":\"https://a.com\",\"ruleSearch\":\"x@y\"}"));
        assertTrue(CmsApiRules.looksLikeBookSource(
                "{\"bookSourceUrl\":\"https://a.com\",\"ruleSearch\":\"x@y\"}"));
        // 缺地址 / 不是书源
        assertNull(CmsApiRules.bookSourceSiteUrl("{\"sourceUrl\":\"https://a.com\"}"));
        assertNull(CmsApiRules.bookSourceSiteUrl("{\"ruleSearch\":\"x@y\"}"));
        assertNull(CmsApiRules.bookSourceSiteUrl("随便一段文本"));
        assertNull(CmsApiRules.bookSourceSiteUrl(null));
    }

    /** 只有直播源的 JSON、多线路清单、网页、频道列表文本都不是订阅配置 */
    @Test
    public void subscriptionShape_rejectsLivesAndJunk() {
        assertEquals(CmsApiRules.SHAPE_LIVES,
                CmsApiRules.subscriptionShape("{\"lives\":[{\"name\":\"央视\",\"url\":\"http://a.tv/1.m3u8\"}]}"));
        assertEquals(CmsApiRules.SHAPE_UNUSABLE,
                CmsApiRules.subscriptionShape("{\"urls\":[{\"name\":\"甲\",\"url\":\"https://a.com/box.json\"}]}"));
        assertEquals(CmsApiRules.SHAPE_UNUSABLE,
                CmsApiRules.subscriptionShape("{\"storeHouse\":[{\"sourceName\":\"甲\",\"sourceUrl\":\"https://a\"}]}"));
        assertEquals(CmsApiRules.SHAPE_UNUSABLE, CmsApiRules.subscriptionShape("{\"name\":\"甲\"}"));
        assertEquals(CmsApiRules.SHAPE_UNUSABLE, CmsApiRules.subscriptionShape("{\"sites\":{\"a\":1}}"));
        assertEquals(CmsApiRules.SHAPE_UNUSABLE, CmsApiRules.subscriptionShape(HOME_HTML));
        assertEquals(CmsApiRules.SHAPE_UNUSABLE, CmsApiRules.subscriptionShape("央视,http://a.tv/1.m3u8"));
        assertEquals(CmsApiRules.SHAPE_UNUSABLE, CmsApiRules.subscriptionShape("{}"));
        assertEquals(CmsApiRules.SHAPE_UNUSABLE, CmsApiRules.subscriptionShape(""));
        assertEquals(CmsApiRules.SHAPE_UNUSABLE, CmsApiRules.subscriptionShape(null));
    }

    /** 加密套路(图片+base64 / AES 包头)内容无法就地判定,不能一律当垃圾拒掉(交加载阶段解码) */
    @Test
    public void subscriptionShape_encryptedKeptForLoader() {
        assertEquals(CmsApiRules.SHAPE_ENCRYPTED,
                CmsApiRules.subscriptionShape("iVBORw0KGgo**eyJzaXRlcyI6W119"));
        assertEquals(CmsApiRules.SHAPE_ENCRYPTED,
                CmsApiRules.subscriptionShape("2423abcdefghijklmnop"));
    }

    // ------------------------------------------------------------------
    // 「图片裹配置」解包(订阅地址导入:响应是二进制,不能当成资源站去嗅探接口)
    // ------------------------------------------------------------------

    /** 真实形态:饭太硬 http://www.饭太硬.net/tv = 一张图的二进制 + 8 位标记 + ** + base64(整份配置) */
    private static final String PACKED_CONFIG_TAIL =
            "eyJzcGlkZXIiOiJodHRwczovL2EuY29tL3guanBnO21kNTthYmMiLCJzaXRlcyI6W3sia2V5IjoiYSIsIm5hbWUiOiJqaWEiLCJ0eXBlIjozLCJhcGkiOiJjc3BfQSJ9XX0=";

    @Test
    public void unwrapPackedConfig_decodesConfigBehindImageBytes() {
        // 图片字节按 UTF-8 解出来是乱码(\ufffd 即 OkHttp 对非法字节的替换符),标记与 base64 正文原样保留
        String packed = "\ufffd\ufffd\u0010JFIF\ufffd\ufffd\ufffd\ufffdzfBUZLYa**" + PACKED_CONFIG_TAIL;
        String decoded = CmsApiRules.unwrapPackedConfig(packed);
        assertNotNull(decoded);
        assertTrue(decoded.contains("\"sites\""));
        assertTrue(decoded.contains("csp_A"));
        assertEquals(CmsApiRules.SHAPE_CONFIG, CmsApiRules.subscriptionShape(decoded));
    }

    /** 多仓/多线路清单被裹进图片时也要解出来(调用方按清单展开成各自的地址) */
    @Test
    public void unwrapPackedConfig_keepsListJson() {
        String urls = CmsApiRules.unwrapPackedConfig("iVBORw0K**"
                + "eyJ1cmxzIjpbeyJuYW1lIjoiamlhIiwidXJsIjoiaHR0cHM6Ly9hLmNvbS9ib3guanNvbiJ9XX0=");
        assertNotNull(urls);
        assertTrue(CmsApiRules.looksLikeListJson(urls));
        // 自包含 AES 包头(2423…):只能确认套路,交加载阶段解
        String aes = CmsApiRules.unwrapPackedConfig("iVBORw0K**MjQyM2FiY2RlZmdoaWprbG1ub3A=");
        assertNotNull(aes);
        assertTrue(aes.startsWith("2423"));
    }

    /**
     * 不能"看着像"就收下:网页正文里偶然出现的 8 位 + ** 很常见(帮助页里的示例文本),
     * 一旦当成加密配置存成订阅,加载阶段必然"解析配置失败"并把当前可用订阅挤掉。
     */
    @Test
    public void unwrapPackedConfig_rejectsWhatIsNotConfig() {
        assertNull(CmsApiRules.unwrapPackedConfig(null));
        assertNull(CmsApiRules.unwrapPackedConfig(""));
        assertNull(CmsApiRules.unwrapPackedConfig(HOME_HTML));
        // 标记后面不是 base64
        assertNull(CmsApiRules.unwrapPackedConfig("iVBORw0K**这不是 base64"));
        // 解出来是网页
        assertNull(CmsApiRules.unwrapPackedConfig("iVBORw0K**"
                + "PGh0bWw+PGJvZHk+YmFuZ3podSB6aG9uZ3hpbjwvYm9keT48L2h0bWw+"));
        // 解出来只有直播源(不是订阅配置,直播源另有填写入口)
        assertNull(CmsApiRules.unwrapPackedConfig("iVBORw0K**eyJsaXZlcyI6W3sibmFtZSI6ImEifV19"));
        // 正文被截断(base64 半截 / JSON 没闭合)同样不收
        assertNull(CmsApiRules.unwrapPackedConfig("iVBORw0K**" + PACKED_CONFIG_TAIL.substring(0, 40)));
    }

    /** 多线路/多仓清单判定:顶层有 urls/storeHouse 才算,普通配置与网页都不算 */
    @Test
    public void looksLikeListJson_onlyListShapes() {
        assertTrue(CmsApiRules.looksLikeListJson("{\"urls\":[{\"name\":\"甲\",\"url\":\"https://a\"}]}"));
        assertTrue(CmsApiRules.looksLikeListJson("{\"storeHouse\":[{\"sourceName\":\"甲\",\"sourceUrl\":\"https://a\"}]}"));
        assertFalse(CmsApiRules.looksLikeListJson("{\"sites\":[{\"key\":\"a\",\"type\":3,\"api\":\"csp_A\"}]}"));
        assertFalse(CmsApiRules.looksLikeListJson(HOME_HTML));
        assertFalse(CmsApiRules.looksLikeListJson(null));
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) n++;
        return n;
    }
}

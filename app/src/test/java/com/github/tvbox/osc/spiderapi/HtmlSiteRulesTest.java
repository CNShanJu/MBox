package com.github.tvbox.osc.spiderapi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 影视站 HTML 结构识别(抓页面接入)单测。夹具取自真实站点:姐姐视频
 * ({@code https://wap.jiejiesp19.xyz/jiejie/},苹果CMS 站,采集接口已关闭,只能抓页面)。
 */
public class HtmlSiteRulesTest {

    /** 首页导航片段(分类链接) */
    private static final String HOME_HTML = "<html><head><title>姐姐视频-在线看！</title></head><body>"
            + "<ul class=\"nav\">"
            + "<li><a href=\"/jiejie/index.php/vod/type/id/363.html\">黄瓜资源</a></li>"
            + "<li><a href=\"/jiejie/index.php/vod/type/id/293.html\">姐姐资源</a></li>"
            + "<li><a href=\"/jiejie/index.php/vod/type/id/86.html\"><span>奥斯卡资源</span></a></li>"
            + "<li><a href=\"/jiejie/index.php/vod/type/id/86.html\">奥斯卡资源</a></li>"
            + "</ul></body></html>";

    /** 分类页片段:海报挂 data-original 在 <a> 上、标题在 h4 里(苹果CMS 默认模板写法) */
    private static final String LIST_HTML = "<html><body><ul class=\"stui-vodlist clearfix\"><li>"
            + "<div class=\"stui-vodlist__box\">"
            + "<a class=\"stui-vodlist__thumb lazyload\" href=\"/jiejie/index.php/vod/play/id/1507984/sid/1/nid/1.html\""
            + " title=\"索菲·韦伯 与我同在\" data-original=\"https://hg.jqydt1.com/upload/vod/20260925-1/abc.jpg\"></a>"
            + "<div class=\"stui-vodlist__detail\"><h4 class=\"title text-overflow\">"
            + "<a href=\"/jiejie/index.php/vod/detail/id/1507984.html\" title=\"索菲·韦伯 与我同在\">索菲·韦伯 与我同在</a>"
            + "</h4></div></div></li>"
            + "<li><div class=\"stui-vodlist__box\">"
            + "<a class=\"stui-vodlist__thumb lazyload\" href=\"/jiejie/index.php/vod/play/id/1507662/sid/1/nid/1.html\""
            + " title=\"另一部\" data-original=\"https://hg.jqydt1.com/upload/vod/20260925-1/def.jpg\"></a>"
            + "<div class=\"stui-vodlist__detail\"><h4 class=\"title text-overflow\">"
            + "<a href=\"/jiejie/index.php/vod/detail/id/1507662.html\" title=\"另一部\">另一部</a>"
            + "</h4></div></div></li>"
            + "</ul>"
            + "<div class=\"stui-page text-center\"><ul>"
            + "<li class=\"active num\"><a>1/147</a></li>"
            + "<li><a href=\"/jiejie/index.php/vod/type/id/363/page/2.html\">下一页</a></li>"
            + "<li><a href=\"/jiejie/index.php/vod/type/id/363/page/147.html\">尾页</a></li>"
            + "</ul></div></body></html>";

    /** 详情页片段:本片播放链接 + "猜你喜欢"里别的片的播放链接(不能被当成本片剧集) */
    private static final String DETAIL_HTML = "<html><head><title>索菲·韦伯 与我同在视频-姐姐视频-在线看！</title></head><body>"
            + "<div class=\"stui-content\"><div class=\"stui-content__thumb\">"
            + "<a class=\"pic\" href=\"/jiejie/index.php/vod/play/id/1507984/sid/1/nid/1.html\" title=\"索菲·韦伯 与我同在\">"
            + "<img class=\"lazyload\" data-original=\"https://hg.jqydt1.com/upload/vod/20260925-1/abc.jpg\""
            + " src=\"/jiejie/template/jiejie1/statics/img/pi.png\" /></a></div>"
            + "<div class=\"stui-content__detail\"><h1 class=\"title\">索菲·韦伯 与我同在</h1>"
            + "<p class=\"data\">类型：欧美精品 / 年份：2026</p>"
            + "<p class=\"desc detail\"><span class=\"detail-sketch\">片子的简介文本</span></p>"
            + "<div class=\"play-btn\"><a href=\"/jiejie/index.php/vod/play/id/1507984/sid/1/nid/1.html\">立即播放</a></div>"
            + "<div class=\"play-btn\"><a href=\"/jiejie/index.php/vod/play/id/1507984/sid/1/nid/2.html\">第2集</a></div>"
            + "</div></div>"
            + "<h3>猜你喜欢</h3><ul class=\"stui-vodlist\"><li>"
            + "<a href=\"/jiejie/index.php/vod/play/id/1507662/sid/1/nid/1.html\" title=\"别的片\"></a>"
            + "</li></ul></body></html>";

    /** 播放页片段:苹果CMS 播放器把真实地址写在 player_aaaa 里(斜杠被转义) */
    private static final String PLAY_HTML = "<html><body><div class=\"stui-player__video\">"
            + "<script type=\"text/javascript\">var player_aaaa={\"flag\":\"play\",\"encrypt\":0,"
            + "\"vod_data\":{\"vod_name\":\"索菲\"},"
            + "\"url\":\"https:\\/\\/c10.hs100.top\\/20260924\\/7bf1d90433ad5c13\\/index.m3u8\",\"from\":\"aosika\"}</script>"
            + "</div></body></html>";

    /** 书源文本:站点挂在 /jiejie 子目录(子目录线索来源) */
    private static final String BOOK_SOURCE_TEXT = "{\"sourceName\":\"姐姐视频\",\"sourceUrl\":\"https://wap.jiejiesp19.xyz\","
            + "\"sortUrl\":\"黄瓜资源::/jiejie/index.php/vod/type/id/87.html\","
            + "\"ruleArticles\":\"ul@li\",\"ruleTitle\":\"h4@text\"}";

    // ------------------------------------------------------------------
    // 站点识别
    // ------------------------------------------------------------------

    @Test
    public void looksLikeMacCmsHtml_needsMaccmsMarkerOrDetailAndPlayLinks() {
        assertTrue(HtmlSiteRules.looksLikeMacCmsHtml(HOME_HTML + "<script>var maccms={\"path\":\"/jiejie\"};</script>"));
        assertTrue(HtmlSiteRules.looksLikeMacCmsHtml(LIST_HTML + DETAIL_HTML));
        assertFalse(HtmlSiteRules.looksLikeMacCmsHtml("<html><body>热爱国</body></html>"));
        assertFalse(HtmlSiteRules.looksLikeMacCmsHtml(""));
        assertFalse(HtmlSiteRules.looksLikeMacCmsHtml(null));
    }

    /** 站点挂在子目录时,书源/页面里的路径就是子目录线索;根目录站点给空串 */
    @Test
    public void subdirHint_readsDirectoryFromRulesAndPages() {
        assertEquals("/jiejie", HtmlSiteRules.subdirHint(BOOK_SOURCE_TEXT));
        assertEquals("/jiejie", HtmlSiteRules.subdirHint(LIST_HTML));
        assertEquals("/m", HtmlSiteRules.subdirHint("https://a.com/m/vodshow/1--------2---.html"));
        // 根目录站点
        assertEquals("", HtmlSiteRules.subdirHint("{\"ruleSearchUrl\":\"/index.php/vod/search.html?wd=x\"}"));
        assertEquals("", HtmlSiteRules.subdirHint(HOME_HTML.replace("/jiejie", "")));
        assertEquals("", HtmlSiteRules.subdirHint(""));
        assertEquals("", HtmlSiteRules.subdirHint(null));
    }

    @Test
    public void classes_keepsOrderAndDedupes() {
        LinkedHashMap<String, String> classes = HtmlSiteRules.classes(HOME_HTML);
        assertEquals(3, classes.size());
        assertEquals("黄瓜资源", classes.get("363"));
        assertEquals("姐姐资源", classes.get("293"));
        assertEquals("奥斯卡资源", classes.get("86"));
        assertTrue(HtmlSiteRules.classes("<html><body>没有分类</body></html>").isEmpty());
    }

    @Test
    public void siteName_fallsBackToHost() {
        assertEquals("姐姐视频", HtmlSiteRules.siteName(HOME_HTML, "https://wap.jiejiesp19.xyz"));
        assertEquals("wap.jiejiesp19.xyz", HtmlSiteRules.siteName("<html>没有标题</html>", "https://wap.jiejiesp19.xyz"));
    }

    // ------------------------------------------------------------------
    // 链接与地址
    // ------------------------------------------------------------------

    @Test
    public void detailHrefs_areDeduped() {
        List<String> hrefs = HtmlSiteRules.detailHrefs(LIST_HTML, 0);
        assertEquals(2, hrefs.size());
        assertEquals("/jiejie/index.php/vod/detail/id/1507984.html", hrefs.get(0));
        assertEquals(1, HtmlSiteRules.detailHrefs(LIST_HTML, 1).size());
        assertTrue(HtmlSiteRules.detailHrefs("<html>无</html>", 0).isEmpty());
    }

    /** 详情页底部"猜你喜欢"里的播放链接不能被当成本片剧集 */
    @Test
    public void playHrefs_onlyCurrentVod() {
        String vodId = HtmlSiteRules.vodIdOfDetailUrl("/jiejie/index.php/vod/detail/id/1507984.html");
        assertEquals("1507984", vodId);
        List<String> plays = HtmlSiteRules.playHrefs(DETAIL_HTML, vodId);
        assertEquals(2, plays.size());
        for (String href : plays) {
            assertTrue("混进了推荐位的链接: " + href, href.contains("/id/1507984/"));
        }
        assertFalse(plays.get(0).contains("1507662"));
    }

    /** 不知道本片 id 时只认第一条,宁少不错 */
    @Test
    public void playHrefs_withoutVodIdTakesFirstOnly() {
        assertEquals(1, HtmlSiteRules.playHrefs(DETAIL_HTML, null).size());
        assertEquals(1, HtmlSiteRules.playHrefs(DETAIL_HTML, "").size());
    }

    @Test
    public void playVodId_supportsBothRoutes() {
        assertEquals("1507984", HtmlSiteRules.playVodId("/jiejie/index.php/vod/play/id/1507984/sid/1/nid/1.html"));
        assertEquals("1507984", HtmlSiteRules.playVodId("/jiejie/vodplay/1507984-1-1.html"));
        assertNull(HtmlSiteRules.playVodId("/jiejie/index.php/vod/detail/id/1507984.html"));
        assertNull(HtmlSiteRules.playVodId(null));
    }

    @Test
    public void pageCount_readsPagerAndSlashTotal() {
        assertEquals(147, HtmlSiteRules.pageCount(LIST_HTML, 1));
        assertEquals(3, HtmlSiteRules.pageCount("<a href=\"/x/vod/type/page/3.html\">尾页</a>", 1));
        assertEquals(2, HtmlSiteRules.pageCount("<html>没有分页</html>", 2));
    }

    /**
     * 伪静态分类路由 {@code /vodtype/{分类id}.html} 里的数字是<b>分类 id</b>,不能当页码:
     * 原来会把 30 当成"共 30 页"→ 列表页数虚高,后面十几页全是空条目(用户以为源坏了)。
     */
    @Test
    public void pageCount_ignoresCategoryIdInPseudoStaticRoute() {
        assertEquals(1, HtmlSiteRules.pageCount("<a href=\"/jiejie/vodtype/30.html\">最新</a>", 1));
        // 伪静态站真正的翻页形如 /vodtype/{分类id}-{页码}.html
        assertEquals(9, HtmlSiteRules.pageCount("<a href=\"/jiejie/vodtype/30-9.html\">尾页</a>", 1));
    }

    // ------------------------------------------------------------------
    // 播放页地址
    // ------------------------------------------------------------------

    /** 苹果CMS 默认播放器:player_aaaa.url 就是真实地址(斜杠被转义) */
    @Test
    public void playerUrl_readsPlayerConfig() {
        assertEquals("https://c10.hs100.top/20260924/7bf1d90433ad5c13/index.m3u8",
                HtmlSiteRules.playerUrl(PLAY_HTML));
    }

    @Test
    public void playerUrl_readsMacPlayer() {
        String html = "<script>var MacPlayer = {\"url\":\"https://a.com/hls/index.m3u8\",\"from\":\"x\"}</script>";
        assertEquals("https://a.com/hls/index.m3u8", HtmlSiteRules.playerUrl(html));
    }

    @Test
    public void playerUrl_regexFallbackAndIframe() {
        assertEquals("https://a.com/live/1.mp4", HtmlSiteRules.playerUrl("<div data-src='https://a.com/live/1.mp4'></div>"));
        assertEquals("https://a.com/player/x.html",
                HtmlSiteRules.playerUrl("<iframe src=\"https://a.com/player/x.html\"></iframe>"));
        // 播放页里只有 vod/play 链接(没有真实地址)时不能当成播放地址
        assertNull(HtmlSiteRules.playerUrl(DETAIL_HTML));
        assertNull(HtmlSiteRules.playerUrl(""));
        assertNull(HtmlSiteRules.playerUrl(null));
    }

    /** player_aaaa 里 url 被写成当前播放页(少数模板如此)时不能当播放地址 */
    @Test
    public void playerUrl_ignoresPageUrlAsMedia() {
        String html = "<script>var player_aaaa={\"url\":\"\\/jiejie\\/index.php\\/vod\\/play\\/id\\/1\\/sid\\/1\\/nid\\/1.html\"}</script>";
        assertNull(HtmlSiteRules.playerUrl(html));
    }

    // ------------------------------------------------------------------
    // 生成的抓取源配置
    // ------------------------------------------------------------------

    /** 生成的 ext 必须是合法 JSON,且带上 host/prefix/分类/搜索模板 */
    @Test
    public void buildExtJson_isValidJsonWithSiteFields() {
        LinkedHashMap<String, String> classes = HtmlSiteRules.classes(HOME_HTML);
        String ext = HtmlSiteRules.buildExtJson("姐姐视频", "https://wap.jiejiesp19.xyz", "/jiejie", classes,
                HtmlSiteRules.defaultListUrl("/jiejie"), HtmlSiteRules.defaultListPageUrl("/jiejie"),
                HtmlSiteRules.defaultSearchUrl("/jiejie"), HtmlSiteRules.defaultSearchPageUrl("/jiejie"));
        JsonObject obj = JsonParser.parseString(ext).getAsJsonObject();
        assertEquals("https://wap.jiejiesp19.xyz", obj.get("host").getAsString());
        assertEquals("/jiejie", obj.get("prefix").getAsString());
        assertEquals("/jiejie/index.php/vod/type/id/{id}.html", obj.get("listUrl").getAsString());
        assertEquals("/jiejie/index.php/vod/search/page/{pg}/wd/{key}.html", obj.get("searchPageUrl").getAsString());
        JsonArray arr = obj.getAsJsonArray("classes");
        assertEquals(3, arr.size());
        assertEquals("363", arr.get(0).getAsJsonObject().get("type_id").getAsString());
        assertTrue(obj.getAsJsonObject("headers").has("User-Agent"));
    }

    /** 没有搜索能力的站点不写 searchUrl(避免运行时拼出无效地址) */
    @Test
    public void buildExtJson_withoutSearchOmitsSearchFields() {
        String ext = HtmlSiteRules.buildExtJson("某站", "https://a.com", "", null,
                HtmlSiteRules.defaultListUrl(""), HtmlSiteRules.defaultListPageUrl(""), null, null);
        JsonObject obj = JsonParser.parseString(ext).getAsJsonObject();
        assertFalse(obj.has("searchUrl"));
        assertFalse(obj.has("searchPageUrl"));
        assertEquals("/index.php/vod/type/id/{id}.html", obj.get("listUrl").getAsString());
    }

    /** 抓页面源是 type 3 + api 指向 App 内运行时,ext 挂站点配置(作为字符串字段) */
    @Test
    public void subscriptionJson_isTypeThreeSpiderWithExt() {
        String ext = HtmlSiteRules.buildExtJson("姐姐视频", "https://wap.jiejiesp19.xyz", "/jiejie",
                HtmlSiteRules.classes(HOME_HTML), HtmlSiteRules.defaultListUrl("/jiejie"),
                HtmlSiteRules.defaultListPageUrl("/jiejie"), HtmlSiteRules.defaultSearchUrl("/jiejie"),
                HtmlSiteRules.defaultSearchPageUrl("/jiejie"));
        String json = CmsApiRules.buildSubscriptionJson("maccms_wap_jiejiesp19_xyz_1", "姐姐视频", 3,
                "assets://js/lib/maccms.js", ext, 1, 1, 0);
        JsonObject site = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonArray("sites").get(0).getAsJsonObject();
        assertEquals(3, site.get("type").getAsInt());
        assertEquals("assets://js/lib/maccms.js", site.get("api").getAsString());
        assertEquals(1, site.get("searchable").getAsInt());
        // ext 是字符串字段:内容本身仍是合法 JSON(JS 运行时的 init 会 parse 它)
        String extField = site.get("ext").getAsString();
        assertTrue(extField.startsWith("{\"siteName\""));
        assertNotNull(JsonParser.parseString(extField).getAsJsonObject().get("classes"));
    }

    @Test
    public void siteUrl_prefixesOnlyWhenNeeded() {
        assertEquals("https://a.com/jiejie/index.php/vod/type/id/1.html",
                HtmlSiteRules.siteUrl("https://a.com", "/jiejie", "/index.php/vod/type/id/1.html"));
        // 已带子目录的路径不再重复拼接
        assertEquals("https://a.com/jiejie/index.php/vod/type/id/1.html",
                HtmlSiteRules.siteUrl("https://a.com", "/jiejie", "/jiejie/index.php/vod/type/id/1.html"));
        assertEquals("https://a.com/index.php/vod/type/id/1.html",
                HtmlSiteRules.siteUrl("https://a.com", "", "/index.php/vod/type/id/1.html"));
        assertEquals("https://a.com/jiejie/", HtmlSiteRules.homeUrl("https://a.com/", "/jiejie"));
    }

    // ------------------------------------------------------------------
    // 路由候选与探测计划(苹果CMS 有"开 index.php"与"伪静态"两类写法)
    // ------------------------------------------------------------------

    /** 分类路由候选:v10 默认排最前,伪静态在后;都要带上站点子目录 */
    @Test
    public void listRoutes_startWithV10AndCoverPseudoStatic() {
        List<HtmlSiteRules.Route> routes = HtmlSiteRules.listRoutes("/jiejie");
        assertEquals(4, routes.size());
        assertEquals("/jiejie/index.php/vod/type/id/{id}.html", routes.get(0).listUrl);
        assertEquals("/jiejie/index.php/vod/type/id/{id}/page/{pg}.html", routes.get(0).listPageUrl);
        assertEquals("/jiejie/vodtype/{id}.html", routes.get(2).listUrl);
        assertEquals("/jiejie/vodshow/{id}--------{pg}---.html", routes.get(3).listPageUrl);
        for (HtmlSiteRules.Route r : routes) {
            assertTrue("路由漏了子目录: " + r.listUrl, r.listUrl.startsWith("/jiejie/"));
        }
        // 默认模板就是候选里的第一条(老调用点行为不变)
        assertEquals(routes.get(0).listUrl, HtmlSiteRules.defaultListUrl("/jiejie"));
        assertEquals(routes.get(0).listPageUrl, HtmlSiteRules.defaultListPageUrl("/jiejie"));
        // 根目录站点不带前缀
        assertEquals("/index.php/vod/type/id/{id}.html", HtmlSiteRules.listRoutes("").get(0).listUrl);
    }

    @Test
    public void searchRoutes_coverPseudoStatic() {
        List<HtmlSiteRules.Route> routes = HtmlSiteRules.searchRoutes("");
        assertEquals(3, routes.size());
        assertEquals("/index.php/vod/search.html?wd={key}", routes.get(0).listUrl);
        assertEquals("/index.php/vod/search/page/{pg}/wd/{key}.html", routes.get(0).listPageUrl);
        assertEquals("/vodsearch/{key}----------1---.html", routes.get(1).listUrl);
        assertEquals(routes.get(0).listUrl, HtmlSiteRules.defaultSearchUrl(""));
        assertEquals(routes.get(0).listPageUrl, HtmlSiteRules.defaultSearchPageUrl(""));
    }

    /** 探测计划:路由 × 分类交叉展开,同一路由先试前几个分类(空分类很常见) */
    @Test
    public void listProbePlan_expandsRoutesThenClasses() {
        List<HtmlSiteRules.Probe> plan = HtmlSiteRules.listProbePlan("/jiejie",
                java.util.Arrays.asList("363", "293", "86", "248"), 3);
        // 4 个路由 × 3 个分类
        assertEquals(12, plan.size());
        assertEquals("/jiejie/index.php/vod/type/id/363.html", plan.get(0).url);
        assertEquals("/jiejie/index.php/vod/type/id/293.html", plan.get(1).url);
        assertEquals("/jiejie/index.php/vod/type/id/86.html", plan.get(2).url);
        // 第 4 条开始换下一个路由(同一路由下把前几个分类排在一起)
        assertEquals("/jiejie/index.php/vod/show/id/{id}.html", plan.get(3).route.listUrl);
        assertEquals("/jiejie/index.php/vod/show/id/363.html", plan.get(3).url);
        assertEquals("/jiejie/vodtype/363.html", plan.get(6).url);
        assertEquals("/jiejie/vodshow/363--------1---.html", plan.get(9).url);
        assertTrue(plan.get(0).route.listUrl.contains("/index.php/vod/type/id/"));
        // 分类少于上限时按实际数量展开
        assertEquals(4, HtmlSiteRules.listProbePlan("", java.util.Arrays.asList("1"), 3).size());
        assertTrue(HtmlSiteRules.listProbePlan("", new java.util.ArrayList<String>(), 3).isEmpty());
        assertTrue(HtmlSiteRules.listProbePlan("", null, 3).isEmpty());
    }

    /** 搜索探测计划用"第 2 页"模板来验证翻页模板真的可用(关键词已编码) */
    @Test
    public void searchProbePlan_usesSecondPageTemplates() {
        List<HtmlSiteRules.Probe> plan = HtmlSiteRules.searchProbePlan("/jiejie", "%E5%A7%90%E5%A7%90");
        assertEquals(3, plan.size());
        assertEquals("/jiejie/index.php/vod/search/page/2/wd/%E5%A7%90%E5%A7%90.html", plan.get(0).url);
        assertEquals("/jiejie/vodsearch/%E5%A7%90%E5%A7%90----------2---.html", plan.get(1).url);
        assertEquals("/jiejie/vodsearch.html?wd=%E5%A7%90%E5%A7%90&page=2", plan.get(2).url);
        assertTrue(HtmlSiteRules.searchProbePlan("/jiejie", "").isEmpty());
    }

    @Test
    public void fill_replacesAllOccurrences() {
        assertEquals("/x/1/page/1.html", HtmlSiteRules.fill("/x/{pg}/page/{pg}.html", "{pg}", "1"));
        assertEquals("/x/{pg}/page/{pg}.html", HtmlSiteRules.fill("/x/{pg}/page/{pg}.html", "{id}", "9"));
    }
}

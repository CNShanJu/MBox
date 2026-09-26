package com.github.tvbox.osc.spiderapi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;

import org.junit.Test;

import java.util.List;

/** AbsXmlParser 纯解析契约测试(type0 XML 样例 + type1 JSON 样例 + normalize 归一回归) */
public class AbsXmlParserTest {

    private static final String SAMPLE_JSON =
            "{\"code\":1,\"list\":[{" +
                    "\"vod_id\":\"1\",\"vod_name\":\"测试剧\"," +
                    "\"vod_play_from\":\"快看\"," +
                    "\"vod_play_url\":\"第1集$http://example.com/1.mp4#第2集$http://example.com/2.mp4\"" +
                    "}]}";

    /** type0 XML 样例:空 year/state(触发清洗)+ 多线路 dd(验证 urls→beanList) */
    private static final String SAMPLE_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<rss>\n" +
                    "  <list page=\"1\" pagecount=\"1\" pagesize=\"20\" recordcount=\"1\">\n" +
                    "    <video>\n" +
                    "      <last>2023</last>\n" +
                    "      <id>1001</id>\n" +
                    "      <tid>0</tid>\n" +
                    "      <name><![CDATA[测试剧]]></name>\n" +
                    "      <type>剧集</type>\n" +
                    "      <pic>http://example.com/pic.jpg</pic>\n" +
                    "      <lang>国语</lang>\n" +
                    "      <area>大陆</area>\n" +
                    "      <year></year>\n" +
                    "      <state></state>\n" +
                    "      <note>更新至2集</note>\n" +
                    "      <actor>张三</actor>\n" +
                    "      <director>李四</director>\n" +
                    "      <dl>\n" +
                    "        <dd flag=\"快看\">第1集$http://example.com/1.m3u8#第2集$http://example.com/2.m3u8</dd>\n" +
                    "        <dd flag=\"线路2\">第1集$http://cdn.com/1.mp4#第2集$http://cdn.com/2.mp4</dd>\n" +
                    "      </dl>\n" +
                    "    </video>\n" +
                    "  </list>\n" +
                    "</rss>";

    @Test
    public void parseJson_returnsMovieAndSetsSourceKey() throws Exception {
        AbsXml data = AbsXmlParser.parseJson(SAMPLE_JSON, "src-key");
        assertNotNull(data);
        assertNotNull(data.movie);
        assertNotNull(data.movie.videoList);
        assertTrue(!data.movie.videoList.isEmpty());
        Movie.Video v = data.movie.videoList.get(0);
        assertEquals("src-key", v.sourceKey);
    }

    @Test
    public void parseJson_parsesPlayUrlToBeanList() throws Exception {
        AbsXml data = AbsXmlParser.parseJson(SAMPLE_JSON, "src-key");
        Movie.Video v = data.movie.videoList.get(0);
        if (v.urlBean != null && v.urlBean.infoList != null && !v.urlBean.infoList.isEmpty()) {
            List<Movie.Video.UrlBean.UrlInfo.InfoBean> beanList =
                    v.urlBean.infoList.get(0).beanList;
            assertNotNull(beanList);
            assertTrue(!beanList.isEmpty());
            // 第一条线路 "第1集$http..."
            assertEquals("第1集", beanList.get(0).name);
        }
    }

    @Test
    public void parseJson_garbageThrows() {
        assertThrows(Exception.class, () -> AbsXmlParser.parseJson("{not-json", "src"));
    }

    @Test
    public void parseXml_returnsMovieAndSetsSourceKey() throws Exception {
        AbsXml data = AbsXmlParser.parseXml(SAMPLE_XML, "xml-src");
        assertNotNull(data);
        assertNotNull(data.movie);
        assertNotNull(data.movie.videoList);
        assertTrue(!data.movie.videoList.isEmpty());
        Movie.Video v = data.movie.videoList.get(0);
        assertEquals("xml-src", v.sourceKey);
        assertEquals("测试剧", v.name);
    }

    @Test
    public void parseXml_parsesDdUrlsToBeanList() throws Exception {
        AbsXml data = AbsXmlParser.parseXml(SAMPLE_XML, "xml-src");
        Movie.Video v = data.movie.videoList.get(0);
        assertNotNull(v.urlBean);
        assertNotNull(v.urlBean.infoList);
        assertEquals(2, v.urlBean.infoList.size());
        // 多线路:每条 dd 的 urls 串拆成 beanList
        Movie.Video.UrlBean.UrlInfo line1 = v.urlBean.infoList.get(0);
        assertEquals("快看", line1.flag);
        assertNotNull(line1.beanList);
        assertEquals(2, line1.beanList.size());
        assertEquals("第1集", line1.beanList.get(0).name);
        assertEquals("http://example.com/2.m3u8", line1.beanList.get(1).url);
        Movie.Video.UrlBean.UrlInfo line2 = v.urlBean.infoList.get(1);
        assertEquals("线路2", line2.flag);
        assertNotNull(line2.beanList);
        assertEquals(2, line2.beanList.size());
    }

    @Test
    public void parseXml_garbageThrows() {
        assertThrows(Exception.class, () -> AbsXmlParser.parseXml("<rss><list><video>未闭合", "src"));
    }

    /** 真机 NPE 回归:typed 产物只要带 urls 文本,normalize 即补 beanList,checkThunder 不再空指针 */
    @Test
    public void normalize_fillsBeanListFromTypedUrlsText() {
        AbsXml data = new AbsXml();
        data.movie = new Movie();
        Movie.Video v = new Movie.Video();
        Movie.Video.UrlBean ub = new Movie.Video.UrlBean();
        Movie.Video.UrlBean.UrlInfo ui = new Movie.Video.UrlBean.UrlInfo();
        ui.flag = "快看";
        ui.urls = "第1集$http://example.com/1.mp4#第2集$http://example.com/2.mp4";
        ub.infoList = new java.util.ArrayList<>();
        ub.infoList.add(ui);
        v.urlBean = ub;
        data.movie.videoList = new java.util.ArrayList<>();
        data.movie.videoList.add(v);

        AbsXmlParser.normalize(data, "typed-src");

        assertEquals("typed-src", v.sourceKey);
        assertNotNull(ui.beanList);
        assertEquals(2, ui.beanList.size());
        assertEquals("第1集", ui.beanList.get(0).name);
    }

    /** normalize 防御:urls 为空/缺线路时产出空 beanList(而非 null),不抛异常 */
    @Test
    public void normalize_nullOrEmptyUrlsYieldsEmptyBeanList() {
        AbsXml data = new AbsXml();
        data.movie = new Movie();
        Movie.Video v = new Movie.Video();
        Movie.Video.UrlBean ub = new Movie.Video.UrlBean();
        Movie.Video.UrlBean.UrlInfo uiNoUrls = new Movie.Video.UrlBean.UrlInfo();
        Movie.Video.UrlBean.UrlInfo uiEmpty = new Movie.Video.UrlBean.UrlInfo();
        uiEmpty.urls = "";
        ub.infoList = new java.util.ArrayList<>();
        ub.infoList.add(uiNoUrls);
        ub.infoList.add(uiEmpty);
        v.urlBean = ub;
        data.movie.videoList = new java.util.ArrayList<>();
        data.movie.videoList.add(v);

        AbsXmlParser.normalize(data, "typed-src");

        assertNotNull(uiNoUrls.beanList);
        assertTrue(uiNoUrls.beanList.isEmpty());
        assertNotNull(uiEmpty.beanList);
        assertTrue(uiEmpty.beanList.isEmpty());
    }

    /** normalize 防御:整体/局部为 null 直接返回,不抛(与 checkThunder 空防御同向) */
    @Test
    public void normalize_nullDataIsNoOp() {
        AbsXmlParser.normalize(null, "src");
        AbsXml empty = new AbsXml();
        AbsXmlParser.normalize(empty, "src");
        AbsXml noList = new AbsXml();
        noList.movie = new Movie();
        AbsXmlParser.normalize(noList, "src");
    }

    /**
     * typed 收口回归:typed 实现将复用 AbsXmlParser.parseJson(含 normalize),
     * VM 收到 typed 产物后再 absXml()=normalize 一次——normalize 必须幂等,
     * 二次归一不得改变 sourceKey/beanList/线路结构(防 double-normalize 破坏)。
     */
    @Test
    public void normalize_isIdempotent() throws Exception {
        AbsXml once = AbsXmlParser.parseJson(SAMPLE_JSON, "src-key");
        AbsXmlParser.normalize(once, "src-key"); // 模拟 VM 收到 typed 后二次 normalize

        Movie.Video v = once.movie.videoList.get(0);
        assertEquals("src-key", v.sourceKey);
        Movie.Video.UrlBean.UrlInfo line =
                v.urlBean.infoList.get(0);
        assertNotNull(line.beanList);
        assertEquals(2, line.beanList.size());
        assertEquals("第1集", line.beanList.get(0).name);
        assertEquals("第2集", line.beanList.get(1).name);
        assertEquals("http://example.com/2.mp4", line.beanList.get(1).url);
    }
}

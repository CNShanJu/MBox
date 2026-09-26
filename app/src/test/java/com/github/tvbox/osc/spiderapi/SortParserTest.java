package com.github.tvbox.osc.spiderapi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.MovieSort;

import org.junit.Test;

/** SortParser(首页/分类 JSON 解析+筛选)单测 */
public class SortParserTest {

    @Test
    public void parseSortJson_withFilters() {
        String json = "{\"class\":[{\"type_id\":\"1\",\"type_name\":\"电影\",\"type_flag\":\"1\"}],"
                + "\"filters\":{\"1\":[{\"key\":\"area\",\"name\":\"地区\","
                + "\"value\":[{\"n\":\"全部\",\"v\":\"\"},{\"n\":\"内地\",\"v\":\"内地\"}]}]}}";
        AbsSortXml xml = SortParser.parseSortJson(json);
        assertNotNull(xml);
        assertNotNull(xml.classes);
        assertEquals(1, xml.classes.sortList.size());
        assertEquals("1", xml.classes.sortList.get(0).id);
        assertNotNull(xml.classes.sortList.get(0).filters);
        assertEquals(1, xml.classes.sortList.get(0).filters.size());
        assertEquals("area", xml.classes.sortList.get(0).filters.get(0).key);
        assertEquals(2, xml.classes.sortList.get(0).filters.get(0).values.size());
        assertTrue(xml.classes.sortList.get(0).filters.get(0).values.containsKey("内地"));
    }

    @Test
    public void parseSortJson_invalid_returnsNull() {
        assertNull(SortParser.parseSortJson("not-json"));
        assertNull(SortParser.parseSortJson(null));
    }

    /**
     * 单个坏 filter 项只丢它自己。
     * <p>
     * 原来整块 {@code catch (Throwable ignored)} 包住所有分类的筛选解析:一个坏项(缺 key、
     * value 不是数组)就会让该源<b>所有分类</b>的筛选面板一起变空,而不是只少那一项。
     */
    @Test
    public void parseSortJson_skipsBrokenFilterItemKeepsOthers() {
        String json = "{\"class\":[{\"type_id\":\"1\",\"type_name\":\"电影\",\"type_flag\":\"1\"},"
                + "{\"type_id\":\"2\",\"type_name\":\"剧集\",\"type_flag\":\"1\"}],"
                + "\"filters\":{"
                + "\"1\":[{\"name\":\"缺 key 的坏项\",\"value\":[]},"
                + "{\"key\":\"area\",\"name\":\"地区\",\"value\":[{\"n\":\"全部\",\"v\":\"\"}]}],"
                + "\"2\":[{\"key\":\"year\",\"name\":\"年份\",\"value\":\"不是数组\"}]}}";
        AbsSortXml xml = SortParser.parseSortJson(json);
        assertNotNull(xml);
        assertEquals(2, xml.classes.sortList.size());
        // 分类 1:坏项被跳过,好项保留
        assertEquals(1, xml.classes.sortList.get(0).filters.size());
        assertEquals("area", xml.classes.sortList.get(0).filters.get(0).key);
        // 分类 2:value 类型不对 → 该项作废,但分类本身照旧(不是整个源没有筛选)
        assertNotNull(xml.classes.sortList.get(1));
        assertTrue(xml.classes.sortList.get(1).filters == null || xml.classes.sortList.get(1).filters.isEmpty());
    }

    /** type0 XML 分类样例:rss/class/ty(id 属性 + name 文本),filters 空补 */
    private static final String SAMPLE_SORT_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<rss>\n" +
                    "  <class>\n" +
                    "    <ty id=\"1\">电影</ty>\n" +
                    "    <ty id=\"2\">电视剧</ty>\n" +
                    "  </class>\n" +
                    "</rss>";

    @Test
    public void parseSortXml_returnsClasses() {
        AbsSortXml xml = SortParser.parseSortXml(SAMPLE_SORT_XML);
        assertNotNull(xml);
        assertNotNull(xml.classes);
        assertNotNull(xml.classes.sortList);
        assertEquals(2, xml.classes.sortList.size());
        assertEquals("1", xml.classes.sortList.get(0).id);
        assertEquals("电影", xml.classes.sortList.get(0).name);
        assertEquals("电视剧", xml.classes.sortList.get(1).name);
    }

    @Test
    public void parseSortXml_fillsEmptyFilters() {
        AbsSortXml xml = SortParser.parseSortXml(SAMPLE_SORT_XML);
        // parseSortXml 对无 filters 的分类补空列表(不返回 null)
        for (MovieSort.SortData sort : xml.classes.sortList) {
            assertNotNull(sort.filters);
            assertTrue(sort.filters.isEmpty());
        }
    }

    @Test
    public void parseSortXml_invalid_returnsNull() {
        assertNull(SortParser.parseSortXml("<rss><class>未闭合"));
        assertNull(SortParser.parseSortXml(null));
    }
}

package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 搜索页两个接口解析的 JVM 单测(纯逻辑,不触碰 Android 类):
 * 取字段、条数上限、去重、坏数据不抛异常、URL 编码。
 */
public class SearchApiParsersTest {

    @Test
    public void 解析360排行取title并按上限截断() {
        StringBuilder data = new StringBuilder();
        for (int i = 1; i <= 15; i++) {
            if (i > 1) data.append(',');
            data.append("{\"title\":\"剧").append(i).append("\",\"cat\":2}");
        }
        String json = "{\"data\":[" + data + "],\"errno\":0}";

        List<String> list = SearchApiParsers.parseHotRank(json);
        assertEquals("最多 10 条", 10, list.size());
        assertEquals("剧1", list.get(0));
        assertEquals("剧10", list.get(9));
    }

    @Test
    public void 解析360排行首条与线上实测一致() {
        String json = "{\"data\":[{\"title\":\"兰香如故\",\"cat\":2},{\"title\":\"醒来\",\"cat\":2}]}";
        List<String> list = SearchApiParsers.parseHotRank(json);
        assertEquals(2, list.size());
        assertEquals("兰香如故", list.get(0));
        assertEquals("醒来", list.get(1));
    }

    @Test
    public void 解析联想取name并去重() {
        String json = "{\"code\":\"A00000\",\"data\":["
                + "{\"name\":\"法医秦明\"},{\"name\":\"法医秦明\"},{\"name\":\"法医秦明张若昀\"}]}";
        List<String> list = SearchApiParsers.parseSuggest(json);
        assertEquals(2, list.size());
        assertEquals("法医秦明", list.get(0));
        assertEquals("法医秦明张若昀", list.get(1));
    }

    @Test
    public void 坏数据一律空列表不抛异常() {
        assertTrue(SearchApiParsers.parseHotRank(null).isEmpty());
        assertTrue(SearchApiParsers.parseHotRank("").isEmpty());
        assertTrue(SearchApiParsers.parseHotRank("<html>502</html>").isEmpty());
        assertTrue(SearchApiParsers.parseHotRank("{\"data\":null}").isEmpty());
        assertTrue(SearchApiParsers.parseSuggest("{\"errno\":1,\"msg\":\"参数错误\"}").isEmpty());
        assertTrue(SearchApiParsers.parseSuggest("[1,2,3]").isEmpty());
    }

    @Test
    public void 关键词编码() {
        assertEquals("fayi", SearchApiParsers.encode("fayi"));
        assertEquals("%E6%B3%95%E5%8C%BB", SearchApiParsers.encode("法医"));
        assertEquals("a%20b%26c", SearchApiParsers.encode("a b&c"));
        assertTrue(SearchApiParsers.suggestUrl("法医")
                .startsWith("https://suggest.video.iqiyi.com/?if=mobile&key=%E6%B3%95"));
    }

    @Test
    public void 列优先折算_十条分两列() {
        // 左列 1-5、右列 6-10(页面按列优先排,网格本身是行优先)
        int[] expect = {0, 5, 1, 6, 2, 7, 3, 8, 4, 9};
        for (int p = 0; p < expect.length; p++) {
            assertEquals("位置 " + p, expect[p], SearchApiParsers.columnMajorIndex(p, 10, 2));
        }
    }

    @Test
    public void 列优先折算_用户示意与奇数条() {
        // 用户示意:1 4 / 2 5 / 3 6
        int[] six = {0, 3, 1, 4, 2, 5};
        for (int p = 0; p < six.length; p++) {
            assertEquals("6 条位置 " + p, six[p], SearchApiParsers.columnMajorIndex(p, 6, 2));
        }
        // 奇数条:左列 1-4、右列 5-7
        int[] seven = {0, 4, 1, 5, 2, 6, 3};
        for (int p = 0; p < seven.length; p++) {
            assertEquals("7 条位置 " + p, seven[p], SearchApiParsers.columnMajorIndex(p, 7, 2));
        }
    }

    @Test
    public void 列优先折算_越界与非法入参() {
        assertEquals(9, SearchApiParsers.columnMajorIndex(10, 10, 2));
        assertEquals(0, SearchApiParsers.columnMajorIndex(-1, 10, 2));
        assertEquals(0, SearchApiParsers.columnMajorIndex(3, 0, 2));
        assertEquals(0, SearchApiParsers.columnMajorIndex(3, 10, 0));
    }
}

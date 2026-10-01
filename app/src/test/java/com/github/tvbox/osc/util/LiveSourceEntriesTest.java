package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 订阅管理「直播源」页列表逻辑的 JVM 单测(纯逻辑,不触碰 Android 类):
 * 订阅导入条目在前、用户自建在后;同地址去重;勾选态只命中一个;订阅导入的不可删。
 */
public class LiveSourceEntriesTest {

    private static List<LiveSourceEntries.Imported> imported(String... nameUrlPairs) {
        List<LiveSourceEntries.Imported> out = new ArrayList<>();
        for (int i = 0; i + 1 < nameUrlPairs.length; i += 2) {
            out.add(new LiveSourceEntries.Imported(nameUrlPairs[i], nameUrlPairs[i + 1]));
        }
        return out;
    }

    @Test
    public void 订阅导入在前_标注来源_且不可删除() {
        List<LiveSourceEntries.Entry> list = LiveSourceEntries.build(
                "饭太硬",
                imported("Kimentanm", "https://gh.927223.xy/x/iptv.m3u",
                        "虎牙一起看", "https://sub.ottiptv.cc/huyayqk.m3u"),
                Collections.singletonList("http://192.168.1.5:8080/mine.txt"),
                "");

        assertEquals(3, list.size());
        assertEquals("Kimentanm", list.get(0).name);
        assertEquals("饭太硬", list.get(0).fromSubscription);
        assertFalse("订阅导入的不能删", list.get(0).removable());
        assertEquals("虎牙一起看", list.get(1).name);
        assertNull("用户自建的没有来源", list.get(2).fromSubscription);
        assertTrue("用户自建的可以删", list.get(2).removable());
    }

    @Test
    public void 勾选态按地址命中_只命中一个() {
        List<LiveSourceEntries.Entry> list = LiveSourceEntries.build(
                "饭太硬",
                imported("A", "https://a/1.m3u", "B", "https://b/2.m3u"),
                Arrays.asList("https://c/3.m3u", "https://d/4.m3u"),
                "https://b/2.m3u");

        int checked = 0;
        String checkedName = null;
        for (LiveSourceEntries.Entry e : list) {
            if (e.checked) {
                checked++;
                checkedName = e.name;
            }
        }
        assertEquals(1, checked);
        assertEquals("B", checkedName);
    }

    @Test
    public void 用户地址与订阅地址重复时只留订阅那条() {
        List<LiveSourceEntries.Entry> list = LiveSourceEntries.build(
                "肥猫",
                imported("Kimentanm", "https://gh.927223.xy/x/iptv.m3u"),
                new ArrayList<>(Collections.singletonList("https://gh.927223.xy/x/iptv.m3u")),
                "");

        assertEquals("同地址只出现一行", 1, list.size());
        assertEquals("留的是订阅那条", "肥猫", list.get(0).fromSubscription);
    }

    @Test
    public void 当前直播源为空时没有任何条目被勾选_等价于用订阅自带直播() {
        List<LiveSourceEntries.Entry> list = LiveSourceEntries.build(
                "饭太硬", imported("A", "https://a/1.m3u"), null, "");
        for (LiveSourceEntries.Entry e : list) {
            assertFalse(e.checked);
        }
    }

    @Test
    public void 内嵌分组_没有地址_不可单独指定() {
        List<LiveSourceEntries.Entry> list = LiveSourceEntries.build(
                "饭太硬", imported("央视", ""), null, "");

        assertEquals(1, list.size());
        assertTrue("内嵌分组没有地址", list.get(0).embedded());
        assertEquals("央视", list.get(0).name);
        assertFalse("内嵌分组不会被勾选", list.get(0).checked);
    }

    @Test
    public void 订阅没给名字时按地址推导展示名() {
        List<LiveSourceEntries.Entry> list = LiveSourceEntries.build(
                "饭太硬", imported("", "http://193.123.86.190:14888/TV/iptv.php"), null, "");

        assertEquals("193.123.86.190:14888/iptv.php", list.get(0).name);
    }

    @Test
    public void 当前直播源不在清单里时补一行并勾选() {
        // 首次装机的内置默认源:在 SystemConfig 里、但不在用户历史里(也不是订阅给的)
        List<LiveSourceEntries.Entry> list = LiveSourceEntries.build(
                "饭太硬",
                imported("Kimentanm", "https://gh.927223.xy/x/iptv.m3u"),
                null,
                "https://gh-proxy.com/raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.txt");

        assertEquals(2, list.size());
        LiveSourceEntries.Entry builtin = list.get(1);
        assertTrue("补的那行是勾选态", builtin.checked);
        assertNull("补的那行算用户自建(可删)", builtin.fromSubscription);
        assertTrue("名字按地址推导", builtin.name.contains("gh-proxy.com"));
    }

    @Test
    public void 当前直播源已在清单里就不重复补行() {
        List<LiveSourceEntries.Entry> list = LiveSourceEntries.build(
                "饭太硬",
                imported("Kimentanm", "https://gh.927223.xy/x/iptv.m3u"),
                null,
                "https://gh.927223.xy/x/iptv.m3u");

        assertEquals(1, list.size());
        assertTrue(list.get(0).checked);
    }

    @Test
    public void 空值容错() {
        assertTrue(LiveSourceEntries.build(null, null, null, null).isEmpty());
        assertEquals("", LiveSourceEntries.displayName(null));
        assertEquals("", LiveSourceEntries.displayName("   "));
    }

    @Test
    public void 超长地址的展示名会截断() {
        StringBuilder longTail = new StringBuilder();
        for (int i = 0; i < 80; i++) longTail.append('a');
        String name = LiveSourceEntries.displayName(
                "http://very-long-host-name.example.com/" + longTail + ".m3u");
        assertTrue("展示名不该无限长", name.length() <= 32);
        assertTrue(name.endsWith("…"));
    }
}

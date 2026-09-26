package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.DownloadRoute;
import com.github.tvbox.osc.bean.VodInfo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 换线路候选挑选单测(纯 JVM)。
 * <p>
 * 事实前提:两条线路的集名风格可能不同("第01集" vs "01")、集数也可能不一致,顺序甚至还可能相反 ——
 * 所以对齐必须"先按集名、再按序号",都取不到就跳过这条线路,不能猜;候选里也不能出现当前线路本身,
 * 否则"换线路"会变成原地重下同一份播放列表(白白丢掉已下碎片)。
 */
public class DownloadRoutePlanTest {

    /** 造一条线路的剧集表:地址形如 https://<线路标签>/<序号>.m3u8,便于断言"这一集的另一条线路" */
    private static List<VodInfo.VodSeries> series(String lineTag, String... names) {
        List<VodInfo.VodSeries> l = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            l.add(new VodInfo.VodSeries(names[i], "https://" + lineTag + "/" + i + ".m3u8"));
        }
        return l;
    }

    private static Map<String, List<VodInfo.VodSeries>> map(String flag1, List<VodInfo.VodSeries> list1,
            String flag2, List<VodInfo.VodSeries> list2) {
        Map<String, List<VodInfo.VodSeries>> m = new LinkedHashMap<>();
        m.put(flag1, list1);
        m.put(flag2, list2);
        return m;
    }

    @Test
    public void picksSameEpisodeByName_keepsSourceOrder() {
        Map<String, List<VodInfo.VodSeries>> m = new LinkedHashMap<>();
        m.put("线路A", series("A", "第01集", "第02集"));
        m.put("线路B", series("B", "第01集", "第02集"));
        m.put("线路C", series("C", "第01集", "第02集"));
        List<DownloadRoute> out = DownloadRoutePlan.alternatives(m, "线路A", "https://A/0.m3u8", 0, "第01集");
        assertEquals("另两条线路都在候选里", 2, out.size());
        assertEquals("线路B", out.get(0).playFlag);
        assertEquals("https://B/0.m3u8", out.get(0).episodeRawUrl);
        assertEquals("第01集", out.get(0).episodeName);
        assertEquals("线路C", out.get(1).playFlag);
    }

    @Test
    public void fallsBackToEpisodeIndex_whenNamesDiffer() {
        // 线路B 集名风格不同(纯序号),按集名匹配不到 → 按序号对齐,取到 B 的第 2 集(index=1)
        List<DownloadRoute> out = DownloadRoutePlan.alternatives(
                map("线路A", series("A", "第01集", "第02集", "第03集"),
                        "线路B", series("B", "01", "02", "03")),
                "线路A", "https://A/1.m3u8", 1, "第02集");
        assertEquals(1, out.size());
        assertEquals("线路B", out.get(0).playFlag);
        assertEquals("https://B/1.m3u8", out.get(0).episodeRawUrl);
        assertEquals("02", out.get(0).episodeName);
    }

    @Test
    public void skipsRoutesThatCannotAlign() {
        // 线路B 只有一集(序号越界)且集名对不上 → 跳过;线路C 能按序号取到
        Map<String, List<VodInfo.VodSeries>> m = new LinkedHashMap<>();
        m.put("线路A", series("A", "第01集", "第02集"));
        m.put("线路B", series("B", "预告"));
        m.put("线路C", series("C", "01", "02"));
        List<DownloadRoute> out = DownloadRoutePlan.alternatives(m, "线路A", "https://A/1.m3u8", 1, "第02集");
        assertEquals(1, out.size());
        assertEquals("线路C", out.get(0).playFlag);
    }

    @Test
    public void skipsSameRawUrlAndBrokenEntries() {
        Map<String, List<VodInfo.VodSeries>> m = new LinkedHashMap<>();
        m.put("线路A", series("A", "第01集"));
        m.put("线路B", Collections.singletonList(new VodInfo.VodSeries("第01集", "https://A/0.m3u8"))); // 与当前同址
        m.put("线路C", Collections.singletonList(new VodInfo.VodSeries("第01集", "")));                 // 无地址
        m.put("线路D", null);                                                                          // 空线路
        List<DownloadRoute> out = DownloadRoutePlan.alternatives(m, "线路A", "https://A/0.m3u8", 0, "第01集");
        assertTrue("同址/无地址/空线路都要跳过", out.isEmpty());
    }

    @Test
    public void capsAlternatives() {
        // 5 条备用线路:最多带 MAX_ALTERNATIVES 条(每换一条都要整集重下,不能无限试)
        Map<String, List<VodInfo.VodSeries>> m = new LinkedHashMap<>();
        for (String f : new String[]{"A", "B", "C", "D", "E", "F"}) {
            m.put(f, series(f, "第01集"));
        }
        List<DownloadRoute> out = DownloadRoutePlan.alternatives(m, "A", "https://A/0.m3u8", 0, "第01集");
        assertEquals(DownloadRoutePlan.MAX_ALTERNATIVES, out.size());
    }

    @Test
    public void handlesEmptyInput() {
        assertTrue(DownloadRoutePlan.alternatives(null, "A", "u", 0, "第01集").isEmpty());
        assertTrue(DownloadRoutePlan.alternatives(new LinkedHashMap<String, List<VodInfo.VodSeries>>(),
                "A", "u", 0, "第01集").isEmpty());
        Map<String, List<VodInfo.VodSeries>> onlyCurrent = new LinkedHashMap<>();
        onlyCurrent.put("A", series("A", "第01集"));
        assertTrue("只有一条线路时没有候选",
                DownloadRoutePlan.alternatives(onlyCurrent, "A", "https://A/0.m3u8", 0, "第01集").isEmpty());
    }

    @Test
    public void routeUsabilityGuard() {
        assertTrue(new DownloadRoute("线路B", "https://x/1.m3u8", "第01集").isUsable());
        assertFalse("缺地址的候选不可用", new DownloadRoute("线路B", "", "第01集").isUsable());
        assertFalse("缺线路名的候选不可用", new DownloadRoute(null, "https://x/1.m3u8", "第01集").isUsable());
        assertEquals("线路B(第01集)", new DownloadRoute("线路B", "https://x/1.m3u8", "第01集").describe());
        assertEquals("线路B", new DownloadRoute("线路B", "https://x/1.m3u8", null).describe());
    }
}

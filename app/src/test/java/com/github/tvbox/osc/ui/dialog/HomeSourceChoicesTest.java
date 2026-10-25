package com.github.tvbox.osc.ui.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.github.tvbox.osc.bean.SourceBean;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class HomeSourceChoicesTest {
    private final HomeSourceChoices.Row first = new HomeSourceChoices.Row("a", "🍓糯米 | 秒播");
    private final HomeSourceChoices.Row second = new HomeSourceChoices.Row("b", "4K影院");
    private final HomeSourceChoices.Row third = new HomeSourceChoices.Row("c", "糯米备用");
    private final List<HomeSourceChoices.Row> rows = Arrays.asList(first, second, third);

    @Test public void matchesPartOfNameAndPreservesSourceIdentityAndOrder() {
        List<HomeSourceChoices.Row> matches = HomeSourceChoices.filter(rows, " 糯米 ");
        assertEquals(2, matches.size());
        assertSame(first, matches.get(0));
        assertSame(third, matches.get(1));
        assertEquals(3, rows.size());
    }

    @Test public void clearingSearchRestoresAllSources() {
        assertEquals(rows, HomeSourceChoices.filter(rows, "   "));
        assertEquals(rows, HomeSourceChoices.filter(rows, null));
        assertTrue(HomeSourceChoices.filter(rows, "不存在").isEmpty());
    }

    @Test public void selectedSourceIsFoundByKeyAfterFiltering() {
        List<HomeSourceChoices.Row> matches = HomeSourceChoices.filter(rows, "糯米");
        assertEquals(1, HomeSourceChoices.selectedIndex(matches, "c"));
        assertEquals(-1, HomeSourceChoices.selectedIndex(matches, "b"));
        assertEquals(1, HomeSourceChoices.selectedIndex(HomeSourceChoices.filter(rows, ""), "b"));
    }

    @Test public void sameNamesRemainSeparateSources() {
        List<HomeSourceChoices.Row> duplicates = Arrays.asList(
                new HomeSourceChoices.Row("one", "影院"), new HomeSourceChoices.Row("two", "影院"));
        List<HomeSourceChoices.Row> matches = HomeSourceChoices.filter(duplicates, "影院");
        assertEquals(2, matches.size());
        assertEquals(1, HomeSourceChoices.selectedIndex(matches, "two"));
    }

    @Test public void latinNamesMatchWithoutDependingOnDeviceLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals(1, HomeSourceChoices.filter(rows, "4k").size());
            assertEquals(1, HomeSourceChoices.filter(Arrays.asList(
                    new HomeSourceChoices.Row("i", "VIP影院")), "vip").size());
        } finally {
            Locale.setDefault(original);
        }
    }

    private static SourceBean source(String name, String api) {
        SourceBean bean = new SourceBean();
        bean.setName(name);
        bean.setApi(api);
        return bean;
    }

    @Test public void channelListApisAreTreatedAsLiveSources() {
        assertTrue(HomeSourceChoices.isLiveLikeSource(source("直播", "https://example.com/live.txt")));
        assertTrue(HomeSourceChoices.isLiveLikeSource(source("IPTV", "https://example.com/iptv.m3u")));
        assertTrue(HomeSourceChoices.isLiveLikeSource(source("电视", "https://example.com/tv.m3u8")));
        // 大小写/空白不影响判定
        assertTrue(HomeSourceChoices.isLiveLikeSource(source("直播", "  https://example.com/LIVE.TXT ")));
    }

    @Test public void liveNamedSourcesWithoutApiAreTreatedAsLiveSources() {
        assertTrue(HomeSourceChoices.isLiveLikeSource(source("直播", "")));
        assertTrue(HomeSourceChoices.isLiveLikeSource(source("Live TV", null)));
        assertTrue(HomeSourceChoices.isLiveLikeSource(source("我的IPTV", "   ")));
    }

    @Test public void ordinaryVodSourcesStayInTheDialog() {
        assertFalse(HomeSourceChoices.isLiveLikeSource(source("饭太硬", "https://www.饭太硬.net/tv")));
        assertFalse(HomeSourceChoices.isLiveLikeSource(source("4K影院", "https://example.com/api.json")));
        assertFalse(HomeSourceChoices.isLiveLikeSource(source("糯米", "csp_XPath")));
        // 名字像直播但 api 是正经点播接口:保留
        assertFalse(HomeSourceChoices.isLiveLikeSource(source("直播盒子", "https://example.com/api.json")));
        assertFalse(HomeSourceChoices.isLiveLikeSource(null));
    }
}

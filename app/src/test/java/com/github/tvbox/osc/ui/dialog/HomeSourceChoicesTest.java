package com.github.tvbox.osc.ui.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
}

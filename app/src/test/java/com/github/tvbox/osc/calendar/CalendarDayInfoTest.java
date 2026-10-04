package com.github.tvbox.osc.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

public class CalendarDayInfoTest {
    private static final HolidayCatalog CATALOG = HolidayCatalogTestFixture.load();

    @Test
    public void known2026LunarDatesAndFestivals() {
        assertDay(2026, 2, 16, "农历腊月廿九", "除夕");
        assertDay(2026, 2, 17, "农历正月初一", "春节");
        assertDay(2026, 6, 19, "农历五月初五", "端午节");
        assertDay(2026, 9, 25, "农历八月十五", "中秋节");
        assertDay(2026, 10, 4, "农历八月廿四", null);
        assertEquals("10月4日 周日", from(2026, 10, 4).getGregorianText());
        assertEquals("农历八月十五·中秋节", from(2026, 9, 25).getSecondaryText());
    }

    @Test
    public void regularMonthPrecedesLeapMonth() {
        LunarCalendar.LunarDate regular = LunarCalendar.fromGregorian(2025, 7, 24);
        assertEquals(6, regular.getMonth());
        assertEquals(30, regular.getDay());
        assertFalse(regular.isLeapMonth());
        assertTrue(regular.isLastDayOfMonth());

        LunarCalendar.LunarDate leap = LunarCalendar.fromGregorian(2025, 7, 25);
        assertEquals(6, leap.getMonth());
        assertEquals(1, leap.getDay());
        assertTrue(leap.isLeapMonth());
        assertEquals("农历闰六月初一", leap.getDisplayText());

    }

    @Test
    public void leapMonthDoesNotRepeatFestival() {
        // HKO's 2009 table places leap fifth-month day 5 on June 27.
        LunarCalendar.LunarDate leap = LunarCalendar.fromGregorian(2009, 6, 27);
        assertEquals(5, leap.getMonth());
        assertEquals(5, leap.getDay());
        assertTrue(leap.isLeapMonth());
        assertNull(from(2009, 6, 27).getFestivalText());
    }

    @Test
    public void festivalPriorityAndSolarTerms() {
        // Mid-Autumn Festival and National Day coincided in 2020; display the lunar festival.
        assertEquals("中秋节·国庆节", from(2020, 10, 1).getFestivalText());
        assertEquals("中秋节·国庆节", from(2020, 10, 1).getHolidayText());
        assertEquals("寒露", from(2026, 10, 8).getFestivalText());
        assertEquals("寒露", from(2026, 10, 8).getHolidayText());
        assertEquals("霜降", from(2026, 10, 23).getFestivalText());
        assertEquals("清明", from(2026, 4, 5).getFestivalText());
        assertEquals("清明", from(2026, 4, 5).getHolidayText());
    }

    @Test
    public void rangeEdgesAndCalendarInput() {
        assertFalse(LunarCalendar.supports(1900, 1, 30));
        assertTrue(LunarCalendar.supports(1900, 1, 31));
        assertEquals("农历正月初一", from(1900, 1, 31).getLunarText());
        assertFalse(LunarCalendar.supports(2051, 1, 1));
        assertEquals("农历日期暂不可用", from(2051, 1, 1).getSecondaryText());

        Calendar localDate = new GregorianCalendar(TimeZone.getTimeZone("Asia/Shanghai"));
        localDate.clear();
        localDate.set(2026, Calendar.OCTOBER, 4, 22, 30);
        assertEquals("10月4日 周日", CalendarDayInfo.from(localDate, CATALOG).getGregorianText());
    }

    private static void assertDay(int year, int month, int day, String lunar, String festival) {
        CalendarDayInfo actual = from(year, month, day);
        assertEquals(lunar, actual.getLunarText());
        assertEquals(festival, actual.getFestivalText());
    }

    private static CalendarDayInfo from(int year, int month, int day) {
        return CalendarDayInfo.from(year, month, day, CATALOG);
    }
}

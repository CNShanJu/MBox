package com.github.tvbox.osc.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HolidayCatalogTest {
    private static final long START = 1_800_000_000_000L;
    private static final long HOUR = 60L * 60 * 1000;

    @Test
    public void overlappingLunarAndSolarHolidaysKeepIndependentDisplayAndFireworks() {
        HolidayCatalog catalog = catalog(
                entry("mid_autumn", "中秋节", "lunar", 8, 15, 15, "fixed", "",
                        false, true, 200, false, 3, "2", true),
                entry("national_day", "国庆节", "solar", 10, 1, 1, "fixed", "",
                        false, true, 100, true, 3, "2", true));
        assertEquals(2, catalog.getMatchingHolidays(2020, 10, 1).size());
        assertEquals("中秋节", catalog.getDisplayHoliday(2020, 10, 1).getName());
        assertEquals("national_day", catalog.getFireworksHoliday(2020, 10, 1).getId());
        assertEquals("中秋节·国庆节", CalendarDayInfo.from(2020, 10, 1, catalog).getHolidayText());
        assertEquals("中秋节·国庆节", CalendarDayInfo.from(2020, 10, 1, catalog).getFestivalText());
        assertTrue(HolidayFireworksPolicy.isCelebrationDay(catalog, 2020, 10, 1));
    }

    @Test
    public void equalDisplayPriorityKeepsJsonOrder() {
        HolidayCatalog catalog = catalog(
                entry("national_day", "国庆节", "solar", 10, 1, 1, "fixed", "",
                        false, true, 100, true, 3, "2", true),
                entry("mid_autumn", "中秋节", "lunar", 8, 15, 15, "fixed", "",
                        false, true, 100, false, 3, "2", true));
        assertEquals("国庆节·中秋节", CalendarDayInfo.from(2020, 10, 1, catalog).getHolidayText());
    }

    @Test
    public void overlappingEnabledFireworksUseJsonOrderNotDisplayPriority() {
        HolidayCatalog catalog = catalog(
                entry("first_rule", "甲", "solar", 10, 1, 1, "fixed", "",
                        false, true, 10, true, 2, "1", true),
                entry("higher_display", "乙", "solar", 10, 1, 1, "fixed", "",
                        false, true, 100, true, 3, "2", true));
        assertEquals("higher_display", catalog.getDisplayHoliday(2026, 10, 1).getId());
        assertEquals("first_rule", catalog.getFireworksHoliday(2026, 10, 1).getId());
    }

    @Test
    public void lunarLeapMonthDoesNotDuplicateOrdinaryFestival() {
        HolidayCatalog catalog = catalog(entry("dragon_boat", "端午节", "lunar", 5, 5, 5,
                "fixed", "", false, true, 100, false, 3, "2", true));
        assertEquals("端午节", catalog.getDisplayHoliday(2009, 5, 28).getName());
        assertNull(catalog.getDisplayHoliday(2009, 6, 27));
        assertNull(CalendarDayInfo.from(2009, 6, 27, catalog).getHolidayText());
    }

    @Test
    public void lunarLastDayAndSolarTermAreCalculatedRatherThanFixedDates() {
        HolidayCatalog catalog = catalog(
                entry("eve", "除夕", "lunar", 12, 0, 0, "last_day", "",
                        false, true, 100, true, 3, "2", true),
                entry("qingming", "清明", "solar", 4, 0, 0, "solar_term", "清明",
                        false, true, 100, false, 3, "2", true));
        assertEquals("除夕", catalog.getDisplayHoliday(2026, 2, 16).getName());
        assertNull(catalog.getDisplayHoliday(2026, 2, 15));
        assertEquals("清明", catalog.getDisplayHoliday(2026, 4, 5).getName());
        assertNull(catalog.getDisplayHoliday(2026, 4, 4));
        assertNull(catalog.getFireworksHoliday(2026, 4, 5));
    }

    @Test
    public void solarTermIsDisplayedOnlyWhenCatalogDefinesItAndDescriptionIsRetained() {
        HolidayCatalog catalog = catalog(entry("cold_dew", "寒露", "solar", 10, 0, 0,
                "solar_term", "寒露", false, true, 10, false, 3, "1", false));
        HolidayCatalog.Entry term = catalog.getDisplayHoliday(2026, 10, 8);
        assertEquals("寒露", term.getName());
        assertEquals("寒露说明", term.getDesc());
        assertEquals("寒露", CalendarDayInfo.from(2026, 10, 8, catalog).getHolidayText());
        assertEquals("寒露", CalendarDayInfo.from(2026, 10, 8, catalog).getFestivalText());
        assertNull(CalendarDayInfo.from(2026, 10, 8).getHolidayText());
        assertNull(CalendarDayInfo.from(2026, 10, 8).getFestivalText());
    }

    @Test
    public void lunarHolidayAndSolarTermOnSameDayBothDisplayInPriorityOrder() {
        LunarCalendar.LunarDate lunar = LunarCalendar.fromGregorian(2026, 10, 8);
        HolidayCatalog catalog = catalog(
                entry("cold_dew", "寒露", "solar", 10, 0, 0,
                        "solar_term", "寒露", false, true, 10, false, 3, "1", false),
                entry("lunar_holiday", "农历节", "lunar", lunar.getMonth(), lunar.getDay(),
                        lunar.getDay(), "fixed", "", lunar.isLeapMonth(), true,
                        100, false, 3, "1", false));
        assertEquals(2, catalog.getMatchingHolidays(2026, 10, 8).size());
        assertEquals("农历节·寒露", CalendarDayInfo.from(2026, 10, 8, catalog).getHolidayText());
    }

    @Test
    public void solarTermFireworksFlagIsIgnoredButCoincidentHolidayCanLaunch() {
        HolidayCatalog termOnly = catalog(entry("cold_dew", "寒露", "solar", 10, 0, 0,
                "solar_term", "寒露", false, true, 100, true, 3, "1", true));
        assertNull(termOnly.getFireworksHoliday(2026, 10, 8));
        assertFalse(HolidayFireworksPolicy.isCelebrationDay(termOnly, 2026, 10, 8));

        LunarCalendar.LunarDate lunar = LunarCalendar.fromGregorian(2026, 10, 8);
        HolidayCatalog overlap = catalog(
                entry("cold_dew", "寒露", "solar", 10, 0, 0,
                        "solar_term", "寒露", false, true, 100, true, 3, "1", true),
                entry("lunar_holiday", "农历节", "lunar", lunar.getMonth(), lunar.getDay(),
                        lunar.getDay(), "fixed", "", lunar.isLeapMonth(), true,
                        50, true, 3, "2", true));
        assertEquals("lunar_holiday", overlap.getFireworksHoliday(2026, 10, 8).getId());
        assertTrue(HolidayFireworksPolicy.isCelebrationDay(overlap, 2026, 10, 8));
    }

    @Test
    public void dayRangeHidesLabelButStillPermitsFireworks() {
        HolidayCatalog catalog = catalog(entry("spring_following", "春节初二至初十", "lunar",
                1, 2, 10, "fixed", "", false, false, 100, true, 3, "2", true));
        assertNull(catalog.getDisplayHoliday(2026, 2, 18));
        assertEquals("spring_following", catalog.getFireworksHoliday(2026, 2, 18).getId());
        assertEquals("spring_following", catalog.getFireworksHoliday(2026, 2, 26).getId());
        assertNull(catalog.getFireworksHoliday(2026, 2, 27));
    }

    @Test
    public void cooldownMustBeFullIntegerHoursAndInvalidValuesBecomeOne() {
        String[] invalid = {"0", "24", "2.5", "\"2\"", "null", "-1"};
        for (String value : invalid) {
            HolidayCatalog catalog = catalog(entry("new_year", "元旦", "solar", 1, 1, 1,
                    "fixed", "", false, true, 100, true, 3, value, true));
            assertEquals(value, 1, catalog.getFireworksHoliday(2026, 1, 1)
                    .getFireworks().getCooldownHours());
            int key = HolidayFireworksPolicy.dayKey(2026, 1, 1);
            assertFalse(HolidayFireworksPolicy.evaluate(catalog, 2026, 1, 1,
                    START + HOUR - 1, key, 1, START).shouldCelebrate());
            assertTrue(HolidayFireworksPolicy.evaluate(catalog, 2026, 1, 1,
                    START + HOUR, key, 1, START).shouldCelebrate());
        }
        String noCooldownField = entry("new_year", "元旦", "solar", 1, 1, 1,
                "fixed", "", false, true, 100, true, 3, "2", true)
                .replace(",\"cooldownHours\":2", "");
        assertEquals(1, catalog(noCooldownField).getFireworksHoliday(2026, 1, 1)
                .getFireworks().getCooldownHours());
        HolidayCatalog upperBound = catalog(entry("new_year", "元旦", "solar", 1, 1, 1,
                "fixed", "", false, true, 100, true, 3, "23", true));
        assertEquals(23, upperBound.getFireworksHoliday(2026, 1, 1)
                .getFireworks().getCooldownHours());
    }

    @Test
    public void unlimitedRuleAndConfiguredDailyLimitUseDifferentPolicies() {
        HolidayCatalog catalog = catalog(
                entry("spring_day_one", "春节", "lunar", 1, 1, 1, "fixed", "",
                        false, true, 100, true, 0, "23", false),
                entry("new_year", "元旦", "solar", 1, 1, 1, "fixed", "",
                        false, true, 100, true, 3, "2", true));
        int lunarKey = HolidayFireworksPolicy.dayKey(2026, 2, 17);
        HolidayFireworksPolicy.Decision unlimited = HolidayFireworksPolicy.evaluate(catalog,
                2026, 2, 17, START, lunarKey, 100, START + HOUR);
        assertTrue(unlimited.shouldCelebrate());
        assertEquals(101, unlimited.getCount());
        assertEquals(0, unlimited.getMaxLaunchesPerDay());

        int solarKey = HolidayFireworksPolicy.dayKey(2026, 1, 1);
        assertEquals(HolidayFireworksPolicy.Reason.COOLDOWN_ACTIVE,
                HolidayFireworksPolicy.evaluate(catalog, 2026, 1, 1,
                        START + 2 * HOUR - 1, solarKey, 1, START).getReason());
        assertTrue(HolidayFireworksPolicy.evaluate(catalog, 2026, 1, 1,
                START + 2 * HOUR, solarKey, 1, START).shouldCelebrate());
        assertEquals(HolidayFireworksPolicy.Reason.DAILY_LIMIT_REACHED,
                HolidayFireworksPolicy.evaluate(catalog, 2026, 1, 1,
                        START + 4 * HOUR, solarKey, 3, START).getReason());
    }

    @Test
    public void staleRecordIsDeletedOnAnOrdinaryDay() {
        HolidayCatalog catalog = catalog(entry("new_year", "元旦", "solar", 1, 1, 1,
                "fixed", "", false, true, 100, true, 3, "2", true));
        HolidayFireworksPolicy.Decision decision = HolidayFireworksPolicy.evaluate(catalog,
                2026, 1, 2, START + 86_400_000L, 20260101, 2, START);
        assertFalse(decision.shouldCelebrate());
        assertTrue(decision.shouldDeletePreviousRecord());
        assertEquals(0, decision.getDayKey());
        assertEquals(0, decision.getCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeLaunchLimitCannotSilentlyMeanUnlimited() {
        catalog(entry("invalid", "无效", "solar", 1, 1, 1,
                "fixed", "", false, true, 100, true, -1, "2", true));
    }

    @Test(expected = IllegalArgumentException.class)
    public void dateFieldsMustExistForBothCalendarTypes() {
        String missingEndDay = entry("invalid", "无效", "lunar", 1, 1, 1,
                "fixed", "", false, true, 100, false, 3, "2", true)
                .replace("\"endDay\":1,", "");
        catalog(missingEndDay);
    }

    @Test(expected = IllegalArgumentException.class)
    public void descriptionIsRequiredForEveryEntry() {
        String missingDescription = entry("invalid", "无效", "solar", 1, 1, 1,
                "fixed", "", false, true, 100, false, 3, "2", true)
                .replace("\"desc\":\"无效说明\",", "");
        catalog(missingDescription);
    }

    private static HolidayCatalog catalog(String... entries) {
        return HolidayCatalog.fromJson("{\"version\":1,\"holidays\":[" + String.join(",", entries) + "]}");
    }

    private static String entry(String id, String name, String type, int month, int day,
                                int endDay, String dateRule, String term, boolean leap,
                                boolean display, int displayPriority, boolean fireworksEnabled,
                                int maxLaunches, String cooldownHours, boolean cooldownEnabled) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"desc\":\"" + name + "说明"
                + "\",\"type\":\"" + type + "\",\"month\":" + month
                + ",\"day\":" + day + ",\"endDay\":" + endDay
                + ",\"dateRule\":\"" + dateRule + "\",\"term\":\"" + term
                + "\",\"includeLeapMonth\":" + leap + ",\"display\":" + display
                + ",\"displayPriority\":" + displayPriority + ",\"fireworks\":{\"enabled\":"
                + fireworksEnabled + ",\"maxLaunchesPerDay\":" + maxLaunches
                + ",\"cooldownHours\":" + cooldownHours + ",\"cooldownEnabled\":"
                + cooldownEnabled + "},\"splash\":{\"enabled\":false,\"mediaType\":\"none\",\"asset\":\"\"}}";
    }
}

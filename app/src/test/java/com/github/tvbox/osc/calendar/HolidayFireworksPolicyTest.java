package com.github.tvbox.osc.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HolidayFireworksPolicyTest {
    private static final long START = 1_800_000_000_000L;
    private static final long TWO_HOURS_MS = 2L * 60 * 60 * 1000;
    private static final HolidayCatalog CATALOG = HolidayCatalogTestFixture.load();

    @Test
    public void celebrationDatesIncludeNewYearEveAndFirstTenLunarDays() {
        assertTrue(celebrates(2026, 1, 1));
        assertTrue(celebrates(2026, 2, 16)); // New Year's Eve
        assertTrue(celebrates(2026, 2, 17)); // Lunar January 1
        assertTrue(celebrates(2026, 2, 26)); // Lunar January 10
        assertTrue(celebrates(2026, 10, 1)); // National Day
        assertFalse(celebrates(2026, 2, 15));
        assertFalse(celebrates(2026, 2, 27)); // Lunar January 11
        assertFalse(celebrates(2026, 10, 4));
        assertTrue(celebrates(2051, 1, 1)); // Solar date survives table limit.
        assertEquals("new_year", CATALOG.getFireworksHoliday(2026, 1, 1).getId());
        assertEquals("new_years_eve", CATALOG.getFireworksHoliday(2026, 2, 16).getId());
        assertEquals("spring_festival", CATALOG.getFireworksHoliday(2026, 2, 17).getId());
        assertEquals("spring_festival_days_2_to_10",
                CATALOG.getFireworksHoliday(2026, 2, 26).getId());
        // Both dates match on 2020-10-01: display Mid-Autumn, fire for National Day.
        assertEquals("mid_autumn", CATALOG.getDisplayHoliday(2020, 10, 1).getId());
        assertEquals("national_day", CATALOG.getFireworksHoliday(2020, 10, 1).getId());
    }

    @Test
    public void lunarNewYearsDayCelebratesEveryLaunchWithoutCapOrCooldown() {
        int day = HolidayFireworksPolicy.dayKey(2026, 2, 17);
        HolidayFireworksPolicy.Decision fourth = evaluate(2026, 2, 17,
                START + 1, day, 3, START);
        assertTrue(fourth.shouldCelebrate());
        assertEquals("spring_festival", fourth.getHoliday().getId());
        assertEquals(0, fourth.getMaxLaunchesPerDay());
        assertFalse(fourth.isCooldownEnabled());
        assertEquals(4, fourth.getCount());
        assertEquals(START + 1, fourth.getLastAtMillis());

        HolidayFireworksPolicy.Decision fifth = evaluate(2026, 2, 17,
                START + 2, fourth.getDayKey(), fourth.getCount(), fourth.getLastAtMillis());
        assertTrue(fifth.shouldCelebrate());
        assertEquals(5, fifth.getCount());

        HolidayFireworksPolicy.Decision saturated = evaluate(2026, 2, 17,
                START + 3, day, Integer.MAX_VALUE, START + 2);
        assertTrue(saturated.shouldCelebrate());
        assertEquals(Integer.MAX_VALUE, saturated.getCount());
    }

    @Test
    public void lunarDayTwoStillUsesCapAndCooldown() {
        int day = HolidayFireworksPolicy.dayKey(2026, 2, 18);
        HolidayFireworksPolicy.Decision cooling = evaluate(2026, 2, 18,
                START + TWO_HOURS_MS - 1, day, 1, START);
        assertFalse(cooling.shouldCelebrate());
        assertEquals(HolidayFireworksPolicy.Reason.COOLDOWN_ACTIVE, cooling.getReason());
        assertEquals("spring_festival_days_2_to_10", cooling.getHoliday().getId());

        HolidayFireworksPolicy.Decision capped = evaluate(2026, 2, 18,
                START + TWO_HOURS_MS, day, 3, START);
        assertFalse(capped.shouldCelebrate());
        assertEquals(HolidayFireworksPolicy.Reason.DAILY_LIMIT_REACHED, capped.getReason());
    }

    @Test
    public void allowsOnlyThreeLaunchesAfterTwoHourCooldown() {
        int day = HolidayFireworksPolicy.dayKey(2026, 1, 1);
        HolidayFireworksPolicy.Decision first = evaluate(2026, 1, 1, START, 0, 0, 0);
        assertTrue(first.shouldCelebrate());
        assertFalse(first.shouldDeletePreviousRecord());
        assertEquals(day, first.getDayKey());
        assertEquals(1, first.getCount());

        long secondAt = START + TWO_HOURS_MS;
        HolidayFireworksPolicy.Decision beforeSecond = evaluate(2026, 1, 1, secondAt - 1,
                first.getDayKey(), first.getCount(), first.getLastAtMillis());
        assertFalse(beforeSecond.shouldCelebrate());
        assertEquals(HolidayFireworksPolicy.Reason.COOLDOWN_ACTIVE, beforeSecond.getReason());
        assertEquals(1, beforeSecond.getCount());
        assertEquals(START, beforeSecond.getLastAtMillis());

        HolidayFireworksPolicy.Decision second = evaluate(2026, 1, 1, secondAt,
                first.getDayKey(), first.getCount(), first.getLastAtMillis());
        assertTrue(second.shouldCelebrate());
        assertEquals(2, second.getCount());

        long thirdAt = secondAt + TWO_HOURS_MS;
        HolidayFireworksPolicy.Decision beforeThird = evaluate(2026, 1, 1, thirdAt - 1,
                second.getDayKey(), second.getCount(), second.getLastAtMillis());
        assertFalse(beforeThird.shouldCelebrate());
        assertEquals(HolidayFireworksPolicy.Reason.COOLDOWN_ACTIVE, beforeThird.getReason());
        assertEquals(2, beforeThird.getCount());

        HolidayFireworksPolicy.Decision third = evaluate(2026, 1, 1, thirdAt,
                second.getDayKey(), second.getCount(), second.getLastAtMillis());
        assertTrue(third.shouldCelebrate());
        assertEquals(3, third.getCount());

        HolidayFireworksPolicy.Decision fourth = evaluate(2026, 1, 1, thirdAt + 1,
                third.getDayKey(), third.getCount(), third.getLastAtMillis());
        assertFalse(fourth.shouldCelebrate());
        assertEquals(HolidayFireworksPolicy.Reason.DAILY_LIMIT_REACHED, fourth.getReason());
        assertEquals(3, fourth.getCount());
    }

    @Test
    public void gapOverTwoHoursIsAllowedButClockRollbackIsNot() {
        int day = HolidayFireworksPolicy.dayKey(2026, 2, 16);
        HolidayFireworksPolicy.Decision later = evaluate(2026, 2, 16,
                START + TWO_HOURS_MS + 1, day, 1, START);
        assertTrue(later.shouldCelebrate());
        assertFalse(later.shouldDeletePreviousRecord());
        assertEquals(2, later.getCount());
        assertEquals(START + TWO_HOURS_MS + 1,
                later.getLastAtMillis());

        HolidayFireworksPolicy.Decision clockBack = evaluate(2026, 2, 16,
                START - 1, day, 1, START);
        assertFalse(clockBack.shouldCelebrate());
        assertEquals(HolidayFireworksPolicy.Reason.CLOCK_MOVED_BACKWARD, clockBack.getReason());
        assertEquals(1, clockBack.getCount());
    }

    @Test
    public void dateRolloverDeletesOldRecordEvenOnOrdinaryDay() {
        int newYearsDay = HolidayFireworksPolicy.dayKey(2026, 1, 1);
        HolidayFireworksPolicy.Decision ordinaryDay = evaluate(2026, 1, 2,
                START + 86_400_000L, newYearsDay, 2, START);
        assertFalse(ordinaryDay.shouldCelebrate());
        assertEquals(HolidayFireworksPolicy.Reason.NOT_HOLIDAY, ordinaryDay.getReason());
        assertTrue(ordinaryDay.shouldDeletePreviousRecord());
        assertEquals(0, ordinaryDay.getDayKey());
        assertEquals(0, ordinaryDay.getCount());
        assertEquals(0, ordinaryDay.getLastAtMillis());

        int newYearsEve = HolidayFireworksPolicy.dayKey(2026, 2, 16);
        HolidayFireworksPolicy.Decision lunarNewYear = evaluate(2026, 2, 17,
                START + 86_400_000L, newYearsEve, 3, START);
        assertTrue(lunarNewYear.shouldCelebrate());
        assertTrue(lunarNewYear.shouldDeletePreviousRecord());
        assertEquals(HolidayFireworksPolicy.dayKey(2026, 2, 17), lunarNewYear.getDayKey());
        assertEquals(1, lunarNewYear.getCount());
    }

    private static HolidayFireworksPolicy.Decision evaluate(int year, int month, int day,
                                                              long now, int storedDay,
                                                              int storedCount, long storedLast) {
        return HolidayFireworksPolicy.evaluate(CATALOG, year, month, day, now,
                storedDay, storedCount, storedLast);
    }

    private static boolean celebrates(int year, int month, int day) {
        return HolidayFireworksPolicy.isCelebrationDay(CATALOG, year, month, day);
    }
}

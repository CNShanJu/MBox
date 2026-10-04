package com.github.tvbox.osc.calendar;

import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Gregorian to Chinese lunar calendar conversion, based on the 1900–2049 year table.
 * The first supported Gregorian date is 1900-01-31. The final supported date is
 * the last day of lunar year 2049.
 */
public final class LunarCalendar {
    private static final int FIRST_YEAR = 1900;
    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final int[] YEAR_INFO = {
            0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
            0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
            0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
            0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
            0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
            0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5d0, 0x14573, 0x052d0, 0x0a9a8, 0x0e950, 0x06aa0,
            0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
            0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b5a0, 0x195a6,
            0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
            0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0,
            0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
            0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
            0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
            0x05aa0, 0x076a3, 0x096d0, 0x04bdb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
            0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0
    };
    private static final String[] MONTH_NAMES = {
            "正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊"
    };
    private static final String[] DAY_DIGITS = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
    private static final long BASE_DAY = gregorianEpochDay(1900, 1, 31);
    private static final long END_DAY_EXCLUSIVE;

    static {
        long days = BASE_DAY;
        for (int year = FIRST_YEAR; year < FIRST_YEAR + YEAR_INFO.length; year++) {
            days += daysInYear(year);
        }
        END_DAY_EXCLUSIVE = days;
    }

    private LunarCalendar() {
    }

    public static boolean supports(int year, int month, int day) {
        long epochDay = gregorianEpochDay(year, month, day);
        return epochDay >= BASE_DAY && epochDay < END_DAY_EXCLUSIVE;
    }

    public static LunarDate fromGregorian(int year, int month, int day) {
        long remaining = gregorianEpochDay(year, month, day) - BASE_DAY;
        if (remaining < 0 || remaining >= END_DAY_EXCLUSIVE - BASE_DAY) {
            throw new IllegalArgumentException("Gregorian date outside supported lunar calendar range");
        }

        int lunarYear = FIRST_YEAR;
        while (remaining >= daysInYear(lunarYear)) {
            remaining -= daysInYear(lunarYear);
            lunarYear++;
        }

        int leapMonth = leapMonth(lunarYear);
        for (int lunarMonth = 1; lunarMonth <= 12; lunarMonth++) {
            int regularDays = daysInMonth(lunarYear, lunarMonth, false);
            if (remaining < regularDays) {
                return new LunarDate(lunarYear, lunarMonth, (int) remaining + 1, false,
                        remaining + 1 == regularDays);
            }
            remaining -= regularDays;

            if (lunarMonth == leapMonth) {
                int leapDays = daysInMonth(lunarYear, lunarMonth, true);
                if (remaining < leapDays) {
                    return new LunarDate(lunarYear, lunarMonth, (int) remaining + 1, true,
                            remaining + 1 == leapDays);
                }
                remaining -= leapDays;
            }
        }
        throw new IllegalStateException("Invalid lunar calendar year table");
    }

    static long gregorianEpochDay(int year, int month, int day) {
        GregorianCalendar calendar = new GregorianCalendar(UTC, Locale.ROOT);
        calendar.setLenient(false);
        calendar.clear();
        calendar.set(year, month - 1, day);
        return calendar.getTimeInMillis() / MILLIS_PER_DAY;
    }

    private static int daysInYear(int year) {
        int days = 0;
        for (int month = 1; month <= 12; month++) {
            days += daysInMonth(year, month, false);
        }
        int leap = leapMonth(year);
        return leap == 0 ? days : days + daysInMonth(year, leap, true);
    }

    private static int daysInMonth(int year, int month, boolean leap) {
        int code = YEAR_INFO[year - FIRST_YEAR];
        return leap ? ((code & 0x10000) == 0 ? 29 : 30)
                : ((code & (0x10000 >> month)) == 0 ? 29 : 30);
    }

    private static int leapMonth(int year) {
        return YEAR_INFO[year - FIRST_YEAR] & 0x0f;
    }

    public static final class LunarDate {
        private final int year;
        private final int month;
        private final int day;
        private final boolean leapMonth;
        private final boolean lastDayOfMonth;

        private LunarDate(int year, int month, int day, boolean leapMonth, boolean lastDayOfMonth) {
            this.year = year;
            this.month = month;
            this.day = day;
            this.leapMonth = leapMonth;
            this.lastDayOfMonth = lastDayOfMonth;
        }

        public int getYear() { return year; }
        public int getMonth() { return month; }
        public int getDay() { return day; }
        public boolean isLeapMonth() { return leapMonth; }
        public boolean isLastDayOfMonth() { return lastDayOfMonth; }

        public String getDisplayText() {
            return "农历" + (leapMonth ? "闰" : "") + MONTH_NAMES[month - 1] + "月" + dayName(day);
        }

        private static String dayName(int day) {
            if (day == 10) return "初十";
            if (day == 20) return "二十";
            if (day == 30) return "三十";
            String prefix = day < 10 ? "初" : day < 20 ? "十" : "廿";
            return prefix + DAY_DIGITS[(day - 1) % 10];
        }
    }
}

package com.github.tvbox.osc.calendar;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Immutable display information for one Gregorian calendar day. */
public final class CalendarDayInfo {
    private static final String WEEKDAY_NAMES = "日一二三四五六";
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private final int year;
    private final int month;
    private final int day;
    private final String gregorianText;
    private final String lunarText;
    private final String festivalText;
    private final String holidayText;
    private final String secondaryText;

    private CalendarDayInfo(int year, int month, int day, String gregorianText,
                            String lunarText, String festivalText, String holidayText,
                            String secondaryText) {
        this.year = year;
        this.month = month;
        this.day = day;
        this.gregorianText = gregorianText;
        this.lunarText = lunarText;
        this.festivalText = festivalText;
        this.holidayText = holidayText;
        this.secondaryText = secondaryText;
    }

    /** Uses the date shown by the supplied calendar's own time zone. */
    public static CalendarDayInfo from(Calendar date) {
        return from(date, null);
    }

    /** Uses the supplied holiday catalog for festival names; null leaves them empty. */
    public static CalendarDayInfo from(Calendar date, HolidayCatalog catalog) {
        if (date == null) throw new IllegalArgumentException("date must not be null");
        return from(date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1,
                date.get(Calendar.DAY_OF_MONTH), catalog);
    }

    /** Month is one-based. Invalid Gregorian dates throw {@link IllegalArgumentException}. */
    public static CalendarDayInfo from(int year, int month, int day) {
        return from(year, month, day, null);
    }

    /** Month is one-based. Holidays come only from the supplied catalog. */
    public static CalendarDayInfo from(int year, int month, int day, HolidayCatalog catalog) {
        GregorianCalendar gregorian = new GregorianCalendar(UTC, Locale.ROOT);
        gregorian.setLenient(false);
        gregorian.clear();
        gregorian.set(year, month - 1, day);
        int weekday = gregorian.get(Calendar.DAY_OF_WEEK);
        String dateText = month + "月" + day + "日 周" + WEEKDAY_NAMES.charAt(weekday - 1);

        LunarCalendar.LunarDate lunar = LunarCalendar.supports(year, month, day)
                ? LunarCalendar.fromGregorian(year, month, day) : null;
        String lunarText = lunar == null ? null : lunar.getDisplayText();
        String holiday = null;
        if (catalog != null) {
            List<HolidayCatalog.Entry> displayed = catalog.getDisplayHolidays(year, month, day);
            if (!displayed.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (HolidayCatalog.Entry entry : displayed) {
                    if (names.length() > 0) names.append('·');
                    names.append(entry.getName());
                }
                holiday = names.toString();
            }
        }
        String festival = holiday;
        String secondary = lunarText == null ? "农历日期暂不可用"
                : festival == null ? lunarText : lunarText + "·" + festival;
        return new CalendarDayInfo(year, month, day, dateText, lunarText, festival, holiday, secondary);
    }

    public int getYear() { return year; }
    public int getMonth() { return month; }
    public int getDay() { return day; }
    public String getGregorianText() { return gregorianText; }
    /** Returns {@code null} outside the lunar year table's supported range. */
    public String getLunarText() { return lunarText; }
    /** Returns all catalog display names joined by a middle dot, including solar terms. */
    public String getFestivalText() { return festivalText; }
    /** Returns all catalog display names joined by a middle dot, including solar terms. */
    public String getHolidayText() { return holidayText; }
    public String getSecondaryText() { return secondaryText; }
}

package com.github.tvbox.osc.calendar;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/** Calculates the 24 solar terms as calendar dates in China Standard Time. */
public final class SolarTermCalculator {
    private static final String[] NAMES = {
            "小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
            "立夏", "小满", "芒种", "夏至", "小暑", "大暑", "立秋", "处暑",
            "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"
    };
    private static final TimeZone CHINA = TimeZone.getTimeZone("Asia/Shanghai");
    private static final double JULIAN_DAY_AT_UNIX_EPOCH = 2440587.5;
    private static final double MILLIS_PER_DAY = 86_400_000.0;
    private static final double TERM_INTERVAL_DAYS = 365.2422 / 24.0;

    private SolarTermCalculator() {
    }

    /** Returns the term on this date, or {@code null} if the date has no solar term. */
    public static String getTermName(int year, int month, int day) {
        if (year < 1900 || year > 2100) return null;
        if (month < 1 || month > 12) throw new IllegalArgumentException("month must be 1–12");
        for (int index = (month - 1) * 2; index < month * 2; index++) {
            if (termDayOfMonth(year, index) == day) return NAMES[index];
        }
        return null;
    }

    private static int termDayOfMonth(int year, int index) {
        double targetLongitude = normalizeDegrees(285.0 + index * 15.0);
        GregorianCalendar estimateDate = new GregorianCalendar(CHINA, Locale.CHINA);
        estimateDate.clear();
        estimateDate.set(year, Calendar.JANUARY, 5, 12, 0, 0);
        long estimate = estimateDate.getTimeInMillis()
                + Math.round(index * TERM_INTERVAL_DAYS * MILLIS_PER_DAY);
        long low = estimate - 7L * 86_400_000L;
        long high = estimate + 7L * 86_400_000L;

        if (differenceAt(low, year, targetLongitude) >= 0
                || differenceAt(high, year, targetLongitude) <= 0) {
            throw new IllegalStateException("Cannot bracket solar term " + year + " " + NAMES[index]);
        }
        while (high - low > 1000L) {
            long middle = low + (high - low) / 2;
            if (differenceAt(middle, year, targetLongitude) < 0) low = middle;
            else high = middle;
        }
        GregorianCalendar chinaDate = new GregorianCalendar(CHINA, Locale.CHINA);
        chinaDate.setTimeInMillis(low + (high - low) / 2);
        return chinaDate.get(Calendar.DAY_OF_MONTH);
    }

    private static double differenceAt(long epochMillis, int year, double targetLongitude) {
        double julianDay = epochMillis / MILLIS_PER_DAY + JULIAN_DAY_AT_UNIX_EPOCH;
        double ephemerisDay = julianDay + deltaTSeconds(year) / 86_400.0;
        double difference = normalizeDegrees(apparentSolarLongitude(ephemerisDay) - targetLongitude);
        return difference > 180.0 ? difference - 360.0 : difference;
    }

    /** Meeus apparent solar longitude, with TT–UT approximated by delta T. */
    private static double apparentSolarLongitude(double ephemerisDay) {
        double t = (ephemerisDay - 2451545.0) / 36525.0;
        double meanLongitude = normalizeDegrees(280.46646 + 36000.76983 * t + 0.0003032 * t * t);
        double meanAnomaly = normalizeDegrees(357.52911 + 35999.05029 * t
                - 0.0001537 * t * t + t * t * t / 24490000.0);
        double radians = Math.toRadians(meanAnomaly);
        double center = (1.914602 - 0.004817 * t - 0.000014 * t * t) * Math.sin(radians)
                + (0.019993 - 0.000101 * t) * Math.sin(2 * radians)
                + 0.000289 * Math.sin(3 * radians);
        double omega = Math.toRadians(125.04 - 1934.136 * t);
        return normalizeDegrees(meanLongitude + center - 0.00569 - 0.00478 * Math.sin(omega));
    }

    private static double deltaTSeconds(int year) {
        double y = year + 0.5;
        if (y < 1920) {
            double t = y - 1900;
            return -2.79 + 1.494119 * t - 0.0598939 * t * t
                    + 0.0061966 * t * t * t - 0.000197 * t * t * t * t;
        }
        if (y < 1941) {
            double t = y - 1920;
            return 21.20 + 0.84493 * t - 0.076100 * t * t + 0.0020936 * t * t * t;
        }
        if (y < 1961) {
            double t = y - 1950;
            return 29.07 + 0.407 * t - t * t / 233.0 + t * t * t / 2547.0;
        }
        if (y < 1986) {
            double t = y - 1975;
            return 45.45 + 1.067 * t - t * t / 260.0 - t * t * t / 718.0;
        }
        if (y < 2005) {
            double t = y - 2000;
            return 63.86 + 0.3345 * t - 0.060374 * t * t
                    + 0.0017275 * Math.pow(t, 3) + 0.000651814 * Math.pow(t, 4)
                    + 0.00002373599 * Math.pow(t, 5);
        }
        if (y < 2050) {
            double t = y - 2000;
            return 62.92 + 0.32217 * t + 0.005589 * t * t;
        }
        double u = (y - 1820) / 100.0;
        return -20.0 + 32.0 * u * u - 0.5628 * (2150.0 - y);
    }

    private static double normalizeDegrees(double degrees) {
        double result = degrees % 360.0;
        return result < 0 ? result + 360.0 : result;
    }
}

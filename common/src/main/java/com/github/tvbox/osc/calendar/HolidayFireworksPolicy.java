package com.github.tvbox.osc.calendar;

/** Launch-count and cooldown decisions using the current holiday catalog. */
public final class HolidayFireworksPolicy {
    private static final long MILLIS_PER_HOUR = 60L * 60 * 1000;

    public enum Reason {
        ALLOWED, NOT_HOLIDAY, DAILY_LIMIT_REACHED, COOLDOWN_ACTIVE, CLOCK_MOVED_BACKWARD
    }

    private HolidayFireworksPolicy() {
    }

    /**
     * Evaluates one genuine app start against the device's local calendar date.
     * The caller records an allowed claim before animating and rolls it back if
     * the animation cannot start. A stale record is deleted even on ordinary days.
     */
    public static Decision evaluate(HolidayCatalog catalog, int year, int month, int day,
                                    long nowMillis, int storedDayKey, int storedCount,
                                    long storedLastAtMillis) {
        if (catalog == null) throw new IllegalArgumentException("holiday catalog must not be null");
        int todayKey = dayKey(year, month, day);
        boolean staleRecord = storedDayKey != 0 && storedDayKey != todayKey;
        HolidayCatalog.Entry holiday = catalog.getFireworksHoliday(year, month, day);
        if (holiday == null) {
            return staleRecord ? new Decision(Reason.NOT_HOLIDAY, null, true, 0, 0, 0)
                    : new Decision(Reason.NOT_HOLIDAY, null, false,
                    storedDayKey, storedCount, storedLastAtMillis);
        }

        if (storedDayKey != todayKey || storedCount <= 0) {
            return new Decision(Reason.ALLOWED, holiday, staleRecord, todayKey, 1, nowMillis);
        }
        HolidayCatalog.Fireworks config = holiday.getFireworks();
        int limit = config.getMaxLaunchesPerDay();
        if (limit > 0 && storedCount >= limit) {
            return new Decision(Reason.DAILY_LIMIT_REACHED, holiday, false,
                    storedDayKey, storedCount, storedLastAtMillis);
        }
        if (config.isCooldownEnabled()) {
            if (nowMillis < storedLastAtMillis) {
                return new Decision(Reason.CLOCK_MOVED_BACKWARD, holiday, false,
                        storedDayKey, storedCount, storedLastAtMillis);
            }
            long interval = config.getCooldownHours() * MILLIS_PER_HOUR;
            if (nowMillis - storedLastAtMillis < interval) {
                return new Decision(Reason.COOLDOWN_ACTIVE, holiday, false,
                        storedDayKey, storedCount, storedLastAtMillis);
            }
        }
        int nextCount = storedCount == Integer.MAX_VALUE ? storedCount : storedCount + 1;
        return new Decision(Reason.ALLOWED, holiday, false, todayKey, nextCount, nowMillis);
    }

    /** Year-month-day key for a local civil date, independent of the current time zone. */
    public static int dayKey(int year, int month, int day) {
        LunarCalendar.gregorianEpochDay(year, month, day); // Validates Gregorian dates.
        return year * 10_000 + month * 100 + day;
    }

    public static boolean isCelebrationDay(HolidayCatalog catalog, int year, int month, int day) {
        if (catalog == null) throw new IllegalArgumentException("holiday catalog must not be null");
        return catalog.getFireworksHoliday(year, month, day) != null;
    }

    public static final class Decision {
        private final Reason reason;
        private final HolidayCatalog.Entry holiday;
        private final boolean shouldDeletePreviousRecord;
        private final int dayKey;
        private final int count;
        private final long lastAtMillis;

        private Decision(Reason reason, HolidayCatalog.Entry holiday, boolean shouldDeletePreviousRecord,
                         int dayKey, int count, long lastAtMillis) {
            this.reason = reason;
            this.holiday = holiday;
            this.shouldDeletePreviousRecord = shouldDeletePreviousRecord;
            this.dayKey = dayKey;
            this.count = count;
            this.lastAtMillis = lastAtMillis;
        }

        public boolean shouldCelebrate() { return reason == Reason.ALLOWED; }
        public Reason getReason() { return reason; }
        public HolidayCatalog.Entry getHoliday() { return holiday; }
        public int getMaxLaunchesPerDay() {
            return holiday == null ? 0 : holiday.getFireworks().getMaxLaunchesPerDay();
        }
        public int getCooldownHours() {
            return holiday == null ? 0 : holiday.getFireworks().getCooldownHours();
        }
        public boolean isCooldownEnabled() {
            return holiday != null && holiday.getFireworks().isCooldownEnabled();
        }
        public boolean shouldDeletePreviousRecord() { return shouldDeletePreviousRecord; }
        public int getDayKey() { return dayKey; }
        public int getCount() { return count; }
        public long getLastAtMillis() { return lastAtMillis; }
    }
}

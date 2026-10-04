package com.github.tvbox.osc.config;

/** Private persisted launch count for the current holiday; callers decide eligibility. */
public final class HolidayFireworksConfig {
    private static final String KEY = "_private_holiday_fireworks_launch_v1";

    private HolidayFireworksConfig() {
    }

    public static Record read() {
        Record saved = PrefsDataStore.getJson(KEY, Record.class, null);
        return saved == null ? new Record(0, 0, 0L) : saved;
    }

    public static void save(int dayKey, int count, long lastAtMillis) {
        PrefsDataStore.putJson(KEY, new Record(dayKey, count, lastAtMillis));
    }

    public static void clear() {
        PrefsDataStore.delete(KEY);
    }

    public static final class Record {
        private final int dayKey;
        private final int count;
        private final long lastAtMillis;

        private Record(int dayKey, int count, long lastAtMillis) {
            this.dayKey = dayKey;
            this.count = count;
            this.lastAtMillis = lastAtMillis;
        }

        public int getDayKey() { return dayKey; }
        public int getCount() { return count; }
        public long getLastAtMillis() { return lastAtMillis; }
    }
}

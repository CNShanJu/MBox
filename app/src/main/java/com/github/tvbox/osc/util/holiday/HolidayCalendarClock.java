package com.github.tvbox.osc.util.holiday;

import java.util.Calendar;

/** Device local date and time shared by all build variants. */
public final class HolidayCalendarClock {
    private HolidayCalendarClock() {
    }

    public static Calendar now() {
        return Calendar.getInstance();
    }
}

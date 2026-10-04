package com.github.tvbox.osc.util.holiday;

import com.github.tvbox.osc.calendar.HolidayCatalog;
import com.github.tvbox.osc.calendar.HolidayFireworksPolicy;
import com.github.tvbox.osc.config.HolidayFireworksConfig;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;

import java.util.Calendar;

/** Coordinates one holiday launch with the persisted daily record. Call on a shared executor. */
public final class HolidayFireworksLaunchCoordinator {
    private static final Object LOCK = new Object();
    private static Claim pending;

    private HolidayFireworksLaunchCoordinator() {
    }

    /** Runs on every fresh app start, including an ordinary day without fireworks. */
    public static void clearPreviousDay() {
        synchronized (LOCK) {
            Calendar today = HolidayCalendarClock.now();
            int todayKey = HolidayFireworksPolicy.dayKey(today.get(Calendar.YEAR),
                    today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH));
            HolidayFireworksConfig.Record saved = HolidayFireworksConfig.read();
            if (saved.getDayKey() == 0 || saved.getDayKey() == todayKey) return;
            HolidayFireworksConfig.clear();
            LogStore.log(Category.SYSTEM, "节日烟花: 跨日清理记录，旧日期=" + saved.getDayKey()
                    + "，旧次数=" + saved.getCount() + "，今日=" + todayKey);
        }
    }

    /** 检查资格并在进程内占位；动画实际启动前不消耗持久化次数。 */
    public static Claim claim(HolidayCatalog catalog) {
        synchronized (LOCK) {
            if (pending != null) {
                LogStore.log(Category.SYSTEM, "节日烟花: 已有启动检查在途，本次不重复发射");
                return null;
            }
            if (catalog == null) {
                LogStore.log(Category.SYSTEM, "节日烟花: 本次启动未发射，节日目录不可用");
                return null;
            }
            Calendar now = HolidayCalendarClock.now();
            int year = now.get(Calendar.YEAR);
            int month = now.get(Calendar.MONTH) + 1;
            int day = now.get(Calendar.DAY_OF_MONTH);
            long nowMillis = now.getTimeInMillis();
            HolidayFireworksConfig.Record previous = HolidayFireworksConfig.read();
            HolidayFireworksPolicy.Decision decision = HolidayFireworksPolicy.evaluate(
                    catalog, year, month, day, nowMillis, previous.getDayKey(), previous.getCount(),
                    previous.getLastAtMillis());
            if (decision.shouldDeletePreviousRecord()) {
                HolidayFireworksConfig.clear();
                LogStore.log(Category.SYSTEM, "节日烟花: 跨日清理记录，旧日期=" + previous.getDayKey()
                        + "，旧次数=" + previous.getCount() + "，今日="
                        + HolidayFireworksPolicy.dayKey(year, month, day));
                previous = HolidayFireworksConfig.read();
            }
            if (!decision.shouldCelebrate()) {
                if (decision.getHoliday() != null) {
                    LogStore.log(Category.SYSTEM, "节日烟花: 本次启动未发射，节日="
                            + holidayName(decision) + "，日期=" + decision.getDayKey()
                            + "，已发射=" + decision.getCount() + "，上次时间="
                            + decision.getLastAtMillis() + "，本次时间=" + nowMillis
                            + "，上限=" + decision.getMaxLaunchesPerDay()
                            + "，冷却小时=" + decision.getCooldownHours()
                            + "，原因=" + decision.getReason());
                }
                return null;
            }
            pending = new Claim(decision, previous);
            LogStore.log(Category.SYSTEM, "节日烟花: 准备发射，节日="
                    + holidayName(decision) + "，日期=" + decision.getDayKey()
                    + "，" + roundName(decision));
            return pending;
        }
    }

    /** A queued UI callback must not fire yesterday's claim after local midnight. */
    public static boolean isClaimForDate(Claim claim, Calendar localDate) {
        return claim != null && claim.decision.getDayKey() == HolidayFireworksPolicy.dayKey(
                localDate.get(Calendar.YEAR), localDate.get(Calendar.MONTH) + 1,
                localDate.get(Calendar.DAY_OF_MONTH));
    }

    /** 只有动画实际启动才持久化本轮；视图不可用仅释放进程内占位。 */
    public static void finish(Claim claim, boolean animationStarted, long fireAtMillis,
                              int groupCount) {
        if (claim == null) return;
        HolidayFireworksPolicy.Decision decision = claim.decision;
        synchronized (LOCK) {
            if (pending != claim) return;
            pending = null;
            if (animationStarted) {
                HolidayFireworksConfig.Record current = HolidayFireworksConfig.read();
                if (current.getDayKey() == claim.previous.getDayKey()
                        && current.getCount() == claim.previous.getCount()
                        && current.getLastAtMillis() == claim.previous.getLastAtMillis()) {
                    HolidayFireworksConfig.save(decision.getDayKey(), decision.getCount(),
                            Math.max(fireAtMillis, decision.getLastAtMillis()));
                } else {
                    LogStore.fail(Category.SYSTEM, "节日烟花: 发射后记录已变化，未覆盖新记录，日期="
                            + decision.getDayKey());
                }
                LogStore.log(Category.SYSTEM, "节日烟花: 错峰启动" + groupCount + "组"
                        + (groupCount * 3) + "朵烟花，节日="
                        + holidayName(decision) + "，日期=" + decision.getDayKey()
                        + "，" + roundName(decision) + "，发射时间=" + fireAtMillis);
            } else {
                LogStore.log(Category.SYSTEM, "节日烟花: 视图不可用，撤销进程内发射占位，日期="
                        + decision.getDayKey());
            }
        }
    }

    private static String holidayName(HolidayFireworksPolicy.Decision decision) {
        HolidayCatalog.Entry holiday = decision.getHoliday();
        return holiday == null ? "无" : holiday.getName() + "(" + holiday.getId() + ")";
    }

    private static String roundName(HolidayFireworksPolicy.Decision decision) {
        return "第" + decision.getCount() + (decision.getMaxLaunchesPerDay() == 0
                ? "轮（不限次数）" : "/" + decision.getMaxLaunchesPerDay() + "轮");
    }

    public static final class Claim {
        private final HolidayFireworksPolicy.Decision decision;
        private final HolidayFireworksConfig.Record previous;

        private Claim(HolidayFireworksPolicy.Decision decision, HolidayFireworksConfig.Record previous) {
            this.decision = decision;
            this.previous = previous;
        }
    }
}

package com.github.tvbox.osc.ui.startup;

/** 本次主页创建的来源。只有启动器里用户主动打开 App 才执行启动专属动作。 */
public enum AppLaunchSource {
    USER_LAUNCH,
    INTERNAL_RESTART,
    NOTIFICATION_ENTRY,
    ACTIVITY_RECREATION,
    SYSTEM_RESTART;

    /** 通知跳转虽然经过 SplashActivity，也不是一次用户从启动器打开。 */
    public static final String EXTRA_NON_USER_ENTRY = "com.github.tvbox.osc.NON_USER_ENTRY";
    /** Same-process reload enters the exported splash with no launcher action. */
    public static final String EXTRA_INTERNAL_RELOAD = "com.github.tvbox.osc.INTERNAL_RELOAD";

    public static AppLaunchSource resolve(boolean fromStartupPage, boolean activityRecreated,
                                          boolean internalRestart, boolean nonUserEntry) {
        if (internalRestart) return INTERNAL_RESTART;
        if (activityRecreated) return ACTIVITY_RECREATION;
        if (nonUserEntry) return NOTIFICATION_ENTRY;
        if (!fromStartupPage) return SYSTEM_RESTART;
        return USER_LAUNCH;
    }

    public static AppLaunchSource resolve(boolean fromStartupPage, boolean activityRecreated,
                                          boolean internalRestart) {
        return resolve(fromStartupPage, activityRecreated, internalRestart, false);
    }

    public boolean allowsStartupActions() {
        return this == USER_LAUNCH;
    }
}

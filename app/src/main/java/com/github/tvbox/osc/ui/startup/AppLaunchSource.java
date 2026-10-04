package com.github.tvbox.osc.ui.startup;

/** 本次主页创建的来源。只有用户经启动页主动打开 App 才执行启动专属动作。 */
public enum AppLaunchSource {
    USER_LAUNCH,
    INTERNAL_RESTART,
    OTHER;

    public static AppLaunchSource resolve(boolean fromStartupPage, boolean activityRecreated,
                                          boolean internalRestart) {
        if (internalRestart) return INTERNAL_RESTART;
        if (activityRecreated || !fromStartupPage) return OTHER;
        return USER_LAUNCH;
    }

    public boolean allowsStartupActions() {
        return this == USER_LAUNCH;
    }
}

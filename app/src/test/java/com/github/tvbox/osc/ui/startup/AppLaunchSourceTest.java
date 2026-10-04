package com.github.tvbox.osc.ui.startup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppLaunchSourceTest {

    @Test
    public void launcherEntryRunsStartupActionsOnlyWithoutInternalRestart() {
        AppLaunchSource user = AppLaunchSource.resolve(true, false, false);
        assertEquals(AppLaunchSource.USER_LAUNCH, user);
        assertTrue(user.allowsStartupActions());

        AppLaunchSource themeOrLanRestart = AppLaunchSource.resolve(true, false, true);
        assertEquals(AppLaunchSource.INTERNAL_RESTART, themeOrLanRestart);
        assertFalse(themeOrLanRestart.allowsStartupActions());
    }

    @Test
    public void taskReloadAndSystemRestoreDoNotRunStartupActions() {
        assertEquals(AppLaunchSource.INTERNAL_RESTART,
                AppLaunchSource.resolve(false, false, true));
        assertEquals(AppLaunchSource.OTHER,
                AppLaunchSource.resolve(true, true, false));
        assertEquals(AppLaunchSource.OTHER,
                AppLaunchSource.resolve(false, false, false));
        assertFalse(AppLaunchSource.resolve(true, true, false).allowsStartupActions());
    }
}

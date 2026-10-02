package com.github.tvbox.osc.ui.dialog;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DialogStyleTest {

    @Test
    public void phonePortraitKeepsExistingCenterWidth() {
        assertEquals(320, DialogStyle.centerWidthDp(393, false, false));
        assertEquals(248, DialogStyle.centerWidthDp(280, false, false));
    }

    @Test
    public void tabletPortraitUsesWindowWidthUpToReadableCap() {
        assertEquals(432, DialogStyle.centerWidthDp(600, true, false));
        assertEquals(560, DialogStyle.centerWidthDp(800, true, false));
        assertEquals(560, DialogStyle.centerWidthDp(1200, true, false));
    }

    @Test
    public void landscapePhoneAndNarrowWindowStayCompact() {
        assertEquals(360, DialogStyle.centerWidthDp(850, false, true));
        assertEquals(320, DialogStyle.centerWidthDp(400, false, false));
    }

    @Test
    public void bottomSheetWidensOnTabletButKeepsPhoneBehavior() {
        assertEquals(393, DialogStyle.bottomWidthDp(393, false, false));
        assertEquals(440, DialogStyle.bottomWidthDp(800, false, true));
        assertEquals(656, DialogStyle.bottomWidthDp(800, true, false));
        assertEquals(720, DialogStyle.bottomWidthDp(1200, true, true));
    }

    @Test
    public void rightDrawerAlsoUsesTabletWindowWidth() {
        assertEquals(300, DialogStyle.drawerWidthDp(393, false, 300));
        assertEquals(400, DialogStyle.drawerWidthDp(800, true, 300));
        assertEquals(560, DialogStyle.drawerWidthDp(1200, true, 320));
    }
}

package com.github.tvbox.osc.bean.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.storage.theme.ThemeBackgroundLibrary;

import org.junit.Test;

public class ThemeBackgroundReferenceTest {
    @Test
    public void onlyDirectThemeLibraryImagesAreAccepted() {
        assertTrue(ThemeBackgroundLibrary.isSafeRef("theme_bg/0123abcd.webp"));
        assertFalse(ThemeBackgroundLibrary.isSafeRef("theme_bg/../outside.webp"));
        assertFalse(ThemeBackgroundLibrary.isSafeRef("theme_bg/nested/image.webp"));
        assertFalse(ThemeBackgroundLibrary.isSafeRef("theme_bg\\image.webp"));
        assertFalse(ThemeBackgroundLibrary.isSafeRef("/private/theme_bg/image.webp"));
        assertFalse(ThemeBackgroundLibrary.isSafeRef("file:///android_asset/theme/backgrounds/image.png"));
    }

    @Test
    public void cachedSplashImageRemainsReferencedInSolidMode() {
        ThemeDef def = new ThemeDef();
        def.getSplashBackground().setMode(ThemeDef.SplashBackground.MODE_SOLID);
        def.getSplashBackground().setRef("theme_bg/0123abcd.webp");
        assertEquals("0123abcd.webp", ThemeBackgroundLibrary.splashRefOf(def));
    }
}

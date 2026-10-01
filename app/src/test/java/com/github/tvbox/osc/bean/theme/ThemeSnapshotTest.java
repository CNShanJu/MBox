package com.github.tvbox.osc.bean.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class ThemeSnapshotTest {

    @Test
    public void builtinAndCustomBrightDarkSnapshotsAreCompleteAndDistinct() {
        ThemeSnapshot builtinBright = snapshot(ThemeType.BRIGHT, false, 0xFFF8F8F8, 12f);
        ThemeSnapshot builtinDark = snapshot(ThemeType.DARK, false, 0xFF101010, 12f);
        ThemeSnapshot customBright = snapshot(ThemeType.BRIGHT, true, 0xFFFFFFFF, 16f);
        ThemeSnapshot customDark = snapshot(ThemeType.DARK, true, 0xFF000000, 17f);
        ThemeSnapshot[] snapshots = {builtinBright, builtinDark, customBright, customDark};
        for (ThemeSnapshot snapshot : snapshots) {
            assertNotNull(snapshot.colors);
            assertNotNull(snapshot.shapes);
            assertFalse(snapshot.fingerprint.isEmpty());
        }
        assertNotEquals(builtinBright.fingerprint, builtinDark.fingerprint);
        assertNotEquals(builtinBright.fingerprint, customBright.fingerprint);
        assertNotEquals(customBright.fingerprint, customDark.fingerprint);
    }

    private static ThemeSnapshot snapshot(ThemeType type, boolean custom, int body, float radius) {
        Map<String, Integer> colors = new LinkedHashMap<>();
        colors.put("bg_body", body);
        Map<String, Float> radii = new LinkedHashMap<>();
        radii.put(ThemeShapePalette.RADIUS_WIDGET_BTN, radius);
        return new ThemeSnapshot(new ThemeColorPalette(colors),
                new ThemeShapePalette(radii, null), type, custom, custom ? "custom" : "builtin");
    }
}

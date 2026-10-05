package com.github.tvbox.osc.ui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SliderTrackColorTest {
    // Actual built-in brand, bg_surface RGB, and bg_body colors. Tests cover card (72%)
    // and floating (96%) panels with their runtime alpha bytes, 184 and 245 respectively.
    private static final int[][] BUILTIN_THEMES = {
            {0x801F2937, 0xF5F1F1F9, 0xFFFAF8FF}, // bright/default
            {0x801F2937, 0xF5F1F1F9, 0xFFFAF8FF}, // bright/lotus
            {0x80F2F2F9, 0xF51A1A1A, 0xFF141414}, // dark/default
            {0x806671E5, 0xF51E2030, 0xFF222436}, // dark/moonlight
            {0x80FFFFFF, 0xF53E3A75, 0xFF2E2368}  // dark/purple
    };

    @Test
    public void builtinCardAndFloatingPanelsReachThreeToOneWithoutChangingRgbOrReducingOpacity() {
        for (int[] theme : BUILTIN_THEMES) {
            for (int alpha : new int[]{184, 245}) {
                int panel = (alpha << 24) | (theme[1] & 0x00FFFFFF);
                int result = SliderTrackColor.visibleTrack(theme[0], panel, theme[2]);
                assertEquals(theme[0] & 0x00FFFFFF, result & 0x00FFFFFF);
                assertTrue((result >>> 24) >= (theme[0] >>> 24));
                assertTrue(renderedContrast(result, panel, theme[2]) >= 3.0);
                assertMinimalIncrease(theme[0], result, panel, theme[2]);
            }
        }
    }

    @Test
    public void moonlightNeedsMoreThanEightyOnePercentOpacity() {
        int[] moonlight = BUILTIN_THEMES[3];
        assertTrue(renderedContrast(moonlight[0], moonlight[1], moonlight[2]) < 2.0);
        int result = SliderTrackColor.visibleTrack(moonlight[0], moonlight[1], moonlight[2]);
        assertEquals(208, result >>> 24); // 81.57%; byte 207 is still below 3:1.
        assertTrue(renderedContrast(result, moonlight[1], moonlight[2]) >= 3.0);
        assertMinimalIncrease(moonlight[0], result, moonlight[1], moonlight[2]);
    }

    @Test
    public void qualifyingDarkThemeRetainsRequestedHalfOpacity() {
        int[] dark = BUILTIN_THEMES[2];
        assertTrue(renderedContrast(dark[0], dark[1], dark[2]) >= 3.0);
        assertEquals(dark[0], SliderTrackColor.visibleTrack(dark[0], dark[1], dark[2]));
    }

    @Test
    public void transparentPanelUsesPageRgbRegardlessOfPageAlpha() {
        int requested = 0x80555555;
        int expected = SliderTrackColor.visibleTrack(requested, 0x00FF0000, 0xFFFFFFFF);
        assertEquals(expected, SliderTrackColor.visibleTrack(requested, 0x00000000, 0x00FFFFFF));
        assertEquals(expected, SliderTrackColor.visibleTrack(requested, 0x0000FF00, 0x7FFFFFFF));
        assertTrue(renderedContrast(expected, 0x00FF0000, 0xFFFFFFFF) >= 3.0);
    }

    @Test
    public void translucentPanelIsCompositedBeforeChoosingOpacity() {
        int requested = 0x80FFFFFF;
        int panel = 0x80000000;
        int page = 0xFFFFFFFF;
        int result = SliderTrackColor.visibleTrack(requested, panel, page);
        // Half black over white renders as #7F7F7F with integer ARGB compositing.
        assertEquals(SliderTrackColor.visibleTrack(requested, 0xFF7F7F7F, page), result);
        assertTrue((result >>> 24) > 128);
        assertTrue(renderedContrast(result, panel, page) >= 3.0);
        assertEquals(requested, SliderTrackColor.visibleTrack(requested, 0xFF000000, page));
        assertMinimalIncrease(requested, result, panel, page);
    }

    @Test
    public void insufficientFullColorRemainsInThemeRgbAtFullOpacity() {
        int requested = 0x80777777;
        int result = SliderTrackColor.visibleTrack(requested, 0xFF808080, 0xFF000000);
        assertEquals(0xFF777777, result);
        assertTrue(renderedContrast(result, 0xFF808080, 0xFF000000) < 3.0);
        assertEquals(0xFF808080,
                SliderTrackColor.visibleTrack(0x00808080, 0xFF808080, 0xFFFFFFFF));
    }

    @Test
    public void zeroAndFullAlphaBoundariesPreserveRgbAndFindFirstQualifyingByte() {
        int result = SliderTrackColor.visibleTrack(0x00FFFFFF, 0xFF000000, 0xFF000000);
        assertEquals(0x00FFFFFF, result & 0x00FFFFFF);
        assertTrue((result >>> 24) > 0);
        assertTrue((result >>> 24) < 255);
        assertTrue(renderedContrast(result, 0xFF000000, 0xFF000000) >= 3.0);
        assertMinimalIncrease(0x00FFFFFF, result, 0xFF000000, 0xFF000000);
        assertEquals(0xFFFFFFFF,
                SliderTrackColor.visibleTrack(0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF));
    }

    @Test
    public void opaqueContrastMatchesKnownBlackWhiteAndIdenticalValues() {
        assertEquals(21.0, SliderTrackColor.contrast(0xFFFFFFFF, 0xFF000000), 0.000001);
        assertEquals(1.0, SliderTrackColor.contrast(0xFF6671E5, 0xFF6671E5), 0.000001);
        assertEquals(3.877715,
                SliderTrackColor.contrast(0xFF6671E5, 0xFF1E2030), 0.000001);
    }

    private static void assertMinimalIncrease(int requested, int result, int panel, int page) {
        if ((result >>> 24) > (requested >>> 24)) {
            int previous = (((result >>> 24) - 1) << 24) | (result & 0x00FFFFFF);
            assertTrue(renderedContrast(previous, panel, page) < 3.0);
        }
    }

    private static double renderedContrast(int track, int panel, int page) {
        int background = referenceComposite(panel, page);
        return referenceContrast(referenceComposite(track, background), background);
    }

    private static int referenceComposite(int foreground, int opaqueBackground) {
        double opacity = (foreground >>> 24) / 255.0;
        int rgb = 0;
        for (int shift : new int[]{16, 8, 0}) {
            int source = (foreground >>> shift) & 255;
            int backdrop = (opaqueBackground >>> shift) & 255;
            // Integer ARGB compositing truncates each channel; epsilon avoids floating noise.
            int channel = (int) Math.floor(source * opacity + backdrop * (1 - opacity) + 1e-9);
            rgb |= channel << shift;
        }
        return 0xFF000000 | rgb;
    }

    private static double referenceContrast(int first, int second) {
        double firstLuminance = referenceLuminance(first);
        double secondLuminance = referenceLuminance(second);
        return (Math.max(firstLuminance, secondLuminance) + 0.05)
                / (Math.min(firstLuminance, secondLuminance) + 0.05);
    }

    private static double referenceLuminance(int color) {
        int[] shifts = {16, 8, 0};
        double[] weights = {0.2126, 0.7152, 0.0722};
        double result = 0;
        for (int i = 0; i < shifts.length; i++) {
            double channel = ((color >>> shifts[i]) & 255) / 255.0;
            double linear = channel <= 0.04045 ? channel / 12.92
                    : Math.pow((channel + 0.055) / 1.055, 2.4);
            result += weights[i] * linear;
        }
        return result;
    }
}

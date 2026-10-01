package com.github.tvbox.osc.bean.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ThemeShapePaletteTest {

    @Test
    public void widgetRadiusAcceptsDocumentedRangeWithoutSilentClamping() {
        for (float value : new float[]{0f, 12f, 16f, 17f}) {
            Map<String, Float> radii = new LinkedHashMap<>();
            radii.put(ThemeShapePalette.RADIUS_WIDGET_BTN, value);
            ThemeShapePalette palette = new ThemeShapePalette(radii, Collections.emptyMap());
            assertEquals(value, palette.radiusDp(ThemeShapePalette.RADIUS_WIDGET_BTN), 0f);
        }
        assertFalse(ThemeShapePalette.isValid(ThemeShapePalette.RADIUS_WIDGET_BTN, 17.01f));
    }

    @Test
    public void densityConversionKeepsDpMeaningStable() {
        Map<String, Float> radii = new LinkedHashMap<>();
        radii.put(ThemeShapePalette.RADIUS_BTN, 12f);
        Map<String, Float> strokes = new LinkedHashMap<>();
        strokes.put(ThemeShapePalette.STROKE_WIDGET_BTN, 0.5f);
        ThemeShapePalette palette = new ThemeShapePalette(radii, strokes);
        assertEquals(12f, palette.radiusPx(ThemeShapePalette.RADIUS_BTN, 1f), 0f);
        assertEquals(24f, palette.radiusPx(ThemeShapePalette.RADIUS_BTN, 2f), 0f);
        assertEquals(36f, palette.radiusPx(ThemeShapePalette.RADIUS_BTN, 3f), 0f);
        assertEquals(1.5f, palette.strokePx(ThemeShapePalette.STROKE_WIDGET_BTN, 3f), 0f);
    }

    /**
     * 非法值处理口径(2026-10-01 修订):
     * <ul>
     *   <li><b>超上限 → 夹到上限</b>:与构建期 {@code build.gradle#readRadii} 同一口径。
     *       原来这里是"回退默认值",于是同一个 chip 编译期轮廓 17dp、运行时 12dp,两套轮廓;</li>
     *   <li><b>负数 / NaN → 回默认</b>:这种值没有任何可用语义,回默认最安全。</li>
     * </ul>
     */
    @Test
    public void invalidValuesAreClampedOrFallBackToDefaults() {
        Map<String, Float> radii = new LinkedHashMap<>();
        radii.put(ThemeShapePalette.RADIUS_DIALOG, -1f);          // 负数 → 回默认
        radii.put(ThemeShapePalette.RADIUS_WIDGET_BTN, 99f);      // 超上限 → 夹到上限
        Map<String, Float> strokes = new LinkedHashMap<>();
        strokes.put(ThemeShapePalette.STROKE_WIDGET_BTN, Float.NaN); // NaN → 回默认
        ThemeShapePalette palette = new ThemeShapePalette(radii, strokes);
        assertEquals(16f, palette.radiusDp(ThemeShapePalette.RADIUS_DIALOG), 0f);
        assertEquals(ThemeShapePalette.maxOf(ThemeShapePalette.RADIUS_WIDGET_BTN),
                palette.radiusDp(ThemeShapePalette.RADIUS_WIDGET_BTN), 0f);
        assertEquals(0.5f, palette.strokeDp(ThemeShapePalette.STROKE_WIDGET_BTN), 0f);
        assertTrue(!palette.fingerprint().isEmpty());
    }
}

package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.util.AttributeSet;

import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.google.android.material.slider.Slider;

/**
 * Shared Material slider appearance for app settings.
 *
 * <p>Colors come from themed resources, including the runtime palette used by built-in
 * themes. Material's cached M3 theme attributes cannot supply every slider color when
 * a custom theme is active. This component changes presentation only; callers own the
 * value range, value, step size, and listeners.
 * Material styles and layout attributes continue to own the original geometry, ticks,
 * and drag label behavior.
 */
public class AppSlider extends Slider {

    private static final int[][] ENABLED_STATES = {
            new int[]{-android.R.attr.state_enabled},
            new int[]{}
    };

    @ColorRes
    private int panelColorResource = R.color.bg_float;

    public AppSlider(Context context) {
        this(context, null);
    }

    public AppSlider(Context context, AttributeSet attrs) {
        super(context, attrs);
        applyThemeColors();
    }

    public AppSlider(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        applyThemeColors();
    }

    /** The host names its panel color so translucent cards and dialogs use the right contrast. */
    public void setPanelColorResource(@ColorRes int colorResource) {
        panelColorResource = colorResource;
        applyThemeColors();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applyThemeColors();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyThemeColors();
    }

    private void applyThemeColors() {
        Context context = getContext();
        int brand = ContextCompat.getColor(context, R.color.text_foreground);
        int inactive = ContextCompat.getColor(context, R.color.text_main_half);
        int panel = ContextCompat.getColor(context, panelColorResource);
        int page = ContextCompat.getColor(context, R.color.bg_body);
        // Start at the theme's half-strength brand; weak palettes need more opacity to show a rail.
        inactive = SliderTrackColor.visibleTrack(inactive, panel, page);
        int disabled = ContextCompat.getColor(context, R.color.text_disable);
        int disabledInactive = ContextCompat.getColor(context, R.color.text_hint);
        int halo = ContextCompat.getColor(context, R.color.press_overlay);

        ColorStateList activeColors = states(brand, disabled);
        ColorStateList inactiveColors = states(inactive, disabledInactive);
        setTrackActiveTintList(activeColors);
        setTrackInactiveTintList(inactiveColors);
        setThumbTintList(activeColors);
        // Preserve contrasting tick dots on the original Material track instead of painting
        // them the same color as the filled rail.
        setTickActiveTintList(ColorStateList.valueOf(panel | 0xFF000000));
        setTickInactiveTintList(activeColors);
        setHaloTintList(states(halo, 0x00000000));
        SliderLabelTheme.apply(this, ContextCompat.getColor(context, R.color.bg_float), brand);
    }

    private static ColorStateList states(int enabled, int disabled) {
        return new ColorStateList(ENABLED_STATES, new int[]{disabled, enabled});
    }
}

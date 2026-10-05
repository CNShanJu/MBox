package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;

/** A read-only color swatch and its value. The parent supplies the picker click action. */
public class ThemeColorValueView extends LinearLayout {

    private final View swatchView;
    private final TextView valueView;
    private final GradientDrawable swatchDrawable;

    public ThemeColorValueView(Context context) {
        this(context, null);
    }

    public ThemeColorValueView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ThemeColorValueView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(dp(44));

        swatchDrawable = new GradientDrawable();
        swatchDrawable.setShape(GradientDrawable.OVAL);
        swatchDrawable.setColor(Color.TRANSPARENT);
        swatchView = new View(context);
        swatchView.setBackground(swatchDrawable);
        addView(swatchView, new LayoutParams(dp(24), dp(24)));

        valueView = new TextView(context);
        valueView.setGravity(Gravity.CENTER_VERTICAL);
        valueView.setSingleLine(true);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LayoutParams textParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        textParams.setMarginStart(dp(8));
        addView(valueView, textParams);

        refreshThemeColors();
    }

    public void setColor(int color) {
        swatchDrawable.setColor(color);
        swatchView.invalidate();
    }

    public void setValueText(CharSequence value) {
        valueView.setText(value);
    }

    public CharSequence getValueText() {
        return valueView.getText();
    }

    public TextView getValueView() {
        return valueView;
    }

    public View getSwatchView() {
        return swatchView;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshThemeColors();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshThemeColors();
    }

    private void refreshThemeColors() {
        swatchDrawable.setStroke(dp(1), ContextCompat.getColor(getContext(), R.color.btn_stroke));
        valueView.setTextColor(ContextCompat.getColor(getContext(), R.color.text_foreground));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

package com.github.tvbox.osc.ui.kit;

import android.content.res.ColorStateList;
import android.util.Log;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.google.android.material.resources.TextAppearance;
import com.google.android.material.slider.Slider;
import com.google.android.material.tooltip.TooltipDrawable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Colors the existing Material value labels without replacing their shape or overlay. */
final class SliderLabelTheme {

    private static final String TAG = "SliderLabelTheme";
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();
    private static volatile boolean labelsFieldResolved;
    private static Field labelsField;

    private SliderLabelTheme() {
    }

    static void apply(Slider slider, int fill, int text) {
        Field field = labelsField();
        if (field == null) return;
        try {
            Object pool = field.get(slider);
            if (!(pool instanceof List<?>)) {
                throw new IllegalStateException("Material slider labels are not a List");
            }
            ColorStateList fillColor = ColorStateList.valueOf(fill);
            ColorStateList textColor = ColorStateList.valueOf(text);
            for (Object item : (List<?>) pool) {
                if (!(item instanceof TooltipDrawable)) {
                    throw new IllegalStateException("Material slider label is not a TooltipDrawable");
                }
                TooltipDrawable label = (TooltipDrawable) item;
                TextAppearance appearance = label.getTextAppearance();
                if (appearance == null) {
                    throw new IllegalStateException("Material slider label has no TextAppearance");
                }
                label.setFillColor(fillColor);
                label.setStrokeColor(fillColor);
                appearance.setTextColor(textColor);
                // Material 1.9.0 drawText updates its TextPaint from this appearance on every
                // draw. Invalidating applies the color now while retaining font and text size.
                label.invalidateSelf();
            }
        } catch (IllegalAccessException | RuntimeException failure) {
            reportFailure(failure);
        }
    }

    private static Field labelsField() {
        if (!labelsFieldResolved) {
            synchronized (SliderLabelTheme.class) {
                if (!labelsFieldResolved) {
                    try {
                        // Pinned Material 1.9.0 has no public label tint API. Its BaseSlider
                        // owns this TooltipDrawable pool; keep the field name in R8 rules.
                        Field field = Slider.class.getSuperclass().getDeclaredField("labels");
                        if (!List.class.isAssignableFrom(field.getType())) {
                            throw new IllegalStateException("Material slider labels field changed type");
                        }
                        field.setAccessible(true);
                        labelsField = field;
                    } catch (NoSuchFieldException | RuntimeException failure) {
                        reportFailure(failure);
                    } finally {
                        labelsFieldResolved = true;
                    }
                }
            }
        }
        return labelsField;
    }

    private static void reportFailure(Exception failure) {
        if (!FAILURE_REPORTED.compareAndSet(false, true)) return;
        String detail = "进度条数值气泡主题适配失败：" + failure;
        LogStore.fail(Category.SYSTEM, detail);
        Log.w(TAG, detail, failure);
    }
}

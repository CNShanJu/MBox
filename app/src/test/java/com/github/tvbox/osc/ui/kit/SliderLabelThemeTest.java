package com.github.tvbox.osc.ui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.ColorStateList;

import com.google.android.material.resources.TextAppearance;
import com.google.android.material.slider.Slider;
import com.google.android.material.tooltip.TooltipDrawable;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.List;

/** Checks the pinned dependency contract used by the label compatibility bridge. */
public class SliderLabelThemeTest {

    @Test
    public void materialSliderOwnsAnAccessibleTooltipPool() throws Exception {
        Class<?> owner = Slider.class.getSuperclass();
        assertEquals("com.google.android.material.slider.BaseSlider", owner.getName());
        Field labels = owner.getDeclaredField("labels");
        assertTrue(Modifier.isPrivate(labels.getModifiers()));
        assertTrue(Modifier.isFinal(labels.getModifiers()));
        assertFalse(Modifier.isStatic(labels.getModifiers()));
        assertEquals(List.class, labels.getType());
        assertTrue(labels.getGenericType() instanceof ParameterizedType);
        ParameterizedType poolType = (ParameterizedType) labels.getGenericType();
        assertEquals(TooltipDrawable.class, poolType.getActualTypeArguments()[0]);
        labels.setAccessible(true);
    }

    @Test
    public void tooltipExposesTheColorAndInvalidationApis() throws Exception {
        assertPublicMethod(TooltipDrawable.class, "setFillColor", void.class, ColorStateList.class);
        assertPublicMethod(TooltipDrawable.class, "setStrokeColor", void.class, ColorStateList.class);
        assertPublicMethod(TooltipDrawable.class, "getTextAppearance", TextAppearance.class);
        assertPublicMethod(TextAppearance.class, "setTextColor", void.class, ColorStateList.class);
        assertPublicMethod(TooltipDrawable.class, "invalidateSelf", void.class);
    }

    private static void assertPublicMethod(Class<?> owner, String name, Class<?> returnType,
                                            Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameterTypes);
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertEquals(returnType, method.getReturnType());
    }
}

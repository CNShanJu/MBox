package com.github.tvbox.osc.bean.theme;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 某个组件如何组合颜色、圆角与描边的不可变配方。 */
public final class ThemeShapeRecipe {

    public static final class PaintState {
        public final String fill;
        public final String stroke;

        public PaintState(String fill, String stroke) {
            this.fill = fill == null ? "transparent" : fill;
            this.stroke = stroke == null ? "" : stroke;
        }
    }

    public final String id;
    /** 形状:{@code rectangle}(默认)或 {@code oval}(圆形悬浮钮) */
    public final String shape;
    public final String radius;
    public final Map<String, String> corners;
    public final Map<String, Float> cornerDp;
    public final String strokeWidth;
    public final float strokeWidthDp;
    public final String ripple;
    public final Map<String, PaintState> states;
    public final List<ThemeShapeRecipe> layers;

    public ThemeShapeRecipe(String id, String shape, String radius, Map<String, String> corners,
                            Map<String, Float> cornerDp, String strokeWidth, float strokeWidthDp,
                            String ripple, Map<String, PaintState> states,
                            List<ThemeShapeRecipe> layers) {
        this.id = id == null ? "" : id;
        this.shape = (shape == null || shape.isEmpty()) ? "rectangle" : shape;
        this.radius = radius == null ? "" : radius;
        this.corners = Collections.unmodifiableMap(new LinkedHashMap<>(
                corners == null ? Collections.<String, String>emptyMap() : corners));
        this.cornerDp = Collections.unmodifiableMap(new LinkedHashMap<>(
                cornerDp == null ? Collections.<String, Float>emptyMap() : cornerDp));
        this.strokeWidth = strokeWidth == null ? "" : strokeWidth;
        this.strokeWidthDp = Math.max(0f, strokeWidthDp);
        this.ripple = ripple == null ? "" : ripple;
        this.states = Collections.unmodifiableMap(new LinkedHashMap<>(
                states == null ? Collections.<String, PaintState>emptyMap() : states));
        this.layers = Collections.unmodifiableList(new ArrayList<>(
                layers == null ? Collections.<ThemeShapeRecipe>emptyList() : layers));
    }
}

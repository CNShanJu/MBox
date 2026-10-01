package com.github.tvbox.osc.bean.theme;

/** 颜色、形状、类型与身份一起原子替换的不可变主题快照。 */
public final class ThemeSnapshot {

    public final ThemeColorPalette colors;
    public final ThemeShapePalette shapes;
    public final ThemeType type;
    public final boolean custom;
    public final String fingerprint;

    public ThemeSnapshot(ThemeColorPalette colors, ThemeShapePalette shapes,
                         ThemeType type, boolean custom, String identity) {
        this.colors = colors;
        this.shapes = shapes == null ? ThemeShapePalette.defaults() : shapes;
        this.type = type == null ? ThemeType.BRIGHT : type;
        this.custom = custom;
        String colorFingerprint = colors == null ? "none" : colors.fingerprint();
        this.fingerprint = this.type.name() + "|" + custom + "|"
                + (identity == null ? "" : identity) + "|" + colorFingerprint + "|"
                + this.shapes.fingerprint();
    }
}

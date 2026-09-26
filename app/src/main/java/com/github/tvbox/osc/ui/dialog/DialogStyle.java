package com.github.tvbox.osc.ui.dialog;

/**
 * 弹窗统一样式常量：所有弹窗(居中/底部/抽屉)共用的 宽度/圆角/间距 集中管理。
 * 调整弹窗基本样式只改本类 + 公共 drawable(app/src/main/res/drawable),全部弹窗一处生效。
 */
public final class DialogStyle {

    /** 居中弹窗统一最大宽度(dp) */
    public static final int CENTER_MAX_WIDTH_DP = 320;

    /** 固定宽度弹窗(确认/删除)统一宽度(dp) */
    public static final int FIXED_WIDTH_DP = 300;

    /** 统一圆角档位(dp): 与 theme_radii 的 radius_background 保持一致 */
    public static final int CORNER_RADIUS_DP = 25;

    private DialogStyle() {
    }
}

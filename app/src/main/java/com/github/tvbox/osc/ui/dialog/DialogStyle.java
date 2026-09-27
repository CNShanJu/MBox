package com.github.tvbox.osc.ui.dialog;

/**
 * 弹窗统一样式常量：所有弹窗(居中/底部/抽屉)共用的 宽度/间距 集中管理。
 * 调整弹窗基本样式只改本类 + 公共 drawable(app/src/main/res/drawable),全部弹窗一处生效。
 *
 * <p><b>这里只放"尺寸"常量,绝不放圆角</b>:圆角唯一来源是主题圆角文件
 * ({@code assets/theme/theme_radii.json} → 生成的 {@code @dimen/radius_*})。
 * 本类曾有一个 {@code CORNER_RADIUS_DP = 25}(注释还写着"与 theme_radii 的 radius_background 保持一致",
 * 而那时主题里其实已经是 12/18dp)—— 这种"组件自带一份默认圆角"的常量正是
 * "我改配置它不动、抽屉圆角看着特别大"的来源,已删除。
 * 需要圆角请用 {@code @dimen/radius_background|radius_dialog|radius_card|radius_btn|radius_widget_btn|common_corners}。
 */
public final class DialogStyle {

    /** 居中弹窗统一最大宽度(dp) */
    public static final int CENTER_MAX_WIDTH_DP = 320;

    /** 固定宽度弹窗(确认/删除)统一宽度(dp) */
    public static final int FIXED_WIDTH_DP = 300;

    private DialogStyle() {
    }
}

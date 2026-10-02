package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * 弹窗统一样式常量：所有弹窗(居中/底部/抽屉)共用的 宽度/间距 集中管理。
 * 调整弹窗基本样式只改本类 + 公共 drawable(app/src/main/res/drawable),全部弹窗一处生效。
 *
 * <p><b>这里只放"尺寸"常量,绝不放圆角</b>:圆角唯一来源是主题圆角文件
 * ({@code assets/theme/radius/theme_radii.json} → 生成的 {@code @dimen/radius_*})。
 * 本类曾有一个 {@code CORNER_RADIUS_DP = 25}(注释还写着"与 theme_radii 的 radius_background 保持一致",
 * 而那时主题里其实已经是 12/18dp)—— 这种"组件自带一份默认圆角"的常量正是
 * "我改配置它不动、抽屉圆角看着特别大"的来源,已删除。
 * 需要圆角请用 {@code @dimen/radius_background|radius_dialog|radius_card|radius_btn|radius_widget_btn|common_corners}。
 */
public final class DialogStyle {

    /** 手机竖屏居中弹窗宽度(dp) */
    public static final int CENTER_MAX_WIDTH_DP = 320;

    /** 平板居中弹窗上限(dp)，宽度仍随当前窗口收缩。 */
    public static final int CENTER_TABLET_MAX_WIDTH_DP = 560;

    /** 底部弹窗在宽屏上的基础宽度上限(dp) */
    public static final int BOTTOM_MAX_WIDTH_DP = 560;

    /** 平板底部弹窗上限(dp)。 */
    public static final int BOTTOM_TABLET_MAX_WIDTH_DP = 720;

    private DialogStyle() {
    }

    private static float windowWidthDp(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float fromMetrics = metrics.widthPixels / metrics.density;
        int fromConfig = context.getResources().getConfiguration().screenWidthDp;
        return fromConfig > 0 ? Math.min(fromMetrics, fromConfig) : fromMetrics;
    }

    public static boolean isTabletWindow(Context context) {
        return context.getResources().getConfiguration().smallestScreenWidthDp >= 600
                && windowWidthDp(context) >= 600f;
    }

    /** 居中弹窗按窗口宽度伸缩；手机竖屏保留原来的 320dp。 */
    static int centerWidthDp(float windowWidthDp, boolean tablet, boolean landscape) {
        int target = tablet ? Math.min(CENTER_TABLET_MAX_WIDTH_DP, Math.round(windowWidthDp * 0.72f))
                : landscape ? Math.min(360, Math.round(windowWidthDp * 0.5f))
                : CENTER_MAX_WIDTH_DP;
        return Math.max(1, Math.min(target, Math.round(windowWidthDp - 32f)));
    }

    public static int centerWidthPx(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        boolean landscape = context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        return Math.round(centerWidthDp(windowWidthDp(context), isTabletWindow(context), landscape)
                * metrics.density);
    }

    /** 底部弹窗在平板占用更多宽度，手机竖屏仍铺满窗口。 */
    static int bottomWidthDp(float windowWidthDp, boolean tablet, boolean landscape) {
        if (tablet) {
            return Math.min(BOTTOM_TABLET_MAX_WIDTH_DP,
                    Math.round(windowWidthDp * (landscape ? 0.65f : 0.82f)));
        }
        return landscape ? Math.min(BOTTOM_MAX_WIDTH_DP, Math.round(windowWidthDp * 0.55f))
                : Math.round(windowWidthDp);
    }

    public static int bottomWidthPx(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        boolean landscape = context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        return Math.round(bottomWidthDp(windowWidthDp(context), isTabletWindow(context), landscape)
                * metrics.density);
    }

    /** 右侧抽屉在平板按窗口扩展，手机沿用调用方指定的宽度。 */
    static int drawerWidthDp(float windowWidthDp, boolean tablet, int requestedDp) {
        if (!tablet) return requestedDp;
        return Math.max(requestedDp, Math.min(560, Math.round(windowWidthDp * 0.5f)));
    }

    public static int drawerWidthPx(Context context, int requestedDp) {
        return Math.round(drawerWidthDp(windowWidthDp(context), isTabletWindow(context), requestedDp)
                * context.getResources().getDisplayMetrics().density);
    }
}

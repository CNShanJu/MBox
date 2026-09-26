package com.github.tvbox.osc.ui.dialog;

import android.content.Context;

import com.blankj.utilcode.util.ScreenUtils;

/**
 * 全局弹窗/抽屉尺寸分档策略(内容自适应 + 分档封顶)。
 * <p>阈值/占比集中在下列常量,全局想调数值只改本类。
 *
 * <p><b>一、居中/普通弹窗</b>(内容少保持内容高,内容超高才封顶):
 * 以“弹窗可用屏高(dp)”分档:
 * <ul>
 *   <li>屏高 ≥ {@link #SCREEN_DP_LARGE}dp → 封顶 {@link #RATIO_LARGE} 屏高;</li>
 *   <li>屏高 ≤ {@link #SCREEN_DP_SMALL}dp → 封顶铺满屏幕;</li>
 *   <li>区间(480~700dp)→ 封顶 {@link #RATIO_MID} 屏高。</li>
 * </ul>
 *
 * <p><b>二、横向抽屉(右侧全屏高抽屉)</b>,见 {@link #rightDrawerMaxWidthPx(Context)}:
 * 高度恒为 100%;宽度按“屏宽(dp)”分档封顶:≤480dp 允许全宽、≥700dp(超宽)固定
 * {@link #HORIZONTAL_FIXED_DP}dp、区间 50% 屏宽。
 *
 * <p><b>三、纵向抽屉(底部弹窗/抽屉)</b>,见 {@link #bottomDrawerMaxHeightPx(Context, boolean)}:
 * 宽度恒为 100%;高度按“屏高(dp)”分档封顶:≤480dp(不高)→ 支持铺满 100%、
 * ≥700dp(过高)→ 固定 {@link #VERTICAL_FIXED_DP}dp、区间 50% 屏高;
 * 区间档内容可被向上拖拽展开(XPopup enableDrag)时上限放宽到 {@link #RATIO_DRAG}。
 *
 * <p>个别弹窗(如长内容详情)可自行覆写 getMaxHeight / getMaxWidth 局部放宽;
 * 长列表类弹窗(SelectDialog 动态模式)另保留“固定撑满”形态。
 */
final class DialogHeightPolicy {

    private DialogHeightPolicy() {
    }

    // ------------------------------------------------------------------
    // 分档常量
    // ------------------------------------------------------------------

    /** 长边/短边 dp ≥ 该值视为“大/过高屏” */
    public static final int SCREEN_DP_LARGE = 700;
    /** dp ≤ 该值视为“小/不高屏” */
    public static final int SCREEN_DP_SMALL = 480;
    /** 大屏普通弹窗占比 */
    public static final float RATIO_LARGE = 0.6f;
    /** 小屏占比(铺满) */
    public static final float RATIO_FULL = 1.0f;
    /** 区间占比(50%) */
    public static final float RATIO_MID = 0.5f;
    /** 可拖拽纵向抽屉在区间档的上限占比(70%) */
    public static final float RATIO_DRAG = 0.7f;
    /** 高屏(≥700dp)纵向抽屉固定封顶高度(dp) */
    public static final int VERTICAL_FIXED_DP = 540;
    /** 超宽屏(≥700dp)横向抽屉固定封顶宽度(dp) */
    public static final int HORIZONTAL_FIXED_DP = 560;

    // ------------------------------------------------------------------
    // 拖拽抽屉状态机(SheetResizeController)统一数值源
    // ------------------------------------------------------------------

    /** 收起(默认)态:50% 屏高 */
    public static final float SHEET_RATIO_COLLAPSED = 0.5f;
    /** 展开态:70% 屏高 */
    public static final float SHEET_RATIO_EXPANDED = 0.7f;
    /** 压到该占比才收起关闭 */
    public static final float SHEET_RATIO_CLOSE = 0.3f;
    /** 松手越过 收↔展 区间的该比例即展开 */
    public static final float SHEET_EXPAND_THRESHOLD_RATIO = 0.4f;
    /** 高度由拖拽状态机管理的弹窗返回该“无上限”,避免 XPopup 按分档封顶(见 SheetResizableBottomPopup) */
    public static final int HEIGHT_UNBOUNDED = 0x3FFFFFFF;

    // ------------------------------------------------------------------
    // 居中/普通弹窗(以屏高 dp 分档)
    // ------------------------------------------------------------------

    /** 按分档得到的屏高占比 */
    public static float ratio(float density, int heightPx) {
        if (heightPx <= 0 || density <= 0) return RATIO_MID;
        float heightDp = heightPx / density;
        if (heightDp >= SCREEN_DP_LARGE) {
            return RATIO_LARGE;
        } else if (heightDp <= SCREEN_DP_SMALL) {
            return RATIO_FULL;
        } else {
            return RATIO_MID;
        }
    }

    /** 以整屏高度为基准的普通弹窗分档封顶高度(px) */
    public static int maxHeightPx(Context context) {
        int screenH = ScreenUtils.getScreenHeight();
        if (screenH <= 0) return 0;
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(screenH * ratio(density, screenH));
    }

    /**
     * 普通弹窗在"扣掉状态栏/导航栏后的可用高度"内的封顶高度(px)。
     * <p>
     * {@link #maxHeightPx(Context)} 以整屏高为基准,而弹窗窗口的可用高度还要减掉系统栏;
     * 长内容弹窗(如更新说明)按整屏高封顶时,底部会被导航栏/手势条裁掉(实测"按钮显示了但没显示完整")。
     * 需要"按钮必须完整可见"的弹窗用本方法。
     */
    public static int maxHeightPxInsideWindow(Context context) {
        int base = maxHeightPx(context);
        if (base <= 0) return 0;
        int inset = systemBarsHeightPx(context);
        int available = ScreenUtils.getScreenHeight() - inset;
        return Math.max(1, Math.min(base, available));
    }

    /** 状态栏 + 导航栏占用高度(取不到按 0,不影响主流程) */
    public static int systemBarsHeightPx(Context context) {
        try {
            int statusBar = com.blankj.utilcode.util.BarUtils.getStatusBarHeight();
            int navBar = com.blankj.utilcode.util.BarUtils.getNavBarHeight();
            return Math.max(0, statusBar) + Math.max(0, navBar);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 纵向抽屉(底部弹窗/抽屉)
    // ------------------------------------------------------------------

    /**
     * 纵向抽屉最大高度(px):低屏(≤480dp)支持铺满 100%;高屏(≥700dp)固定
     * {@link #VERTICAL_FIXED_DP}dp(不随占比继续放大);区间 50% 屏高,
     * 可拖拽展开(dragExpandable, 即 XPopup enableDrag)时放宽到 {@link #RATIO_DRAG}。
     */
    public static int bottomDrawerMaxHeightPx(Context context, boolean dragExpandable) {
        int screenH = ScreenUtils.getScreenHeight();
        if (screenH <= 0) return 0;
        float density = context.getResources().getDisplayMetrics().density;
        float heightDp = screenH / density;
        if (heightDp <= SCREEN_DP_SMALL) {
            return screenH;
        }
        if (heightDp >= SCREEN_DP_LARGE) {
            return Math.round(VERTICAL_FIXED_DP * density);
        }
        return Math.round(screenH * (dragExpandable ? RATIO_DRAG : RATIO_MID));
    }

    // ------------------------------------------------------------------
    // 横向抽屉(右侧抽屉:高度恒 100%,宽度按屏宽 dp 分档)
    // ------------------------------------------------------------------

    /**
     * 横向抽屉最大宽度(px):窄屏(≤480dp)允许全宽;超宽屏(≥700dp)固定
     * {@link #HORIZONTAL_FIXED_DP}dp;区间 50% 屏宽。作为上限,调用方传入的更窄宽度保持不变。
     */
    public static int rightDrawerMaxWidthPx(Context context) {
        int screenW = ScreenUtils.getScreenWidth();
        if (screenW <= 0) return 0;
        float density = context.getResources().getDisplayMetrics().density;
        float widthDp = screenW / density;
        if (widthDp <= SCREEN_DP_SMALL) {
            return screenW;
        }
        if (widthDp >= SCREEN_DP_LARGE) {
            return Math.round(HORIZONTAL_FIXED_DP * density);
        }
        return Math.round(screenW * RATIO_MID);
    }
}

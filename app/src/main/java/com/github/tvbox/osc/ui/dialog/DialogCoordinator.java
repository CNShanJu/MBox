package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;

import com.blankj.utilcode.util.ConvertUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.enums.PopupPosition;
import com.lxj.xpopup.interfaces.XPopupCallback;

/**
 * 弹窗展示协调器（改进.txt §三"弹窗组件统一走协调器"）。
 * <p>
 * 收敛各页面散落的 {@code new XPopup.Builder(...).isViewMode(...)...asCustom(x).show()} 组装细节，
 * 页面只表达"弹哪个内容 + 什么形态"；XPopup 壳参数（view 模式 / 导航栏 / 位置 / 宽高 / 主题）统一在这里决定，
 * 后续要改全局弹窗策略只动本类。返回 {@link BasePopupView} 由调用方持有并自行 show/dismiss
 * （维持既有"字段引用 + onBackPressed dismiss"语义不变）。
 */
public final class DialogCoordinator {

    private DialogCoordinator() {
    }

    // ------------------------------------------------------------------
    // 居中弹窗（默认，主题化背景由 App*PopupView 基类负责）
    // ------------------------------------------------------------------

    /** 居中弹窗（XPopup 默认尺寸） */
    public static BasePopupView center(Context ctx, BasePopupView content) {
        return new XPopup.Builder(ctx)
                .asCustom(content);
    }

    /** 居中弹窗 + 暗色主题（内容含系统默认色元素时用，如 XPopup 内置列表/输入框） */
    public static BasePopupView centerDark(Context ctx, BasePopupView content) {
        return new XPopup.Builder(ctx)
                .isDarkTheme(Utils.isDarkTheme())
                .asCustom(content);
    }

    /** 居中弹窗，限最大宽度（px；转 dp 语义见调用处注释） */
    public static BasePopupView centerMaxWidth(Context ctx, BasePopupView content, int maxWidthDp) {
        return new XPopup.Builder(ctx)
                .maxWidth(ConvertUtils.dp2px(maxWidthDp))
                .asCustom(content);
    }

    // ------------------------------------------------------------------
    // 右侧抽屉（全屏高，可设宽）
    // ------------------------------------------------------------------

    /**
     * 右侧全屏抽屉（view 模式避免手势条闪烁；禁拖拽由调用方按内容决定）。
     * 宽度除 widthDp 外还受横向抽屉分档上限约束(见 {@link AppDrawerPopupView#getMaxWidth()})。
     *
     * @param widthDp   抽屉宽度 dp（300/320/360 等，随内容定；<=0 用 XPopup 默认宽）
     * @param enableDrag 是否允许横向拖拽关闭（内部含横向列表的传 false）
     */
    public static BasePopupView right(Context ctx, BasePopupView content, int widthDp, boolean enableDrag) {
        return right(ctx, content, widthDp, enableDrag, true, null);
    }

    /** {@link #right(Context, BasePopupView, int, boolean)} + 弹窗生命周期回调（如关闭防重入清理） */
    public static BasePopupView right(Context ctx, BasePopupView content, int widthDp, boolean enableDrag,
                                      XPopupCallback callback) {
        return right(ctx, content, widthDp, enableDrag, true, callback);
    }

    /** 全参数右侧抽屉：可关阴影（列表类抽屉常 hasShadowBg(false) 更清爽） */
    public static BasePopupView right(Context ctx, BasePopupView content, int widthDp, boolean enableDrag,
                                      boolean hasShadowBg, XPopupCallback callback) {
        XPopup.Builder builder = new XPopup.Builder(ctx)
                .isViewMode(true)         // 隐藏导航栏(手势条)在 dialog 模式下会闪一下，改 view 模式（onBack 由调用方处理）
                .hasNavigationBar(false)
                .popupHeight(ScreenUtils.getScreenHeight())
                .popupPosition(PopupPosition.Right);
        if (widthDp > 0) {
            builder = builder.popupWidth(ConvertUtils.dp2px(widthDp));
        }
        if (!hasShadowBg) {
            builder = builder.hasShadowBg(false);
        }
        if (!enableDrag) {
            builder = builder.enableDrag(false);
        }
        if (callback != null) {
            builder = builder.setPopupCallback(callback);
        }
        return builder.asCustom(content);
    }

    // ------------------------------------------------------------------
    // 底部弹窗
    // ------------------------------------------------------------------

    /** 底部弹窗，指定最大高度 px（如 2/3 屏或 1/2 屏）；view 模式防手势条闪烁；heightPx<=0 用弹窗自身高度 */
    public static BasePopupView bottom(Context ctx, BasePopupView content, int heightPx) {
        return bottom(ctx, content, heightPx, null);
    }

    /** 底部弹窗 + 生命周期回调；heightPx<=0 用弹窗自身高度（如 App*PopupView 自带 getMaxHeight） */
    public static BasePopupView bottom(Context ctx, BasePopupView content, int heightPx, XPopupCallback callback) {
        XPopup.Builder builder = new XPopup.Builder(ctx)
                .isViewMode(true)
                .hasNavigationBar(false);
        if (heightPx > 0) {
            builder = builder.popupHeight(heightPx);
        }
        if (callback != null) {
            builder = builder.setPopupCallback(callback);
        }
        return builder.asCustom(content);
    }

    /**
     * 可上拉拖拽的纵向抽屉(底部弹窗,XPopup enableDrag 走 SmartDragLayout 手势):
     * 高度分档同纵向抽屉策略,区间档(480~700dp 屏高)上限由 50% 放宽到 70%
     * (见 {@link DialogHeightPolicy#bottomDrawerMaxHeightPx(Context, boolean)})。
     */
    public static BasePopupView bottomDraggable(Context ctx, BasePopupView content, int heightPx) {
        return bottomDraggable(ctx, content, heightPx, null);
    }

    /** {@link #bottomDraggable(Context, BasePopupView, int)} + 生命周期回调 */
    public static BasePopupView bottomDraggable(Context ctx, BasePopupView content, int heightPx,
                                                XPopupCallback callback) {
        XPopup.Builder builder = new XPopup.Builder(ctx)
                .isViewMode(true)
                .hasNavigationBar(false)
                .enableDrag(true);
        if (heightPx > 0) {
            builder = builder.popupHeight(heightPx);
        }
        if (callback != null) {
            builder = builder.setPopupCallback(callback);
        }
        return builder.asCustom(content);
    }

    /** 底部弹窗，指定最高值（Builder.maxHeight，可小于 popupHeight 封顶内容高度） */
    public static BasePopupView bottomMaxHeight(Context ctx, BasePopupView content, int maxHeightPx) {
        return new XPopup.Builder(ctx)
                .isViewMode(true)
                .hasNavigationBar(false)
                .maxHeight(maxHeightPx)
                .asCustom(content);
    }

    /** 底部弹窗 + 生命周期回调 */
    public static BasePopupView bottomMaxHeight(Context ctx, BasePopupView content, int maxHeightPx,
                                                XPopupCallback callback) {
        XPopup.Builder builder = new XPopup.Builder(ctx)
                .isViewMode(true)
                .hasNavigationBar(false)
                .maxHeight(maxHeightPx);
        if (callback != null) {
            builder = builder.setPopupCallback(callback);
        }
        return builder.asCustom(content);
    }

    // ------------------------------------------------------------------
    // 通用便捷（确认 / 加载）
    // ------------------------------------------------------------------

    /** 主题化确认弹窗（统一走 ConfirmDialog 工厂，保证 Builder 绑定 popupInfo） */
    public static void confirm(Context ctx, String title, String message,
                               String confirmText, Runnable onConfirm) {
        ConfirmDialog.show(ctx, title, message, confirmText, onConfirm);
    }

    /** 加载框（替代 XPopup asLoading 默认转圈;统一走全局加载态 Lottie,可挂任意 Activity 上） */
    public static BasePopupView loading(Activity activity) {
        return new XPopup.Builder(activity)
                .isLightNavigationBar(true)
                .hasShadowBg(true)              // 暗色遮罩垫底(加载框无卡片背景)
                .dismissOnTouchOutside(false)   // 加载框不点外关闭
                .asCustom(new LoadingDialog(activity));
    }

    /** BaseActivity 加载框缓存/显示辅助（与既有 showLoadingDialog 行为一致） */
    public static void showLoading(BaseActivity activity) {
        if (activity != null) {
            activity.showLoadingDialog();
        }
    }

    /** BaseActivity 加载框隐藏辅助 */
    public static void dismissLoading(BaseActivity activity) {
        if (activity != null) {
            activity.dismissLoadingDialog();
        }
    }
}

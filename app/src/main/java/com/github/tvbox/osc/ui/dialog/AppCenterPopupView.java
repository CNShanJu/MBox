package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.lxj.xpopup.core.CenterPopupView;

/**
 * 统一的居中弹窗基类:
 * <ul>
 *   <li>背景统一 {@link R.drawable#bg_dialog}(全圆角 + bg_float 主题色)——由布局根设置,
 *       <b>圆角档只有一个来源</b>:居中弹窗那层面 = 弹窗/抽屉面板档 {@code radius_dialog},
 *       与底部面板(bg_bottom_dialog)、右侧抽屉(bg_drawer)完全同档。
 *       2026-09-27 之前这里分裂过:8 个弹窗布局挂着 {@code bg_large_round_popup}
 *       (那是 {@code radius_background} = 页面卡片/大面板档),于是"确认框比抽屉更圆、而选择框跟抽屉一样"
 *       —— 用户口径"弹窗的圆角和抽屉的圆角,设置出来显示效果不一致"。两份 drawable 只差一行 corners 引用,
 *       现已合并成一份 bg_dialog;</li>
 *       基类不强设避免与 XPopup 默认容器叠加产生四角异常;</li>
 *   <li>宽度统一由 {@link DialogStyle#centerWidthPx(Context)} 按窗口计算；平板上的固定 300/320dp
 *       布局也在这里同步扩展，手机保持原尺寸;</li>
 *   <li>最大高度统一按 {@link DialogHeightPolicy} 分档封顶(内容自适应,超高自动包 ScrollView 内部滚动);</li>
 * </ul>
 * 调主题背景/宽度/高度只改基类/常量,一处生效全部居中弹窗。
 * 子类实现 {@link #getImplLayoutId()} 与 {@link #onCreate()};
 * 标题+内容+按钮结构时,中间内容区用 weight=1 + 内部滚动,避免挤压上下标题/按钮。
 */
public abstract class AppCenterPopupView extends CenterPopupView {

    public AppCenterPopupView(@NonNull Context context) {
        super(context);
    }

    @Override
    protected int getMaxWidth() {
        return DialogStyle.centerWidthPx(getContext());
    }

    /**
     * 统一最大高度:按“屏幕可用高度(dp)”分档封顶(≥700dp→60%、≤480dp→铺满、区间→50%),
     * 内容少时保持内容自然高度;内容超高时内部滚动。
     * 阈值/占比集中在 {@link DialogHeightPolicy}。
     */
    @Override
    protected int getMaxHeight() {
        return DialogHeightPolicy.maxHeightPx(getContext());
    }

    /**
     * 内容根是否自带滚动能力(列表/ScrollView)。
     * 默认 false:内容超高时由基类把整卡包进 ScrollView 兜底;
     * 自带滚动区的弹窗(如 SelectDialog 的 TvRecyclerView)应返回 true,
     * 由内容区自行吃掉超高余量滚动,避免"整卡滚动"。
     */
    protected boolean contentSelfScrollable() {
        return false;
    }

    @Override
    protected void onCreate() {
        PopupKeyboardPolicy.onCreate(this);
        super.onCreate();
        // 弹窗晚于 Activity 创建:显示时按明确资源重新应用,不扫描像素颜色猜面板语义。
        View root = getPopupImplView();
        if (root != null) {
            ViewGroup.LayoutParams params = root.getLayoutParams();
            int targetWidth = getMaxWidth();
            if (params != null && (DialogStyle.isTabletWindow(getContext())
                    || (params.width > 0 && params.width > targetWidth))) {
                params.width = targetWidth;
                root.setLayoutParams(params);
            }
            com.github.tvbox.osc.theme.ThemeDrawables.applyBackground(root, R.drawable.bg_dialog);
            com.github.tvbox.osc.theme.ThemeSweep.watchItems(root);
        }
        // 暂停弹窗圆角诊断日志，需要排障时恢复调用。
        // com.github.tvbox.osc.theme.RadiusCheck.reportPopup(getContext(), getClass().getSimpleName());
        // 内容超高且不自带滚动区时,自动包一层 ScrollView:防止被 maxHeight 裁剪(纯文本/按钮弹窗兜底)
        if (!contentSelfScrollable()) {
            wrapContentInScrollIfOverflow();
        }
    }

    @Override
    public void focusAndProcessBackPress() {
        super.focusAndProcessBackPress();
        PopupKeyboardPolicy.afterFocus(this);
    }

    private void wrapContentInScrollIfOverflow() {
        try {
            final View content = getPopupImplView();
            if (content != null) {
                content.post(() -> {
                    try {
                        int maxH = getMaxHeight();
                        if (content.getHeight() > maxH) {
                            wrapInScrollView(content);
                        }
                    } catch (Throwable ignored) {
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把弹窗内容根包进 ScrollView(仅超高时;内部按钮/列表不受影响) */
    private void wrapInScrollView(View content) {
        android.view.ViewGroup parent = (android.view.ViewGroup) content.getParent();
        if (parent == null || content.getParent() instanceof ScrollView) return;
        int idx = parent.indexOfChild(content);
        parent.removeView(content);
        ScrollView sv = new ScrollView(getContext());
        sv.setFillViewport(true);
        sv.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        android.view.ViewGroup.LayoutParams lp = content.getLayoutParams();
        sv.addView(content, lp != null ? lp
                : new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(sv, idx, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
    }
}

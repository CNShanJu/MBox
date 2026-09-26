package com.github.tvbox.osc.ui.dialog;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ScrollView;

import androidx.interpolator.view.animation.LinearOutSlowInInterpolator;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ScreenUtils;

/**
 * 底部抽屉“顶部热区统一手势 + 50%↔70% 状态机”。
 * <p>适用布局(内容弹性区 + 其余固定区):
 * <pre>
 *   LinearLayout(root, wrap)
 *     ├─ 顶部手势条/标题区(可拖、可点)
 *     ├─ flex:weight=1 弹性区 —— 列表(list_box+RecyclerView) 或 ScrollView(内含可测内容)
 *     └─ (可选)底部固定按钮区
 * </pre>
 * <p>交互:
 * <ul>
 *   <li>顶部热区(手势条+标题一带;ScrollView 型再多含开头一小段)始终响应;
 *       <b>按住上拉的判断始终执行</b>:可展开(内容自然高 &gt; 50% 屏高) →
 *       默认 50%,过阈值展开到 70%;高度已定(内容少) → 上拉只做越界回弹,不误动;</li>
 *   <li>下拉在任何状态都生效:未过阈值回到收起高;压到 ≤ 30% 屏高松手 → 收起关闭
 *       (高度已定的短内容抽屉同样能这样关掉;以前这里提前 return,短内容只能橡皮筋、关不掉);</li>
 *   <li>点击热区 = 位移不超过 slop 即点击:可展开 → 50/70 切换(动画),
 *       不可展开 → 脉冲回弹;<b>点击永不收起关闭</b>;</li>
 *   <li>动作经 {@link #setActionListener(ActionListener)} 回传(展开/收回/收起),
 *       {@link #expand()} / {@link #collapse()} 供宿主复用。</li>
 * </ul>
 * 高度直接作用于 XPopup 底部容器与内容根;flex 区吃满剩余,内部自行滚动/滑动。
 */
public class SheetResizeController implements View.OnTouchListener {

    /** 状态数值统一见 {@link DialogHeightPolicy}(SHEET_RATIO_*)。以下为交互手感参数 */
    /** 越界“橡皮筋”阻尼 */
    private static final float RUBBER_DAMPING = 0.35f;
    /** 上拉越界最大像素(dp) */
    private static final float RUBBER_MAX_DP = 80f;
    /** 下拉回弹最大像素(dp)(高度已定时反馈) */
    private static final float RUBBER_DOWN_MAX_DP = 36f;
    /** 状态切换动画时长(ms) */
    private static final int ANIM_MS = 300;

    /** 动作回传 */
    public interface ActionListener {
        /** 收→展(高度弹到 70%) */
        void onSheetExpanded();

        /** 展→收(高度回到 50%) */
        void onSheetCollapsed();

        /** 被收起(压到 ≤30% 屏高关闭) */
        void onSheetClosed();
    }

    private final AppBottomPopupView popup;
    private final View root;     // 内容根
    private final View flex;     // weight=1 弹性区(list_box 或 ScrollView)
    private final View content;  // 用于测自然高的内容(rv 或 ScrollView 内的内容)

    private int collapsedH;       // 收起(默认)高:默认 50% 屏高(详情类简介可按“5 行可读”上调/下调)
    private final int expandedH;  // 展开高:70% 屏高
    private final int closeH;     // 30% 屏高(压到此高度松手 → 收起关闭)
    private int expandH;          // 松手高于此高 → 展开(略低于中点,触发更跟手)
    private final float density;
    private final int maxRubberUp;
    private final int maxRubberDown;
    private final int touchSlop;
    private final long tapMaxMs;

    private ActionListener listener;
    private boolean resizable;
    private boolean expanded;
    private boolean inited;       // 是否已按内容定过一次高度(避免每次 sync 重置已展开的高度)
    private int curH;
    private int visualH;
    private int topHitH;

    private boolean dragging;
    private boolean moved;
    private long downTime;
    private float downRawX;
    private float downRawY;
    private int downH;
    private ValueAnimator settleAnim;

    private SheetResizeController(AppBottomPopupView popup, View root, View flex, View content) {
        this.popup = popup;
        this.root = root;
        this.flex = flex;
        this.content = content;
        Context ctx = popup.getContext();
        this.density = ctx.getResources().getDisplayMetrics().density;
        int screenH = ScreenUtils.getScreenHeight();
        this.collapsedH = screenH > 0 ? Math.round(screenH * DialogHeightPolicy.SHEET_RATIO_COLLAPSED) : 0;
        this.expandedH = screenH > 0 ? Math.round(screenH * DialogHeightPolicy.SHEET_RATIO_EXPANDED) : 0;
        this.closeH = screenH > 0 ? Math.round(screenH * DialogHeightPolicy.SHEET_RATIO_CLOSE) : 0;
        this.expandH = Math.round(collapsedH + (expandedH - collapsedH)
                * DialogHeightPolicy.SHEET_EXPAND_THRESHOLD_RATIO);
        this.maxRubberUp = Math.round(RUBBER_MAX_DP * density);
        this.maxRubberDown = Math.round(RUBBER_DOWN_MAX_DP * density);
        this.touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        this.tapMaxMs = ViewConfiguration.getLongPressTimeout() + 150;
        this.curH = collapsedH;
        this.visualH = collapsedH;
        root.setOnTouchListener(this);
    }

    /** 绑定:root=list 内容根;flex=weight=1 弹性区(list_box/ScrollView);content=测量用内容(rv/文本) */
    public static SheetResizeController attach(AppBottomPopupView popup, View root, View flex, View content) {
        return new SheetResizeController(popup, root, flex, content);
    }

    public void setActionListener(ActionListener listener) {
        this.listener = listener;
    }

    /** 内容就绪后调用(布局完成后):只做一次“内容自适应 or 可展开”决策;
     *  之后再调用(如数据刷新/重查)不改动当前展开高度,避免“展开后被压回 50%”。 */
    void sync() {
        try {
            if (inited) return;
            int natural = measureNaturalHeight();
            if (natural <= 0) return;
            inited = true;
            if (natural > collapsedH) {
                // 内容足以展开:默认收起态 50%
                resizable = true;
                expanded = false;
                curH = collapsedH;
                visualH = collapsedH;
                applyHeight(visualH);
            } else {
                // 内容少:按内容固定高展示;手势仍生效,只做越界回弹反馈
                resizable = false;
                expanded = false;
                curH = natural;
                visualH = natural;
                applyHeight(natural);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 数据未就绪阶段:先按收起态 50% 占位 */
    void applyDefault() {
        curH = collapsedH;
        visualH = collapsedH;
        applyHeight(visualH);
    }

    /**
     * 详情类自定义:跳过自然高测量,直接进入“可展开”状态(收起高可定制)。
     * 用于“简介预渲染汇总”后:文本可能展开 → 一律可 50↔70;收起高按需抬到
     * 能让折叠区(如 5 行简介)完整可见的高度。
     */
    void forceExpandable(int collapsedPx) {
        resizable = true;
        inited = true;
        expanded = false;
        if (collapsedPx > 0 && collapsedPx <= expandedH) {
            collapsedH = collapsedPx;
        }
        expandH = Math.round(collapsedH + (expandedH - collapsedH)
                * DialogHeightPolicy.SHEET_EXPAND_THRESHOLD_RATIO);
        if (collapsedH > 0 && collapsedH <= expandedH) {
            curH = collapsedH;
            visualH = collapsedH;
            applyHeight(collapsedH);
        } else {
            curH = expandedH;
            visualH = expandedH;
            applyHeight(expandedH);
        }
    }

    /**
     * 内容不足展开(短内容):按调用方算好的高度固定展示,不进 50↔70 展开状态机。
     * <p>
     * 与 {@link #sync()} 的"内容少"分支同一意图,区别是高度由宿主给(它掌握简介/内边距口径),
     * 且上限压到展开高 —— 短内容不该被 50% 档截断(那个状态下又没有"展开"入口,用户就读不全)。
     * 上拉只做越界回弹(没有可展开内容),<b>下拉仍可压到 ≤30% 收起关闭</b>(见 release)。
     */
    void forceFixedHeight(int heightPx) {
        inited = true;
        resizable = false;
        expanded = false;
        int h = heightPx > 0 ? Math.min(heightPx, expandedH) : expandedH;
        curH = h;
        visualH = h;
        applyHeight(h);
    }

    /** 宿主“展开”:弹到 70% 并回传(无可展开内容时给脉冲反馈) */
    void expand() {
        if (!resizable) {
            pulse();
            return;
        }
        settleTo(expandedH);
        if (!expanded) {
            expanded = true;
            if (listener != null) listener.onSheetExpanded();
        }
    }

    /** 宿主“收回”:回到 50% 并回传 */
    void collapse() {
        if (!resizable) {
            pulse();
            return;
        }
        settleTo(collapsedH);
        if (expanded) {
            expanded = false;
            if (listener != null) listener.onSheetCollapsed();
        }
    }

    /** 是否展开态(供宿主同步 icon 状态) */
    boolean isExpanded() {
        return expanded;
    }

    /** 顶部热区高度:到弹性区开始为止;ScrollView 型再多含其开头一段(可点可不点内容) */
    private int topHitHeight() {
        if (topHitH > 0) return topHitH;
        View ref = flex != null ? flex : content;
        int top = ref != null ? ref.getTop() : 0;
        int extra = (flex instanceof ScrollView) ? Math.round(88 * density) : 0;
        if (top <= 0) top = Math.round(72 * density);
        topHitH = Math.max(top + extra, Math.round(48 * density));
        return topHitH;
    }

    // ------------------------------------------------------------------
    // 测量 / 改高 / 动画
    // ------------------------------------------------------------------

    /**
     * 自然总高 = 固定区(root 高 - flex 可视高) + flex 内边距 + 内容自然高。
     * <p>
     * 注意:ScrollView 型 flex 要量的是**它里面的内容根**(内层容器自带 paddingBottom 等),
     * 不是直接传进来的那个 TextView —— 只量 TextView 会漏掉「ScrollView 的上下 padding +
     * 内层容器 padding」(详情弹窗实测漏 34dp),弹出来就比内容矮一截:底部文字被截,
     * 而且内容高于可视区时还能滑动(ScrollView 的 setEnabled(false) 挡不住触摸滚动)。
     */
    private int measureNaturalHeight() {
        if (content == null || root == null) return -1;
        int rootH = root.getHeight();
        int flexH = flex != null ? flex.getHeight() : 0;
        if (rootH <= 0 || (flex != null && flexH <= 0)) return -1;

        View target = content;
        if (flex instanceof ViewGroup && flex instanceof ScrollView
                && ((ViewGroup) flex).getChildCount() > 0) {
            target = ((ViewGroup) flex).getChildAt(0);
        }
        int width = target.getWidth();
        if (width <= 0) width = target.getMeasuredWidth();
        if (width <= 0 && flex != null) {
            // 还没量过宽度:用 flex 的可用宽(扣掉它自己的左右 padding)兜底
            width = flex.getWidth() - flex.getPaddingLeft() - flex.getPaddingRight();
        }
        if (width <= 0) return -1;

        ViewGroup.LayoutParams lp = target.getLayoutParams();
        if (lp == null) {
            lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        int oldH = lp.height;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        target.setLayoutParams(lp);
        int wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        target.measure(wSpec, hSpec);
        int contentH = target.getMeasuredHeight();
        lp.height = oldH;
        target.setLayoutParams(lp);
        if (contentH <= 0) return -1;
        if (flex != null) contentH += flex.getPaddingTop() + flex.getPaddingBottom();
        int overhead = rootH - (flex != null ? flexH : 0);
        return Math.max(overhead, 0) + contentH;
    }

    /** 弹窗(容器+内容根)高度设为 h;flex 区吃满剩余,内部自行滚动 */
    private void applyHeight(int hPx) {
        if (hPx <= 0) return;
        try {
            View container = popup.getPopupContentView();
            if (container != null) {
                ViewGroup.LayoutParams clp = container.getLayoutParams();
                if (clp == null) {
                    clp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hPx);
                } else {
                    clp.height = hPx;
                }
                container.setLayoutParams(clp);
            }
            ViewGroup.LayoutParams rl = root.getLayoutParams();
            if (rl == null) {
                rl = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hPx);
            } else {
                rl.height = hPx;
            }
            root.setLayoutParams(rl);
            if (container != null) container.requestLayout();
            root.requestLayout();
        } catch (Throwable ignored) {
        }
    }

    private void settleTo(int targetH) {
        cancelSettle();
        if (visualH == targetH) {
            curH = targetH;
            return;
        }
        final int from = visualH;
        curH = targetH;
        settleAnim = ValueAnimator.ofInt(from, targetH);
        settleAnim.setDuration(ANIM_MS);
        settleAnim.setInterpolator(new LinearOutSlowInInterpolator());
        settleAnim.addUpdateListener(a -> {
            visualH = (Integer) a.getAnimatedValue();
            applyHeight(visualH);
        });
        settleAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                visualH = targetH;
                curH = targetH;
                settleAnim = null;
            }
        });
        settleAnim.start();
    }

    /** 无可展开内容时的反馈:轻微脉冲回弹 */
    private void pulse() {
        if (settleAnim != null) settleAnim.cancel();
        settleAnim = null;
        final int base = curH;
        final int peak = base + Math.round(14 * density);
        ValueAnimator up = ValueAnimator.ofInt(base, peak);
        up.setDuration(110);
        up.setInterpolator(new LinearOutSlowInInterpolator());
        up.addUpdateListener(a -> {
            visualH = (Integer) a.getAnimatedValue();
            applyHeight(visualH);
        });
        up.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                ValueAnimator down = ValueAnimator.ofInt(peak, base);
                down.setDuration(170);
                down.setInterpolator(new LinearOutSlowInInterpolator());
                down.addUpdateListener(a -> {
                    visualH = (Integer) a.getAnimatedValue();
                    applyHeight(visualH);
                });
                down.start();
            }
        });
        up.start();
    }

    private void cancelSettle() {
        if (settleAnim != null) {
            settleAnim.cancel();
            settleAnim = null;
        }
        visualH = curH;
        applyHeight(visualH);
    }

    // ------------------------------------------------------------------
    // 触摸
    // ------------------------------------------------------------------

    private int rubberOf(int beyond) {
        int r = Math.round(beyond * RUBBER_DAMPING);
        if (beyond > 0 && r > maxRubberUp) r = maxRubberUp;
        if (beyond < 0 && r < -maxRubberDown) r = -maxRubberDown;
        return r;
    }

    private void updateDrag(float raw) {
        int rawH = Math.round(raw);
        if (resizable) {
            if (rawH > expandedH) {
                curH = expandedH;
                visualH = expandedH + rubberOf(rawH - expandedH);
            } else if (rawH < closeH) {
                curH = closeH;
                visualH = closeH;
            } else {
                curH = rawH;
                visualH = rawH;
            }
        } else {
            // 高度已定(内容不足展开):**上拉**只做越界回弹(没有可展开的内容,不该被拉高);
            // **下拉**要真的变矮 —— 否则"按住往下压收起"这个底部抽屉的通用手势就失效了
            // (以前这里上下都只做橡皮筋,配合 release 里的早退,短内容抽屉根本关不掉)
            if (rawH < downH) {
                curH = Math.max(rawH, downH - closeDragPx());
                visualH = curH;
            } else {
                curH = downH;
                visualH = downH + rubberOf(rawH - downH);
            }
        }
        if (visualH > 0) applyHeight(visualH);
    }

    /**
     * 高度已定时"下拉收起"的位移阈值(px)。
     * <p>
     * 不能沿用可展开态那种绝对高度判定(压到 ≤30% 屏高):抽屉本来就不高于 30% 屏高时
     * (大屏 + 内容极少,详情弹窗真的会遇到),绝对阈值一拖就立刻成立 → 稍碰一下就关闭;
     * 也不能把下限钳到 30% 屏高,那反而会把矮抽屉拉高。故这里按位移判定:
     * 拖够「30% 屏高」或「抽屉自身高度的 1/2」(取小,且不小于下拉回弹量)即视为要收起关闭。
     */
    private int closeDragPx() {
        return Math.min(closeH, Math.max(Math.round(downH * 0.5f), maxRubberDown));
    }

    private void release(float upRawX, float upRawY) {
        // 最终位移判定:按住拖动后即使中间 MOVE 未到,只要最终离开起点的距离够大就算拖动,不算点击
        double dx = upRawX - downRawX;
        double dy = upRawY - downRawY;
        if (!moved && Math.sqrt(dx * dx + dy * dy) > touchSlop) {
            moved = true;
            updateDrag(downH + (downRawY - upRawY));
        }
        if (!moved) {
            if (System.currentTimeMillis() - downTime > tapMaxMs && Math.sqrt(dx * dx + dy * dy) < touchSlop * 2) {
                // 长按不动也当“按压反馈”(非收起),轻微脉冲
                pulse();
                return;
            }
            // 点击:可展开 → 50/70 切换;不可展开 → 脉冲;点击永不收起关闭
            if (expanded) collapse(); else expand();
            return;
        }
        if (!resizable) {
            // 高度已定(内容不足展开):往下拖够阈值同样算"收起关闭"(与可展开态一致的手感),
            // 否则平滑弹回拖动前的高度 —— 阈值口径见 closeDragPx()
            if (downH - curH >= closeDragPx()) {
                if (listener != null) listener.onSheetClosed();
                popup.dismiss();
                return;
            }
            settleTo(downH);
            return;
        }
        if (curH <= closeH) {
            if (listener != null) listener.onSheetClosed();
            popup.dismiss();
            return;
        }
        if (curH >= expandH) {
            settleTo(expandedH);
            if (!expanded) {
                expanded = true;
                if (listener != null) listener.onSheetExpanded();
            }
        } else {
            settleTo(collapsedH);
            if (expanded) {
                expanded = false;
                if (listener != null) listener.onSheetCollapsed();
            }
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getY() > topHitHeight()) return false;
                cancelSettle();
                dragging = true;
                moved = false;
                downTime = System.currentTimeMillis();
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downH = curH;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                if (!moved && (Math.abs(event.getRawX() - downRawX) > touchSlop
                        || Math.abs(event.getRawY() - downRawY) > touchSlop)) {
                    moved = true;
                }
                if (moved) updateDrag(downH + (downRawY - event.getRawY()));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) return false;
                dragging = false;
                release(event.getRawX(), event.getRawY());
                return true;
            }
            default:
                return dragging;
        }
    }
}

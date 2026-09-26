package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;

/**
 * 支持"越界跟手回弹"的竖向列表(RubberBandEngine 的 RecyclerView 宿主)。
 *
 * 用于首页/分类/搜索结果等列表容器;底部"到底继续上推"即回弹。
 * 若外层再套 {@link RubberBandSwipeRefreshLayout}(顶部下拉混合刷新),顶部方向由
 * 刷新容器接管,本组件只承担内容区间滚动与到底回弹,互不冲突(此时布局上建议
 * 设 app:rubber_top_enabled="false",顶部统一交给刷新容器)。
 *
 * 说明:
 * - 直接继承 androidx RecyclerView,保持与原列表一致的测量/间距/动画行为;
 * - 默认回弹作用对象 = 整个列表容器自身(container);列表子条目是复用的,不适合 content 模式;
 * - 布局属性与 RubberBandScrollView 共用一套(rubber_top_enabled / rubber_bottom_enabled /
 *   rubber_max_overscroll / rubber_saturation),其中 rubber_target 对本组件固定视为 container;
 * - 原生滚动/惯性/遥控器焦点滚动不受影响,仅接管触摸越界;
 * - 构造内自动关闭系统 overscroll,避免与自绘回弹叠加。
 */
public class RubberBandRecyclerView extends RecyclerView implements RubberBandEngine.Host {

    private final RubberBandEngine mEngine;

    public RubberBandRecyclerView(Context context) {
        this(context, null);
    }

    public RubberBandRecyclerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RubberBandRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        mEngine = new RubberBandEngine(this);
        mEngine.setTarget(RubberBandEngine.Target.CONTAINER);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RubberBandView);
            mEngine.setTopEnabled(
                    a.getBoolean(R.styleable.RubberBandView_rubber_top_enabled, mEngine.isTopEnabled()));
            mEngine.setBottomEnabled(
                    a.getBoolean(R.styleable.RubberBandView_rubber_bottom_enabled, mEngine.isBottomEnabled()));
            mEngine.setMaxOverscrollPx(a.getDimension(
                    R.styleable.RubberBandView_rubber_max_overscroll, mEngine.getMaxOverscrollPx()));
            mEngine.setSaturation(a.getFloat(
                    R.styleable.RubberBandView_rubber_saturation, mEngine.getSaturation()));
            a.recycle();
        }
    }

    /** 引擎(动态改配置/开关回弹经它) */
    public RubberBandEngine engine() {
        return mEngine;
    }

    // ================= RubberBandEngine.Host =================

    @Override
    public View view() {
        return this;
    }

    @Override
    public boolean canScrollUp() {
        return canScrollVertically(-1);
    }

    @Override
    public boolean canScrollDown() {
        return canScrollVertically(1);
    }

    @Override
    public void scrollContentBy(int dyPx) {
        scrollBy(0, dyPx);
    }

    @Override
    public void nativeBeginGesture(float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        super.onTouchEvent(down);
        down.recycle();
    }

    @Override
    public void nativeEndGesture() {
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
        super.onTouchEvent(cancel);
        cancel.recycle();
    }

    // ================= 事件接线 =================

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mEngine.shouldIntercept(ev)) {
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mEngine.onTouchEvent(ev)) {
            return true;
        }
        boolean handled = super.onTouchEvent(ev);
        mEngine.onNativeTouch(ev);
        return handled;
    }
}

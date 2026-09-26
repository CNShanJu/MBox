package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

import com.github.tvbox.osc.R;

/**
 * 支持越界"跟手回弹"的竖向 ScrollView(RubberBandEngine 的 ScrollView 宿主)。
 *
 * 布局属性(共用 RubberBandView 声明):
 * <pre>
 *   app:rubber_top_enabled    顶部方向(下拉越界)是否回弹,默认 true
 *   app:rubber_bottom_enabled 底部方向(上推越界)是否回弹,默认 true
 *   app:rubber_target         回弹作用对象:content(内容子View,默认)| container(整个容器一起回弹)
 *   app:rubber_max_overscroll 最大越界位移,默认 120dp
 *   app:rubber_saturation     橡皮筋"拉伸比"(>=1,1≈1:1 跟手),默认 1
 * </pre>
 * 例如设置页整块容器回弹:
 * <pre>
 *   &lt;com.github.tvbox.osc.ui.kit.RubberBandScrollView
 *       android:layout_width="match_parent"
 *       android:layout_height="wrap_content"
 *       app:rubber_target="container" /&gt;
 * </pre>
 * 代码方式:在实例上调用 {@link #engine()} 取引擎配置即可。
 */
public class RubberBandScrollView extends ScrollView implements RubberBandEngine.Host {

    private final RubberBandEngine mEngine;

    public RubberBandScrollView(Context context) {
        this(context, null);
    }

    public RubberBandScrollView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RubberBandScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // 回弹完全由引擎负责,关闭系统发光/Android12 stretch 避免叠加
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        mEngine = new RubberBandEngine(this);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RubberBandView);
            mEngine.setTopEnabled(
                    a.getBoolean(R.styleable.RubberBandView_rubber_top_enabled, mEngine.isTopEnabled()));
            mEngine.setBottomEnabled(
                    a.getBoolean(R.styleable.RubberBandView_rubber_bottom_enabled, mEngine.isBottomEnabled()));
            mEngine.setTarget(a.getInt(R.styleable.RubberBandView_rubber_target, 0) == 1
                    ? RubberBandEngine.Target.CONTAINER : RubberBandEngine.Target.CONTENT);
            mEngine.setMaxOverscrollPx(a.getDimension(
                    R.styleable.RubberBandView_rubber_max_overscroll, mEngine.getMaxOverscrollPx()));
            mEngine.setSaturation(a.getFloat(
                    R.styleable.RubberBandView_rubber_saturation, mEngine.getSaturation()));
            a.recycle();
        }
    }

    /** 引擎(后续动态改配置/开关回弹都经它) */
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

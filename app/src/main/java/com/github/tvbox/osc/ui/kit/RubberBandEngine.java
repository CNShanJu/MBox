package com.github.tvbox.osc.ui.kit;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * 越界"跟手回弹"引擎(iOS 橡皮筋手感),与具体滚动组件解耦的可复用能力。
 *
 * 现象:触摸/鼠标把竖向列表拖到顶部/底部后继续往外拖,内容会"超出边界"跟手一段
 * (位移随拖出距离阻尼饱和),松手后带轻微过冲弹回;避免拖到边界硬停的"死板"手感。
 *
 * 接入方式(任意竖向滚动组件,如 ScrollView / NestedScrollView / RecyclerView):
 * <pre>
 * class XxxView extends ... implements RubberBandEngine.Host {
 *     private final RubberBandEngine engine = new RubberBandEngine(this);
 *     {@literal @}Override public boolean onInterceptTouchEvent(MotionEvent ev) {
 *         return engine.shouldIntercept(ev) || super.onInterceptTouchEvent(ev);
 *     }
 *     {@literal @}Override public boolean onTouchEvent(MotionEvent ev) {
 *         if (engine.onTouchEvent(ev)) return true;          // 引擎已消费(回弹中/边界接管)
 *         boolean handled = super.onTouchEvent(ev);          // 区间内滚动/惯性仍走原生
 *         engine.onNativeTouch(ev);                          // 同步原生手势状态
 *         return handled;
 *     }
 *     // Host:view()/canScrollUp()/canScrollDown()/scrollContentBy()/
 *     //      nativeBeginGesture()/nativeEndGesture() 按各自组件实现
 * }
 * </pre>
 *
 * 能力:
 * <ul>
 *   <li>顶部/底部回弹可分别开关 {@link #setTopEnabled(boolean)} / {@link #setBottomEnabled(boolean)}
 *       (布局属性 rubber_top_enabled / rubber_bottom_enabled)。如首页列表"到底继续下拉才回弹、
 *       顶部不触发"(顶部已让位给下拉刷新)即可只开 bottom;</li>
 *   <li>回弹作用对象可选:内容子 View({@link Target#CONTENT},默认)或整个容器自身
 *       ({@link Target#CONTAINER},如 我的-设置 整块一起动);
 *       (布局属性 rubber_target="content|container")</li>
 *   <li>最大越界位移 / 阻尼系数可调(rubber_max_overscroll / rubber_saturation)。</li>
 * </ul>
 *
 * 约定:
 * <ul>
 *   <li>区间内的正常滚动与惯性(fling)始终交给容器原生实现,引擎只在"边界+向外拖"时接管,
 *       因此遥控器焦点滚动、各组件原生滚动手感不受影响;</li>
 *   <li>建议宿主在构造里自行 {@code setOverScrollMode(OVER_SCROLL_NEVER)},
 *       避免系统发光/Android12 stretch 与自绘回弹叠加。</li>
 * </ul>
 */
public class RubberBandEngine {

    /** 回弹作用对象 */
    public enum Target {
        /** 只让容器内容子 View 跟手(内容本身超出边界) */
        CONTENT,
        /** 整个容器自身一起回弹(如 我的-设置 整块容器) */
        CONTAINER
    }

    /**
     * 引擎与滚动容器的桥接契约:不同滚动组件各写一个薄实现即可复用引擎。
     */
    public interface Host {

        /** 滚动容器自身(回弹 container 模式、子 View 查找、触摸坐标基准) */
        View view();

        /** 内容上方是否还有可滚动余量(等价 canScrollVertically(-1)) */
        boolean canScrollUp();

        /** 内容下方是否还有可滚动余量(等价 canScrollVertically(1)) */
        boolean canScrollDown();

        /** 内容区内按原生语义滚动 dyPx(scrollY 正方向=向下看更多内容) */
        void scrollContentBy(int dyPx);

        /** 让原生在 (x, y) 重新开始一次滚动手势(通常向 super 补发 ACTION_DOWN) */
        void nativeBeginGesture(float x, float y);

        /** 终止原生正在进行的滚动手势(通常向 super 补发 ACTION_CANCEL) */
        void nativeEndGesture();
    }

    private static final int SPRING_DURATION_BASE = 180;
    private static final int SPRING_DURATION_MAX = 360;

    private final Host mHost;
    private final float mTouchSlop;
    private final float mDensity;

    /** 是否由本组件接管了垂直拖动(超过 slop) */
    private boolean mTracking = false;
    /** 原生(宿主 super)是否已持有当前手势 */
    private boolean mNativeActive = false;

    private float mDownX = 0f;
    private float mDownY = 0f;
    private float mLastX = 0f;
    private float mLastY = 0f;

    /** 越界原始拖出距离(px,有符号):顶部为正,底部为负 */
    private float mOverscrollRaw = 0f;

    private ValueAnimator mSpring = null;

    // ---- 可配置项(默认值即"两方向都可回弹、内容模式") ----
    private boolean mTopEnabled = true;
    private boolean mBottomEnabled = true;
    private Target mTarget = Target.CONTENT;
    private float mMaxOverscrollPx;
    /** 阻尼"拉伸比"参数:>=1,越大回弹越"松"(跟手比例 1/k);1=接近 1:1 跟手 */
    private float mSaturation = 1.0f;

    public RubberBandEngine(Host host) {
        mHost = host;
        mTouchSlop = ViewConfiguration.get(host.view().getContext()).getScaledTouchSlop();
        mDensity = host.view().getResources().getDisplayMetrics().density;
        mMaxOverscrollPx = 120f * mDensity;
    }

    // ================= 配置接口 =================

    public boolean isTopEnabled() {
        return mTopEnabled;
    }

    public void setTopEnabled(boolean topEnabled) {
        mTopEnabled = topEnabled;
    }

    public boolean isBottomEnabled() {
        return mBottomEnabled;
    }

    public void setBottomEnabled(boolean bottomEnabled) {
        mBottomEnabled = bottomEnabled;
    }

    public Target getTarget() {
        return mTarget;
    }

    public void setTarget(Target target) {
        mTarget = target != null ? target : Target.CONTENT;
    }

    public float getMaxOverscrollPx() {
        return mMaxOverscrollPx;
    }

    /** 最大越界位移(px) */
    public void setMaxOverscrollPx(float maxOverscrollPx) {
        mMaxOverscrollPx = Math.max(0f, maxOverscrollPx);
    }

    /** 最大越界位移(dp,便捷) */
    public void setMaxOverscrollDp(float maxOverscrollDp) {
        mMaxOverscrollPx = Math.max(0f, maxOverscrollDp * mDensity);
    }

    public float getSaturation() {
        return mSaturation;
    }

    /** 橡皮筋阻尼"拉伸比"(>=1,1=接近 1:1 跟手;不允许 <1 以免边界初段位移放大抖动) */
    public void setSaturation(float saturation) {
        mSaturation = saturation >= 1f ? saturation : 1f;
    }

    /** 简化开关:一次设置两个方向 */
    public void setBounceDirection(boolean topEnabled, boolean bottomEnabled) {
        mTopEnabled = topEnabled;
        mBottomEnabled = bottomEnabled;
    }

    // ================= 事件接入 =================

    /**
     * 由宿主 onInterceptTouchEvent 调用:决定是否接管垂直拖动(超过 touch slop)。
     * 点击/横向手势不拦截;DOWN 同时复位引擎状态。
     */
    public boolean shouldIntercept(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                resetGesture(ev.getX(), ev.getY());
                mTracking = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!mTracking) {
                    float dy = ev.getY() - mDownY;
                    float dx = ev.getX() - mDownX;
                    if (Math.abs(dy) > mTouchSlop && Math.abs(dy) > Math.abs(dx)) {
                        mTracking = true;
                        return true;
                    }
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTracking = false;
                return false;
            default:
                return false;
        }
    }

    /**
     * 由宿主 onTouchEvent 调用:
     *
     * @return true = 引擎消费了本事件(回弹中/边界接管/松手回弹);
     *         false = 请宿主把本事件交给原生处理(区间内滚动/惯性),处理完再调 {@link #onNativeTouch}
     */
    public boolean onTouchEvent(MotionEvent ev) {
        final View bounceView = resolveBounceView();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                resetGesture(ev.getX(), ev.getY());
                // 交给原生处理 DOWN(宿主随后调 onNativeTouch 记录原生已接管)
                return false;
            case MotionEvent.ACTION_MOVE: {
                float x = ev.getX();
                float y = ev.getY();
                float dy = y - mLastY;

                // 已处于越界回弹:同向加大;反向先减小,越过原点后转原生滚动
                if (mOverscrollRaw != 0f) {
                    float before = mOverscrollRaw;
                    float after = before + dy;
                    boolean crossed = after == 0f || Math.signum(after) != Math.signum(before);
                    if (crossed) {
                        // 拖回越过原点:剩余位移 = 内容正常滚动(手指反向拖动)
                        float leftover = after;
                        mOverscrollRaw = 0f;
                        applyBounce(bounceView, 0f);
                        // 死区:边界处的微小反向抖动(<1.5px)不滚动、不交还原生,
                        // 避免"越界↔滚动"来回切换造成步进跳动
                        if (Math.abs(leftover) >= 1.5f) {
                            mHost.scrollContentBy(-Math.round(leftover));
                            // 后续移动交原生接管(保持原生滚动/惯性)
                            ensureNativeGesture(x, y);
                        }
                    } else {
                        mOverscrollRaw = after;
                        applyBounce(bounceView, rubberBand(after));
                    }
                    mLastX = x;
                    mLastY = y;
                    return true;
                }

                // 边界 + 对应方向开启时 → 进入自绘回弹
                boolean atTop = !mHost.canScrollUp();
                boolean atBottom = !mHost.canScrollDown();
                boolean pullTop = atTop && dy > 0f && mTopEnabled;
                boolean pullBottom = atBottom && dy < 0f && mBottomEnabled;
                if (pullTop || pullBottom) {
                    if (mNativeActive) {
                        mHost.nativeEndGesture();
                        mNativeActive = false;
                    }
                    mOverscrollRaw = dy;
                    applyBounce(bounceView, rubberBand(dy));
                    mLastX = x;
                    mLastY = y;
                    return true;
                }

                // 区间内滚动(含方向被关闭的边界):交原生,必要时先补原生手势起点
                ensureNativeGesture(mLastX, mLastY);
                mLastX = x;
                mLastY = y;
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mTracking = false;
                if (mOverscrollRaw != 0f) {
                    // 松开时仍处于越界 → 回弹
                    springBack(bounceView);
                    mNativeActive = false;
                    return true;
                }
                // 原生收尾(惯性滑动)
                return false;
            }
            default:
                return false;
        }
    }

    /**
     * 宿主把引擎未消费的事件交给原生处理之后调用(同步原生手势生命周期):
     * DOWN=原生已持有手势;UP/CANCEL=原生手势结束。
     */
    public void onNativeTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mNativeActive = true;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mNativeActive = false;
                break;
            default:
                break;
        }
    }

    // ================= 内部实现 =================

    /** 新手势/复位:停动画、清越界、记录起点 */
    private void resetGesture(float x, float y) {
        stopSpring();
        mOverscrollRaw = 0f;
        View bounceView = resolveBounceView();
        if (bounceView != null) {
            bounceView.setTranslationY(0f);
        }
        mDownX = mLastX = x;
        mDownY = mLastY = y;
        mNativeActive = false;
    }

    /** 按 Target 决定把回弹位移加在哪个 View 上 */
    private View resolveBounceView() {
        if (mTarget == Target.CONTAINER) {
            return mHost.view();
        }
        View v = mHost.view();
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            if (group.getChildCount() > 0) {
                return group.getChildAt(0);
            }
        }
        return null;
    }

    private void applyBounce(View bounceView, float translation) {
        if (bounceView != null) {
            bounceView.setTranslationY(translation);
        }
    }

    /** 越界原始距离 → 实际显示位移(指数饱和,逼近上限) */
    private float rubberBand(float dy) {
        return dampOffset(dy, mMaxOverscrollPx, mSaturation);
    }

    /**
     * 橡皮筋阻尼换算(供引擎及配套组件共用,如下拉刷新容器的拉伸位移):
     * y = maxPx * (1 - e^(-|raw| / (maxPx * ratio))),ratio >= 1;
     * 起点斜率 = 1/ratio <= 1,保证边界初段"位移跟随手指不超过 1:1",不会放大抖动;
     * 随后单调逼近上限,拖起来先轻后重。
     */
    public static float dampOffset(float raw, float maxPx, float ratio) {
        if (maxPx <= 0f) {
            return 0f;
        }
        float sign = Math.signum(raw);
        float abs = Math.abs(raw);
        float k = ratio >= 1f ? ratio : 1f;
        float y = maxPx * (1f - (float) Math.exp(-abs / (maxPx * k)));
        return sign * y;
    }

    /** 确保原生已持有手势(没有则在其起点补发 DOWN) */
    private void ensureNativeGesture(float x, float y) {
        if (!mNativeActive) {
            mHost.nativeBeginGesture(x, y);
            mNativeActive = true;
        }
    }

    /** 松手回弹:当前位移 → 0,带轻微过冲 */
    private void springBack(View bounceView) {
        if (bounceView == null) {
            mOverscrollRaw = 0f;
            return;
        }
        final float from = bounceView.getTranslationY();
        if (from == 0f) {
            mOverscrollRaw = 0f;
            return;
        }
        stopSpring();
        mSpring = ValueAnimator.ofFloat(from, 0f);
        int duration = (int) Math.min(SPRING_DURATION_MAX, SPRING_DURATION_BASE + Math.abs(from) * 0.6f);
        mSpring.setDuration(duration);
        // 柔和减速回弹(不过冲):内容不足一屏/频繁往复拖动时避免"抖"
        mSpring.setInterpolator(new DecelerateInterpolator(1.8f));
        mSpring.addUpdateListener(animation -> {
            float value = (Float) animation.getAnimatedValue();
            bounceView.setTranslationY(value);
        });
        mSpring.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mOverscrollRaw = 0f;
                bounceView.setTranslationY(0f);
                mSpring = null;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mOverscrollRaw = 0f;
                bounceView.setTranslationY(0f);
                mSpring = null;
            }
        });
        mSpring.start();
    }

    private void stopSpring() {
        if (mSpring != null) {
            mSpring.cancel();
            mSpring = null;
        }
    }
}

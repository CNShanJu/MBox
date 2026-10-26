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
    /** 上一帧 MOVE 的 y(shouldIntercept 与 onTouchEvent 共用,用于估算单帧速度) */
    private float mPrevMoveY = 0f;
    /** 是否已收到过 MOVE(判断 mPrevMoveY 是否有效) */
    private boolean mHasMove = false;

    /**
     * 本次手势是否被判定为"快速滑动"(fling 意图)。
     *
     * 越界回弹只服务于"慢速、有意识地拖到边界后继续往外拉"的手感;快速甩动(单帧位移
     * 明显超过 touch slop)时,用户要的是原生 fling/惯性滚动,而非"跟手越界回弹"。若在
     * 快速滑动时也抢手势做回弹,手指高速移动会让回弹位移(raw)在几帧内从 slop 飙升到
     * 数百 px,内容"被猛地拉下去一大截再弹回",快速反复滑动即表现为"抽动/震荡"。
     * 因此一旦确认本次手势是快速滑动,整段手势都禁止越界回弹,交原生处理。
     */
    private boolean mFastGesture = false;

    /**
     * 本次手势 DOWN 瞬间,内容是否已经停在顶部/底部边界。
     *
     * 越界回弹只能发生在「手指按下时内容本来就停在边界、然后继续往外拖」这一种情况;
     * 若 DOWN 时内容在中间(快速滑动/惯性滚动中按下),即使后续 MOVE 里 canScrollUp()
     * 暂时返回 false,也绝不能接管 —— 那只是惯性滚到边界的瞬间,抢走手势会导致
     * 「下滑却内容上走 / 原地抖动」的震荡。
     */
    private boolean mDownAtTop = false;
    private boolean mDownAtBottom = false;

    /**
     * DOWN 瞬间内容是否"不足一屏"(既无法上滚也无法下滚,canUp==false 且 canDown==false)。
     *
     * 内容不足一屏时不存在"越界"概念:任何方向的拖动都会同时命中"顶部"和"底部"边界,
     * 快速来回滑动时回弹方向随 dy 符号反复翻转(+1/-1 高频切换),每次翻转都 nativeEndGesture
     * + 重设 translationY,表现为"原地抖动/抽动"。因此该场景必须整体禁用越界回弹。
     */
    private boolean mDownContentFits = false;

    /** 越界原始拖出距离(px,有符号):顶部为正,底部为负 */
    private float mOverscrollRaw = 0f;
    /** 回弹方向:0=未回弹;+1=顶部下拉(越界位移为正);-1=底部上推(越界位移为负) */
    private int mBounceDir = 0;
    /** 越界位移基准 y:越界位移 = 当前 y - 基准,排除"区间内已滚动"部分,避免起步闪现 */
    private float mBounceBaseY = 0f;

    private ValueAnimator mSpring = null;

    /** 退出回弹的滞回阈值(px):反向位移超过它才退出回弹,否则停在原点 —— 见 onTouchEvent 的 MOVE 分支 */
    private final float mBounceHysteresis;

    // ---- 可配置项(默认值即"两方向都可回弹、内容模式") ----
    private boolean mTopEnabled = true;
    private boolean mBottomEnabled = true;
    private Target mTarget = Target.CONTENT;
    private float mMaxOverscrollPx;
    /** 阻尼"拉伸比"参数:>=1,越大回弹越"松"(起始跟手比例 1/k);1=1:1 跟手(无阻尼感) */
    private float mSaturation = 3.0f;

    public RubberBandEngine(Host host) {
        mHost = host;
        mTouchSlop = ViewConfiguration.get(host.view().getContext()).getScaledTouchSlop();
        mDensity = host.view().getResources().getDisplayMetrics().density;
        mMaxOverscrollPx = 120f * mDensity;
        // 滞回按系统 touch slop 计:反向位移不到 slop 一律视为"边界微抖",不退出回弹
        mBounceHysteresis = mTouchSlop;
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
                    // 速度检测(快速甩动判定):
                    // 1) 有相邻帧时,单帧位移明显超过 slop → 快速;
                    // 2) 首帧(dy 首次越过 slop)若累计位移已远超 slop → 说明是高速甩动,同样判快速。
                    //    若不判,快速滑动"第一个 MOVE 就大位移"会漏检,仍被当成慢速拖拽抢走手势。
                    if (mHasMove) {
                        float frameDy = Math.abs(ev.getY() - mPrevMoveY);
                        if (frameDy > mTouchSlop * 1.5f) {
                            mFastGesture = true;
                        }
                    }
                    if (!mHasMove && Math.abs(dy) > mTouchSlop * 4f) {
                        mFastGesture = true;
                    }
                    mPrevMoveY = ev.getY();
                    mHasMove = true;
                    if (Math.abs(dy) > mTouchSlop && Math.abs(dy) > Math.abs(dx)) {
                        // 只在"手指按下时就已在边界、且向外拖"时才由引擎接管;区间内滚动一律放行
                        // 给原生,让 RecyclerView/ScrollView 自己拦截并保持原生惯性与速度跟踪。
                        // 若这里对所有竖向拖动都拦截,会抢走原生手势、丢掉拦截帧的位移(表现为
                        // 起步"闪现"),并破坏 VelocityTracker(fling 变弱、太跟手)。
                        //
                        // 关键:用 DOWN 瞬间的边界快照(mDownAtTop/mDownAtBottom)判断,而不是每帧
                        // 动态 canScrollUp/canScrollDown。快速滑动/惯性滚动时,内容会短暂经过边界,
                        // 动态判断会在"还在滚"的瞬间误判成"已到顶要回弹",抢走手势 → 表现为
                        // "下滑却内容上走 / 原地抖动"的震荡(见 mDownAtTop 字段说明)。
                        //
                        // 拦截阶段只处理"DOWN 时就已在边界"的直接回弹;DOWN 时在中间、慢速拖到
                        // 顶后再继续拉的回弹,由 onTouchEvent(内容已进入原生滚动后)处理。
                        boolean wantTop = mDownAtTop && dy > 0f && mTopEnabled;
                        boolean wantBottom = mDownAtBottom && dy < 0f && mBottomEnabled;
                        // 内容不足一屏:同时"到顶且到底",任何方向的回弹都是无意义的越界抖动,
                        // 快速来回滑动时方向反复翻转造成震荡 —— 整体禁用,交原生处理。
                        if (mDownContentFits) {
                            wantTop = false;
                            wantBottom = false;
                        }
                        // 快速滑动(fling 意图):禁止越界回弹,交原生惯性滚动处理,
                        // 避免"跟手回弹位移飙升"导致的抽动/震荡。
                        if (mFastGesture) {
                            wantTop = false;
                            wantBottom = false;
                        }
                        if (wantTop || wantBottom) {
                            // 与 onTouchEvent 的进入分支保持对称:接管前先无条件结束原生手势。
                            // 原生可能已在 DRAGGING(速度跟踪未回收),不取消就会与引擎的 translationY
                            // 回弹叠加 —— 表现为"快速拉到边界原地弹跳/震荡"。
                            mHost.nativeEndGesture();
                            mNativeActive = false;
                            mTracking = true;
                            mBounceDir = wantTop ? 1 : -1;
                            mBounceBaseY = ev.getY();
                            return true;
                        }
                        // 区间内:不置 mTracking,交给 super.onInterceptTouchEvent 处理
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

                // 速度检测:单帧位移明显超过 slop 即判定为快速甩动(整段手势禁回弹)
                if (mHasMove && Math.abs(y - mPrevMoveY) > mTouchSlop * 1.5f) {
                    mFastGesture = true;
                }
                if (!mHasMove && Math.abs(y - mDownY) > mTouchSlop * 4f) {
                    mFastGesture = true;
                }
                mPrevMoveY = y;
                mHasMove = true;

                // 已处于越界回弹:位移 = 相对 intercept 基准的偏移(排除"区间内已滚动"部分,避免起步闪现)
                if (mBounceDir != 0) {
                    float raw = y - mBounceBaseY;
                    if (raw != 0f && Math.signum(raw) != mBounceDir && Math.abs(raw) > mBounceHysteresis) {
                        // 反向位移超过滞回阈值:明确的反向拖动,退出回弹,剩余位移转为内容正常滚动
                        float leftover = raw;
                        mBounceDir = 0;
                        mOverscrollRaw = 0f;
                        applyBounce(bounceView, 0f);
                        mHost.scrollContentBy(-Math.round(leftover));
                        // 后续移动交原生接管(保持原生滚动/惯性)
                        ensureNativeGesture(x, y);
                    } else if (raw != 0f && Math.signum(raw) != mBounceDir) {
                        // 反向但未超滞回阈值:视为快速滑动到底后的边界微抖,停在回弹原点,
                        // 不跟随反向也不退出 —— 消除"回弹↔滚动"来回切换导致的震荡
                        mOverscrollRaw = 0f;
                        applyBounce(bounceView, 0f);
                    } else {
                        // 同向跟手;raw==0(刚接管的首帧)也走这里:保持接管、零位移,
                        // 不再因"恰好回到原点"反复进出回弹(震荡的主要来源之一)
                        mOverscrollRaw = raw;
                        applyBounce(bounceView, rubberBand(raw));
                    }
                    mLastX = x;
                    mLastY = y;
                    return true;
                }

                // 边界 + 对应方向开启时 → 进入自绘回弹
                // (用 DOWN 快照为主;DOWN 时不在边界但慢速拖到边界后继续拉也允许,避免惯性误判)
                boolean atTopNow = !mHost.canScrollUp();
                boolean atBottomNow = !mHost.canScrollDown();
                // 内容不足一屏:无"越界"概念,禁用回弹(同 shouldIntercept 的口径);
                // 快速滑动(fling 意图):禁用回弹,交原生惯性滚动,避免跟手位移飙升造成的抽动。
                boolean pullTop = !mDownContentFits && !mFastGesture && mTopEnabled && dy > 0f
                        && (mDownAtTop || (atTopNow && slowGesture(dy)));
                boolean pullBottom = !mDownContentFits && !mFastGesture && mBottomEnabled && dy < 0f
                        && (mDownAtBottom || (atBottomNow && slowGesture(dy)));
                if (pullTop || pullBottom) {
                    // 无条件向原生补发 CANCEL:RecyclerView/ScrollView 可能已在原生侧进入拖拽
                    // (此时 mNativeActive 未必为真)。不取消会让它停留在 DRAGGING、速度跟踪不回收,
                    // 松手后与回弹叠加成"震荡"。
                    mHost.nativeEndGesture();
                    mNativeActive = false;
                    mBounceDir = pullTop ? 1 : -1;
                    // 以上一帧为越界基准:本帧 dy 即"越过边界后"的增量,不含区间内已滚动部分
                    mBounceBaseY = mLastY;
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
                if (mBounceDir != 0) {
                    // 松开时仍处于越界 → 回弹
                    mBounceDir = 0;
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

    /** 新手势/复位:停动画、清越界、记录起点与 DOWN 瞬间的边界快照 */
    private void resetGesture(float x, float y) {
        // 关键:若回弹动画正在进行(上次松手的 springBack),不要打断它、也不要瞬间把
        // translationY 归零 —— 快速来回滑动时 DOWN 高频触发,瞬间归零会让内容在
        // "回弹拉伸位"与"0"之间高频跳变,这正是"原地抖动/震荡"的直接来源。
        // 让上一次的回弹动画自然完成,本次手势从当前位置(可能还在回弹途中)继续。
        boolean settlingBack = mSpring != null && mSpring.isRunning();
        if (!settlingBack) {
            stopSpring();
            View bounceView = resolveBounceView();
            if (bounceView != null && bounceView.getTranslationY() != 0f) {
                // 不在回弹动画中但仍有残留位移:平滑归零而非瞬间跳变
                springBack(bounceView);
            }
        }
        mOverscrollRaw = 0f;
        mBounceDir = 0;
        mBounceBaseY = 0f;
        mDownX = mLastX = x;
        mDownY = mLastY = y;
        mPrevMoveY = y;
        mHasMove = false;
        mFastGesture = false;
        mNativeActive = false;
        // 锁定本次手势起点时的边界状态:越界回弹只允许发生在"按下时就已停在边界"的情况下
        mDownAtTop = !mHost.canScrollUp();
        mDownAtBottom = !mHost.canScrollDown();
        mDownContentFits = mDownAtTop && mDownAtBottom;
    }

    /**
     * 单帧拖动是否"慢速"(相邻两帧位移不超过 touch slop)。
     *
     * 用于区分两类"DOWN 时不在边界"的后续:MOVE 中内容滚到边界后,
     * - 慢速:是用户有意识地把内容慢慢拖到顶/底后继续拉,应允许进入回弹;
     * - 快速:是惯性/fling 甩过边界,此时禁止回弹(抢手势会导致反向/震荡)。
     */
    private boolean slowGesture(float dy) {
        return Math.abs(dy) <= mTouchSlop;
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

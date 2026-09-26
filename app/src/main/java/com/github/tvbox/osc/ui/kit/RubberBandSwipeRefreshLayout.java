package com.github.tvbox.osc.ui.kit;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;

/**
 * 顶部下拉 = "拉伸回弹 + 刷新转圈"混合容器(iOS 风格混合下拉刷新)。
 *
 * 在内容滚到顶部后继续下拉:内容(主滚动列表)随手指弹性拉伸,顶部转圈随拉动
 * 渐显/填充;松手未过阈值 → 弹回;越过阈值 → 保持拉伸并转圈"刷新中",
 * 回调 {@link OnRefreshListener},业务数据就绪后调 {@link #setRefreshing(false)} 收起回弹。
 * 底部"到底继续上推"的回弹由内容列表自身的回弹宿主承担(如 {@link RubberBandRecyclerView})。
 *
 * 用法(与旧 SwipeRefreshLayout 几乎一致的接入面):
 * <pre>
 *   refresh.setProgressBackgroundColorSchemeResource(bg);
 *   refresh.setColorSchemeResources(color);
 *   refresh.setOnRefreshListener(() -> reload());
 *   // 数据返回后:
 *   if (refresh.isRefreshing()) refresh.setRefreshing(false);
 * </pre>
 *
 * 结构约定:本组件是普通 FrameLayout,直接/间接内容放在其中;拉伸作用于
 * "可见且可滚动的后代"(最接近原生 SwipeRefreshLayout 的语义),找不到时退化为整个内容。
 * 下拉手势只在内容无法继续向上滚动(已到顶)时接管,遥控焦点滚动、横向手势不受影响。
 */
public class RubberBandSwipeRefreshLayout extends FrameLayout {

    /** 下拉刷新回调(与 androidx SwipeRefreshLayout.OnRefreshListener 同形) */
    public interface OnRefreshListener {
        void onRefresh();
    }

    /** 刷新被"上拉打断/取消"时的回调(容器已收起并弹回) */
    public interface OnRefreshCancelListener {
        void onRefreshCancelled();
    }

    private static final int DEFAULT_TRIGGER_DP = 64;
    private static final float MAX_STRETCH_DP = 160f;
    private static final float STRETCH_SATURATION = 1.0f; // 拉伸比:>=1,1=接近 1:1 跟手(避免边界放大抖动)
    /** 出现起点/收回终点的缩放(0.35 → 1.0) */
    private static final float INDICATOR_MIN_SCALE = 0.35f;
    /** 出现时机:内容位移达到满格的这一比例前指示基本不可见,之后在剩余行程里放大到 1 */
    private static final float APPEAR_START_FRACTION = 0.40f;
    /**
     * 下拉 scrub 帧"慢速系数":一整轮动画需要走 该系数 × (出现→触发) 的位移。
     * 越大帧前进越慢(跟手越"钝");1.0 = 拉满一个触发行程正好转完一轮(偏快),
     * 2.0 = 需要约两个触发行程才转完一轮(明显变慢,手感更从容)。
     */
    private static final float SCRUB_CYCLE_MULTIPLIER = 6.0f;
    private static final int CANCEL_TRIGGER_DP = 20;

    private final float mDensity;
    private final float mTouchSlop;

    // ---- 配置 ----
    private OnRefreshListener mRefreshListener;
    private OnRefreshCancelListener mRefreshCancelListener;
    private float mTriggerPx;
    /** 刷新中"上拉打断"需要的上拉距离(px) */
    private float mCancelTriggerPx;

    // ---- 交互状态 ----
    private boolean mRefreshing = false;
    private boolean mDragging = false;
    private boolean mSettling = false;
    /** 刷新中用户上拉打断手势是否进行中 */
    private boolean mCancelling = false;
    /** 刷新中再次下拉(重复下拉识别):忽略不触发新一轮,只跟手拉伸,松手回到刷新保持位 */
    private boolean mRePullDown = false;
    /** 本次重复下拉是否已提示过(toast+日志各一次,避免识别过程反复弹) */
    private boolean mRePullNotified = false;
    private float mCancelPull = 0f;
    /** 打断后到手指抬起前:吞掉同一手势的剩余移动,避免"回拉-再下拉"来回反复触发 */
    private boolean mSwallow = false;
    /** 打断冷却:此时间点前不允许再次进入下拉刷新(时间戳,uptimeMillis) */
    private long mReArmAt = 0L;
    private static final long RE_ARM_COOLDOWN_MS = 700L;
    private float mDownY = 0f;
    private float mRawPull = 0f;         // 本次原始下拉距离(px)
    private float mPullBase = 0f;        // 本次手势开始前内容已停留的位移(接续上次未完成回弹)
    private float mOffset = 0f;          // 当前内容位移(px)
    private View mTarget = null;         // 本次拉伸作用对象
    private ValueAnimator mSettleAnim = null;
    /** 回弹动画被新手势/新动画打断时置真:旧动画 onEnd 不再复位内容,避免闪跳 */
    private boolean mSettleInterrupted = false;

    // ---- 刷新指示:全局加载态 Lottie(叠加层,画在内容之上,随空白区移动) ----
    private LottieAnimationView mIndicatorView;
    private boolean mIndicatorPlaying = false;
    /** 下拉 scrub 当前帧进度(0..1):拖动手势中随位移推进并自动轮回;松手触发刷新时从此帧续播 */
    private float mScrubProgress = 0f;

    // ---- 注入环境:动画资产/toast/业务日志(由页面用 LoadingAnim/AppBubble/LogStore 组装,kit 不直连) ----
    private PullRefreshEnv mEnv = PullRefreshEnv.NONE;

    /** 注入页面环境(动画文件/尺寸 + toast/业务日志);不注入则动画/提示无副作用 */
    public void setEnv(@Nullable PullRefreshEnv env) {
        mEnv = env != null ? env : PullRefreshEnv.NONE;
    }

    private void toast(String msg) {
        mEnv.toast(msg);
    }

    private void logBiz(String msg) {
        mEnv.log(msg);
    }

    public RubberBandSwipeRefreshLayout(@NonNull Context context) {
        this(context, null);
    }

    public RubberBandSwipeRefreshLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RubberBandSwipeRefreshLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = getResources().getDisplayMetrics().density;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mTriggerPx = DEFAULT_TRIGGER_DP * mDensity;
        mCancelTriggerPx = CANCEL_TRIGGER_DP * mDensity;
    }

    // ================= 对外 API(SwipeRefreshLayout 兼容面) =================

    public void setOnRefreshListener(@Nullable OnRefreshListener listener) {
        mRefreshListener = listener;
    }

    /**
     * 刷新被用户"上拉打断"时的回调(容器已收起转圈并弹回;业务据此可中止本次刷新轮次)。
     */
    public void setOnRefreshCancelListener(@Nullable OnRefreshCancelListener listener) {
        mRefreshCancelListener = listener;
    }

    public boolean isRefreshing() {
        return mRefreshing;
    }

    /** 触发后的"刷新保持位"位移(下拉到底停留展示转圈处) */
    private float refreshHoldOffset() {
        return RubberBandEngine.dampOffset(mTriggerPx, MAX_STRETCH_DP * mDensity, STRETCH_SATURATION);
    }

    /**
     * 结束/开始刷新动画:业务数据就绪后调 {@code setRefreshing(false)} 收回下拉;
     * 进入刷新态由下拉越过阈值自动触发,一般无需手动置 true。
     */
    public void setRefreshing(boolean refreshing) {
        if (mRefreshing == refreshing) return;
        mRefreshing = refreshing;
        if (mRefreshing) {
            scheduleTimeout();
            settleTo(Math.max(mOffset, refreshHoldOffset()));
        } else {
            cancelTimeout();
            // 数据就绪收尾:若手指仍按着"重复下拉",本轮已结束,复位识别状态,
            // 手势退回普通语义(需抬起再拉才可能开新一轮),避免完成瞬间误触发
            mRePullDown = false;
            mRePullNotified = false;
            settleTo(0f);
            logBiz("下拉刷新: 结束(数据就绪)");
            toast("刷新完成");
        }
        updateIndicator();
    }

    /** 图标已改为"全局加载态 Lottie",颜色接口仅保留 SwipeRefreshLayout 兼容面(无效果) */
    public void setColorSchemeResources(int... colorResIds) {
    }

    /** 图标已改为"全局加载态 Lottie",颜色接口仅保留 SwipeRefreshLayout 兼容面(无效果) */
    public void setProgressBackgroundColorSchemeResource(int colorResId) {
    }

    /** 触发刷新的下拉距离(dp),默认 64dp */
    public void setDistanceToTriggerSync(int distanceDp) {
        mTriggerPx = Math.max(1, distanceDp) * mDensity;
    }

    // ================= 手势 =================

    /** 内容(含后代)是否还能向上滚动(未到顶);true=不能 → 顶部下拉可触发刷新 */
    private boolean canNotScrollUp() {
        View content = getChildCount() > 0 ? getChildAt(0) : null;
        return !findScrollableUp(content);
    }

    private static boolean findScrollableUp(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        if (view.canScrollVertically(-1)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (findScrollableUp(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    /** 找可见且可滚动的后代作为拉伸对象;没有则退化到第一个可见子 View */
    private View findTarget() {
        ViewGroup root = this;
        for (int i = 0; i < root.getChildCount(); i++) {
            View found = findPrimaryScrollable(root.getChildAt(i));
            if (found != null) return found;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) return child;
        }
        return null;
    }

    private static View findPrimaryScrollable(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        if (view.canScrollVertically(1) || view.canScrollVertically(-1)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findPrimaryScrollable(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownY = ev.getY();
                mRawPull = 0f;
                mPullBase = mOffset;
                mDragging = false;
                mCancelling = false;
                mRePullDown = false;
                mRePullNotified = false;
                mSwallow = false;
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (mDragging) return true;
                if (mSwallow) {
                    // 打断后冷却期:不响应,整段手势交容器吞掉直到抬起
                    mDragging = true;
                    return true;
                }
                float dy = ev.getY() - mDownY;
                if (mRefreshing) {
                    // 刷新中:方向分流——
                    //   向下继续拉 = "重复下拉"识别:本轮未完成不触发新流程,只跟手拉伸(识别时 toast+日志一次);
                    //   向上拖 = 打断手势(超过 slop 后接管,阈值由 MOVE 判)
                    if (dy > mTouchSlop) {
                        mDragging = true;
                        mCancelling = false;
                        mRePullDown = true;
                        mDownY = ev.getY();
                        mRawPull = 0f;
                        mPullBase = mOffset;
                        mTarget = findTarget();
                        return true;
                    }
                    if (dy < -mTouchSlop) {
                        mCancelling = true;
                        mDragging = true;
                        mDownY = ev.getY();
                        mCancelPull = 0f;
                        mTarget = findTarget();
                        return true;
                    }
                    return false;
                }
                if (mSettling) return false;
                boolean wantPull = dy > mTouchSlop && canNotScrollUp() && now() >= mReArmAt;
                if (wantPull) {
                    mDragging = true;
                    // 内容滚到顶后继续下拉的那刻起算拉伸(此前可能已滚动过列表)
                    mDownY = ev.getY();
                    mRawPull = 0f;
                    mPullBase = mOffset;
                    mTarget = findTarget();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                mCancelling = false;
                mRePullDown = false;
                mRePullNotified = false;
                mSwallow = false;
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                if (mSwallow) {
                    // 打断后的冷却手势:直接吞掉
                    return true;
                }
                if (!mDragging) {
                    // 未被本容器接管(点击/子视图消费等),交由原生/子视图处理
                    return super.onTouchEvent(ev);
                }
                float y = ev.getY();
                if (mCancelling) {
                    mCancelPull = Math.abs(mDownY - y);
                    if (mCancelPull >= mCancelTriggerPx) {
                        cancelRefresh();
                    }
                    return true;
                }
                if (mRePullDown) {
                    // 刷新中重复下拉:只跟手拉伸(在本轮保持位基础上再拉开);
                    // 反向明显上拖(超过打断阈值)= 转为打断
                    if ((mDownY - y) >= mCancelTriggerPx) {
                        mRePullDown = false;
                        cancelRefresh();
                        return true;
                    }
                    mRawPull = Math.max(0f, y - mDownY);
                    applyPull(mRawPull);
                    // 识别:一旦确认是"又一次下拉刷新意图"(越过小距离)即 toast+日志提示一次,
                    // 不触发新流程(上一轮未完成)
                    if (!mRePullNotified && mRawPull >= mCancelTriggerPx) {
                        mRePullNotified = true;
                        logBiz("下拉刷新: 识别到重复下拉(上一轮未完成),防抖不触发新流程");
                        toast("正在刷新中,请稍候");
                    }
                    return true;
                }
                if (mTarget == null) return true;
                mRawPull = Math.max(0f, y - mDownY);
                applyPull(mRawPull);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mDragging = false;
                if (mSwallow) {
                    mSwallow = false;
                    mRePullDown = false;
                    mRePullNotified = false;
                    return true;
                }
                if (mCancelling) {
                    // 打断手势未达阈值:刷新继续
                    mCancelling = false;
                    mRePullDown = false;
                    mRePullNotified = false;
                    return true;
                }
                if (mRePullDown) {
                    // 刷新中重复下拉松手:不触发新流程,回落到"刷新保持位",本轮继续
                    mRePullDown = false;
                    mRePullNotified = false;
                    if (mTarget != null && mRefreshing) {
                        settleTo(refreshHoldOffset());
                    }
                    return true;
                }
                if (mTarget == null) return true;
                if (mRawPull >= mTriggerPx && !mRefreshing && mRefreshListener != null) {
                    // 越过阈值:进入刷新
                    mRefreshing = true;
                    scheduleTimeout();
                    settleTo(refreshHoldOffset());
                    updateIndicator();
                    logBiz("下拉刷新: 触发,等待业务数据");
                    toast("开始刷新");
                    mRefreshListener.onRefresh();
                } else if (!mRefreshing) {
                    settleTo(0f);
                }
                return true;
            }
            default:
                return super.onTouchEvent(ev);
        }
    }

    /**
     * 打断当前刷新(用户上拉触发):收转圈、内容弹回,并回调业务取消本次刷新;
     * 打断后同一手势其余移动吞掉,且短时间内不允许再次触发,避免"来回反复刷新"。
     */
    public void cancelRefresh() {
        if (!mRefreshing) return;
        mRefreshing = false;
        mCancelling = false;
        mDragging = false;
        mRePullDown = false;
        mRePullNotified = false;
        mSwallow = true;
        mReArmAt = now() + RE_ARM_COOLDOWN_MS;
        cancelTimeout();
        logBiz("下拉刷新: 打断(用户操作,将丢弃在途结果)");
        toast("刷新已取消");
        if (mRefreshCancelListener != null) {
            mRefreshCancelListener.onRefreshCancelled();
        }
        settleTo(0f);
        updateIndicator();
    }

    /** 按(上次停留 + 本次下拉)更新内容位移与刷新指示 */
    private void applyPull(float raw) {
        float offset = mPullBase
                + RubberBandEngine.dampOffset(raw, MAX_STRETCH_DP * mDensity, STRETCH_SATURATION);
        mOffset = offset;
        if (mTarget != null) {
            mTarget.setTranslationY(offset);
        }
        updateIndicator();
        invalidate();
    }

    /** 平滑过渡到目标位移(回弹 0 / 刷新保持),期间刷新指示随空白区联动 */
    private void settleTo(float targetOffset) {
        stopSettle();
        final View target = mTarget;
        final float from = mOffset;
        if (target == null || Math.abs(targetOffset - from) < 0.5f) {
            mOffset = targetOffset;
            if (target != null) target.setTranslationY(targetOffset);
            mSettling = false;
            updateIndicator();
            return;
        }
        mSettling = true;
        mSettleInterrupted = false;
        mSettleAnim = ValueAnimator.ofFloat(from, targetOffset);
        int duration = (int) Math.min(320, 140 + Math.abs(targetOffset - from) * 0.8f);
        mSettleAnim.setDuration(duration);
        mSettleAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        mSettleAnim.addUpdateListener(animation -> {
            float value = (Float) animation.getAnimatedValue();
            mOffset = value;
            if (target != null) target.setTranslationY(value);
            updateIndicator();
        });
        mSettleAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mSettling = false;
                mSettleAnim = null;
                // 正常结束才收尾复位;被新动画/新手势打断时不抢内容位移
                if (!mRefreshing && !mSettleInterrupted && mTarget != null) {
                    mTarget.setTranslationY(0f);
                }
                updateIndicator();
            }
        });
        mSettleAnim.start();
    }

    private void stopSettle() {
        if (mSettleAnim != null) {
            mSettleInterrupted = true;
            mSettleAnim.cancel();
            mSettleAnim = null;
        }
    }

    // ================= 刷新指示:全局加载态 Lottie =================

    /** 懒创建:追加为容器最后一个子 View(画在最上层),动画与尺寸来自注入的 {@link PullRefreshEnv} */
    private void ensureIndicator() {
        if (mIndicatorView != null || getContext() == null) return;
        String animFile = mEnv.loadingAnimFilePath();
        if (animFile == null) return; // 未注入动画:仅保持拉伸回弹,不显示指示
        LottieAnimationView lav = new LottieAnimationView(getContext());
        // 下拉刷新专用尺寸:由页面注入(来源 config.json 的 size_refresh)
        int sizeDp = mEnv.refreshIndicatorSizeDp();
        int px = Math.round(sizeDp * mDensity);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px, px);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lav.setLayoutParams(lp);
        try {
            lav.setAnimation(animFile); // 与全局加载态同一动画文件
            lav.setRepeatMode(LottieDrawable.RESTART);
            lav.setRepeatCount(LottieDrawable.INFINITE);
            lav.setSpeed(1f);
            lav.setClipToCompositionBounds(false); // 光晕等超出画布内容不被裁剪
        } catch (Throwable ignored) {
        }
        lav.setVisibility(View.GONE);
        mIndicatorView = lav;
        addView(lav); // 最后加入 → 最上层叠加
    }

    /**
     * 按当前状态摆放刷新指示:水平居中、垂直始终居于"剩余空白区"中心
     * (空白区高度 = 内容下移量 mOffset,中心 y = mOffset/2);
     * 出现/收回按 40% 阈值之后在剩余行程放大到 1、渐显,反向缩小渐隐。
     *
     * 帧播放语义(下拉阶段=scrub 驱动):
     * - 手指下拉:动画帧随下拉位移逐帧前进,走过一整轮后自动从头再来一轮
     *   (进度 = 越过出现点后的行程,按 慢速系数×(出现→触发) 这一轮程取模轮回);
     * - 手指按住不放:没有新的位移,帧静止(不随时间播放);
     * - 松手越过阈值触发刷新:从松开瞬间所在的那一帧继续按时间循环播放
     *   (resumeAnimation 不清零),不会跳回第 0 帧。
     */
    private void updateIndicator() {
        ensureIndicator();
        if (mIndicatorView == null) return;

        float fullOffset = RubberBandEngine.dampOffset(mTriggerPx, MAX_STRETCH_DP * mDensity, STRETCH_SATURATION);
        float reveal = mRefreshing ? 1f : 0f;
        if (!mRefreshing && fullOffset > 0f) {
            float start = APPEAR_START_FRACTION * fullOffset;
            float span = (1f - APPEAR_START_FRACTION) * fullOffset;
            reveal = span > 0f ? Math.min(1f, Math.max(0f, (mOffset - start) / span)) : 0f;
        }

        if (!mRefreshing && reveal <= 0f) {
            if (mIndicatorView.getVisibility() != View.GONE) {
                mIndicatorView.pauseAnimation();
                mIndicatorPlaying = false;
                mIndicatorView.setVisibility(View.GONE);
            }
            return;
        }

        if (mIndicatorView.getVisibility() != View.VISIBLE) {
            mIndicatorView.setVisibility(View.VISIBLE);
        }
        int px = mIndicatorView.getLayoutParams().width;
        float cy = mOffset / 2f; // 空白区中心(容器坐标系)
        mIndicatorView.setTranslationY(cy - px / 2f);
        float scale = mRefreshing ? 1f : (INDICATOR_MIN_SCALE + (1f - INDICATOR_MIN_SCALE) * reveal);
        mIndicatorView.setScaleX(scale);
        mIndicatorView.setScaleY(scale);
        mIndicatorView.setAlpha(mRefreshing ? 1f : reveal);
        if (mRefreshing) {
            // 刷新中:从松开瞬间所在帧起按时间续播(无限轮回),不清零重播
            if (!mIndicatorPlaying) {
                mIndicatorView.setProgress(mScrubProgress);
                mIndicatorView.resumeAnimation();
                mIndicatorPlaying = true;
            }
        } else {
            if (mIndicatorPlaying) {
                // 打断/结束时:定格当前正在播放的帧,之后收起动画不再让帧乱动
                mScrubProgress = mIndicatorView.getProgress();
                mIndicatorView.pauseAnimation();
                mIndicatorPlaying = false;
            } else if (mDragging) {
                // 只有手指正在下拉才 scrub:帧随位移推进,一轮结束自动轮回
                mScrubProgress = scrubProgress(mOffset, fullOffset);
            }
            // 收起/回弹过程:帧停在松开(或打断)瞬间,只做缩放渐隐;
            // 下拉过程中同步到 scrub 结果
            mIndicatorView.setProgress(mScrubProgress);
        }
    }

    /**
     * 下拉 scrub 帧进度:从"出现点"(40% 触发行程)起算,其后每走完
     * {@code SCRUB_CYCLE_MULTIPLIER} × (出现→触发 60% 行程)即从头再来一轮——
     * 对应"下拉一点动一帧、超过一圈开始新的一轮";系数越大一轮所需位移越多、帧走得越慢。
     *
     * @return [0,1):0=动画首帧,越靠近 1 越接近末帧;到达 1 自动回到 0 继续
     */
    private static float scrubProgress(float offset, float fullOffset) {
        if (fullOffset <= 0f) return 0f;
        float start = APPEAR_START_FRACTION * fullOffset;
        float span = (1f - APPEAR_START_FRACTION) * fullOffset * SCRUB_CYCLE_MULTIPLIER;
        if (span <= 0f) return 0f;
        float rel = offset - start;
        if (rel <= 0f) return 0f;
        float p = rel / span;
        p = p - (float) Math.floor(p); // 超过一圈:去掉整数圈,进入下一轮
        return p;
    }

    // ================= 刷新超时保险(回调丢失也不允许"卡住不回弹") =================

    private static long now() {
        return SystemClock.uptimeMillis();
    }

    private static final long REFRESH_TIMEOUT_MS = 8000L;

    private final Runnable mRefreshTimeout = () -> {
        if (mRefreshing) {
            mRefreshing = false;
            logBiz("下拉刷新: 超时(8s)强制收起");
            settleTo(0f);
            updateIndicator();
        }
    };

    private void scheduleTimeout() {
        removeCallbacks(mRefreshTimeout);
        postDelayed(mRefreshTimeout, REFRESH_TIMEOUT_MS);
    }

    private void cancelTimeout() {
        removeCallbacks(mRefreshTimeout);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopSettle();
        cancelTimeout();
        if (mIndicatorView != null) {
            mIndicatorView.pauseAnimation();
            mIndicatorPlaying = false;
        }
    }
}

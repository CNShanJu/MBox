package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;

import java.util.ArrayList;
import java.util.List;

/**
 * 底部悬浮提示控制器(统一"到底了"逻辑):
 *
 * - 列表正在请求(busy)且停在底部时,底部显示"全局加载态 Lottie"(尺寸=下拉刷新 size_refresh),
 *   替代"到底了"文字;
 * - 空闲且满足「数据非空 + 已到底标志 + 确实滚到底 + 曾超一屏」时显示"到底了";
 * - 其余情况全部隐藏。
 *
 * 消除 UserFragment / GridFragment / FastSearchActivity 三处重复的
 * refreshEndTip/updateEndTip 实现;各页面只需按自身语义实现 {@link State}。
 */
public final class ListEndTipController {

    /** 页面状态供给:由各列表页按自身语义实现 */
    public interface State {

        /** 当前生效的结果列表(来源抽屉等切换时返回当前可见者,可为 null) */
        @Nullable
        RecyclerView list();

        /** 列表当前是否有数据 */
        boolean hasData();

        /** 是否已确认"没有更多内容"(分类=endReached;搜索=整轮完成;主页=恒真) */
        boolean endReached();

        /** 对应的列表是否正在发起接口请求(加载更多/整轮搜索未完成等) */
        boolean busy();
    }

    private final View mTip;      // "到底了"TextView
    private final State mState;
    private final List<RecyclerView> mAttached = new ArrayList<>();
    /** 注入环境:busy Lottie 的动画文件/尺寸由页面提供(经 PullRefreshEnv),kit 不直连配置 */
    private PullRefreshEnv mEnv;

    /** 底部请求进行中的 Lottie(与 mTip 同槽位互斥显示) */
    private LottieAnimationView mLoadingView;
    private boolean mLoadingPlaying = false;

    public ListEndTipController(@NonNull View endTipView, @NonNull State state) {
        this(endTipView, state, null);
    }

    public ListEndTipController(@NonNull View endTipView, @NonNull State state, @Nullable PullRefreshEnv env) {
        mTip = endTipView;
        mState = state;
        mEnv = env != null ? env : PullRefreshEnv.NONE;
        mTip.setVisibility(View.GONE);
    }

    /** 绑定滚动触发(滚动时实时判定);同一列表重复绑定忽略 */
    public void attach(@Nullable RecyclerView recyclerView) {
        if (recyclerView == null || mAttached.contains(recyclerView)) return;
        mAttached.add(recyclerView);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                refresh();
            }
        });
    }

    /** 懒创建底部 Lottie:作为 "到底了"TextView 的兄弟加进同一容器(同锚点,盖在内容上方) */
    private void ensureLoadingView() {
        if (mLoadingView != null) return;
        String animFile = mEnv.loadingAnimFilePath();
        if (animFile == null) return; // 未注入动画:busy 时不显示 Lottie(仅隐藏文字)
        ViewGroup parent = (ViewGroup) mTip.getParent();
        Context ctx = mTip.getContext();
        if (parent == null || ctx == null) return;
        LottieAnimationView lav = new LottieAnimationView(ctx);
        int sizePx = Math.round(mEnv.refreshIndicatorSizeDp() * ctx.getResources().getDisplayMetrics().density);
        // 布局参数以 tip 自身为模板按实际类型复制(父容器相关:RelativeLayout 对齐规则 /
        // FrameLayout gravity / 边距均原样继承),避免硬编码 FrameLayout.LayoutParams
        // 在 RelativeLayout 父容器下丢失底部居中规则。
        ViewGroup.LayoutParams base = mTip.getLayoutParams();
        ViewGroup.LayoutParams lp;
        if (base instanceof FrameLayout.LayoutParams) {
            lp = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) base);
        } else if (base instanceof RelativeLayout.LayoutParams) {
            lp = new RelativeLayout.LayoutParams((RelativeLayout.LayoutParams) base);
        } else if (base instanceof LinearLayout.LayoutParams) {
            lp = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) base);
        } else {
            lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        }
        lp.width = sizePx;
        lp.height = sizePx;
        lav.setLayoutParams(lp);
        try {
            lav.setAnimation(animFile); // 与全局加载态同一动画文件
            lav.setRepeatMode(LottieDrawable.RESTART);
            lav.setRepeatCount(LottieDrawable.INFINITE);
            lav.setSpeed(1f);
            lav.setClipToCompositionBounds(false);
        } catch (Throwable ignored) {
        }
        lav.setVisibility(View.GONE);
        parent.addView(lav);
        mLoadingView = lav;
    }

    /** 同步刷新判定:请求中显示 Lottie;空闲按"到底了"规则显隐文字 */
    public void refresh() {
        if (mTip == null) return;
        RecyclerView list = mState.list();
        boolean atBottom = list != null && !list.canScrollVertically(1);
        boolean busy = mState.busy() && atBottom;

        if (busy) {
            ensureLoadingView();
            if (mLoadingView != null) {
                mTip.setVisibility(View.GONE);
                if (mLoadingView.getVisibility() != View.VISIBLE) {
                    mLoadingView.setVisibility(View.VISIBLE);
                }
                if (!mLoadingPlaying) {
                    mLoadingView.playAnimation();
                    mLoadingPlaying = true;
                }
            } else {
                // Lottie 创建失败(无容器):退回显示到底文字? 请求中无内容可到底,直接隐藏
                mTip.setVisibility(View.GONE);
            }
            return;
        }

        if (mLoadingView != null && mLoadingView.getVisibility() != View.GONE) {
            mLoadingView.pauseAnimation();
            mLoadingPlaying = false;
            mLoadingView.setVisibility(View.GONE);
        }
        boolean show = list != null
                && mState.endReached()
                && mState.hasData()
                && atBottom
                && list.canScrollVertically(-1); // 曾有多屏内容
        mTip.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** 数据/滚动/请求状态变化后调度:post 到下一帧再判 */
    public void update() {
        RecyclerView list = mState.list();
        View anchor = list != null ? list : mTip;
        if (anchor != null) {
            anchor.post(this::refresh);
        }
    }
}

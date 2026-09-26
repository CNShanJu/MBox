package com.github.tvbox.osc.ui.kit;

import androidx.annotation.Nullable;

/**
 * 下拉刷新"打断丢弃"守卫:用户在下拉刷新中拖动打断(容器回调 cancel)后置标记;
 * 页面数据到达入口调用 {@link #shouldDiscardArrival()}:返回 true 表示本轮已被打断,
 * 应丢弃在途结果并维持下拉前的旧列表。
 *
 * 消除了 UserFragment / GridFragment 等页面各自维护 cancel 标记的重复实现。
 */
public final class PullRefreshDiscardGuard {

    private final RubberBandSwipeRefreshLayout mContainer;
    private boolean mDiscardArrival = false;

    public PullRefreshDiscardGuard(@Nullable RubberBandSwipeRefreshLayout container) {
        mContainer = container;
        if (mContainer != null) {
            // 打断回调由守卫统一接管(页面无需再 setOnRefreshCancelListener)
            mContainer.setOnRefreshCancelListener(() -> mDiscardArrival = true);
        }
    }

    /** 新一轮刷新开始前调用:清掉上一轮打断标记 */
    public void onRefreshStarted() {
        mDiscardArrival = false;
    }

    /**
     * 在数据到达入口调用:
     *
     * @return true = 本轮刷新已被用户打断,应丢弃该结果并复位;false = 正常应用结果
     */
    public boolean shouldDiscardArrival() {
        if (!mDiscardArrival) return false;
        mDiscardArrival = false;
        return true;
    }
}

package com.github.tvbox.osc.ui.kit;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 列表页"下拉刷新 + 到底了"装配门面:
 *
 * 把 UserFragment/GridFragment(两个首页 fragment)里重复的样板一次收口:
 * 环境注入(动画/toast/日志)、onRefresh 接线、打断丢弃守卫、到底了控制器及其滚动绑定。
 * 页面只需要提供 {@link Callback}:
 * - {@link Callback#onRefresh()}       下拉越过阈值后的数据拉取
 * - {@link Callback#list()/hasData()/endReached()/busy()}  到底了显示所需的页面状态
 *
 * 搜索结果(FastSearch)走静默刷新轮次管理,保持直连容器+同一 ListEndTipController;
 * 若也需"打断丢弃"可在其取消路径接入本门面语义。
 */
public final class ListRefreshSupport {

    /** 页面业务与状态供给 */
    public interface Callback {

        /** 下拉越过阈值:页面开始拉取数据 */
        void onRefresh();

        /** 到底了判定用的当前列表(可为 null 时不绑定滚动刷新) */
        @Nullable
        RecyclerView list();

        /** 当前是否有数据 */
        boolean hasData();

        /** 是否已确认"没有更多内容" */
        boolean endReached();

        /** 是否正在发起接口请求(底部显示加载 Lottie) */
        boolean busy();
    }

    private final RubberBandSwipeRefreshLayout mRefresh;
    private final PullRefreshDiscardGuard mDiscard;
    @Nullable
    private final ListEndTipController mEndTip;

    private ListRefreshSupport(@NonNull RubberBandSwipeRefreshLayout container,
                               @NonNull PullRefreshDiscardGuard discard,
                               @Nullable ListEndTipController endTip) {
        mRefresh = container;
        mDiscard = discard;
        mEndTip = endTip;
    }

    /**
     * 一键装配:注入环境并接线 onRefresh、接管打断回调、创建到底控制器并绑定滚动。
     *
     * @param container  下拉刷新容器(布局根)
     * @param endTipView "到底了"TextView(可为 null 则该页不显示到底提示)
     * @param callback   页面业务/状态
     * @param env        环境注入(动画文件/尺寸 + toast/业务日志);可为 null 表示不注入(组件无副作用降级)
     */
    public static ListRefreshSupport attach(@NonNull RubberBandSwipeRefreshLayout container,
                                            @Nullable View endTipView,
                                            @NonNull Callback callback,
                                            @Nullable PullRefreshEnv env) {
        PullRefreshEnv e = env != null ? env : PullRefreshEnv.NONE;
        container.setEnv(e);
        container.setOnRefreshListener(callback::onRefresh);

        PullRefreshDiscardGuard discard = new PullRefreshDiscardGuard(container);

        ListEndTipController endTip = null;
        if (endTipView != null) {
            endTip = new ListEndTipController(endTipView, new ListEndTipController.State() {
                @Override
                public RecyclerView list() {
                    return callback.list();
                }

                @Override
                public boolean hasData() {
                    return callback.hasData();
                }

                @Override
                public boolean endReached() {
                    return callback.endReached();
                }

                @Override
                public boolean busy() {
                    return callback.busy();
                }
            }, e);
            RecyclerView list = callback.list();
            if (list != null) {
                endTip.attach(list);
            }
        }
        return new ListRefreshSupport(container, discard, endTip);
    }

    /** 新一轮刷新开始前调用:清掉上一轮打断标记 */
    public void onRefreshStarted() {
        mDiscard.onRefreshStarted();
    }

    /** 数据到达入口调用:true=本轮刷新已被打断,应丢弃结果并复位 */
    public boolean shouldDiscardArrival() {
        return mDiscard.shouldDiscardArrival();
    }

    /** 当前是否处于下拉刷新中 */
    public boolean isRefreshing() {
        return mRefresh.isRefreshing();
    }

    /** 结束下拉刷新动画(业务数据就绪/被丢弃后调用) */
    public void finishRefreshing() {
        if (isRefreshing()) {
            mRefresh.setRefreshing(false);
        }
    }

    /** 立即刷新"到底了"判定 */
    public void refreshEndTip() {
        if (mEndTip != null) {
            mEndTip.refresh();
        }
    }

    /** 数据/滚动/请求状态变化后调度到底判定(post 到下一帧) */
    public void updateEndTip() {
        if (mEndTip != null) {
            mEndTip.update();
        }
    }

    /** 原生容器(页面仍可直取,如查询 isRefreshing 等) */
    @NonNull
    public RubberBandSwipeRefreshLayout container() {
        return mRefresh;
    }
}

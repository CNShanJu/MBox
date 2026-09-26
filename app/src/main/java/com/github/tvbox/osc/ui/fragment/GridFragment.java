package com.github.tvbox.osc.ui.fragment;
import com.github.tvbox.osc.util.AppBubble;

import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.BounceInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.LogUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.dialog.GridFilterDialog;
import com.github.tvbox.osc.ui.tv.widget.LoadMoreView;
import com.github.tvbox.osc.ui.RefreshUiEnvFactory;
import com.github.tvbox.osc.ui.kit.ListRefreshSupport;
import com.github.tvbox.osc.ui.kit.RubberBandSwipeRefreshLayout;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.state.SystemState;
import com.github.tvbox.osc.state.SystemStateMonitor;
import com.github.tvbox.osc.util.Utils;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;
import java.util.List;
import java.util.Stack;
import android.view.ViewGroup;
import android.widget.Toast;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class GridFragment extends BaseLazyFragment {
    private MovieSort.SortData sortData = null;
    private RecyclerView mGridView;
    private SourceViewModel sourceViewModel;
    private GridFilterDialog gridFilterDialog;
    private GridAdapter gridAdapter;
    private int page = 1;
    private int maxPage = 1;
    private boolean isLoad = false;
    /** 是否已确认"没有更多"(loadmore 判 end 后置真;恢复快照/刷新时按层复位) */
    private boolean mEndReached = false;
    /** 是否正在加载更多(到底部 Lottie 显示依据) */
    private boolean mLoadMoreBusy = false;
    /** 刷新轮次:看门狗与"断网收尾"按它作废在途的那一轮(见 startLoadWatchdog) */
    private int mRefreshEpoch = 0;
    /** 是否有一轮加载/刷新在途(用于"页面重新可见时是否该补一次加载"的判断) */
    private boolean mLoadInFlight = false;
    /** 下拉刷新 + 到底了 装配门面 */
    private ListRefreshSupport mRefreshSupport = null;
    private boolean isTop = true;
    private View focusedView = null;
    /** 层级快照:每深入一层只把上一层的轻量状态(数据引用/分页/滚动)入栈;
     *  全 fragment 只保留一套 RecyclerView + GridAdapter,不再逐层新建/隐藏视图(避免深目录内存累积) */
    private static class GridInfo{
        public String sortID="";
        public List<Movie.Video> data;    // 该层已加载数据(引用快照;该层不活动时不会被改动)
        public int page = 1;
        public int maxPage = 1;
        public boolean isLoad = false;
        public boolean loadMoreEnd = false; // 该层是否已到“没有更多”(返回时还原 footer 状态)
        public int scrollPos = 0;           // 离开该层时列表首个可见条目位置(返回时还原滚动)
        public int scrollOffset = 0;        // 该条目顶部偏移
    }
    Stack<GridInfo> mGrids = new Stack<GridInfo>(); //导航快照栈(只存轻量状态,不再持有每层各自的 RecyclerView)

    /** 网络监听是否已注册(幂等:可见时注册、离开或销毁时注销) */
    private boolean mNetBound = false;
    /** 上一次已知的离线状态:用于"恢复联网后自动补一次刷新" */
    private boolean mWasOffline = false;

    /**
     * 系统网络状态订阅(单点 {@link SystemStateMonitor},与下载侧同一份事实源)。
     * <p>
     * 这里只负责"恢复联网后自动补一次刷新"(断网期间列表通常是空的,原来必须手动下拉)。
     * 断网提示与"回原页面"改由独立的无网络页承担(NetworkIssueRouter/NoNetworkActivity),
     * 内容页不再显示断网横幅。
     */
    private final SystemStateMonitor.Listener mNetListener = e -> {
        if (e == null || !SystemStateMonitor.TYPE_NETWORK.equals(e.type)) return;
        boolean offline = SystemStateMonitor.VAL_NONE.equals(e.value);
        boolean recovered = mWasOffline && !offline;
        mWasOffline = offline;
        if (offline) stopLoadingForOffline();
        if (recovered) refreshAfterNetworkBack();
    };

    /**
     * 断网时收尾"在途加载"。
     * <p>
     * 为什么必须有:{@code listResult} 的观察者是<b>唯一</b>会调 {@code finishRefreshing()} 的地方,
     * 而断网时请求被网络层快速失败、异常被上层吞成"无结果" → LiveData 从不发射 →
     * 下拉刷新的转圈会一直转(用户从无网络页点"返回/我知道了"回来看到的就是这个)。
     * 收尾后列表若是空的就显示空态;网络恢复由 {@link #refreshAfterNetworkBack()} 自动补一次刷新。
     */
    private void stopLoadingForOffline() {
        mRefreshEpoch++; // 作废在途刷新的看门狗(同一轮才有意义)
        mLoadInFlight = false;
        if (mRefreshSupport != null && mRefreshSupport.isRefreshing()) {
            mRefreshSupport.finishRefreshing();
        }
        if (mLoadMoreBusy) {
            mLoadMoreBusy = false;
            if (gridAdapter != null) gridAdapter.loadMoreComplete(); // 顺带收掉底部"加载中"footer
        }
        if (gridAdapter == null || gridAdapter.getData().isEmpty()) showEmpty();
    }

    public static GridFragment newInstance(MovieSort.SortData sortData) {
        return new GridFragment().setArguments(sortData);
    }

    public GridFragment setArguments(MovieSort.SortData sortData) {
        this.sortData = sortData;
        return this;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_grid;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null && this.sortData == null) {
            //activity销毁再进入,会直接恢复fragment,从而直接getList,导致sortData为空闪退
            this.sortData = GsonUtils.fromJson(savedInstanceState.getString("sortDataJson"), MovieSort.SortData.class);
        }
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("sortDataJson", GsonUtils.toJson(sortData));
    }

    private void changeView(String id, Boolean isFolder){
        if(isFolder){
            this.sortData.flag ="1"; // 修改sortData.flag
        }else {
            this.sortData.flag =null; // 修改sortData.flag
        }
        initView();            // 幂等:确保唯一网格视图/适配器已建立
        saveCurrentView();     // 进入更深层前:把当前层(数据/分页/滚动)压入快照栈
        switchToNewLevel();    // 复用同一视图/适配器,清空旧层数据并复位分页
        this.sortData.id =id; // 修改sortData.id为新的ID
        initViewModel();
        initData();
    }
    // 获取当前页面UI的显示模式 ‘0’ 正常模式 '1' 文件夹模式 '2' 显示缩略图的文件夹模式
    public char getUITag(){
        System.out.println(sortData);
        return (sortData == null || sortData.flag == null || sortData.flag.length() ==0 ) ?  '0' : sortData.flag.charAt(0);
    }
    // 是否允许聚合搜索 sortData.flag的第二个字符为‘1’时允许聚搜
    public boolean enableFastSearch(){  return sortData.flag == null || sortData.flag.length() < 2 || (sortData.flag.charAt(1) == '1'); }
    // 保存当前层级快照(进入更深层前调用):只记录数据引用/分页/滚动等轻量状态
    private void saveCurrentView(){
        if(this.mGridView == null || gridAdapter == null || sortData == null) return;
        GridInfo info = new GridInfo();
        info.sortID = this.sortData.id;
        info.data = gridAdapter.getData(); // 引用当前层数据列表(该层不活动时不会被改动)
        info.page = this.page;
        info.maxPage = this.maxPage;
        info.isLoad = this.isLoad;
        info.loadMoreEnd = this.page > this.maxPage; // 与加载回调中 page>maxPage -> loadMoreEnd 的判定一致
        // 记录离开前的滚动位置,返回时在同一视图上还原,避免回退后列表跳回顶部
        RecyclerView.LayoutManager lm = mGridView.getLayoutManager();
        if (lm instanceof GridLayoutManager) {
            GridLayoutManager glm = (GridLayoutManager) lm;
            int first = glm.findFirstVisibleItemPosition();
            if (first != RecyclerView.NO_POSITION) {
                View v = glm.findViewByPosition(first);
                info.scrollPos = first;
                if (v != null) {
                    info.scrollOffset = Math.max(0, glm.getDecoratedTop(v) - mGridView.getPaddingTop());
                }
            }
        }
        this.mGrids.push(info);
    }
    // 返回上一层:弹出快照,把旧层数据重新挂到唯一适配器上(数据还在,秒开且不重新请求网络)
    public boolean restoreView(){
        if(mGrids.empty()) return false;
        GridInfo info = mGrids.pop();
        this.sortData.id = info.sortID;
        this.page = info.page;
        this.maxPage = info.maxPage;
        this.isLoad = info.isLoad;
        if(mGridView != null && gridAdapter != null){
            this.showSuccess(); // 收起加载/空态占位
            gridAdapter.setNewData(info.data); // 恢复该层数据(BRVH 会自动复位加载更多开关)
            if (info.loadMoreEnd) {
                // 该层之前已"没有更多":还原状态(不再渲染 BRVAH end 行,由底部悬浮提示承担)
                mEndReached = true;
                gridAdapter.loadMoreComplete();
                gridAdapter.setEnableLoadMore(false);
            } else {
                mEndReached = false;
            }
            restoreScroll(info.scrollPos, info.scrollOffset);
            mGridView.requestFocus();
            if (mRefreshSupport != null) mRefreshSupport.updateEndTip();
        }
        return true;
    }
    // 换数据后 RecyclerView 会重置滚动,布局就绪后再滚回原位置(两次调用幂等,保证生效)
    // 注:scrollToPositionWithOffset 属于 LinearLayoutManager/GridLayoutManager,RecyclerView 本身没有
    private void restoreScroll(int pos, int offset){
        if(mGridView == null || mGridView.getLayoutManager() == null || pos <= 0) return;
        LinearLayoutManager lm = mGridView.getLayoutManager() instanceof LinearLayoutManager
                ? (LinearLayoutManager) mGridView.getLayoutManager() : null;
        if (lm == null) return;
        lm.scrollToPositionWithOffset(pos, offset);
        mGridView.post(() -> {
            if(mGridView != null && mGridView.getLayoutManager() instanceof LinearLayoutManager){
                int itemCount = mGridView.getAdapter() == null ? 0 : mGridView.getAdapter().getItemCount();
                int target = itemCount <= 0 ? 0 : Math.min(pos, itemCount - 1);
                ((LinearLayoutManager) mGridView.getLayoutManager()).scrollToPositionWithOffset(target, offset);
            }
        });
    }
    // 切换到新层级:复用同一套 RecyclerView/Adapter,立即清空视图上的旧层数据并复位分页
    private void switchToNewLevel(){
        if(gridAdapter != null){
            gridAdapter.setNewData(null); // 释放当前视图持有的旧层条目(数据本身已入快照栈)
        }
        this.page = 1;
        this.maxPage = 1;
        this.isLoad = false;
        this.mEndReached = false;
        if (mRefreshSupport != null) mRefreshSupport.updateEndTip(); // 清掉旧层残留的"到底了"
    }

    private void initView() {
        if (mGridView != null) return; // 唯一视图/适配器只需初始化一次(initView 会被多次调用)
        mGridView = findViewById(R.id.mGridView);
        mGridView.setHasFixedSize(true);
        // 列数自适应:单卡宽度不超过 GRID_CARD_MAX_WIDTH_DP,屏幕越宽列数越多
        mGridView.setLayoutManager(new V7GridLayoutManager(this.mContext, Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)));
        gridAdapter = new GridAdapter(); // 单适配器:所有层级复用,层级数据通过 setNewData 进出
        mGridView.setAdapter(gridAdapter);

        gridAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                gridAdapter.setEnableLoadMore(true);
                mLoadMoreBusy = true;
                if (mRefreshSupport != null) mRefreshSupport.updateEndTip();
                sourceViewModel.getList(sortData, page);
            }
        }, mGridView);
        gridAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    bundle.putString("vodName", video.name);
                    SourceBean homeSourceBean = com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getHomeSourceBean();
                    if(("12".indexOf(getUITag()) != -1) && (video.tag.equals("folder") || video.tag.equals("cover"))){
                        focusedView = view;
                        changeView(video.id,video.tag.equals("folder"));
                    }
                    else if(homeSourceBean.isQuickSearch() && SystemConfig.isFastSearchMode() && enableFastSearch()){
                        jumpActivity(FastSearchActivity.class, bundle);
                    }else{
                        if(TextUtils.isEmpty(video.id) || video.id.startsWith("msearch:")){
                            jumpActivity(FastSearchActivity.class, bundle);
//                            jumpActivity(SearchActivity.class, bundle);
                        }else {
                            jumpActivity(DetailActivity.class, bundle);
                        }
                    }

                }
            }
        });
        gridAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    jumpActivity(FastSearchActivity.class, bundle);
                }
                return true;
            }
        });
        gridAdapter.setLoadMoreView(new LoadMoreView());

        // 下拉刷新 + 到底了:一键装配(门面统一主题色/onRefresh/打断守卫/到底控制器与滚动绑定)
        attachRefreshAndEndTip();
        findViewById(R.id.btn_filter).setOnClickListener(view -> showFilter());
        syncFilterButton();
        setLoadSir2(mGridView);
    }

    /**
     * 筛选悬浮钮的显隐:该分类<b>没有筛选项内容</b>时隐藏 —— 那种情况下点它也不会弹窗
     * (见 {@link #showFilter()} 的判空),留着只会让人以为是坏的。
     * 筛选项来自分类的 SortData(进页时已确定),换源重建 fragment 时按新值重新校正。
     */
    private void syncFilterButton() {
        View btn = findViewById(R.id.btn_filter);
        if (btn == null) return;
        btn.setVisibility(hasFilterContent() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        bindNetworkListener();
    }

    @Override
    protected void onFragmentPause() {
        unbindNetworkListener();
        super.onFragmentPause();
    }

    @Override
    public void onDestroyView() {
        unbindNetworkListener();
        super.onDestroyView();
    }

    /** 订阅系统网络状态(幂等:只在可见期间订阅),并记录当前是否离线作为"恢复"的基准 */
    private void bindNetworkListener() {
        mWasOffline = isOffline();
        // 页面重新可见时(例如用户从无网络页点了"返回/我知道了"回来)补一次收尾:
        // 断网事件可能在页面不可见期间就发过了,那时监听是注销的,转圈会一直留着
        if (mWasOffline) stopLoadingForOffline();
        // 页面重新可见 + 从未成功加载过 + 当前没有加载在途 + 现在有网 → 补一次完整初始化。
        // 为什么需要:"断网→恢复"的那次状态变化常常发生在页面不可见期间(无网络页盖住、切到别的页),
        // 页面重新可见时它已经错过了,于是网络恢复了页面也不会自己去取数据(真机反馈:恢复后仍无内容,
        // 而且 loading 视图盖着列表连下拉都点不动 —— 那半边已由 stopLoadingForOffline/看门狗修掉)。
        if (!mWasOffline && !isLoad() && !mLoadInFlight
                && (gridAdapter == null || gridAdapter.getData().isEmpty())
                && (mRefreshSupport == null || !mRefreshSupport.isRefreshing())) {
            android.util.Log.i("GridFragment", "页面重新可见且从未加载成功:补一次初始化");
            initData();
        }
        if (mNetBound) return;
        SystemStateMonitor.registerSafe(mNetListener, SystemStateMonitor.TYPE_NETWORK);
        mNetBound = true;
    }

    private void unbindNetworkListener() {
        if (!mNetBound) return;
        SystemStateMonitor.unregisterSafe(mNetListener);
        mNetBound = false;
    }

    private static boolean isOffline() {
        // 口径统一走系统状态单点(未 init/读不到一律按有网;原来这里 catch 返 false、
        // NoNetworkActivity 那份 catch 返 true,同一份判定两处相反)
        return SystemStateMonitor.isOfflineNow();
    }

    /**
     * 恢复联网后自动补一次刷新:断网期间列表通常是空的,原来必须手动下拉才出内容。
     * 只在"当前可见 + 列表为空 + 没有在刷新/加载更多"时补一次,避免与用户操作或加载更多打架。
     */
    private void refreshAfterNetworkBack() {
        if (!currentVisibleState) return;
        if (mRefreshSupport != null && mRefreshSupport.isRefreshing()) return;
        if (mLoadMoreBusy) return;
        if (gridAdapter != null && !gridAdapter.getData().isEmpty()) return;
        onPullRefresh();
    }

    /** 有没有"筛选项内容":有分组、且至少一组里有关键值(空分组 / 组里没值 = 弹窗也是空的,同一个判据) */
    private boolean hasFilterContent() {
        if (sortData == null || sortData.filters == null) return false;
        for (MovieSort.SortFilter filter : sortData.filters) {
            if (filter != null && filter.values != null && !filter.values.isEmpty()) return true;
        }
        return false;
    }

    /**
     * 下拉刷新 + 到底了:一键装配(两个首页 fragment 共用同一门面)
     */
    private void attachRefreshAndEndTip() {
        RubberBandSwipeRefreshLayout container = findViewById(R.id.swipe_refresh);
        View endTip = findViewById(R.id.end_tip);
        if (container == null) return;
        mRefreshSupport = ListRefreshSupport.attach(container, endTip, new ListRefreshSupport.Callback() {
            @Override
            public void onRefresh() {
                onPullRefresh();
            }

            @Override
            public RecyclerView list() {
                return mGridView;
            }

            @Override
            public boolean hasData() {
                return gridAdapter != null && !gridAdapter.getData().isEmpty();
            }

            @Override
            public boolean endReached() {
                return mEndReached;
            }

            @Override
            public boolean busy() {
                // 正在加载更多 / 下拉刷新进行中
                return mLoadMoreBusy || (mRefreshSupport != null && mRefreshSupport.isRefreshing());
            }
        }, RefreshUiEnvFactory.create());
    }

    private void onPullRefresh() {
        if (mRefreshSupport != null) mRefreshSupport.onRefreshStarted(); // 新一轮刷新
        if (sourceViewModel == null) {
            if (mRefreshSupport != null) mRefreshSupport.finishRefreshing();
            return;
        }
        page = 1;
        maxPage = 1;
        isLoad = false;
        mEndReached = false;
        // 复位 footer: 重新开启加载更多并清除旧的"到底了"状态, 下一页请求期间显示"加载中"
        gridAdapter.loadMoreComplete();
        gridAdapter.setEnableLoadMore(true);
        if (mRefreshSupport != null) mRefreshSupport.updateEndTip();
        startLoadWatchdog();
        sourceViewModel.getList(sortData, page);
    }

    /** 加载/刷新看门狗:请求"没人回结果"时兜底收尾,别让首页 loading 永久转圈 */
    private static final long LOAD_WATCHDOG_MS = 45_000L;

    /**
     * 起一轮看门狗(带轮次号)。
     * <p>
     * 为什么需要:结束"加载中/下拉刷新"的唯一入口是 {@code listResult} 的观察者,而有些失败
     * (网络被快速失败后异常被上层吞掉、VM 侧提前 return、源自己抛异常等)根本不发射数据 →
     * loading 永远不停;更糟的是 loading 视图会盖住列表,<b>用户连下拉刷新都点不动</b>
     * (真机反馈:离线冷启动后首页一直转、恢复网络也无从刷新)。
     * 兜底时间取得比 VM 侧所有超时之和还长(typed 15s + 字符串通道 15s),正常慢请求不会被打断;
     * 轮次号保证上一轮的看门狗不会动到下一轮。
     */
    private void startLoadWatchdog() {
        final int epoch = ++mRefreshEpoch;
        mLoadInFlight = true;
        if (mGridView == null) return;
        mGridView.postDelayed(() -> {
            if (epoch != mRefreshEpoch) return;                     // 已收到结果/已进入下一轮:本条作废
            mLoadInFlight = false;
            if (mRefreshSupport != null && mRefreshSupport.isRefreshing()) {
                mRefreshSupport.finishRefreshing();
            }
            if (gridAdapter == null || gridAdapter.getData().isEmpty()) {
                android.util.Log.w("GridFragment", "加载看门狗触发:请求无结果,强制收尾(显示空态)");
                showEmpty();
            }
        }, LOAD_WATCHDOG_MS);
    }

    private void initViewModel() {
        if(sourceViewModel != null) { return;}
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.listResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                // 收到任何结果(数据/空/null)都算本轮结束:作废看门狗并清"在途"标记
                mRefreshEpoch++;
                mLoadInFlight = false;
                // 刷新被用户打断:丢弃在途的第一页结果,维持下拉前旧列表
                if (page == 1 && mRefreshSupport != null && mRefreshSupport.shouldDiscardArrival()) {
                    mLoadMoreBusy = false;
                    mRefreshSupport.updateEndTip();
                    mRefreshSupport.finishRefreshing();
                    return;
                }
//                if(mGridView != null) mGridView.requestFocus();
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    if (page == 1) {
                        showSuccess();
                        isLoad = true;
                        gridAdapter.setNewData(absXml.movie.videoList);
                        // 复位 footer 状态, 避免上一次"到底了"残留(下一页请求期间应显示"加载中")
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    } else {
                        gridAdapter.addData(absXml.movie.videoList);
                    }
                    page++;
                    maxPage = absXml.movie.pagecount;

                    if (page > maxPage) {
                        // 确认没有更多:不再渲染列表尾的 BRVAH end 行,改由底部悬浮提示承担(贴导航栏)
                        mEndReached = true;
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(false);
                        if(page>2)AppBubble.toast("没有更多了");
                    } else {
                        mEndReached = false;
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    }
                } else {
                    if(page == 1){
                        if (gridAdapter != null && !gridAdapter.getData().isEmpty()) {
                            // 刷新返回空但已有旧内容:保留旧列表(反复下拉/打断时序下避免被误清成"暂无数据")
                        } else {
                            showEmpty();
                        }
                    }else{
                        AppBubble.toast("没有更多了");
                        mEndReached = true;
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(false);
                    }
                }
                mLoadMoreBusy = false; // 本轮请求结束(成功/空/到底),底部回到文字判定
                if (mRefreshSupport != null) {
                    mRefreshSupport.updateEndTip();
                    mRefreshSupport.finishRefreshing();
                }
            }
        });
    }

    public boolean isLoad() {
        return isLoad || !mGrids.empty(); //如果有缓存页的话也可以认为是加载了数据的
    }

    private void initData() {
        if (com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getHomeSourceBean().getApi()==null){// 系统杀死app恢复缓存的fragment后会直接getList,此时首页api都未加载完
            showEmpty();
            return;
        }
        showLoading();
        isLoad = false;
        mEndReached = false;
        scrollTop();
        startLoadWatchdog();
        sourceViewModel.getList(sortData, page);
    }

    public boolean isTop() {
        return isTop;
    }

    public void scrollTop() {
        isTop = true;
        mGridView.scrollToPosition(0);
    }

    public void showFilter() {
        if (hasFilterContent() && gridFilterDialog == null) {
            gridFilterDialog = new GridFilterDialog(mContext);
            gridFilterDialog.setData(sortData);
            gridFilterDialog.setOnDismiss(new GridFilterDialog.Callback() {
                @Override
                public void change() {
                    page = 1;
                    initData();
                }
            });
        }
        if (gridFilterDialog != null)
            gridFilterDialog.show();
    }

    /**
     * 屏幕旋转 / 窗口尺寸变化(大屏横竖屏切换)时,按新宽度重算网格列数,
     * 配合卡片布局的固定宽高比,让整页自动刷新,无需重启页面。
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int span = Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP);
        // 所有层级共用同一个网格视图:只需更新一次跨度;返回旧层时会按新跨度自动重排
        updateGridSpan(mGridView, span);
    }

    private void updateGridSpan(RecyclerView recyclerView, int span) {
        if (recyclerView != null && recyclerView.getLayoutManager() instanceof GridLayoutManager) {
            ((GridLayoutManager) recyclerView.getLayoutManager()).setSpanCount(span);
        }
    }
}
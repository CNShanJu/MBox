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
        sourceViewModel.getList(sortData, page);
    }

    private void initViewModel() {
        if(sourceViewModel != null) { return;}
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.listResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
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
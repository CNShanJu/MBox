package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.BounceInterpolator;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.util.AppBubble;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.ui.activity.CollectActivity;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.HistoryActivity;
import com.github.tvbox.osc.ui.activity.LiveActivity;
import com.github.tvbox.osc.ui.activity.MainActivity;

import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.RefreshUiEnvFactory;
import com.github.tvbox.osc.ui.kit.ListRefreshSupport;
import com.github.tvbox.osc.ui.kit.RubberBandSwipeRefreshLayout;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HCallBack;
import com.github.tvbox.osc.util.HeavyTaskUtil;
import com.github.tvbox.osc.util.HomeHotCache;
import com.github.tvbox.osc.util.HomeHotPreloader;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.util.UA;
import com.github.tvbox.osc.util.Utils;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author pj567
 * @date :2021/3/9
 * @description:
 */
public class UserFragment extends BaseLazyFragment {

    private GridAdapter homeHotVodAdapter;
    private List<Movie.Video> homeSourceRec;
    RecyclerView tvHotList1;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 作废刷新及销毁视图后仍在途的缓存、解析和网络结果。 */
    private final AtomicInteger homeHotLoadEpoch = new AtomicInteger();
    /** 下拉刷新 + 到底了 装配门面(容器/打断守卫/到底控制器统一收口) */
    private ListRefreshSupport mRefreshSupport = null;

    public static UserFragment newInstance(List<Movie.Video> recVod) {
        return new UserFragment().setArguments(recVod);
    }

    public UserFragment setArguments(List<Movie.Video> recVod) {
        this.homeSourceRec = recVod;
        return this;
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();

        updateGridSpan();
        // 回到主页再校正一次:期间可能换过订阅/改过直播源,频道列表有无会变
        syncLiveButton();
    }

    /**
     * 直播悬浮钮的显隐:<b>频道列表为空就不显示</b> —— 那种情况点进去只会弹一句"频道列表为空"然后退出
     * (见 {@code LiveActivity.initLiveChannelList}),留着是个死按钮。
     * 列表在源配置解析时确定(ApiConfig.parseJson):优先用户在设置里配的直播源,没配才用订阅源自带的直播,
     * 两者都没有就是空 → 不显示。进主页时已就绪;换源/回前台再校正一次。
     */
    private void syncLiveButton() {
        View btn = findViewById(R.id.btn_live);
        if (btn == null) return;
        boolean hasLive = !com.github.tvbox.osc.spiderapi.LiveChannelConfigProviders.get()
                .getChannelGroupList().isEmpty();
        btn.setVisibility(hasLive ? View.VISIBLE : View.GONE);
    }

    /**
     * 屏幕旋转 / 窗口尺寸变化(大屏横竖屏切换)时,按新宽度重算列数并刷新
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateGridSpan();
    }

    private void updateGridSpan() {
        if (tvHotList1 != null && tvHotList1.getLayoutManager() instanceof GridLayoutManager) {
            GridLayoutManager manager = (GridLayoutManager) tvHotList1.getLayoutManager();
            int span = Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP);
            if (manager.getSpanCount() != span) manager.setSpanCount(span);
        }
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }

    @Override
    protected void init() {
        tvHotList1 = findViewById(R.id.tvHotList1);
        tvHotList1.setHasFixedSize(true);
        // 布局管理器只在首次初始化时创建，切回主页复用它的滚动位置。
        tvHotList1.setLayoutManager(new GridLayoutManager(mContext,
                Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)));
        // 主页右下角直播悬浮按钮(无频道列表时隐藏,见 syncLiveButton)
        findViewById(R.id.btn_live).setOnClickListener(view -> jumpActivity(LiveActivity.class));
        syncLiveButton();
        homeHotVodAdapter = new GridAdapter();
        homeHotVodAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                if (com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getSourceBeanList().isEmpty()){
                    AppBubble.toast("暂无订阅");
                    return;
                }
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));
                Bundle bundle = new Bundle();
                if (!TextUtils.isEmpty(vod.id)) {
                    bundle.putString("id", vod.id);
                    bundle.putString("sourceKey", vod.sourceKey);
                    bundle.putString("vodName", vod.name);
                    jumpActivity(DetailActivity.class, bundle);
                } else {
                    bundle.putString("title", vod.name);
                    openFastSearchFromHome(bundle);
                }
            }
        });

        homeHotVodAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                if (com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getSourceBeanList().isEmpty()) return true;
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));
                Bundle bundle = new Bundle();
                bundle.putString("title", vod.name);
                openFastSearchFromHome(bundle);
                return true;
            }
        });

        tvHotList1.setAdapter(homeHotVodAdapter);
        attachRefreshAndEndTip();
        setLoadSir2(tvHotList1);
        initHomeHotVod(homeHotVodAdapter, false);
    }

    private void openFastSearchFromHome(Bundle bundle) {
        if (getParentFragment() instanceof HomeFragment) {
            ((HomeFragment) getParentFragment()).openFastSearch(bundle);
        } else {
            jumpActivity(FastSearchActivity.class, bundle);
        }
    }

    private void notifyInitialPageSettled() {
        if (getParentFragment() instanceof HomeFragment) {
            ((HomeFragment) getParentFragment()).onFirstPageSettled(this);
        }
    }

    /**
     * 下拉刷新 + 到底了:一键装配(门面统一主题色/onRefresh 接线/打断守卫/到底控制器与滚动绑定)
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
                return tvHotList1;
            }

            @Override
            public boolean hasData() {
                return homeHotVodAdapter != null && !homeHotVodAdapter.getData().isEmpty();
            }

            @Override
            public boolean endReached() {
                return true; // 主页数据一次拉完:到底即提示
            }

            @Override
            public boolean busy() {
                // 主页无分页;下拉刷新进行中且恰好停在底部时,底部也可显示加载 Lottie
                return mRefreshSupport != null && mRefreshSupport.isRefreshing();
            }
        }, RefreshUiEnvFactory.create());
    }

    /**
     * 下拉刷新首页:站点推荐直接重设列表;豆瓣热门清掉当日缓存强制重新拉取(网络请求完成后收起动画)
     */
    private void onPullRefresh() {
        if (mRefreshSupport != null) mRefreshSupport.onRefreshStarted(); // 新一轮刷新
        if (mActivity instanceof MainActivity) {
            ((MainActivity) mActivity).consumeStartupHomeHotPreloader();
        }
        if (SystemConfig.getHomeRec() == 1) {
            if (homeSourceRec != null && homeSourceRec.size() > 0) {
                homeHotVodAdapter.setNewData(homeSourceRec);
                showSuccess();
                if (mRefreshSupport != null) mRefreshSupport.updateEndTip();
            } else {
                showEmpty();
            }
            if (mRefreshSupport != null) mRefreshSupport.finishRefreshing();
            return;
        }
        initHomeHotVod(homeHotVodAdapter, true);
    }

    private void initHomeHotVod(GridAdapter adapter, boolean clearCache) {
        final int epoch = homeHotLoadEpoch.incrementAndGet();
        if (SystemConfig.getHomeRec() == 1) {
            if (homeSourceRec != null && homeSourceRec.size() > 0) {
                showSuccess();
                adapter.setNewData(homeSourceRec);
                if (mRefreshSupport != null) mRefreshSupport.updateEndTip();
            }else {
                showEmpty();
            }
            if (mRefreshSupport != null) mRefreshSupport.finishRefreshing();
            notifyInitialPageSettled();
            return;
        }
        if (!clearCache && mActivity instanceof MainActivity) {
            HomeHotPreloader preloader = ((MainActivity) mActivity).startupHomeHotPreloader();
            if (preloader != null) {
                if (!preloader.isSettled() && adapter.getData().isEmpty()) showLoading();
                preloader.videos().observe(getViewLifecycleOwner(), videos -> {
                    if (!isCurrentHotLoad(adapter, epoch)) return;
                    if (videos != null && !videos.isEmpty()) {
                        showSuccess();
                        adapter.setNewData(videos);
                    } else if (adapter.getData().isEmpty()) {
                        showEmpty();
                    }
                    if (mRefreshSupport != null) {
                        mRefreshSupport.updateEndTip();
                        mRefreshSupport.finishRefreshing();
                    }
                    notifyInitialPageSettled();
                    ((MainActivity) mActivity).consumeStartupHomeHotPreloader();
                });
                return;
            }
        }
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DATE);
        String today = String.format("%d%d%d", year, month, day);
        HeavyTaskUtil.getSerialExecutorService().execute(() -> {
            if (homeHotLoadEpoch.get() != epoch) return;
            ArrayList<Movie.Video> cached = null;
            try {
                if (clearCache) HomeHotCache.clear();
                if (today.equals(HomeHotCache.getDay())) {
                    String json = HomeHotCache.getData();
                    if (!json.isEmpty()) cached = loadHots(json);
                }
            } catch (Throwable ignored) {
                // 缓存不可用时仍从网络加载。
            }
            final ArrayList<Movie.Video> hotMovies = cached;
            mainHandler.post(() -> {
                if (!isCurrentHotLoad(adapter, epoch)) return;
                if (hotMovies != null && !hotMovies.isEmpty()) {
                    showSuccess();
                    adapter.setNewData(hotMovies);
                    if (mRefreshSupport != null) mRefreshSupport.updateEndTip();
                    if (mRefreshSupport != null) mRefreshSupport.finishRefreshing();
                    notifyInitialPageSettled();
                } else {
                    // 缓存没命中时才显示加载态；命中缓存不闪一次 loading。
                    if (adapter.getData().isEmpty()) showLoading();
                    requestHomeHotVod(adapter, today, year, epoch);
                }
            });
        });
    }

    private void requestHomeHotVod(GridAdapter adapter, String today, int year, int epoch) {
        String doubanUrl = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range=" + year + "," + year;
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA.randomOne());
        try {
            HttpClient.get(doubanUrl, headers, null, new HCallBack() {
                @Override
                public void onSuccess(String netJson) {
                    if (homeHotLoadEpoch.get() != epoch) return;
                    HeavyTaskUtil.getSerialExecutorService().execute(() -> {
                        if (homeHotLoadEpoch.get() != epoch) return;
                        ArrayList<Movie.Video> videos = loadHots(netJson);
                        try {
                            HomeHotCache.save(today, netJson);
                        } catch (Throwable ignored) {
                            // 缓存写入失败不影响本次数据展示。
                        }
                        mainHandler.post(() -> {
                            if (!isCurrentHotLoad(adapter, epoch)) return;
                            // 刷新被用户打断:丢弃在途结果,维持下拉前旧列表。
                            if (mRefreshSupport != null && mRefreshSupport.shouldDiscardArrival()) {
                                mRefreshSupport.finishRefreshing();
                                return;
                            }
                            if (!videos.isEmpty()) {
                                showSuccess();
                                adapter.setNewData(videos);
                                if (mRefreshSupport != null) mRefreshSupport.updateEndTip();
                            } else if (adapter.getData().isEmpty()) {
                                showEmpty();
                            } else if (mRefreshSupport != null) {
                                mRefreshSupport.updateEndTip();
                            }
                            if (mRefreshSupport != null) mRefreshSupport.finishRefreshing();
                            notifyInitialPageSettled();
                        });
                    });
                }

                @Override
                public void onError(Throwable e) {
                    mainHandler.post(() -> {
                        if (!isCurrentHotLoad(adapter, epoch)) return;
                        if (adapter.getData().isEmpty()) showEmpty();
                        if (mRefreshSupport != null) mRefreshSupport.finishRefreshing();
                        notifyInitialPageSettled();
                    });
                }
            });
        } catch (Throwable th) {
            th.printStackTrace();
            if (!isCurrentHotLoad(adapter, epoch)) return;
            if (adapter.getData().isEmpty()) showEmpty();
            if (mRefreshSupport != null) mRefreshSupport.finishRefreshing();
            notifyInitialPageSettled();
        }
    }

    private boolean isCurrentHotLoad(GridAdapter adapter, int epoch) {
        return homeHotLoadEpoch.get() == epoch && isAdded() && getView() != null
                && adapter == homeHotVodAdapter;
    }

    @Override
    public void onDestroyView() {
        homeHotLoadEpoch.incrementAndGet();
        super.onDestroyView();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        // BaseLazyFragment keeps rootView but does not rerun init() after a view recreation.
        boolean hadInitialized = !mIsFirstVisible;
        super.onViewCreated(view, savedInstanceState);
        if (hadInitialized && homeHotVodAdapter != null && homeHotVodAdapter.getData().isEmpty()) {
            initHomeHotVod(homeHotVodAdapter, false);
        }
    }

    private ArrayList<Movie.Video> loadHots(String json) {
        return HomeHotPreloader.parse(json);
    }
}

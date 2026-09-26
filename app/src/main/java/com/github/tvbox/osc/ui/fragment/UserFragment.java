package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
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

import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.RefreshUiEnvFactory;
import com.github.tvbox.osc.ui.kit.ListRefreshSupport;
import com.github.tvbox.osc.ui.kit.RubberBandSwipeRefreshLayout;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HCallBack;
import com.github.tvbox.osc.util.HomeHotCache;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.util.UA;
import com.github.tvbox.osc.util.Utils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author pj567
 * @date :2021/3/9
 * @description:
 */
public class UserFragment extends BaseLazyFragment {

    private GridAdapter homeHotVodAdapter;
    private List<Movie.Video> homeSourceRec;
    RecyclerView tvHotList1;
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

        tvHotList1.setHasFixedSize(true);
        // 列数自适应:单卡宽度不超过 GRID_CARD_MAX_WIDTH_DP,屏幕越宽列数越多
        final int span = Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP);
        tvHotList1.setLayoutManager(new GridLayoutManager(this.mContext, span));
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
        if (tvHotList1 != null && tvHotList1.getLayoutManager() instanceof GridLayoutManager) {
            ((GridLayoutManager) tvHotList1.getLayoutManager()).setSpanCount(Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP));
        }
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }

    @Override
    protected void init() {
        tvHotList1 = findViewById(R.id.tvHotList1);
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
                    jumpActivity(FastSearchActivity.class, bundle);
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
                jumpActivity(FastSearchActivity.class, bundle);
                return true;
            }
        });

        tvHotList1.setAdapter(homeHotVodAdapter);
        attachRefreshAndEndTip();
        setLoadSir2(tvHotList1);
        initHomeHotVod(homeHotVodAdapter);
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
        try {
            HomeHotCache.clear();
        } catch (Throwable ignored) {
        }
        initHomeHotVod(homeHotVodAdapter);
    }

    private void initHomeHotVod(GridAdapter adapter) {
        if (SystemConfig.getHomeRec() == 1) {
            if (homeSourceRec != null && homeSourceRec.size() > 0) {
                showSuccess();
                adapter.setNewData(homeSourceRec);
                if (mRefreshSupport != null) mRefreshSupport.updateEndTip();
            }else {
                showEmpty();
            }
            if (mRefreshSupport != null) mRefreshSupport.finishRefreshing();
            return;
        }
        try {
            Calendar cal = Calendar.getInstance();
            int year = cal.get(Calendar.YEAR);
            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DATE);
            String today = String.format("%d%d%d", year, month, day);
            String requestDay = HomeHotCache.getDay();
            if (requestDay.equals(today)) {
                String json = HomeHotCache.getData();
                if (!json.isEmpty()) {
                    ArrayList<Movie.Video> hotMovies = loadHots(json);
                    if (hotMovies != null && hotMovies.size() > 0) {
                        showSuccess();
                        adapter.setNewData(hotMovies);
                        if (mRefreshSupport != null) mRefreshSupport.updateEndTip();
                        if (mRefreshSupport != null) mRefreshSupport.finishRefreshing();
                        return;
                    }
                }
            }
            // 首次加载给出状态;下拉刷新时已有旧数据则不整页盖住
            if (adapter.getData().isEmpty()) {
                showLoading();
            }
            String doubanUrl = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range=" + year + "," + year;
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA.randomOne());
            HttpClient.get(doubanUrl, headers, null, new HCallBack() {
                @Override
                public void onSuccess(String netJson) {
                    HomeHotCache.save(today, netJson);
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // 刷新被用户打断:丢弃在途结果,维持下拉前旧列表
                            if (mRefreshSupport != null && mRefreshSupport.shouldDiscardArrival()) {
                                if (mRefreshSupport != null) mRefreshSupport.finishRefreshing();
                                return;
                            }
                            ArrayList<Movie.Video> videos = loadHots(netJson);
                            if (videos.size() > 0) {
                                showSuccess();
                                adapter.setNewData(videos);
                                if (mRefreshSupport != null) mRefreshSupport.updateEndTip();
                            } else if (adapter.getData().isEmpty()) {
                                // 无旧内容才进空态;已有内容时刷新拿到空结果保留旧列表,
                                // 避免"反复下拉/打断"时序把已有内容误清成"暂无数据"
                                showEmpty();
                            } else {
                                if (mRefreshSupport != null) mRefreshSupport.updateEndTip();
                            }
                            if (mRefreshSupport != null) mRefreshSupport.finishRefreshing();
                        }
                    });
                }

                @Override
                public void onError(Throwable e) {
                    // 保持原行为(旧列表不变); 首载失败也要有状态,避免一直停在 loading
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (adapter.getData().isEmpty()) {
                                showEmpty();
                            }
                            if (mRefreshSupport != null) mRefreshSupport.finishRefreshing();
                        }
                    });
                }
            });
        } catch (Throwable th) {
            th.printStackTrace();
            if (adapter.getData().isEmpty()){
                showEmpty();
            }
            if (mRefreshSupport != null) mRefreshSupport.finishRefreshing();
        }
    }

    private ArrayList<Movie.Video> loadHots(String json) {
        ArrayList<Movie.Video> result = new ArrayList<>();
        try {
            JsonObject infoJson = new Gson().fromJson(json, JsonObject.class);
            JsonArray array = infoJson.getAsJsonArray("data");
            for (JsonElement ele : array) {
                JsonObject obj = (JsonObject) ele;
                Movie.Video vod = new Movie.Video();
                vod.name = obj.get("title").getAsString();
                vod.note = obj.get("rate").getAsString();
                if (!vod.note.isEmpty()) vod.note += " 分";
                vod.pic = obj.get("cover").getAsString() + "@Referer=https://movie.douban.com/@User-Agent=" + UA.random();
                result.add(vod);
            }
        } catch (Throwable th) {

        }
        return result;
    }
}
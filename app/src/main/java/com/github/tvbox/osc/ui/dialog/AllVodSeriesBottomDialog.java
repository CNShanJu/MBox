package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.airbnb.lottie.LottieAnimationView;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.ui.kit.GridSpacingItemDecoration;
import com.github.tvbox.osc.ui.widget.RoundChip;
import com.github.tvbox.osc.util.LoadingAnim;
import com.github.tvbox.osc.util.LoadingAnimFit;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.interfaces.OnSelectListener;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * 全集弹窗(详情页"全部"):RoundChip 文字样式(与全屏选集/下载抽屉统一,无边框无背景),网格,单选。
 * 不像全屏右侧弹窗一样共用activity的adapter,adapter横向和网格布局逻辑不同,同屏显示切换会有视觉差
 * <p>
 * <b>先弹出、后填内容</b>(与下载抽屉 {@link DownloadSeriesDialog} 同一套时序,用户口径 2026-11-20):
 * 构造时不接收集数列表,onCreate 只建空网格 + loading 占位并进入收起态;
 * 宿主在弹窗真正显示后调 {@link #setData},再一次性灌数据。
 * <p>
 * 旧实现在构造时就把整表交给 BaseQuickAdapter,弹窗首帧即要 inflate 全部 RoundChip
 * (sheet 定高还会对列表做 UNSPECIFIED 高度测量,等于把所有条目都布局一遍),几千集时
 * 点击后主线程被占住,表现就是"点了没反应,过一会儿才弹出来"。
 */
public class AllVodSeriesBottomDialog extends SheetResizableBottomPopup {

    /** 集数超过这个量就跳过"按内容测自然高"(必然超出一屏),避免整表布局 */
    private static final int LONG_LIST_SKIP_MEASURE = 40;

    private List<VodInfo.VodSeries> mList = Collections.emptyList();
    private RecyclerView mRv;
    /** 数据就绪前的占位(与下载抽屉一致) */
    private View mLoadingView;
    /** 是否还没做过首次填充(滚动到选中集、决定弹窗高度都只在首次做) */
    private boolean mFirstFill = true;
    private GridLayoutManager mGridManager;
    private GridSpacingItemDecoration mGridDecoration;
    private BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> mSeriesAdapter;
    private final OnSelectListener mSelectListener;
    /** 倒序回调:执行详情页 sortSeries(共用状态);由 DetailActivity 传入 */
    private final Runnable mSortAction;
    /** 当前是否已倒序(供按钮文字切换);由 DetailActivity 注入 isSeriesReversed */
    private final java.util.function.BooleanSupplier mIsReversed;
    private TextView mTvSort;

    public AllVodSeriesBottomDialog(@NonNull @NotNull Context context,
                                    OnSelectListener selectListener, Runnable sortAction,
                                    java.util.function.BooleanSupplier isReversed) {
        super(context);
        mSelectListener = selectListener;
        mSortAction = sortAction;
        mIsReversed = isReversed;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_all_series;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        mRv = findViewById(R.id.rv);
        mLoadingView = findViewById(R.id.fl_loading);
        // 加载动画跟随设置页"加载动画"选项(默认/Glowing Fish),与下载抽屉一致
        LottieAnimationView lav = findViewById(R.id.lottie_loading);
        LoadingAnim.apply(lav);
        if (mLoadingView instanceof android.view.ViewGroup) {
            LoadingAnimFit.bindToSlot(lav, (android.view.ViewGroup) mLoadingView, 8);
        }

        // 倒序按钮:文字随共用状态切换(未倒序=倒序, 已倒序=正序);与下载/全屏弹窗共用 sortSeries
        mTvSort = findViewById(R.id.tv_sort);
        updateSortButton();
        findViewById(R.id.tv_sort).setOnClickListener(v -> {
            if (mSortAction != null) mSortAction.run();
            updateSortButton();
            // 倒序后同一列表引用内容已反转,刷新显示(选中态随 item 保持)
            if (mSeriesAdapter != null) mSeriesAdapter.notifyDataSetChanged();
        });

        // 集数网格:最多3列,基于文字长度自适应(1列/2列/3列),RoundChip 文字条目(与全屏抽屉同款,无边框)。
        // 数据未到前先用默认 3 列,setData 时按实际集名长度重算
        mGridManager = new GridLayoutManager(getContext(), 3);
        mRv.setLayoutManager(mGridManager);
        mGridDecoration = new GridSpacingItemDecoration(3, 20, true);
        mRv.addItemDecoration(mGridDecoration);

        mSeriesAdapter =
                new BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder>(R.layout.item_series) {
                    @Override
                    protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
                        RoundChip chip = helper.getView(R.id.sl);
                        chip.setTitle(item.name);
                        chip.setSelected(item.selected);
                        chip.setChipTextSize(16f); // 与全屏选集抽屉文字大小一致
                    }
                };
        mRv.setAdapter(mSeriesAdapter);

        mSeriesAdapter.setOnItemClickListener((adapter, view, position) -> {
            if (position < 0 || position >= mList.size() || mSelectListener == null) return;
            mSelectListener.onSelect(position, "");
            // 选中状态由宿主维护；本弹窗只根据更新后的 item 状态重绑。
            if (mSeriesAdapter != null) mSeriesAdapter.notifyDataSetChanged();
        });

        // 空列表 + loading:抽屉先以收起态弹出来,内容随后填
        showLoading(true);
        // 顶部手势条/标题一带可拖、可点:至少保持 50%;内容超高可展开到 70%。
        // 不自动 sync —— 高度决策等数据到了再做(setData 里),否则量的是空列表
        attachSheet(R.id.bg, R.id.list_box, R.id.rv, false);
    }

    /**
     * 填充集数(主线程调用;弹窗显示后由宿主触发)。
     * <p>
     * 与 {@link #updateData} 同一实现:首开与显示中刷新都走它,避免两条路径各自维护。
     */
    public void setData(List<VodInfo.VodSeries> list) {
        updateData(list);
    }

    /** 宿主切换线路或刷新选集后同步当前数据和网格列数。 */
    public void updateData(List<VodInfo.VodSeries> list) {
        mList = list != null ? list : Collections.emptyList();
        updateSortButton();
        if (mRv == null || mSeriesAdapter == null) return;
        showLoading(false);
        // 只有首次填充才滚到正在播放的那集、才决定弹窗高度:
        // 显示中刷新(切线路/倒序后重绑)不该把用户已经拖开的抽屉压回收起态,也不该把列表拽回去
        final boolean firstFill = mFirstFill;
        mFirstFill = false;
        int span = Utils.getSeriesSpanCount(mList);
        if (mGridManager != null && mGridManager.getSpanCount() != span) {
            mGridManager.setSpanCount(span);
            if (mGridDecoration != null) mRv.removeItemDecoration(mGridDecoration);
            mGridDecoration = new GridSpacingItemDecoration(span, 20, true);
            mRv.addItemDecoration(mGridDecoration);
        }
        mSeriesAdapter.setNewData(mList);
        if (firstFill) scrollToSelected();
        mRv.post(() -> {
            if (!firstFill) return;
            SheetResizeController controller = sheetResize();
            if (controller == null || !isShow()) return;
            // 长列表是选集的常态:直接进"可展开"态,跳过按内容测自然高 ——
            // 那次测量用 UNSPECIFIED 高度,会让 RecyclerView 把整表都布局一遍,
            // 几千集时正是 loading 转圈也跟着顿住的来源。集数很少时才走自适应测量。
            if (mList.size() > LONG_LIST_SKIP_MEASURE) controller.forceExpandable(0);
            else controller.sync();
        });
    }

    /** 滚到当前播放的那一集(xpopup 重写 maxHeight 后布局完成未滑动完毕,定位需延迟) */
    private void scrollToSelected() {
        if (mRv == null) return;
        mRv.postDelayed(() -> {
            if (!isShow()) return;
            for (int i = 0; i < mList.size(); i++) {
                if (mList.get(i).selected) {
                    mRv.smoothScrollToPosition(i);
                }
            }
        }, 300);
    }

    private void showLoading(boolean loading) {
        if (mLoadingView != null) mLoadingView.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (mRv != null) mRv.setVisibility(loading ? View.GONE : View.VISIBLE);
    }

    /** 倒序按钮文字跟随共用状态:已倒序显示"正序",否则"倒序" */
    public void updateSortButton() {
        try {
            if (mTvSort != null && mIsReversed != null) {
                mTvSort.setText(mIsReversed.getAsBoolean() ? "正序" : "倒序");
            }
        } catch (Throwable ignored) {
        }
    }
}

package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.ui.kit.GridSpacingItemDecoration;
import com.github.tvbox.osc.ui.widget.RoundChip;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.interfaces.OnSelectListener;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * 全集弹窗(详情页"全部"):RoundChip 文字样式(与全屏选集/下载抽屉统一,无边框无背景),网格,单选。
 * 不像全屏右侧弹窗一样共用activity的adapter,adapter横向和网格布局逻辑不同,同屏显示切换会有视觉差
 */
public class AllVodSeriesBottomDialog extends SheetResizableBottomPopup {

    private List<VodInfo.VodSeries> mList;
    private RecyclerView mRv;
    private GridLayoutManager mGridManager;
    private GridSpacingItemDecoration mGridDecoration;
    private BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> mSeriesAdapter;
    private final OnSelectListener mSelectListener;
    /** 倒序回调:执行详情页 sortSeries(共用状态);由 DetailActivity 传入 */
    private final Runnable mSortAction;
    /** 当前是否已倒序(供按钮文字切换);由 DetailActivity 注入 isSeriesReversed */
    private final java.util.function.BooleanSupplier mIsReversed;
    private TextView mTvSort;

    public AllVodSeriesBottomDialog(@NonNull @NotNull Context context, List<VodInfo.VodSeries> list,
                                    OnSelectListener selectListener, Runnable sortAction,
                                    java.util.function.BooleanSupplier isReversed) {
        super(context);
        mList = list != null ? list : Collections.emptyList();
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

        // 倒序按钮:文字随共用状态切换(未倒序=倒序, 已倒序=正序);与下载/全屏弹窗共用 sortSeries
        mTvSort = findViewById(R.id.tv_sort);
        updateSortButton();
        findViewById(R.id.tv_sort).setOnClickListener(v -> {
            if (mSortAction != null) mSortAction.run();
            updateSortButton();
            // 倒序后同一列表引用内容已反转,刷新显示(选中态随 item 保持)
            if (mSeriesAdapter != null) mSeriesAdapter.notifyDataSetChanged();
        });

        // 集数网格:最多3列,基于文字长度自适应(1列/2列/3列),RoundChip 文字条目(与全屏抽屉同款,无边框)
        int span = Utils.getSeriesSpanCount(mList);
        mGridManager = new GridLayoutManager(getContext(), span);
        mRv.setLayoutManager(mGridManager);
        mGridDecoration = new GridSpacingItemDecoration(span, 20, true);
        mRv.addItemDecoration(mGridDecoration);

        mSeriesAdapter =
                new BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder>(R.layout.item_series, mList) {
                    @Override
                    protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
                        RoundChip chip = helper.getView(R.id.sl);
                        chip.setTitle(item.name);
                        chip.setSelected(item.selected);
                    }
                };
        mRv.setAdapter(mSeriesAdapter);

        mRv.postDelayed(() -> {//xpopup重写maxHeight后布局完成未滑动完毕导致定位异常,加延时可正常滑动
            if (!isShow()) return;
            for (int i = 0; i < mList.size(); i++) {
                if (mList.get(i).selected) {
                    mRv.smoothScrollToPosition(i);
                }
            }
        }, 500);

        // 顶部手势条/标题一带可拖、可点:至少保持 50%;内容超高可展开到 70%
        attachSheet(R.id.bg, R.id.list_box, R.id.rv, true);

        mSeriesAdapter.setOnItemClickListener((adapter, view, position) -> {
            if (position < 0 || position >= mList.size() || mSelectListener == null) return;
            mSelectListener.onSelect(position, "");
            // 选中状态由宿主维护；本弹窗只根据更新后的 item 状态重绑。
            if (mSeriesAdapter != null) mSeriesAdapter.notifyDataSetChanged();
        });
    }

    /** 宿主切换线路或刷新选集后同步当前数据和网格列数。 */
    public void updateData(List<VodInfo.VodSeries> list) {
        mList = list != null ? list : Collections.emptyList();
        updateSortButton();
        if (mRv == null || mSeriesAdapter == null) return;
        int span = Utils.getSeriesSpanCount(mList);
        if (mGridManager != null && mGridManager.getSpanCount() != span) {
            mGridManager.setSpanCount(span);
            if (mGridDecoration != null) mRv.removeItemDecoration(mGridDecoration);
            mGridDecoration = new GridSpacingItemDecoration(span, 20, true);
            mRv.addItemDecoration(mGridDecoration);
        }
        mSeriesAdapter.setNewData(mList);
        mRv.post(() -> {
            SheetResizeController controller = sheetResize();
            if (controller != null && isShow()) controller.sync();
        });
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

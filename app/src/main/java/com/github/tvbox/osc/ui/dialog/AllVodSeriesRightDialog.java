package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.ui.adapter.SeriesAdapter;
import com.github.tvbox.osc.ui.adapter.SeriesFlagAdapter;
import com.github.tvbox.osc.ui.kit.GridSpacingItemDecoration;
import com.github.tvbox.osc.util.Utils;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 点播右侧全集弹窗(横屏)。
 * 复用宿主(详情页)的线路/选集 adapter(同一实例,host 与弹窗共享数据与选中态),
 * 依赖经构造注入,不绑定具体 Activity 类型;倒序状态经 isSeriesReversed/sortSeries 回调。
 */
public class AllVodSeriesRightDialog extends AppDrawerPopupView {

    private final SeriesFlagAdapter seriesFlagAdapter;
    private final SeriesAdapter seriesAdapter;
    /** 倒序回调:执行详情页 sortSeries(共用状态);由 DetailActivity 传入 */
    private final Runnable mSortAction;
    /** 当前是否已倒序(供按钮文字切换);由 DetailActivity 注入 isSeriesReversed */
    private final BooleanSupplier mIsReversed;

    public AllVodSeriesRightDialog(@NonNull @NotNull Context context,
                                   SeriesFlagAdapter seriesFlagAdapter,
                                   SeriesAdapter seriesAdapter,
                                   Runnable sortAction,
                                   BooleanSupplier isReversed) {
        super(context);
        this.seriesFlagAdapter = seriesFlagAdapter;
        this.seriesAdapter = seriesAdapter;
        this.mSortAction = sortAction;
        this.mIsReversed = isReversed;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_all_series_with_group;
    }

    @Override
    protected void onCreate() {
        super.onCreate();

        RecyclerView mGridViewFlag = findViewById(R.id.mGridViewFlag);
        mGridViewFlag.setHasFixedSize(true);
        mGridViewFlag.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        if (seriesFlagAdapter != null) {//复用activity的adapter
            seriesFlagAdapter.setDetailStyle(false); // 抽屉里线路保持原样(详情页才与选集 chip 对齐)
            mGridViewFlag.setAdapter(seriesFlagAdapter);

            List<VodInfo.VodSeriesFlag> data = seriesFlagAdapter.getData();
            for (int i = 0; i < data.size(); i++) {
                if (data.get(i).selected) {
                    mGridViewFlag.scrollToPosition(i);
                }
            }
        }

        if (seriesAdapter != null) {//复用activity的adapter
            RecyclerView rv = findViewById(R.id.rv);
            rv.setLayoutManager(new GridLayoutManager(getContext(), Utils.getSeriesSpanCount(seriesAdapter.getData())));
            rv.addItemDecoration(new GridSpacingItemDecoration(Utils.getSeriesSpanCount(seriesAdapter.getData()), 20, true));
            seriesAdapter.setGird(true);
            seriesAdapter.setChipTextSize(16f); // 全屏选集文字调大
            seriesAdapter.notifyDataSetChanged();
            rv.setAdapter(seriesAdapter);

            List<VodInfo.VodSeries> data = seriesAdapter.getData();
            for (int i = 0; i < data.size(); i++) {
                if (data.get(i).selected) {
                    rv.scrollToPosition(i);
                }
            }
        }

        // 倒序按钮:文字随状态切换(未倒序=倒序, 已倒序=正序);与下载/详情弹窗共用 sortSeries 状态
        updateSortButton();
        findViewById(R.id.tvSort).setOnClickListener(view -> {
            if (mSortAction != null) mSortAction.run();
            updateSortButton();
        });
    }

    /** 倒序按钮文字跟随共用状态:已倒序显示"正序",否则"倒序" */
    private void updateSortButton() {
        try {
            TextView tv = findViewById(R.id.tvSort);
            if (tv != null && mIsReversed != null) {
                tv.setText(mIsReversed.getAsBoolean() ? "正序" : "倒序");
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDismiss() {
        super.onDismiss();
        if (seriesAdapter != null) {//重置状态,避免竖屏时显示异常
            seriesAdapter.setGird(false);
            seriesAdapter.setChipTextSize(12f); // 恢复详情页默认字号
            seriesAdapter.notifyDataSetChanged();
        }
        if (seriesFlagAdapter != null) {
            seriesFlagAdapter.setDetailStyle(true); // 线路恢复详情页样式(与选集 chip 一致)
        }
    }
}

package com.github.tvbox.osc.ui.adapter;

import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ConvertUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.ui.widget.RoundChip;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */
public class SeriesAdapter extends BaseQuickAdapter<VodInfo.VodSeries, BaseViewHolder> {
    private boolean isGird;
    private float chipTextSize = 12f;

    public SeriesAdapter(boolean isGird) {
        super(R.layout.item_series, new ArrayList<>());
        this.isGird = isGird;
    }

    /** 全屏右侧抽屉用:调大条目文字(详情页保持默认,关闭抽屉时需重置) */
    public void setChipTextSize(float sp) {
        chipTextSize = sp;
    }

    @Override
    protected void convert(BaseViewHolder helper, VodInfo.VodSeries item) {
        RoundChip chip = helper.getView(R.id.sl);
        chip.setSelected(item.selected);
        chip.setTitle(item.name);
        chip.setChipTextSize(chipTextSize);

        if (!isGird){// 详情页横向展示时固定宽度
            ViewGroup.LayoutParams layoutParams = chip.getLayoutParams();
            layoutParams.width = ConvertUtils.dp2px(120);
            chip.setLayoutParams(layoutParams);
        }
    }

    public void setGird(boolean gird) {
        isGird = gird;
    }

}
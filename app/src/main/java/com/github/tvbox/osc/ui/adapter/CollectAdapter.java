package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.picasso.RoundTransformation;
import com.github.tvbox.osc.spiderapi.SourceConfigProviders;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.MD5;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class CollectAdapter extends BaseQuickAdapter<VodCollect, BaseViewHolder> {
    public CollectAdapter() {
        super(R.layout.item_grid, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, VodCollect item) {
        helper.setVisible(R.id.tvNote, false);
        TextView tvYear = helper.getView(R.id.tvYear);
        if (SourceConfigProviders.get().getSource(item.sourceKey)!=null) {
            tvYear.setText(SourceConfigProviders.get().getSource(item.sourceKey).getName());
            tvYear.setVisibility(View.VISIBLE);
        } else {
            tvYear.setVisibility(View.GONE);
        }
        helper.setText(R.id.tvName, item.name);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        // 统一加载入口:占位→实图淡入(消除 3:4 卡片 centerCrop 占位方图的视觉跳变)
        com.github.tvbox.osc.util.PicassoLoad.into(ivThumb, item.pic);
    }
}
package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.DoubanSuggestBean;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.picasso.RoundTransformation;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PicassoLoad;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class DoubanSuggestAdapter extends BaseQuickAdapter<DoubanSuggestBean, BaseViewHolder> {
    public DoubanSuggestAdapter(List<DoubanSuggestBean> list) {
        super(R.layout.item_douban_suggest, list);
    }

    @Override
    protected void convert(BaseViewHolder helper, DoubanSuggestBean item) {
        helper.setText(R.id.tvName,item.getTitle())
                .setText(R.id.tvRating,"豆瓣: "+item.getDoubanRating()+"\n烂番茄: "+item.getRottenRating()+"\nIMDB: "+item.getImdbRating());

        // 占位放 ImageView 背景层(不塞 src):带自定义圆角变换的适配器用不了 PicassoLoad.into,
        // 但要沿用它的背景层占位/失败态(灰底+居中图标按宿主尺寸自适应,失败态带"图片加载失败"文字)
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        PicassoLoad.showLoadingPlaceholder(ivThumb);
        Picasso.get()
                .load(item.getImg())
                .transform(new RoundTransformation(MD5.string2MD5(item.getImg() + "position=" + helper.getLayoutPosition()))
                        .centerCorp(true)
                        .override(AutoSizeUtils.dp2px(mContext, 110), AutoSizeUtils.dp2px(mContext, 160))
                        .roundRadius(AutoSizeUtils.dp2px(mContext, 6), RoundTransformation.RoundType.ALL))
                .into(ivThumb, new Callback() {
                    @Override
                    public void onSuccess() {
                    }

                    @Override
                    public void onError(Exception e) {
                        PicassoLoad.showFailedPlaceholder(ivThumb);
                    }
                });
    }
}
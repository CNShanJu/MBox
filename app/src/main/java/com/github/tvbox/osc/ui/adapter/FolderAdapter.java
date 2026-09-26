package com.github.tvbox.osc.ui.adapter;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.blankj.utilcode.util.LogUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VideoFolder;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.picasso.RoundTransformation;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.LocalVideoFrameLoader;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PicassoLoad;
import com.squareup.picasso.Callback;
import com.squareup.picasso.LruCache;
import com.squareup.picasso.Picasso;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class FolderAdapter extends BaseQuickAdapter<VideoFolder, BaseViewHolder> {
    public FolderAdapter() {
        super(R.layout.item_folder);
    }

    @Override
    protected void convert(BaseViewHolder helper, VideoFolder item) {
        List<VideoInfo> videoList = item.getVideoList();
        helper.setText(R.id.tv_name,item.getName());
        helper.setText(R.id.tv_count,videoList.size()+"个视频");

        // 统一图片加载到 Picasso 单例(共享 OkHttp 连接池/磁盘缓存),移除 Glide 双依赖
        // 本地视频文件路径交给异步取帧(与本地视频列表一致),远程地址交给 Picasso
        ImageView iv = helper.getView(R.id.iv);
        if (videoList != null && !videoList.isEmpty() && videoList.get(0) != null) {
            String firstPath = videoList.get(0).getPath();
            if (!TextUtils.isEmpty(firstPath) && new File(firstPath).exists()) {
                // 取帧前先铺统一占位的"加载态"(LocalVideoFrameLoader 失败时保持原样,那里没有失败语义)
                PicassoLoad.showLoadingPlaceholder(iv);
                LocalVideoFrameLoader.load(iv, firstPath);
                return;
            }
            // 占位/失败态走统一入口的背景层(不塞 src:占位塞 src 会被 scaleType 放大裁剪,小卡片上会溢出)
            PicassoLoad.showLoadingPlaceholder(iv);
            Picasso.get()
                    .load(firstPath == null ? "" : firstPath)
                    .into(iv, new Callback() {
                        @Override
                        public void onSuccess() {
                        }

                        @Override
                        public void onError(Exception e) {
                            PicassoLoad.showFailedPlaceholder(iv);
                        }
                    });
        } else {
            // 没有视频 → 无封面,按统一失败/空图占位(灰底+图标+"图片加载失败")
            PicassoLoad.showFailedPlaceholder(iv);
        }
    }
}
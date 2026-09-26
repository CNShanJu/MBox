package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.blankj.utilcode.util.ColorUtils;
import com.blankj.utilcode.util.LogUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.bean.VideoFolder;
import com.github.tvbox.osc.bean.VideoInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

public class SubscriptionAdapter extends BaseQuickAdapter<Subscription, BaseViewHolder> {

    /** 导出选择态:勾选语义从"当前订阅(单选)"切到"要导出的订阅(多选)" */
    private boolean exportMode = false;
    /** 导出态下已勾选的地址(与列表顺序解耦,列表重排也不丢) */
    private final LinkedHashSet<String> exportSelected = new LinkedHashSet<>();

    public SubscriptionAdapter() {
        super(R.layout.item_subscription);
    }

    @Override
    protected void convert(BaseViewHolder helper, Subscription item) {
        helper.setText(R.id.tv_name,item.getName())
        .setText(R.id.tv_url,item.getUrl());

        if (exportMode) {
            // 导出态:复选框表示"要导出",删除/置顶标记先收起来(避免与当前订阅勾选混为一谈)
            helper.setChecked(R.id.cb, item.getUrl() != null && exportSelected.contains(item.getUrl()))
                    .setVisible(R.id.iv_del, false)
                    .setVisible(R.id.iv_pushpin, false);
            return;
        }
        helper.setChecked(R.id.cb,item.isChecked())
                .setVisible(R.id.iv_del, true)
                .setVisible(R.id.iv_pushpin,item.isTop());

        helper.addOnClickListener(R.id.iv_del);
    }

    // ------------------------------------------------------------------
    // 导出选择
    // ------------------------------------------------------------------

    public boolean isExportMode() {
        return exportMode;
    }

    /** 进出导出态(进入即清空上次勾选) */
    public void setExportMode(boolean on) {
        exportMode = on;
        exportSelected.clear();
        notifyDataSetChanged();
    }

    /** 勾选/取消一条;返回该条当前是否被勾选 */
    public boolean toggleExport(String url) {
        if (url == null) return false;
        boolean selected;
        if (!exportSelected.remove(url)) {
            exportSelected.add(url);
            selected = true;
        } else {
            selected = false;
        }
        notifyDataSetChanged();
        return selected;
    }

    public int getExportSelectedCount() {
        return exportSelected.size();
    }

    /** 全选/全不选(按当前列表数据) */
    public void setExportAll(boolean all) {
        exportSelected.clear();
        if (all) {
            for (Subscription s : getData()) {
                if (s != null && s.getUrl() != null) exportSelected.add(s.getUrl());
            }
        }
        notifyDataSetChanged();
    }

    /** 是否已全选(列表非空时才有意义) */
    public boolean isExportAllSelected() {
        List<Subscription> data = getData();
        if (data == null || data.isEmpty()) return false;
        for (Subscription s : data) {
            if (s == null || s.getUrl() == null || !exportSelected.contains(s.getUrl())) return false;
        }
        return true;
    }

    /** 按列表顺序返回勾选的订阅(导出抓取顺序 = 列表顺序,先到先得) */
    public List<Subscription> exportSelection() {
        List<Subscription> out = new ArrayList<>();
        for (Subscription s : getData()) {
            if (s != null && s.getUrl() != null && exportSelected.contains(s.getUrl())) out.add(s);
        }
        return out;
    }

    /**
     * 刷新列表时候,添加去重和排序
     * @param data
     */
    @Override
    public void setNewData(@Nullable List<Subscription> data) {
        if (data!=null){
            //去除url重复的订阅
            for (int i = 0; i < data.size(); i++) {
                for (int j = i+1; j < data.size(); j++) {
                    if (data.get(i).getUrl().equals(data.get(j).getUrl())){
                        data.remove(j);
                        j--;
                    }
                }
            }
            data.sort(mComparator);
        }
        super.setNewData(data);
    }

    Comparator<Subscription> mComparator = (s1, s2) -> {
        if (s1.isTop() && !s2.isTop()) {
            return -1;
        } else if (!s1.isTop() && s2.isTop()) {
            return 1;
        } else if (s1.isChecked() && !s2.isChecked()) {
            return -1;
        } else if (!s1.isChecked() && s2.isChecked()) {
            return 1;
        } else {
            return 0;
        }
    };
}
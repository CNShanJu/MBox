package com.github.tvbox.osc.ui.adapter;

import android.graphics.drawable.Drawable;
import android.util.Pair;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.util.HistorySourceBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class HistoryAdapter extends BaseQuickAdapter<VodInfo, BaseViewHolder> {
    private boolean selectMode;
    private final Set<Pair<String, String>> selectedKeys = new HashSet<>();

    public HistoryAdapter() {
        super(R.layout.item_collect_grid, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, VodInfo item) {
        TextView tvYear = helper.getView(R.id.tvYear);
        /*if (item.year <= 0) {
            tvYear.setVisibility(View.GONE);
        } else {
            tvYear.setText(String.valueOf(item.year));
            tvYear.setVisibility(View.VISIBLE);
        }*/
        tvYear.setText(HistorySourceBinding.sourceLabel(item));

        TextView tvNote = helper.getView(R.id.tvNote);
        if (item.note == null || item.note.isEmpty()) {
            tvNote.setVisibility(View.GONE);
        } else {
            tvNote.setText(item.note);
            Drawable drawable = ContextCompat.getDrawable(mContext, R.drawable.ic_history_18);
            tvNote.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        }
        helper.setText(R.id.tvName, item.name);
        // helper.setText(R.id.tvActor, item.actor);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        // 统一加载入口:占位→实图淡入(消除 3:4 卡片 centerCrop 占位方图的视觉跳变)
        com.github.tvbox.osc.util.PicassoLoad.into(ivThumb, item.pic);

        helper.setVisible(R.id.layout_source, !selectMode);
        helper.setVisible(R.id.iv_select, selectMode);
        if (selectMode) {
            ImageView selectIcon = helper.getView(R.id.iv_select);
            selectIcon.setImageResource(selectedKeys.contains(keyOf(item))
                    ? R.drawable.ic_select_checked : R.drawable.ic_select_ring);
        }
    }

    public boolean isSelectMode() {
        return selectMode;
    }

    public void enterSelectMode(int position) {
        VodInfo item = getItem(position);
        if (item == null) return;
        selectMode = true;
        selectedKeys.add(keyOf(item));
        notifyDataSetChanged();
    }

    public void exitSelectMode() {
        selectMode = false;
        selectedKeys.clear();
        notifyDataSetChanged();
    }

    public void toggleSelection(int position) {
        VodInfo item = getItem(position);
        if (item == null) return;
        Pair<String, String> key = keyOf(item);
        if (!selectedKeys.add(key)) selectedKeys.remove(key);
        notifyItemChanged(position);
    }

    public void selectItem(int position) {
        VodInfo item = getItem(position);
        if (item != null && selectedKeys.add(keyOf(item))) notifyItemChanged(position);
    }

    public void selectAll() {
        for (VodInfo item : getData()) selectedKeys.add(keyOf(item));
        notifyDataSetChanged();
    }

    public void cancelAllSelection() {
        if (selectedKeys.isEmpty()) return;
        selectedKeys.clear();
        notifyDataSetChanged();
    }

    public List<Pair<String, String>> selectedKeySnapshot() {
        return new ArrayList<>(selectedKeys);
    }

    public int selectedCount() {
        return selectedKeys.size();
    }

    private static Pair<String, String> keyOf(VodInfo item) {
        return Pair.create(item.sourceKey, item.id);
    }
}

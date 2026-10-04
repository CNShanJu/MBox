package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.spiderapi.SourceConfigProviders;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CollectAdapter extends BaseQuickAdapter<VodCollect, BaseViewHolder> {
    private boolean selectMode;
    private final Set<Integer> selectedIds = new HashSet<>();

    public CollectAdapter() {
        super(R.layout.item_collect_grid, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, VodCollect item) {
        helper.setVisible(R.id.tvNote, false);
        TextView tvYear = helper.getView(R.id.tvYear);
        SourceBean source = SourceConfigProviders.get().getSource(item.sourceKey);
        if (source != null) {
            tvYear.setText(source.getName());
            tvYear.setVisibility(View.VISIBLE);
        } else {
            tvYear.setVisibility(View.GONE);
        }
        helper.setText(R.id.tvName, item.name);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        // 统一加载入口:占位→实图淡入(消除 3:4 卡片 centerCrop 占位方图的视觉跳变)
        com.github.tvbox.osc.util.PicassoLoad.into(ivThumb, item.pic);

        // 多选时用同下载聚合页的圆形勾选标记替换来源徽标。
        helper.setVisible(R.id.layout_source, !selectMode && source != null);
        helper.setVisible(R.id.iv_select, selectMode);
        if (selectMode) {
            ImageView selectIcon = helper.getView(R.id.iv_select);
            selectIcon.setImageResource(selectedIds.contains(item.getId())
                    ? R.drawable.ic_select_checked : R.drawable.ic_select_ring);
        }
    }

    public boolean isSelectMode() {
        return selectMode;
    }

    public void enterSelectMode(int position) {
        VodCollect item = getItem(position);
        if (item == null) return;
        selectMode = true;
        selectedIds.add(item.getId());
        notifyDataSetChanged();
    }

    public void exitSelectMode() {
        selectMode = false;
        selectedIds.clear();
        notifyDataSetChanged();
    }

    public void toggleSelection(int position) {
        VodCollect item = getItem(position);
        if (item == null) return;
        if (!selectedIds.add(item.getId())) selectedIds.remove(item.getId());
        notifyItemChanged(position);
    }

    public void selectItem(int position) {
        VodCollect item = getItem(position);
        if (item != null && selectedIds.add(item.getId())) notifyItemChanged(position);
    }

    public void selectAll() {
        for (VodCollect item : getData()) selectedIds.add(item.getId());
        notifyDataSetChanged();
    }

    public void cancelAllSelection() {
        if (selectedIds.isEmpty()) return;
        selectedIds.clear();
        notifyDataSetChanged();
    }

    /** 删除确认时先取快照，后续异步操作不再依赖可变的行位置。 */
    public List<Integer> selectedIdSnapshot() {
        return new ArrayList<>(selectedIds);
    }

    public int selectedCount() {
        return selectedIds.size();
    }
}

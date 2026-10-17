package com.github.tvbox.osc.ui.adapter;

import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.util.SourceOrigin;
import com.github.tvbox.osc.util.SubscriptionIndex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CollectAdapter extends BaseQuickAdapter<VodCollect, BaseViewHolder> {
    private boolean selectMode;
    private final Set<Integer> selectedIds = new HashSet<>();
    /** 来源订阅快照:每次列表加载由页面重建并注入,逐条徽标只做内存比对(见 SubscriptionIndex)。 */
    private SubscriptionIndex originIndex;

    public CollectAdapter() {
        super(R.layout.item_collect_grid, new ArrayList<>());
    }

    /** 页面加载收藏列表时注入新鲜的订阅快照;不注入则首次绑定时懒建一次。 */
    public void setOriginIndex(SubscriptionIndex index) {
        if (index != null) this.originIndex = index;
    }

    private SubscriptionIndex originIndex() {
        if (originIndex == null) originIndex = SubscriptionIndex.build();
        return originIndex;
    }

    @Override
    protected void convert(BaseViewHolder helper, VodCollect item) {
        helper.setVisible(R.id.tvNote, false);
        // 源不在当前订阅时不再隐藏徽标,统一显示"源未启用"(与观看历史一致),并可点开来源信息。
        SourceOrigin origin = originIndex().resolve(item.sourceKey, item.subscriptionFingerprint);
        helper.setText(R.id.tvYear, origin.getBadgeLabel());
        helper.setText(R.id.tvName, item.name);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        // 统一加载入口:占位→实图淡入(消除 3:4 卡片 centerCrop 占位方图的视觉跳变)
        com.github.tvbox.osc.util.PicassoLoad.into(ivThumb, item.pic);

        // 多选时用同下载聚合页的圆形勾选标记替换来源徽标;非多选时徽标常驻且可点(弹出来源信息)。
        helper.setVisible(R.id.layout_source, !selectMode);
        helper.setVisible(R.id.iv_select, selectMode);
        helper.addOnClickListener(R.id.layout_source);
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

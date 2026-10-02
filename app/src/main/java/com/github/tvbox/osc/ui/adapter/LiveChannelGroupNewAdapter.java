package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannelGroup;

import java.util.ArrayList;


/**
 * @author pj567
 * @date :2021/1/12
 * @description:
 */
public class LiveChannelGroupNewAdapter extends BaseQuickAdapter<LiveChannelGroup, BaseViewHolder> {
    private int selectedGroupIndex = -1;
    private int focusedGroupIndex = -1;

    public LiveChannelGroupNewAdapter() {
        super(R.layout.item_live_channel_group_new, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveChannelGroup item) {
        View root = holder.getView(R.id.root);
        TextView tvGroupName = holder.getView(R.id.tvChannelGroupName);
        tvGroupName.setText(item.getGroupName());
        int groupIndex = item.getGroupIndex();
        // 字色走 Resources(getColor → 换肤包装,跟着主题);底必须走 ThemeDrawables.applyBackground ——
        // 原来用 getResources().getDrawable(...) 拿的是编译期那份,自定义主题下"底没变、字变了",
        // 与中间那栏是同一个毛病(见 LiveChannelItemNewAdapter.convert 的说明)。
        if (groupIndex == selectedGroupIndex && groupIndex != focusedGroupIndex) {
            // 选中态与预设 chip 同源：深色默认底透明时用高亮字与同色描边。
            tvGroupName.setTextColor(mContext.getResources().getColor(R.color.btn_select_text));
            com.github.tvbox.osc.theme.ThemeDrawables.applyBackground(root, R.drawable.bg_r_common_solid_select);
        } else {
            tvGroupName.setTextColor(mContext.getResources().getColor(R.color.text_foreground));
            com.github.tvbox.osc.theme.ThemeDrawables.applyBackground(root, R.drawable.bg_transparent);
        }
    }

    public void setSelectedGroupIndex(int selectedGroupIndex) {
        if (selectedGroupIndex == this.selectedGroupIndex) return;
        int preSelectedGroupIndex = this.selectedGroupIndex;
        this.selectedGroupIndex = selectedGroupIndex;
        if (preSelectedGroupIndex != -1)
            notifyItemChanged(preSelectedGroupIndex);
        if (this.selectedGroupIndex != -1)
            notifyItemChanged(this.selectedGroupIndex);
    }

    public int getSelectedGroupIndex() {
        return selectedGroupIndex;
    }

    public void setFocusedGroupIndex(int focusedGroupIndex) {
        this.focusedGroupIndex = focusedGroupIndex;
        if (this.focusedGroupIndex != -1)
            notifyItemChanged(this.focusedGroupIndex);
        else if (this.selectedGroupIndex != -1)
            notifyItemChanged(this.selectedGroupIndex);
    }
}

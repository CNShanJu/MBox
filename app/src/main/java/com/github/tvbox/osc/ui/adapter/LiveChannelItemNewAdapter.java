package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveChannelItem;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2021/1/12
 * @description: 直播频道列表(条目只显示频道名)
 * <p>
 * 注意:条目<b>不显示顺序号</b>。以前名字前面有一列 {@code channelNum},它是解析直播源时
 * 跨分组累加出来的索引(不是源里真实的频道号),占的那点宽度会把长频道名(尤其电台)
 * 挤成省略号,已去掉;列表里不要再加回来。
 */
public class LiveChannelItemNewAdapter extends BaseQuickAdapter<LiveChannelItem, BaseViewHolder> {
    private int selectedChannelIndex = -1;
    private int focusedChannelIndex = -1;

    public LiveChannelItemNewAdapter() {
        super(R.layout.item_live_channel_new, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveChannelItem item) {
        View root = holder.getView(R.id.root);
        TextView tvChannel = holder.getView(R.id.tvChannelName);
        tvChannel.setText(item.getChannelName());
        int channelIndex = item.getChannelIndex();
        // 字色:走 Resources(Activity 的 getResources 已被 BaseActivity 换成换肤包装),
        // getColor(text_foreground) 在自定义主题下会返回主题的文字主色 —— 这一句本来是跟着主题走的。
        // 底:**必须走 ThemeDrawables.applyBackground**。原来写的是
        // mContext.getResources().getDrawable(...):它拿的是**编译期**那份 drawable(换肤包装只对
        // 单色矢量图标做 tint,不会重建 shape 里的 @color),于是自定义主题下"底仍是内置的浅色填充、
        // 字已经换成主题的对比色(btn_select_text)" —— 白字压浅底,用户口径就是
        // "直播页中间那栏频道名的文字没走文字主色"(其实字走对了,是底没跟着走)。
        if (channelIndex == selectedChannelIndex && channelIndex != focusedChannelIndex) {
            // 选中态与全局"已选中"实心样式一致(背景图设置页那几个预设 chip 同款):
            // 底 = btn_select_bg(主题主色),字 = btn_select_text(主色上的文字)—— 不再用各页面自配色
            tvChannel.setTextColor(mContext.getResources().getColor(R.color.btn_select_text));
            com.github.tvbox.osc.theme.ThemeDrawables.applyBackground(root, R.drawable.bg_r_common_solid_select);
        } else {
            tvChannel.setTextColor(mContext.getResources().getColor(R.color.text_foreground));
            com.github.tvbox.osc.theme.ThemeDrawables.applyBackground(root, R.drawable.bg_transparent);
        }
    }

    public void setSelectedChannelIndex(int selectedChannelIndex) {
        if (selectedChannelIndex == this.selectedChannelIndex) return;
        int preSelectedChannelIndex = this.selectedChannelIndex;
        this.selectedChannelIndex = selectedChannelIndex;
        if (preSelectedChannelIndex != -1)
            notifyItemChanged(preSelectedChannelIndex);
        if (this.selectedChannelIndex != -1)
            notifyItemChanged(this.selectedChannelIndex);
    }

    public void setFocusedChannelIndex(int focusedChannelIndex) {
        int preFocusedChannelIndex = this.focusedChannelIndex;
        this.focusedChannelIndex = focusedChannelIndex;
        if (preFocusedChannelIndex != -1)
            notifyItemChanged(preFocusedChannelIndex);
        if (this.focusedChannelIndex != -1)
            notifyItemChanged(this.focusedChannelIndex);
        else if (this.selectedChannelIndex != -1)
            notifyItemChanged(this.selectedChannelIndex);
    }
}
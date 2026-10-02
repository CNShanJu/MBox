package com.github.tvbox.osc.ui.adapter;

import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveSettingGroup;

import java.util.ArrayList;


/**
 * 直播设置面板的左侧分组列(画面比例 / 播放解码 / 超时换源 / 偏好设置 / 后台播放)。
 * <p>
 * 与右侧条目列({@link LiveSettingItemAdapter})同一套文字口径,一起对齐播放详情的选集 chip:
 * 未选中 = 次要灰常规,选中 = 主题高亮色加粗 + 大一号;**无边框无背景**。
 * 只改右列会变成"左列一块蓝底、右列没有",整个面板观感割裂,故两列同时收敛。
 * <p>
 * 历史:同 {@link LiveSettingItemAdapter},选中色曾是写死的蓝 {@code accent_on_dark} + 同色聚焦底,
 * 已随聚焦底一起去掉;{@code setFocusedGroupIndex} 也是只为绕开"蓝底压蓝字"而存在、从无调用方,一并删除。
 */
public class LiveSettingGroupAdapter extends BaseQuickAdapter<LiveSettingGroup, BaseViewHolder> {

    /** 基准字号(sp):与右侧条目列、选集 chip 一致 */
    private static final float SIZE_SP = 16f;
    /** 选中字号(sp):基准 +1(选中大一号) */
    private static final float SIZE_SP_SELECTED = SIZE_SP + 1f;

    private int selectedGroupIndex = -1;

    public LiveSettingGroupAdapter() {
        super(R.layout.item_live_setting_group, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveSettingGroup group) {
        TextView tvGroupName = holder.getView(R.id.tvSettingGroupName);
        tvGroupName.setText(group.getGroupName());
        boolean selected = group.getGroupIndex() == selectedGroupIndex && selectedGroupIndex != -1;
        // 与右侧条目列、选集 chip(RoundChip.updateColor)同一口径:
        // 选中 = 文字主色 text_foreground + 加粗 + 大一号;未选中 = text_sub_foreground 常规。
        // (原来用 colorPrimary = text_accent:自定义主题下"别的都跟主题、只有这两个选中项不跟")
        tvGroupName.setTextColor(ContextCompat.getColor(mContext,
                selected ? R.color.text_foreground : R.color.text_sub_foreground));
        tvGroupName.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        tvGroupName.setTextSize(TypedValue.COMPLEX_UNIT_SP, selected ? SIZE_SP_SELECTED : SIZE_SP);
    }

    public void setSelectedGroupIndex(int selectedGroupIndex) {
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
}

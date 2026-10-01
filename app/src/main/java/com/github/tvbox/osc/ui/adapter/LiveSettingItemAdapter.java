package com.github.tvbox.osc.ui.adapter;

import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.LiveSettingItem;

import java.util.ArrayList;


/**
 * 直播线路抽屉 / 直播设置面板的条目列(两处共用同一个 adapter 与 {@code item_live_setting.xml})。
 * <p>
 * 文字样式与播放详情的选集 chip 对齐(同 {@link com.github.tvbox.osc.ui.widget.RoundChip} 与
 * 全屏选集抽屉用的 {@link SeriesAdapter} 基准字号):未选中 = 次要灰常规,选中 = 主题高亮色
 * 加粗 + 大一号;**无边框无背景**,选中只靠文字区分。
 * <p>
 * 历史:选中色曾是写死的蓝 {@code accent_on_dark},条目还带一块同色聚焦底
 * ({@code shape_live_focus});灰字压蓝底看不清、且与选集 chip 两套观感,已一并去掉
 * (连带删掉了只为绕开"蓝底压蓝字"而存在的 {@code setFocusedItemIndex},它从来没有调用方)。
 */
public class LiveSettingItemAdapter extends BaseQuickAdapter<LiveSettingItem, BaseViewHolder> {

    /** 基准字号(sp):与全屏选集抽屉的 chip 一致(见 SeriesAdapter 的 setChipTextSize(16f)) */
    private static final float SIZE_SP = 16f;
    /** 选中字号(sp):基准 +1,选集 chip 同款"选中大一号" */
    private static final float SIZE_SP_SELECTED = SIZE_SP + 1f;

    public LiveSettingItemAdapter() {
        super(R.layout.item_live_setting, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveSettingItem item) {
        TextView tvItemName = holder.getView(R.id.tvSettingItemName);
        tvItemName.setText(item.getItemName());
        boolean selected = item.isItemSelected();
        // 与选集 chip(RoundChip.updateColor)同一口径:选中 = **文字主色** text_foreground + 加粗 + 大一号;
        // 未选中 = text_sub_foreground 常规。
        // 原来用的是 colorPrimary(= text_accent,主题里的"高亮文字"蓝):直播设置/线路抽屉在自定义主题下
        // 就成了"别的都跟着主题、只有这两个选中项不跟"(用户口径"选中文字的样式没跟主题走")。
        tvItemName.setTextColor(ContextCompat.getColor(mContext,
                selected ? R.color.text_foreground : R.color.text_sub_foreground));
        tvItemName.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        tvItemName.setTextSize(TypedValue.COMPLEX_UNIT_SP, selected ? SIZE_SP_SELECTED : SIZE_SP);
    }

    public void selectItem(int selectedItemIndex, boolean select, boolean unselectPreItemIndex) {
        if (unselectPreItemIndex) {
            int preSelectedItemIndex = getSelectedItemIndex();
            if (preSelectedItemIndex != -1) {
                getData().get(preSelectedItemIndex).setItemSelected(false);
                notifyItemChanged(preSelectedItemIndex);
            }
        }
        if (selectedItemIndex != -1) {
            getData().get(selectedItemIndex).setItemSelected(select);
            notifyItemChanged(selectedItemIndex);
        }
    }

    public int getSelectedItemIndex() {
        for (LiveSettingItem item : getData()) {
            if (item.isItemSelected())
                return item.getItemIndex();
        }
        return -1;
    }
}

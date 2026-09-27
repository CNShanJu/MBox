package com.github.tvbox.osc.ui.adapter;

import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.util.TextLineHeight;

import java.util.ArrayList;

/**
 * 线路(片源线路)列表:详情页与"全屏抽屉"复用同一个 adapter 实例(见 AllVodSeriesRightDialog)。
 * <ul>
 *   <li><b>详情页样式</b>(默认):文字与选集 chip 完全一致 —— 未选中 = `text_sub_foreground` 12sp,
 *       选中 = **文字主色 text_main** 13sp 加粗;同样按选中态字号预留行高,避免选中把整行撑高;</li>
 *   <li><b>抽屉样式</b>(全屏播放的抽屉):保持原样,只按选中给文字上主题色,不动字号/字重
 *       —— 由弹窗在 onCreate/onDismiss 切换(与系列 adapter 的 setChipTextSize 同一套做法)。</li>
 * </ul>
 */
public class SeriesFlagAdapter extends BaseQuickAdapter<VodInfo.VodSeriesFlag, BaseViewHolder> {

    /** 详情页 chip 基准字号(与 SeriesAdapter 默认 chip 字号一致) */
    private static final float SIZE_SP = 12f;
    /** 选中的字号(基准 +1sp,与选集 chip 的选中态一致) */
    private static final float SIZE_SP_SELECTED = SIZE_SP + 1f;

    /** true = 详情页样式(与选集一致);false = 全屏抽屉样式(保持原样) */
    private boolean detailStyle = true;

    public SeriesFlagAdapter() {
        super(R.layout.item_select_flag, new ArrayList<>());
    }

    /** 切换样式:详情页(与选集一致) / 全屏抽屉(保持原样);切换后重绑一次 */
    public void setDetailStyle(boolean detail) {
        if (detailStyle == detail) return;
        detailStyle = detail;
        notifyDataSetChanged();
    }

    @Override
    protected void convert(BaseViewHolder helper, VodInfo.VodSeriesFlag item) {
        View select = helper.getView(R.id.vFlag);
        if (item.selected) {
            select.setVisibility(View.VISIBLE);
        } else {
            select.setVisibility(View.GONE);
        }
        helper.setText(R.id.tvFlag, item.name);

        TextView tvFlag = helper.getView(R.id.tvFlag);
        // 选中的线路:文字与"文字下方那条小横线"(shape_source_flag_line)同色 = **文字主色**
        tvFlag.setTextColor(ContextCompat.getColor(mContext,
                item.selected ? R.color.text_foreground : R.color.text_sub_foreground));
        if (detailStyle) {
            // 与选集 chip 一致:选中加粗 + 大一号;并给所有条目预留同一行高,避免选中撑高整行
            tvFlag.setTypeface(null, item.selected ? Typeface.BOLD : Typeface.NORMAL);
            tvFlag.setTextSize(TypedValue.COMPLEX_UNIT_SP, item.selected ? SIZE_SP_SELECTED : SIZE_SP);
            tvFlag.setMinHeight(TextLineHeight.forSp(tvFlag, SIZE_SP_SELECTED));
        } else {
            // 全屏抽屉:保持原样(不改字号字重,也不预留行高)
            tvFlag.setTypeface(null, Typeface.NORMAL);
            tvFlag.setMinHeight(0);
        }
    }
}

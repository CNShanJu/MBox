package com.github.tvbox.osc.ui.adapter;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.util.TextLineHeight;

import java.util.ArrayList;

public class ParseAdapter extends BaseQuickAdapter<ParseBean, BaseViewHolder> {
    public ParseAdapter() {
        super(R.layout.item_select_flag, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, ParseBean item) {
        View select = helper.getView(R.id.vFlag);
        boolean selected = item.isDefault();
        if (selected) {
            select.setVisibility(View.VISIBLE);
        } else {
            select.setVisibility(View.GONE);
        }
        helper.setText(R.id.tvFlag, item.getName());
        TextView text = helper.getView(R.id.tvFlag);
        int selectedColor = ContextCompat.getColor(mContext, R.color.text_foreground);
        text.setTextColor(selected ? selectedColor
                : ContextCompat.getColor(mContext, R.color.text_sub_foreground));
        text.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, selected ? 13f : 12f);
        text.setMinHeight(TextLineHeight.forSp(text, 13f));
        ViewCompat.setBackgroundTintList(select, ColorStateList.valueOf(selectedColor));
    }
}

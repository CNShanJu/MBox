package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.lxj.xpopup.impl.PartShadowPopupView;
import com.lxj.xpopup.interfaces.OnSelectListener;
import com.zhy.view.flowlayout.FlowLayout;
import com.zhy.view.flowlayout.TagAdapter;
import com.zhy.view.flowlayout.TagFlowLayout;

import java.util.List;

/**
 * Description: 自定义局部阴影弹窗
 * Create by dance, at 2018/12/21
 */
public class SearchSuggestionsDialog extends PartShadowPopupView {

    private List<String> mList;
    private OnSelectListener onSelectListener;
    private TagFlowLayout mFl;

    public SearchSuggestionsDialog(@NonNull Context context, List<String> list, OnSelectListener onSelectListener) {
        super(context);
        mList = list;
        this.onSelectListener = onSelectListener;
    }
    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_search_uggestions;
    }

    TextView text;
    @Override
    protected void onCreate() {
        super.onCreate();
// 换肤兜底:本类直接继承 XPopup 的 *PopupView,没走 AppBottom/Center/Drawer 那层壳,
        // 面板底不会被换肤注入覆盖到 —— 这里补同一趟"内置面 → 主题面"扫描
        // (用户口径:"切换布局气泡的背景色没走卡片与悬浮层颜色")。
        try {
            com.github.tvbox.osc.theme.ThemeSweep.apply(getPopupImplView());
            com.github.tvbox.osc.theme.ThemeSweep.watchItems(getPopupImplView());
        } catch (Throwable ignored) {
        }
        mFl = findViewById(R.id.fl_suggest);
        updateSuggestions(mList);
        mFl.setOnTagClickListener(new TagFlowLayout.OnTagClickListener() {
            @Override
            public boolean onTagClick(View view, int position, FlowLayout parent) {
                if (onSelectListener!=null){
                    onSelectListener.onSelect(position,mList.get(position));
                }
                return true;
            }
        });
    }

    public void updateSuggestions(List<String> list){
        mList = list;
        if (mFl!=null){// 搜索框文字变化太快,先于onCreate执行(偶现)
            mFl.setAdapter(new TagAdapter<String>(mList)
            {
                @Override
                public View getView(FlowLayout parent, int position, String s)
                {
                    TextView tv = (TextView) LayoutInflater.from(getContext()).inflate(R.layout.item_search_word_hot,
                            mFl, false);
                    // 联想标签就是小组件按钮(item_search_word_hot 自带 style/WidgetBtn 的底与字),
                    // 这里只补"点击型"的点击特效:按下整键透明度 80% 再恢复(用户口径)
                    com.github.tvbox.osc.ui.kit.WidgetPressEffect.attach(tv);
                    tv.setText(s);
                    return tv;
                }
            });
        }
    }
}

package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.lxj.xpopup.core.PositionPopupView;
import com.lxj.xpopup.enums.DragOrientation;

public class LastViewedDialog extends PositionPopupView {
    private final VodInfo vodInfo;

    public LastViewedDialog(@NonNull Context context, VodInfo vodInfo) {
        super(context);
        this.vodInfo = vodInfo;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_last_viewed;
    }

    @Override
    protected void onCreate() {
        // 换肤兜底:本类直接继承 XPopup 的 *PopupView,没走 AppBottom/Center/Drawer 那层壳,
        // 面板底不会被换肤注入覆盖到 —— 这里补同一趟"内置面 → 主题面"扫描
        // (用户口径:"切换布局气泡的背景色没走卡片与悬浮层颜色")。
        try {
            com.github.tvbox.osc.theme.ThemeSweep.apply(getPopupImplView());
            com.github.tvbox.osc.theme.ThemeSweep.watchItems(getPopupImplView());
        } catch (Throwable ignored) {
        }
        super.onCreate();
        TextView textView = findViewById(R.id.tv);
        textView.setText("上次看到: "+vodInfo.name+" "+vodInfo.note);
        // 触发跑马灯滚动(超出单行时不换行、循环滚动播放)
        textView.setSelected(true);
        textView.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            dismiss();
            Bundle bundle = new Bundle();
            bundle.putString("id", vodInfo.id);
            bundle.putString("sourceKey", vodInfo.sourceKey);
            bundle.putString("vodName", vodInfo.name);
            getContext().startActivity(new Intent(getContext(),DetailActivity.class).putExtras(bundle));
        });
    }

    @Override
    protected DragOrientation getDragOrientation() {
        return DragOrientation.DragToRight;
    }
}
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
    protected int getMaxWidth() {
        return Math.round(DialogStyle.CENTER_MAX_WIDTH_DP
                * getContext().getResources().getDisplayMetrics().density);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_last_viewed;
    }

    @Override
    protected void onCreate() {
        PopupKeyboardPolicy.onCreate(this);
        super.onCreate();
        TextView textView = findViewById(R.id.tv);
        // 显示时明确重放气泡资源,不按原像素猜 bg_float。
        com.github.tvbox.osc.theme.ThemeDrawables.applyBackground(textView, R.drawable.bg_bubble);
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
    public void focusAndProcessBackPress() {
        super.focusAndProcessBackPress();
        PopupKeyboardPolicy.afterFocus(this);
    }

    @Override
    protected DragOrientation getDragOrientation() {
        return DragOrientation.DragToRight;
    }
}

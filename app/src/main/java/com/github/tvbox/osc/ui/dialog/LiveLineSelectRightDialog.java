package com.github.tvbox.osc.ui.dialog;

import android.content.Context;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

/**
 * 线路选择抽屉(右侧样式,与全屏设置抽屉一致):全屏控制条点线路信息时使用。
 * 列表装载与点击处理复用 {@link LiveLineSelectDialog#bind}。
 */
public class LiveLineSelectRightDialog extends AppDrawerPopupView {

    private final LiveLineSelectHost mHost;

    public LiveLineSelectRightDialog(@NonNull @NotNull Context context, @NonNull LiveLineSelectHost host) {
        super(context);
        mHost = host;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_live_line_select;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        TvRecyclerView lineView = findViewById(R.id.mLineView);
        LiveLineSelectDialog.bind(lineView, mHost, this::dismiss);
    }
}

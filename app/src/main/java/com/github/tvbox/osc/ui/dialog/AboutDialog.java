package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.activity.DisclaimerActivity;

import org.jetbrains.annotations.NotNull;

/**
 * 「关于」底部弹窗:项目说明和免责声明入口。
 */
public class AboutDialog extends AppBottomPopupView {

    public AboutDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_about;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        findViewById(R.id.iv_close).setOnClickListener(v -> dismiss());

        findViewById(R.id.btn_disclaimer).setOnClickListener(v -> {
            final Context ctx = getContext();
            dismissWith(() -> ctx.startActivity(new Intent(ctx, DisclaimerActivity.class)
                    .putExtra(DisclaimerActivity.EXTRA_REVIEW_MODE, true)));
        });
    }
}

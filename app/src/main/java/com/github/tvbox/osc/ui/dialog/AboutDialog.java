package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.update.UpdateCheck;
import com.github.tvbox.osc.update.UpdateInfo;
import com.github.tvbox.osc.util.AppBubble;
import com.google.android.material.button.MaterialButton;
import com.lxj.xpopup.core.BottomPopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 「关于」底部弹窗:版本信息 + 检查更新。
 * <p>
 * 检查/下载动作收敛在 {@link UpdateCheck}(与启动自动检查共用同一套逻辑,避免两处行为分叉);
 * 本弹窗只负责把状态显示在 {@code tv_update_status} 上。
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

        final TextView tvStatus = findViewById(R.id.tv_update_status);
        final MaterialButton btn = findViewById(R.id.btn_check_update);

        btn.setOnClickListener(v -> {
            btn.setEnabled(false);
            showStatus(tvStatus, "正在检查更新...");
            UpdateCheck.check(getContext(), new UpdateCheck.Listener() {
                @Override
                public void onChecking() {
                    showStatus(tvStatus, "正在检查更新...");
                }

                @Override
                public boolean onResult(UpdateInfo newVersion) {
                    btn.setEnabled(true);
                    if (newVersion == null) {
                        showStatus(tvStatus, "当前已是最新版本");
                        return false;   // 无更新:留在本弹窗显示状态即可
                    }
                    showStatus(tvStatus, "发现新版本 v" + newVersion.versionName);
                    // 先让底部弹窗把退场动画播完,再弹更新说明弹窗:
                    // 之前是"边关抽屉边弹新弹窗",两个动效硬切,观感僵硬(实测反馈)
                    final Context ctx = getContext();
                    dismissWith(() -> UpdateCheck.showNote(ctx, newVersion));
                    return true;   // 弹窗时机由本类接管,UpdateCheck 不再立即弹
                }

                @Override
                public void onFailed(String message) {
                    tvStatus.setVisibility(View.GONE);
                    btn.setEnabled(true);
                    AppBubble.toast(message);
                }
            });
        });
    }

    private static void showStatus(TextView tvStatus, String text) {
        if (tvStatus != null) {
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(text);
        }
    }
}

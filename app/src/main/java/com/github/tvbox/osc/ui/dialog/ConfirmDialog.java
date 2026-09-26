package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.CenterPopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 主题化确认弹窗(标题 + 消息 + 取消/确定):
 * 弹窗背景直接来自布局根(dialog_confirm = bg_large_round_popup → 主题色 bg_popup,
 * 半透明度由 theme_colors 的 bg_popup_alpha 控制, 深浅色随主题),圆角走主题圆角档 radius_dialog,
 * 与 DeleteDownloadDialog 等下载相关弹窗视觉一致(替代 XPopup 默认 asConfirm 的库内固定圆角)。
 *
 * <p><b>必须通过 {@link #show(Context, String, String, String, Runnable)} 弹出</b>:
 * XPopup 的 popupInfo 只由 Builder 绑定,直接 {@code new ConfirmDialog(ctx).show()} 会抛
 * {@code popupInfo is null}(BasePopupView.show 硬校验)——统一工厂内部走 Builder 绑定,避免各调用点漏写。</p>
 */
public class ConfirmDialog extends AppCenterPopupView {

    /** 统一弹出入口:XPopup.Builder 绑定 popupInfo 后再 show,context 须为 Activity */
    public static void show(Context context, String title, String message,
                            String confirmText, Runnable onConfirm) {
        new XPopup.Builder(context)
                .asCustom(new ConfirmDialog(context, title, message, confirmText, onConfirm))
                .show();
    }

    private final String mTitle;
    private final String mMessage;
    private final String mConfirmText;
    private final Runnable mOnConfirm;

    public ConfirmDialog(@NonNull @NotNull Context context, String title, String message,
                         String confirmText, Runnable onConfirm) {
        super(context);
        mTitle = title;
        mMessage = message;
        mConfirmText = confirmText;
        mOnConfirm = onConfirm;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_confirm;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // 弹窗背景由布局根 dialog_confirm 提供(bg_large_round_popup → 主题 bg_popup,含 bg_popup_alpha 透明度)

        TextView tvTitle = findViewById(R.id.tv_title);
        TextView tvMessage = findViewById(R.id.tv_message);
        if (mTitle != null) tvTitle.setText(mTitle);
        tvMessage.setText(mMessage == null ? "" : mMessage);

        TextView tvOk = findViewById(R.id.tv_ok);
        if (mConfirmText != null && !mConfirmText.isEmpty()) tvOk.setText(mConfirmText);
        findViewById(R.id.tv_cancel).setOnClickListener(v -> dismiss());
        tvOk.setOnClickListener(v -> {
            dismiss();
            if (mOnConfirm != null) {
                mOnConfirm.run();
            }
        });
    }
}

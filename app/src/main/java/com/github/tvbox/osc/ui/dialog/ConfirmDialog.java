package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.CenterPopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 主题化确认弹窗(标题 + 消息 + 取消/确定):
 * 弹窗背景直接来自布局根(dialog_confirm = bg_dialog → 主题色 bg_float,
 * 半透明度由 theme_colors 的 bg_float_alpha 控制, 深浅色随主题),圆角走主题圆角档 radius_dialog,
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
        show(context, title, message, confirmText, onConfirm, false);
    }

    /**
     * 危险动作确认(无可撤销的删除/清空):确定键使用红色空心样式,
     * 描边和文字都取 {@code text_danger}。
     */
    public static void showDanger(Context context, String title, String message,
                                  String confirmText, Runnable onConfirm) {
        show(context, title, message, confirmText, onConfirm, true);
    }

    public static void show(Context context, String title, String message,
                            String confirmText, Runnable onConfirm, boolean danger) {
        new XPopup.Builder(context)
                .asCustom(new ConfirmDialog(context, title, message, confirmText, onConfirm, danger))
                .show();
    }

    private final String mTitle;
    private final String mMessage;
    private final String mConfirmText;
    private final Runnable mOnConfirm;
    /** true = 确定键用红边红字,给不可逆的删除/清空用 */
    private final boolean mDanger;

    public ConfirmDialog(@NonNull @NotNull Context context, String title, String message,
                         String confirmText, Runnable onConfirm) {
        this(context, title, message, confirmText, onConfirm, false);
    }

    public ConfirmDialog(@NonNull @NotNull Context context, String title, String message,
                         String confirmText, Runnable onConfirm, boolean danger) {
        super(context);
        mTitle = title;
        mMessage = message;
        mConfirmText = confirmText;
        mOnConfirm = onConfirm;
        mDanger = danger;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_confirm;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // 弹窗背景由布局根 dialog_confirm 提供(bg_dialog → 主题 bg_float,含 bg_float_alpha 透明度)

        TextView tvTitle = findViewById(R.id.tv_title);
        TextView tvMessage = findViewById(R.id.tv_message);
        if (mTitle != null) tvTitle.setText(mTitle);
        tvMessage.setText(mMessage == null ? "" : mMessage);

        TextView normalOk = findViewById(R.id.tv_ok);
        TextView dangerOk = findViewById(R.id.tv_ok_danger);
        normalOk.setVisibility(mDanger ? View.GONE : View.VISIBLE);
        dangerOk.setVisibility(mDanger ? View.VISIBLE : View.GONE);
        TextView tvOk = mDanger ? dangerOk : normalOk;
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

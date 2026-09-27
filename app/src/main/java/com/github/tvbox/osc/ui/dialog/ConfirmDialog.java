package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.google.android.material.button.MaterialButton;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.CenterPopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 主题化确认弹窗(标题 + 消息 + 取消/确定):
 * 弹窗背景直接来自布局根(dialog_confirm = bg_large_round_popup → 主题色 bg_float,
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
     * 危险动作确认(无可撤销的删除/清空):确定键走主题的危险色(红底 + 红底上的文字)。
     * 入口与"无底危险文字"是两档色:{@code swipe_red}/{@code swipe_red_text} 给"确认执行"键,
     * {@code text_danger} 给列表工具条/条目上那些没有底色的危险入口。
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
    /** true = 确定键用危险色(红底白字),给不可逆的删除/清空用 */
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
        // 弹窗背景由布局根 dialog_confirm 提供(bg_large_round_popup → 主题 bg_float,含 bg_float_alpha 透明度)

        TextView tvTitle = findViewById(R.id.tv_title);
        TextView tvMessage = findViewById(R.id.tv_message);
        if (mTitle != null) tvTitle.setText(mTitle);
        tvMessage.setText(mMessage == null ? "" : mMessage);

        TextView tvOk = findViewById(R.id.tv_ok);
        if (mConfirmText != null && !mConfirmText.isEmpty()) tvOk.setText(mConfirmText);
        if (mDanger) applyDangerStyle(tvOk);
        findViewById(R.id.tv_cancel).setOnClickListener(v -> dismiss());
        tvOk.setOnClickListener(v -> {
            dismiss();
            if (mOnConfirm != null) {
                mOnConfirm.run();
            }
        });
    }

    /**
     * 把确定键换成危险色(主题 swipe_red 底 + swipe_red_text 字)。
     * 就地改 tint/文字色而不换 style:布局里 tv_ok 是 MaterialButton(BtnPrimary),
     * 换 style 得在 XML 里并排两套按钮再让代码挑,资源与调用点都要动,这两个属性就够。
     */
    private void applyDangerStyle(TextView tvOk) {
        if (tvOk instanceof MaterialButton) {
            ((MaterialButton) tvOk).setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(getContext(), R.color.swipe_red)));
        }
        tvOk.setTextColor(ContextCompat.getColor(getContext(), R.color.swipe_red_text));
    }
}

package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.storage.theme.ThemeStore;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.interfaces.XPopupCallback;

import org.jetbrains.annotations.NotNull;

/**
 * 主题命名弹窗:保存时还没有名字(新建)就弹它。
 *
 * <p>两条用户口径都落在这里:
 * <ul>
 *   <li><b>重名不许过</b>:点确定时用 {@link ThemeStore#checkName} 校验,重名<b>不关闭弹窗</b>,
 *       就地红字提示,用户改完再点;</li>
 *   <li><b>允许取消</b>:取消 = 不保存这个主题(新建流程整个放弃;改名流程保留原名),由 {@link Listener#onCancel()} 决定后续。</li>
 * </ul>
 */
public class ThemeNameDialog extends AppCenterPopupView {

    public interface Listener {
        /** 名字合法(已 trim);返回 false 表示"仍然不合法,别关弹窗"(本类只用于外部追加校验,一般为 true) */
        boolean onConfirm(String name);

        /** 用户取消(或返回键) */
        void onCancel();
    }

    private final String initialName;
    /** 改名时排除自己(重名校验用);新建传空 */
    private final String exceptId;

    private Listener listener;
    private EditText input;
    private TextView error;

    public ThemeNameDialog(@NonNull @NotNull Context context, String initialName, String exceptId) {
        super(context);
        this.initialName = initialName == null ? "" : initialName;
        this.exceptId = exceptId == null ? "" : exceptId;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_theme_name;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        input = findViewById(R.id.et_name);
        error = findViewById(R.id.tv_error);
        input.setText(initialName);
        if (!initialName.isEmpty()) {
            input.setSelection(initialName.length());
        }
        // 边改边清掉错误提示:用户已经在改了,红字留着只会让人以为还没改对
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (error.getVisibility() == View.VISIBLE) error.setVisibility(View.GONE);
            }
        });

        findViewById(R.id.btn_cancel).setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onCancel();
        });
        findViewById(R.id.btn_ok).setOnClickListener(v -> submit());
        input.postDelayed(this::showKeyboard, 150);
    }

    private void submit() {
        String name = input.getText().toString().trim();
        String err = ThemeStore.checkName(name, exceptId);
        if (err != null) {
            error.setText(err);
            error.setVisibility(View.VISIBLE);
            return; // 不关闭,留在输入框里改
        }
        if (listener != null && !listener.onConfirm(name)) {
            return;
        }
        dismiss();
    }

    private void showKeyboard() {
        try {
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) {
        }
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 统一弹出入口(与 ConfirmDialog 同口径:必须经 Builder 绑定 popupInfo) */
    public static void show(Context context, String initialName, String exceptId, Listener listener) {
        ThemeNameDialog dialog = new ThemeNameDialog(context, initialName, exceptId);
        dialog.setListener(listener);
        new XPopup.Builder(context)
                .isDarkTheme(Utils.isAppDarkTheme())
                .asCustom(dialog)
                .show();
    }

    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isAppDarkTheme())
                    .setPopupCallback(new XPopupCallback() {
                        @Override public void onCreated(BasePopupView v) { }
                        @Override public void beforeShow(BasePopupView v) { }
                        @Override public void onShow(BasePopupView v) { }
                        @Override public void onDismiss(BasePopupView v) { }
                        @Override public void beforeDismiss(BasePopupView v) { }
                        @Override public boolean onBackPressed(BasePopupView v) {
                            if (listener != null) listener.onCancel();
                            return true;
                        }
                        @Override public void onKeyBoardStateChanged(BasePopupView v, int h) { }
                        @Override public void onDrag(BasePopupView v, int c, float x, boolean b) { }
                        @Override public void onClickOutside(BasePopupView v) { }
                    })
                    .asCustom(this).show();
        }
        return super.show();
    }
}

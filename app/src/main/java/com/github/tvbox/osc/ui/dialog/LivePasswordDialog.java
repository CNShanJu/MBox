package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.widget.EditText;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.interfaces.XPopupCallback;

import org.jetbrains.annotations.NotNull;

/**
 * 直播源分组密码输入弹窗（统一走 XPopup 居中弹窗 AppCenterPopupView;观感与其它 XPopup 弹窗一致）。
 * <p>外部用法不变:{@code new LivePasswordDialog(activity)} + {@code setOnListener} + {@code show()};
 * 提交按钮触发 {@link OnListener#onChange(String)} 后关闭;返回键触发 {@link OnListener#onCancel()}。
 */
public class LivePasswordDialog extends AppCenterPopupView {

    private OnListener listener = null;
    private EditText inputPassword;

    public LivePasswordDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_live_password;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        inputPassword = findViewById(R.id.input);
        findViewById(R.id.inputSubmit).setOnClickListener(v -> {
            String password = inputPassword.getText().toString().trim();
            if (!password.isEmpty()) {
                listener.onChange(password);
                dismiss();
            }
        });
    }

    /** 兼容旧调用点：popupInfo 未绑定时经 Builder 绑定（返回键→onCancel 后关闭） */
    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isDarkTheme())
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

    public void setOnListener(OnListener listener) {
        this.listener = listener;
    }

    public interface OnListener {
        void onChange(String password);

        void onCancel();
    }
}

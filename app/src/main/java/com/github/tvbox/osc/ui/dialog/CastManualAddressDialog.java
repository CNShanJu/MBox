package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.theme.ThemeDrawables;
import com.lxj.xpopup.core.BasePopupView;

/** Small themed address prompt shown on demand from the cast target list. */
final class CastManualAddressDialog extends AppCenterPopupView {
    interface OnConnect { void onConnect(String address); }

    static BasePopupView show(@NonNull Context context, String initialAddress,
                              @NonNull OnConnect onConnect) {
        CastManualAddressDialog content = new CastManualAddressDialog(
                context, initialAddress, onConnect);
        return DialogCoordinator.centerInHostView(context, content).show();
    }

    private final String initialAddress;
    private final OnConnect onConnect;

    private CastManualAddressDialog(@NonNull Context context, String initialAddress,
                                    @NonNull OnConnect onConnect) {
        super(context);
        this.initialAddress = initialAddress == null ? "" : initialAddress;
        this.onConnect = onConnect;
    }

    @Override protected int getImplLayoutId() { return R.layout.dialog_cast_manual; }

    @Override protected void onCreate() {
        super.onCreate();
        EditText input = findViewById(R.id.cast_address_input);
        ThemeDrawables.applyBackground(input, R.drawable.bg_lan_import_field);
        input.setText(initialAddress);
        input.setSelection(input.length());
        findViewById(R.id.cast_address_cancel).setOnClickListener(v -> dismiss());
        findViewById(R.id.cast_address_confirm).setOnClickListener(v -> submit(input));
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            submit(input);
            return true;
        });
    }

    @Override public void focusAndProcessBackPress() {
        super.focusAndProcessBackPress();
        Window window = getHostWindow();
        if (window != null)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void submit(EditText input) {
        String address = input.getText().toString().trim();
        if (address.isEmpty()) {
            input.setError("请输入接收端 IP:端口");
            return;
        }
        dismiss();
        onConnect.onConnect(address);
    }
}

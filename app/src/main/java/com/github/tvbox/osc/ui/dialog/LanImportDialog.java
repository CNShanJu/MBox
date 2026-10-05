package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogLanImportBinding;
import com.github.tvbox.osc.server.LanImportAlerts;
import com.github.tvbox.osc.theme.ThemeDrawables;
import com.github.tvbox.osc.transfer.ConfigImportSource;
import com.github.tvbox.osc.transfer.ConfigImportSession;
import com.github.tvbox.osc.transfer.ConfigImportSources;
import com.github.tvbox.osc.util.HeavyTaskUtil;

/** 手输或扫码连接入口；连接状态由 ConfigImportSession 持有。 */
public final class LanImportDialog extends AppCenterPopupView {
    public interface ScanRequest {
        void onScanRequested(String address, String code);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onConnected;
    private final ScanRequest scanRequest;
    private final String initialAddress;
    private final String initialCode;
    private final boolean connectImmediately;
    private int epoch;

    public LanImportDialog(@NonNull Context context, @NonNull String initialAddress,
                           @NonNull String initialCode, boolean connectImmediately,
                           @NonNull Runnable onConnected,
                           @NonNull ScanRequest scanRequest) {
        super(context);
        this.initialAddress = initialAddress;
        this.initialCode = initialCode;
        this.connectImmediately = connectImmediately;
        this.onConnected = onConnected;
        this.scanRequest = scanRequest;
    }

    @Override protected int getImplLayoutId() { return R.layout.dialog_lan_import; }

    @Override protected void onCreate() {
        super.onCreate();
        DialogLanImportBinding b = DialogLanImportBinding.bind(getPopupImplView());
        ThemeDrawables.applyBackground(b.etLanPeerAddress, R.drawable.bg_lan_import_field);
        ThemeDrawables.applyBackground(b.etLanPeerCode, R.drawable.bg_lan_import_field);
        if (!connectImmediately) {
            b.etLanPeerAddress.setText(initialAddress);
            b.etLanPeerCode.setText(initialCode);
        } else {
            // 扫码结果只传给连接流程，不显示在输入框，也不要求再次点击连接。
            b.tvLanImportDescription.setVisibility(View.GONE);
            b.rowLanPeerAddress.setVisibility(View.GONE);
            b.etLanPeerAddress.setVisibility(View.GONE);
            b.tvLanPeerCodeLabel.setVisibility(View.GONE);
            b.etLanPeerCode.setVisibility(View.GONE);
            b.btnLanConnect.setVisibility(View.GONE);
            b.tvLanImportStatus.setVisibility(View.VISIBLE);
            b.tvLanImportStatus.setText("正在连接服务器…");
            b.getRoot().setFocusableInTouchMode(true);
            b.getRoot().requestFocus();
        }
        b.btnLanScan.setOnClickListener(v -> {
            String address = b.etLanPeerAddress.getText().toString().trim();
            String code = b.etLanPeerCode.getText().toString().trim();
            ++epoch;
            dismiss();
            scanRequest.onScanRequested(address, code);
        });
        b.btnLanCancel.setOnClickListener(v -> {
            ++epoch;
            dismiss();
        });
        b.btnLanConnect.setOnClickListener(v -> connect(b,
                b.etLanPeerAddress.getText().toString().trim(),
                b.etLanPeerCode.getText().toString().trim()));
        if (connectImmediately) {
            // 扫码成功后直接走与手动连接相同的校验/配对流程；失败时保留表单供修正。
            b.getRoot().post(() -> {
                if (epoch == 0 && b.getRoot().isAttachedToWindow()) {
                    connect(b, initialAddress, initialCode);
                }
            });
        }
    }

    @Override public void focusAndProcessBackPress() {
        super.focusAndProcessBackPress();
        // 连接表单必须随输入法缩进可见区域，否则键盘会盖住地址和配对码。
        Window window = getHostWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    @Override protected void onDismiss() {
        ++epoch;
        super.onDismiss();
    }

    private void connect(DialogLanImportBinding b, String address, String code) {
        if (!b.btnLanConnect.isEnabled()) return;
        int request = ++epoch;
        b.btnLanConnect.setEnabled(false);
        b.tvLanImportStatus.setVisibility(View.VISIBLE);
        b.tvLanImportStatus.setText("正在连接服务器…");
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            try {
                ConfigImportSource connected = ConfigImportSources.connectMBox(getContext(), address, code);
                main.post(() -> {
                    if (request != epoch || !b.getRoot().isAttachedToWindow()) return;
                    LanImportAlerts.attach(getContext());
                    ConfigImportSession.get().connect(connected, address);
                    dismiss();
                    main.postDelayed(onConnected, 220L);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (request != epoch || !b.getRoot().isAttachedToWindow()) return;
                    b.btnLanConnect.setEnabled(true);
                    if (connectImmediately) showManualForm(b);
                    b.tvLanImportStatus.setText("连接失败：" + error.getMessage());
                });
            }
        });
    }

    private void showManualForm(DialogLanImportBinding b) {
        b.tvLanImportDescription.setVisibility(View.VISIBLE);
        b.rowLanPeerAddress.setVisibility(View.VISIBLE);
        b.etLanPeerAddress.setVisibility(View.VISIBLE);
        b.tvLanPeerCodeLabel.setVisibility(View.VISIBLE);
        b.etLanPeerCode.setVisibility(View.VISIBLE);
        b.btnLanConnect.setVisibility(View.VISIBLE);
    }
}

package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogLanImportBinding;
import com.github.tvbox.osc.server.LanImportAlerts;
import com.github.tvbox.osc.theme.ThemeDrawables;
import com.github.tvbox.osc.transfer.ConfigImportSource;
import com.github.tvbox.osc.transfer.ConfigImportSession;
import com.github.tvbox.osc.transfer.ConfigImportSources;
import com.github.tvbox.osc.util.HeavyTaskUtil;

/** 只负责输入地址与配对码；连接状态由 ConfigImportSession 持有。 */
public final class LanImportDialog extends AppBottomPopupView {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onConnected;
    private int epoch;

    public LanImportDialog(@NonNull Context context, @NonNull Runnable onConnected) {
        super(context);
        this.onConnected = onConnected;
    }

    @Override protected int getImplLayoutId() { return R.layout.dialog_lan_import; }

    @Override protected void onCreate() {
        super.onCreate();
        DialogLanImportBinding b = DialogLanImportBinding.bind(getPopupImplView());
        ThemeDrawables.applyBackground(b.etLanPeerAddress, R.drawable.bg_lan_import_field);
        ThemeDrawables.applyBackground(b.etLanPeerCode, R.drawable.bg_lan_import_field);
        b.btnLanConnect.setOnClickListener(v -> connect(b));
    }

    private void connect(DialogLanImportBinding b) {
        String address = b.etLanPeerAddress.getText().toString().trim();
        String code = b.etLanPeerCode.getText().toString().trim();
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
                    b.tvLanImportStatus.setText("连接失败：" + error.getMessage());
                });
            }
        });
    }
}

package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogLanPairQrBinding;
import com.github.tvbox.osc.util.LanPairQr;

import java.util.List;

/** 向另一台 MBox 展示访问地址和配对码的扫码入口。 */
public final class LanPairQrDialog extends AppCenterPopupView {
    private final List<String> addresses;
    private final String code;
    private int selectedAddress;
    private DialogLanPairQrBinding binding;

    public LanPairQrDialog(@NonNull Context context, @NonNull List<String> addresses,
                           @NonNull String code) {
        super(context);
        this.addresses = addresses;
        this.code = code;
    }

    @Override protected int getImplLayoutId() { return R.layout.dialog_lan_pair_qr; }

    @Override protected void onCreate() {
        super.onCreate();
        binding = DialogLanPairQrBinding.bind(getPopupImplView());
        binding.btnNextLanAddress.setVisibility(addresses.size() > 1 ? View.VISIBLE : View.GONE);
        binding.btnNextLanAddress.setOnClickListener(v -> {
            selectedAddress = (selectedAddress + 1) % addresses.size();
            render();
        });
        binding.btnCloseLanQr.setOnClickListener(v -> dismiss());
        render();
    }

    private void render() {
        if (addresses.isEmpty()) return;
        String address = addresses.get(selectedAddress);
        binding.tvLanQrAddress.setText(address);
        binding.tvLanQrCode.setText("配对码：" + code);
        try {
            binding.ivLanQr.setImageBitmap(LanPairQr.bitmap(
                    LanPairQr.encode(address, code), dp(240)));
            binding.tvLanQrError.setVisibility(View.GONE);
        } catch (Exception error) {
            binding.ivLanQr.setImageBitmap(null);
            binding.tvLanQrError.setVisibility(View.VISIBLE);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogInputSubsriptionBinding;
import com.github.tvbox.osc.databinding.DialogLiveApiBinding;
import com.github.tvbox.osc.util.LiveConfig;
import com.github.tvbox.osc.config.SystemConfig;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.CenterPopupView;
import com.lxj.xpopup.interfaces.OnInputConfirmListener;

import java.util.ArrayList;

public class LiveApiDialog extends AppCenterPopupView {

    private com.github.tvbox.osc.databinding.DialogLiveApiBinding mBinding;

    public LiveApiDialog(@NonNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_live_api;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        mBinding = DialogLiveApiBinding.bind(getPopupImplView());
        String liveApi = SystemConfig.getLiveUrl();
        updateEt(liveApi);

        mBinding.ivHistory.setOnClickListener(view -> {
            ArrayList<String> liveHistory = LiveConfig.liveHistory();
            if (liveHistory.isEmpty()){
                AppBubble.toast("暂无历史记录");
                return;
            }
            DialogCoordinator.center(getContext(), new ApiHistoryDialog(getContext(), liveApi, this::updateEt)).show();
        });

        mBinding.btnCancel.setOnClickListener(v -> dismiss());
        mBinding.btnConfirm.setOnClickListener(view -> {
            String newLive = mBinding.etUrl.getText().toString().trim();
            // Capture Live input into Settings & Live History (max 20)
            SystemConfig.setLiveUrl(newLive);
            if (!newLive.isEmpty()) {
                ArrayList<String> liveHistory = LiveConfig.liveHistory();
                if (!liveHistory.contains(newLive))
                    liveHistory.add(0, newLive);
                if (liveHistory.size() > 20)
                    liveHistory.remove(20);
                LiveConfig.setLiveHistory(liveHistory);
            }
            AppBubble.toast("设置成功");
            dismiss();
        });
    }

    private void updateEt(String text){
        mBinding.etUrl.setText(text);
        mBinding.etUrl.setSelection(text.length());
    }
}
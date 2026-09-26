package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.download.DownloadFacade;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.lxj.xpopup.core.CenterPopupView;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

/**
 * 下载设置弹窗(跟随主题):
 * - 仅 WiFi 下载:AppSwitch 开关组件
 * - 下载并发:点击弹 SelectDialog(1-5)
 * 与下载页标题栏齿轮、全局设置页共用 DownloadFacade,单一事实源。
 */
public class DownloadSettingsDialog extends AppCenterPopupView {

    private TextView mTvConcurrent;
    private com.github.tvbox.osc.ui.kit.AppSwitch mSwitchWifi;

    public DownloadSettingsDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_download_settings;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        mTvConcurrent = findViewById(R.id.tv_concurrent);
        mSwitchWifi = findViewById(R.id.switch_wifi);

        // 仅 WiFi 下载开关(点击整行切换,AppSwitch 展示状态)
        mSwitchWifi.setChecked(DownloadFacade.get().isWifiOnly());
        findViewById(R.id.ll_wifi).setOnClickListener(v -> {
            boolean newVal = !DownloadFacade.get().isWifiOnly();
            DownloadFacade.get().setWifiOnly(newVal);
            mSwitchWifi.setChecked(newVal);
        });

        // 下载并发:弹 SelectDialog(1-5)
        refreshConcurrent();
        findViewById(R.id.ll_concurrent).setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            ArrayList<String> types = new ArrayList<>();
            for (int i = 1; i <= 5; i++) types.add("并发 " + i);
            int defaultPos = DownloadFacade.get().getMaxConcurrent() - 1;
            SelectDialog<String> dialog = new SelectDialog<>(getContext());
            dialog.setTip("选择同时下载任务数");
            dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<String>() {
                @Override
                public void click(String value, int pos) {
                    DownloadFacade.get().setMaxConcurrent(pos + 1);
                    refreshConcurrent();
                }

                @Override
                public String getDisplay(String name) {
                    return name;
                }
            }, SelectDialogAdapter.stringDiff, types, defaultPos);
            dialog.show();
        });
    }

    private void refreshConcurrent() {
        mTvConcurrent.setText(DownloadFacade.get().getMaxConcurrent() + " 个");
    }
}

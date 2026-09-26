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
 * - 下载限速:点击弹 SelectDialog(不限速 / 512KB/s / 1MB/s / 2MB/s / 5MB/s)
 * 与下载页标题栏齿轮、全局设置页共用 DownloadFacade,单一事实源;
 * 档位与文案取自 util/ThrottlePolicy(同一份,避免两处设置项漂移)。
 */
public class DownloadSettingsDialog extends AppCenterPopupView {

    private TextView mTvConcurrent;
    private TextView mTvSpeed;
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
        mTvSpeed = findViewById(R.id.tv_speed);
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

        // 下载限速:弹 SelectDialog(不限速 … 5MB/s);改完对运行中的任务当场生效
        refreshSpeed();
        findViewById(R.id.ll_speed).setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            ArrayList<String> types = new ArrayList<>();
            for (long bps : com.github.tvbox.osc.util.ThrottlePolicy.PRESET_BYTES_PER_SEC) {
                types.add(com.github.tvbox.osc.util.ThrottlePolicy.label(bps));
            }
            int defaultPos = com.github.tvbox.osc.util.ThrottlePolicy
                    .presetIndex(DownloadFacade.get().getSpeedLimitBytesPerSec());
            SelectDialog<String> dialog = new SelectDialog<>(getContext());
            dialog.setTip("选择下载限速(不限速即跑满带宽)");
            dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<String>() {
                @Override
                public void click(String value, int pos) {
                    DownloadFacade.get().setSpeedLimitBytesPerSec(
                            com.github.tvbox.osc.util.ThrottlePolicy.PRESET_BYTES_PER_SEC[pos]);
                    refreshSpeed();
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

    private void refreshSpeed() {
        mTvSpeed.setText(com.github.tvbox.osc.util.ThrottlePolicy
                .label(DownloadFacade.get().getSpeedLimitBytesPerSec()));
    }
}

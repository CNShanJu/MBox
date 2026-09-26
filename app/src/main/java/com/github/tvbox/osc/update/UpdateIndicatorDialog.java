package com.github.tvbox.osc.update;

import android.content.Context;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.dialog.AppCenterPopupView;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.XPopup;

import org.jetbrains.annotations.NotNull;

/**
 * 更新下载信息弹窗(全局悬浮圈点击后展示)。
 * <p>展示版本/进度,并暴露 暂停/继续、不再更新、安装 三个动作;随 {@link UpdateManager}
 * 状态实时刷新进度。
 */
public class UpdateIndicatorDialog extends AppCenterPopupView implements UpdateManager.Listener {

    private android.widget.TextView tvVersion;
    private android.widget.TextView tvProgressText;
    private ProgressBar progressBar;
    private android.widget.TextView btnPauseResume;
    private android.widget.TextView btnInstall;

    public UpdateIndicatorDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_update_indicator;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        tvVersion = findViewById(R.id.update_version);
        tvProgressText = findViewById(R.id.update_progress_text);
        progressBar = findViewById(R.id.update_progress);
        btnPauseResume = findViewById(R.id.btn_pause_resume);
        btnInstall = findViewById(R.id.btn_install);
        final android.widget.TextView btnDismiss = findViewById(R.id.btn_dismiss);

        findViewById(R.id.iv_close).setOnClickListener(v -> dismiss());

        btnPauseResume.setOnClickListener(v -> {
            UpdateManager.State s = UpdateManager.get().getState();
            if (s == UpdateManager.State.DOWNLOADING) {
                UpdateManager.get().pause();
            } else if (s == UpdateManager.State.PAUSED) {
                UpdateManager.get().resume();
            } else if (s == UpdateManager.State.FAILED) {
                // 重试(断点续传):沿用最后一次的 update 信息
                UpdateInfo info = UpdateManager.get().getInfo();
                if (info != null) {
                    UpdateManager.get().start(getContext(), info, null);
                }
            }
            // 状态刷新由 onUpdate 回调驱动
        });

        btnDismiss.setOnClickListener(v -> {
            UpdateManager.get().cancel();
            dismiss();
        });

        btnInstall.setOnClickListener(v -> {
            if (UpdateManager.get().installCurrent(getContext())) {
                AppBubble.toast("正在安装新版...");
                dismiss();
            }
        });

        // 订阅 UpdateManager,弹窗打开期间实时刷新
        UpdateManager.get().addListener(this);
        refresh();
    }

    @Override
    protected void onDismiss() {
        UpdateManager.get().removeListener(this);
        super.onDismiss();
    }

    @Override
    public void onUpdate(UpdateManager.State state, long downloaded, long total, UpdateInfo info) {
        refresh();
    }

    private void refresh() {
        try {
            UpdateManager m = UpdateManager.get();
            UpdateInfo info = m.getInfo();
            String version = info == null ? "" : (info.versionName == null ? "" : "v" + info.versionName);
            tvVersion.setText(("发现新版本 " + version).trim());

            long downloaded = m.getDownloaded();
            long total = m.getTotal();
            int percent = total > 0 ? (int) (downloaded * 100 / total) : 0;
            progressBar.setProgress(Math.max(0, Math.min(100, percent)));

            UpdateManager.State s = m.getState();
            String stateText;
            switch (s) {
                case DOWNLOADING:
                    stateText = "下载中 " + percent + "% (" + size(downloaded) + "/" + (total > 0 ? size(total) : "未知") + ")";
                    btnPauseResume.setText("暂停");
                    btnPauseResume.setVisibility(android.view.View.VISIBLE);
                    btnInstall.setVisibility(android.view.View.GONE);
                    break;
                case PAUSED:
                    stateText = "已暂停 " + percent + "% (" + size(downloaded) + "/" + (total > 0 ? size(total) : "未知") + ")";
                    btnPauseResume.setText("继续");
                    btnPauseResume.setVisibility(android.view.View.VISIBLE);
                    btnInstall.setVisibility(android.view.View.GONE);
                    break;
                case COMPLETED:
                    stateText = "下载完成 (" + size(downloaded) + "),点击安装";
                    btnPauseResume.setVisibility(android.view.View.GONE);
                    btnInstall.setVisibility(android.view.View.VISIBLE);
                    break;
                case FAILED:
                    stateText = "下载失败: " + (m.getError() == null ? "未知错误" : m.getError());
                    btnPauseResume.setVisibility(android.view.View.GONE);
                    btnPauseResume.setText("重试");
                    btnPauseResume.setVisibility(android.view.View.VISIBLE);
                    btnInstall.setVisibility(android.view.View.GONE);
                    break;
                default:
                    stateText = "空闲";
                    btnPauseResume.setVisibility(android.view.View.GONE);
                    btnInstall.setVisibility(android.view.View.GONE);
                    break;
            }
            tvProgressText.setText(stateText);
        } catch (Throwable ignored) {
        }
    }

    private static String size(long bytes) {
        if (bytes <= 0) return "0";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format("%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    /** 兼容旧调用点:popupInfo 未绑定时经 Builder 绑定后展示(跟随主题) */
    @Override
    public BasePopupView show() {
        if (popupInfo == null) {
            return new XPopup.Builder(getContext())
                    .isDarkTheme(Utils.isAppDarkTheme())
                    .asCustom(this).show();
        }
        return super.show();
    }
}

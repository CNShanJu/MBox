package com.github.tvbox.osc.update;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ScaleDrawable;
import android.view.Gravity;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.dialog.AppCenterPopupView;
import com.github.tvbox.osc.ui.dialog.ConfirmDialog;
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
    private android.widget.TextView tvDownloadRoute;
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
        tvDownloadRoute = findViewById(R.id.update_download_route);
        tvProgressText = findViewById(R.id.update_progress_text);
        progressBar = findViewById(R.id.update_progress);
        initProgressDrawable();
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
            ConfirmDialog.showDanger(getContext(), "放弃更新",
                    "确定停止更新并删除已下载的安装包吗？", "放弃更新", () -> {
                        UpdateManager.get().cancel();
                        dismiss();
                    });
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

    /** 轨道按文字主色淡化，填充用 ScaleDrawable 保留任意进度下的圆角右端。 */
    private void initProgressDrawable() {
        float radius = 3f * getResources().getDisplayMetrics().density;
        GradientDrawable track = roundedBar(UpdateProgressColors.trackColor(getContext()), radius);
        GradientDrawable fill = roundedBar(UpdateProgressColors.themeColor(getContext(),
                R.color.download_active), radius);
        Drawable scaledFill = new ScaleDrawable(fill, Gravity.START, 1f, -1f);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, scaledFill});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        progressBar.setProgressDrawable(layers);
    }

    private static GradientDrawable roundedBar(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
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
            if (info == null) {
                tvDownloadRoute.setVisibility(android.view.View.GONE);
            } else {
                String url = m.getCurrentDownloadUrl();
                String route;
                if (url != null && !url.isEmpty()) {
                    String prefix = s == UpdateManager.State.FAILED ? "最后尝试链路：" : "当前下载链路：";
                    route = prefix + UpdateDownloadRoute.describe(url);
                } else if (s == UpdateManager.State.COMPLETED) {
                    route = "当前下载链路：本地缓存（无需下载）";
                } else if (s == UpdateManager.State.DOWNLOADING) {
                    route = "当前下载链路：连接中…";
                } else {
                    route = "当前下载链路：尚未连接";
                }
                tvDownloadRoute.setText(route);
                tvDownloadRoute.setVisibility(android.view.View.VISIBLE);
            }
            String stateText;
            // 状态行配色与「视频下载」同一套语义色(下载中=download_active、完成=download_done、失败=红)
            int stateColor;
            switch (s) {
                case DOWNLOADING:
                    stateText = "下载中 " + percent + "% (" + size(downloaded) + "/" + (total > 0 ? size(total) : "未知") + ")";
                    stateColor = R.color.download_active;
                    btnPauseResume.setText("暂停");
                    btnPauseResume.setVisibility(android.view.View.VISIBLE);
                    btnInstall.setVisibility(android.view.View.GONE);
                    break;
                case PAUSED:
                    stateText = "已暂停 " + percent + "% (" + size(downloaded) + "/" + (total > 0 ? size(total) : "未知") + ")";
                    stateColor = R.color.download_active;
                    btnPauseResume.setText("继续");
                    btnPauseResume.setVisibility(android.view.View.VISIBLE);
                    btnInstall.setVisibility(android.view.View.GONE);
                    break;
                case COMPLETED:
                    stateText = "下载完成 (" + size(downloaded) + ")";
                    stateColor = R.color.download_done;
                    btnPauseResume.setVisibility(android.view.View.GONE);
                    btnInstall.setVisibility(android.view.View.VISIBLE);
                    break;
                case FAILED:
                    stateText = "下载失败: " + (m.getError() == null ? "未知错误" : m.getError());
                    stateColor = R.color.red;
                    btnPauseResume.setVisibility(android.view.View.GONE);
                    btnPauseResume.setText("重试");
                    btnPauseResume.setVisibility(android.view.View.VISIBLE);
                    btnInstall.setVisibility(android.view.View.GONE);
                    break;
                default:
                    stateText = "空闲";
                    stateColor = R.color.text_sub_foreground;
                    btnPauseResume.setVisibility(android.view.View.GONE);
                    btnInstall.setVisibility(android.view.View.GONE);
                    break;
            }
            tvProgressText.setText(stateText);
            int color = UpdateProgressColors.themeColor(getContext(), stateColor);
            tvProgressText.setTextColor(color);
            // 进度条与状态行**同色**(与更新气泡的"进度环/图标/百分比同色"一个口径):
            // 下载中=download_active、完成=download_done 绿、失败=红。
            // 以前这里只给文字上色,进度条写死蓝色,下载完成后
            // 就出现"一条蓝杠 + 绿字",用户口径:"看着不协调"。
            progressBar.setProgressTintList(ColorStateList.valueOf(color));
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

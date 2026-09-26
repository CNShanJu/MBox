package com.github.tvbox.osc.download.internal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import com.github.tvbox.osc.download.R;

import com.github.tvbox.osc.bean.DownloadTask;

import java.io.File;

/**
 * 下载通知器（可选轻组件）：下载完成/失败系统通知，点击打开文件。
 * 独立于下载核心逻辑（executor 完成点调用）；无通知权限/渠道失败时静默降级（不影响下载）。
 * 后续可扩展"关闭/仅失败/全部"策略开关（配置门面模式）。
 */
public final class DownloadNotifier {

    private static final String CHANNEL_ID = "download";
    private static volatile boolean initialized = false;
    private static volatile Context appContext;

    private DownloadNotifier() {
    }

    /** App 启动时调用一次（创建通知渠道，API 26+） */
    public static void init(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = context.getSystemService(NotificationManager.class);
                if (nm == null) return;
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "下载通知", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(channel);
            }
            initialized = true;
            appContext = context == null ? null : context.getApplicationContext();
        } catch (Throwable ignored) {
        }
    }

    /** 下载完成通知（点击打开文件）；无权限/异常静默降级 */
    public static void notifyCompleted(DownloadTask t) {
        if (t == null || t.savePath == null || !initialized) return;
        try {
            Context ctx = appContext;
            if (ctx == null) return;
            if (Build.VERSION.SDK_INT >= 33
                    && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return; // 未授予通知权限: 静默降级
            }
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;

            Intent open = new Intent(Intent.ACTION_VIEW);
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", new File(t.savePath));
            open.setDataAndType(uri, "video/*");
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            PendingIntent pi = PendingIntent.getActivity(
                    ctx, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_play)
                    .setContentTitle("下载完成")
                    .setContentText(t.fileName == null ? "视频已下载" : t.fileName)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify((t.id == null ? "dl" : t.id).hashCode(), n);
        } catch (Throwable ignored) {
        }
    }
}

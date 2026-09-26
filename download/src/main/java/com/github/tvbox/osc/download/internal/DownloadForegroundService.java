package com.github.tvbox.osc.download.internal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.download.R;

import java.util.List;

/**
 * 下载前台服务（可选增强）：有任务下载中时保活（防进程被杀）+ 通知栏展示进度。
 * <p>
 * 使用方式（均由 DownloadManager 在调度点自动调用，业务/UI 无感）：
 * <ul>
 *   <li>{@link #startIfNeeded(Context)}——存在下载中/等待任务时拉起（幂等）；</li>
 *   <li>{@link #update(Context, int, long)}——进度刷新（通知栏进度条）；</li>
 *   <li>{@link #stopIfIdle(Context)}——无活动任务时停止。</li>
 * </ul>
 * Android 13+ 需通知权限（未授予时静默跳过，不影响下载）；API 34 需
 * FOREGROUND_SERVICE_DATA_SYNC 权限（app 侧 manifest 声明）。
 */
public final class DownloadForegroundService extends Service {

    private static final String CHANNEL_ID = "download_foreground";
    private static final int NOTIFICATION_ID = 0x1001;
    private static volatile boolean running = false;

    public static final String ACTION_UPDATE = "com.github.tvbox.osc.download.action.UPDATE";

    // ── 对外入口（DownloadManager 调度点调用）──

    /** 有下载中/等待任务时启动前台服务（幂等：已运行则仅刷新通知） */
    public static void startIfNeeded(Context ctx, List<DownloadTask> tasks) {
        if (ctx == null) return;
        try {
            int downloading = countActive(tasks);
            if (downloading <= 0) return;
            Intent intent = new Intent(ctx, DownloadForegroundService.class);
            intent.setAction(ACTION_UPDATE);
            intent.putExtra("count", downloading);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Throwable th) {
            // 前台服务启动失败(如后台启动限制/权限)静默降级:下载不受影响
            th.printStackTrace();
        }
    }

    /** 更新通知栏进度（下载中任务数/进行中的总进度） */
    public static void update(Context ctx, int activeCount, long downloaded, long total) {
        if (ctx == null || !running) return;
        try {
            Intent intent = new Intent(ctx, DownloadForegroundService.class);
            intent.setAction(ACTION_UPDATE);
            intent.putExtra("count", activeCount);
            intent.putExtra("downloaded", downloaded);
            intent.putExtra("total", total);
            ctx.startService(intent);
        } catch (Throwable ignored) {
        }
    }

    /** 无活动任务时停止前台服务 */
    public static void stopIfIdle(Context ctx, List<DownloadTask> tasks) {
        if (ctx == null || !running) return;
        try {
            if (countActive(tasks) > 0) return;
            ctx.stopService(new Intent(ctx, DownloadForegroundService.class));
            running = false;
        } catch (Throwable ignored) {
        }
    }

    private static int countActive(List<DownloadTask> tasks) {
        if (tasks == null) return 0;
        int count = 0;
        for (DownloadTask t : tasks) {
            if (t != null && (t.state == DownloadTask.STATE_DOWNLOADING || t.state == DownloadTask.STATE_WAITING)) {
                count++;
            }
        }
        return count;
    }

    // ── Service ──

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "下载进行中", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int count = intent == null ? 1 : intent.getIntExtra("count", 1);
        long downloaded = intent == null ? 0 : intent.getLongExtra("downloaded", 0);
        long total = intent == null ? 0 : intent.getLongExtra("total", 0);
        startForeground(NOTIFICATION_ID, buildNotification(count, downloaded, total));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    private Notification buildNotification(int count, long downloaded, long total) {
        String title = "正在下载 " + count + " 个任务";
        String text = "下载完成后将收到通知";
        int progress = 0;
        boolean indeterminate = true;
        if (total > 0 && downloaded > 0) {
            progress = (int) (100L * downloaded / total);
            indeterminate = false;
            text = "已完成 " + downloaded + " / " + total + " 字节";
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, indeterminate);
        return b.build();
    }
}

package com.github.tvbox.osc.update;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.startup.AppLaunchSource;
import com.github.tvbox.osc.util.LOG;

import java.util.Locale;

/** Keeps an actively downloading update in the foreground when the app leaves the screen. */
public final class UpdateDownloadService extends Service implements UpdateManager.Listener {
    private static final String TAG = "UpdateDownloadService";
    private static final String EXTRA_WORKER_EPOCH = "worker_epoch";
    private static final String CHANNEL_ID = "mbox_update_download";
    private static final int NOTIFICATION_ID = 2109;
    private static final long NOTIFICATION_INTERVAL_MS = 500L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long WAKE_LOCK_RENEWAL_MS = 5 * 60 * 1000L;

    private NotificationManager notificationManager;
    private PowerManager.WakeLock cpuLock;
    private long lastNotificationAt;
    private long lastWakeLockAt;
    private boolean foreground;

    /** Call after UpdateManager enters DOWNLOADING from a user initiated start or resume. */
    public static boolean start(Context context, long workerEpoch) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        try {
            ContextCompat.startForegroundService(app, new Intent(app, UpdateDownloadService.class)
                    .putExtra(EXTRA_WORKER_EPOCH, workerEpoch));
            return true;
        } catch (RuntimeException error) {
            LOG.e(TAG, "无法启动更新下载前台服务: " + error);
            return false;
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        app.stopService(new Intent(app, UpdateDownloadService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            notificationManager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "MBox 更新下载", NotificationManager.IMPORTANCE_LOW));
        }
        UpdateManager.get().addListener(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        UpdateManager manager = UpdateManager.get();
        long workerEpoch = intent == null ? -1 : intent.getLongExtra(EXTRA_WORKER_EPOCH, -1);
        if (!manager.isActiveDownload(workerEpoch)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            Notification notification = notification(manager.getDownloaded(), manager.getTotal(), manager.getInfo());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foreground = true;
        } catch (RuntimeException error) {
            LOG.e(TAG, "无法进入更新下载前台服务: " + error);
            manager.onForegroundServiceStartFailed(workerEpoch);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        // Completion may have arrived while the service was being created.
        if (manager.getState() != UpdateManager.State.DOWNLOADING) {
            stopActiveDownloadService();
            return START_NOT_STICKY;
        }
        holdCpuWhileDownloading();
        return START_NOT_STICKY;
    }

    @Override public void onUpdate(UpdateManager.State state, long downloaded, long total, UpdateInfo info) {
        // The callback was queued on the main thread; a newer run may already have started.
        UpdateManager manager = UpdateManager.get();
        if (manager.getState() != UpdateManager.State.DOWNLOADING) {
            stopActiveDownloadService();
            return;
        }
        if (!foreground) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastWakeLockAt >= WAKE_LOCK_RENEWAL_MS) holdCpuWhileDownloading();
        if (now - lastNotificationAt < NOTIFICATION_INTERVAL_MS) return;
        lastNotificationAt = now;
        if (notificationManager != null) {
            try {
                notificationManager.notify(NOTIFICATION_ID,
                        notification(manager.getDownloaded(), manager.getTotal(), manager.getInfo()));
            } catch (RuntimeException error) {
                LOG.e(TAG, "无法更新下载通知: " + error);
            }
        }
    }

    private Notification notification(long downloaded, long total, UpdateInfo info) {
        String version = info == null || info.versionName == null ? "" : " " + info.versionName;
        String progress;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle("MBox 正在下载更新" + version)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (total > 0) {
            int percent = (int) Math.min(100, Math.max(0, downloaded * 100.0 / total));
            progress = String.format(Locale.CHINA, "%d%% · %.1f / %.1f MB",
                    percent, downloaded / 1048576.0, total / 1048576.0);
            builder.setProgress(100, percent, false);
        } else {
            progress = String.format(Locale.CHINA, "已下载 %.1f MB", downloaded / 1048576.0);
            builder.setProgress(0, 0, true);
        }
        builder.setContentText(progress);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.putExtra(AppLaunchSource.EXTRA_NON_USER_ENTRY, true);
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION_ID, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(open);
        }
        return builder.build();
    }

    private void holdCpuWhileDownloading() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power == null) return;
        if (cpuLock == null) {
            cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MBox:UpdateDownload");
            cpuLock.setReferenceCounted(false);
        } else if (cpuLock.isHeld()) {
            cpuLock.release();
        }
        cpuLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        lastWakeLockAt = SystemClock.uptimeMillis();
    }

    private void stopActiveDownloadService() {
        releaseCpuLock();
        if (foreground) {
            stopForeground(true);
            foreground = false;
        }
        stopSelf();
    }

    private void releaseCpuLock() {
        if (cpuLock != null) {
            if (cpuLock.isHeld()) cpuLock.release();
            cpuLock = null;
        }
    }

    @Override public void onDestroy() {
        UpdateManager.get().removeListener(this);
        releaseCpuLock();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}

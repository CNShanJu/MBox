package com.github.tvbox.osc.cast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.ui.activity.LanServiceActivity;
import com.github.tvbox.osc.ui.startup.AppLaunchSource;

/** Keeps only the temporary DLNA media listener alive while a cast session is active. */
public final class CastMediaService extends Service {
    private static final String CHANNEL_ID = "mbox_cast_media";
    private static final String EXTRA_GENERATION = "cast_media_generation";
    private static final String ACTION_STOP = "com.github.tvbox.osc.cast.STOP_MEDIA";
    private static final String ACTION_RELEASE = "com.github.tvbox.osc.cast.RELEASE_MEDIA";
    private static final int NOTIFICATION_ID = 2108;
    private long generation = -1;
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;

    static void start(Context context, long generation) {
        Context app = context.getApplicationContext();
        Intent start = new Intent(app, CastMediaService.class);
        start.putExtra(EXTRA_GENERATION, generation);
        ContextCompat.startForegroundService(app, start);
    }

    static void stop(Context context, long generation) {
        Context app = context.getApplicationContext();
        Intent release = new Intent(app, CastMediaService.class);
        release.setAction(ACTION_RELEASE);
        release.putExtra(EXTRA_GENERATION, generation);
        app.startService(release);
    }

    /** Read-only status for a notification page opened before the TV confirms the cast. */
    public static boolean isServingGeneration(long expectedGeneration) {
        return CastMediaRelay.hasActiveRelay(expectedGeneration);
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    "MBox 投屏媒体", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        long requested = intent == null ? -1 : intent.getLongExtra(EXTRA_GENERATION, -1);
        if (intent != null && ACTION_RELEASE.equals(intent.getAction())) {
            // An old relay's cleanup must not tear down the replacement foreground service.
            if (!CastMediaRelay.hasActiveRelay()) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            CastMediaRelay.cancelActiveCast(requested, null);
            if (!CastMediaRelay.hasActiveRelay()) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!CastMediaRelay.hasActiveRelay(requested)) {
            if (!CastMediaRelay.hasActiveRelay()) stopSelf(startId);
            return START_NOT_STICKY;
        }
        generation = requested;
        try {
            startForeground(NOTIFICATION_ID, notification());
            holdWhileServing();
            LogStore.log(Category.PLAYER, "投屏媒体服务: 前台保活已启动");
        } catch (RuntimeException error) {
            LogStore.fail(Category.PLAYER, "投屏媒体服务: 前台保活失败="
                    + error.getClass().getSimpleName());
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        CastMediaRelay.ActiveCast active = CastMediaRelay.activeCast();
        boolean confirmed = active != null && active.generation == generation;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle(confirmed ? "DLNA 正在投屏" : "DLNA 准备投屏")
                .setContentText(confirmed ? "点此查看并停止 DLNA 投屏" : "点此查看投屏状态")
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        Intent launch = new Intent(this, LanServiceActivity.class);
        launch.putExtra(LanServiceActivity.EXTRA_FROM_NOTIFICATION, true);
        launch.putExtra(LanServiceActivity.EXTRA_DLNA_GENERATION, generation);
        launch.putExtra(AppLaunchSource.EXTRA_NON_USER_ENTRY, true);
        // Keep each notification entry tied to its own cast, including PendingIntents retained by
        // the system after a newer cast replaces it.
        launch.setData(android.net.Uri.parse("mbox://dlna-cast/" + generation));
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this,
                (int) (generation & 0x7fffffffL), launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setContentIntent(open);
        if (confirmed) {
            Intent stop = new Intent(this, CastMediaService.class);
            stop.setAction(ACTION_STOP);
            stop.putExtra(EXTRA_GENERATION, generation);
            stop.setData(android.net.Uri.parse("mbox://dlna-stop/" + generation));
            PendingIntent stopAction = PendingIntent.getService(this,
                    (int) (generation & 0x7fffffffL), stop,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.ic_close_24, "停止 DLNA 投屏", stopAction);
        }
        return builder.build();
    }

    private void holdWhileServing() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null && cpuLock == null) {
            cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MBox:CastMedia");
            cpuLock.setReferenceCounted(false);
            cpuLock.acquire();
        }
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null && wifiLock == null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MBox:CastMedia");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    @Override public void onDestroy() {
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        wifiLock = null;
        cpuLock = null;
        CastMediaRelay.stopActiveFromService(generation);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}

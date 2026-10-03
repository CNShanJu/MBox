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

/** Keeps only the temporary DLNA media listener alive while a cast session is active. */
public final class CastMediaService extends Service {
    private static final String CHANNEL_ID = "mbox_cast_media";
    private static final String EXTRA_GENERATION = "cast_media_generation";
    private static final String ACTION_STOP = "com.github.tvbox.osc.cast.STOP_MEDIA";
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

    static void stop(Context context) {
        Context app = context.getApplicationContext();
        app.stopService(new Intent(app, CastMediaService.class));
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
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (CastMediaRelay.hasActiveRelay(requested)) {
                CastMediaRelay.stopActiveFromService(requested);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (!CastMediaRelay.hasActiveRelay(requested)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        generation = requested;
        try {
            startForeground(NOTIFICATION_ID, notification());
            holdWhileServing();
        } catch (RuntimeException error) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle("MBox 正在投屏")
                .setContentText("当前媒体临时共享给电视，结束投屏后自动关闭")
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION_ID, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(open);
        }
        Intent stop = new Intent(this, CastMediaService.class);
        stop.setAction(ACTION_STOP);
        stop.putExtra(EXTRA_GENERATION, generation);
        PendingIntent stopAction = PendingIntent.getService(this,
                (int) (generation & 0x7fffffffL), stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(R.drawable.ic_close_24, "停止投屏", stopAction);
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

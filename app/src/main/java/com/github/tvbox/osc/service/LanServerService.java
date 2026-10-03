package com.github.tvbox.osc.service;

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
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.activity.LanServiceActivity;

/** 用户开启局域网服务后承载 HTTP 服务；退到后台和锁屏后继续响应已配对设备。 */
public final class LanServerService extends Service {
    private static final String CHANNEL_ID = "mbox_lan_service";
    private static final int NOTIFICATION_ID = 2107;
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;

    public static void start(Context context) {
        if (!SystemConfig.isLanServerEnabled()) return;
        try {
            ContextCompat.startForegroundService(context.getApplicationContext(),
                    new Intent(context.getApplicationContext(), LanServerService.class));
        } catch (RuntimeException unavailable) {
            // Android 12+ 在部分后台启动窗口会拒绝前台服务，页面仍可继续使用本机服务。
            LogStore.fail(Category.SYSTEM, "局域网前台服务启动失败: " + unavailable);
        }
    }

    public static void stop(Context context) {
        context.getApplicationContext().stopService(
                new Intent(context.getApplicationContext(), LanServerService.class));
    }

    /** 关闭对外监听并保留应用自身所需的回环服务。 */
    public static void disable(Context context) {
        SystemConfig.setLanServerEnabled(false);
        ControlManager manager = ControlManager.get();
        manager.stopServer();
        stop(context);
        manager.startServer();
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "MBox 局域网服务", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!SystemConfig.isLanServerEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID, notification());
        } catch (RuntimeException unavailable) {
            LogStore.fail(Category.SYSTEM, "局域网前台服务进入失败: " + unavailable);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        ControlManager.get().startServer();
        if (!ControlManager.get().isLanServing()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        holdNetworkWhileServing();
        return START_STICKY;
    }

    private Notification notification() {
        Intent open = new Intent(this, LanServiceActivity.class);
        open.putExtra(LanServiceActivity.EXTRA_FROM_NOTIFICATION, true);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle("MBox 局域网服务已开启")
                .setContentText("点此查看访问地址、配对码和已连接设备")
                .setContentIntent(pending)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void holdNetworkWhileServing() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null && cpuLock == null) {
            cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MBox:LanServer");
            cpuLock.setReferenceCounted(false);
            cpuLock.acquire();
        }
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null && wifiLock == null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MBox:LanServer");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    @Override public void onDestroy() {
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        wifiLock = null;
        cpuLock = null;
        if (ControlManager.get().isLanServing()) ControlManager.get().stopServer();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}

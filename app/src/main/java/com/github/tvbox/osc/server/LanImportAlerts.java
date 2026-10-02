package com.github.tvbox.osc.server;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.transfer.ConfigImportSession;
import com.github.tvbox.osc.ui.activity.LanServiceActivity;
import com.github.tvbox.osc.util.AppBubble;

/** 局域网配对状态的用户提示，与通用配置导入会话分离。 */
public final class LanImportAlerts {
    private static final String CHANNEL_ID = "mbox_lan_peer";
    private static final int KICK_NOTIFICATION_ID = 2108;
    private static Context appContext;
    private static ConfigImportSession.State previous = ConfigImportSession.State.DISCONNECTED;

    private LanImportAlerts() { }

    public static void attach(Context context) {
        if (appContext != null) return;
        appContext = context.getApplicationContext();
        ConfigImportSession.get().addListener(LanImportAlerts::onSessionChanged);
    }

    private static void onSessionChanged() {
        ConfigImportSession.State current = ConfigImportSession.get().state();
        if (current == previous) return;
        previous = current;
        if (current == ConfigImportSession.State.CONNECTED) {
            try { NotificationManagerCompat.from(appContext).cancel(KICK_NOTIFICATION_ID); }
            catch (RuntimeException ignored) { }
        } else if (current == ConfigImportSession.State.KICKED) {
            announceKick();
        }
    }

    private static void announceKick() {
        AppBubble.toast("服务器已断开配对，请重新连接");
        Context context = appContext;
        if (context == null) return;
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "局域网连接", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent open = new Intent(context, LanServiceActivity.class)
                    .putExtra(LanServiceActivity.EXTRA_FROM_NOTIFICATION, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pending = PendingIntent.getActivity(context, KICK_NOTIFICATION_ID, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationManagerCompat.from(context).notify(KICK_NOTIFICATION_ID,
                    new NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.app_icon)
                            .setContentTitle("MBox 局域网连接已断开")
                            .setContentText("服务器已移除这台设备，点此重新连接")
                            .setContentIntent(pending)
                            .setAutoCancel(true)
                            .build());
        } catch (RuntimeException ignored) {
            // 未授权通知时，页面状态与前台提示仍可告知用户。
        }
    }
}

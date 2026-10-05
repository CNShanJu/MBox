package com.github.tvbox.osc.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.ResultReceiver;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.activity.LanServiceActivity;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.util.HeavyTaskUtil;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArraySet;

/** 用户开启局域网服务后承载 HTTP 服务；退到后台和锁屏后继续响应已配对设备。 */
public final class LanServerService extends Service {
    public static final String CHANNEL_ID = "mbox_lan_service";
    private static final String EXTRA_START_RESULT = "lan_start_result";
    public static final int START_ACTIVE = 1;
    private static final int NOTIFICATION_ID = 2107;
    private static final AtomicLong NEXT_OWNER = new AtomicLong();
    private static final CopyOnWriteArraySet<StateListener> STATE_LISTENERS = new CopyOnWriteArraySet<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile long foregroundOwner;
    private static volatile LanServerService activeInstance;
    private static final long NOTIFICATION_CHECK_INTERVAL_MS = 15_000L;
    private final Handler notificationHandler = new Handler(Looper.getMainLooper());
    private final Runnable notificationCheck = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (!SystemConfig.isLanServerEnabled()) {
                // 上次关闭若被 socket 异常挡住，保留通知并继续重试关闭。
                if (ControlManager.get().isLanServing()) disable(LanServerService.this);
                else stopSelf();
                notificationHandler.postDelayed(this, NOTIFICATION_CHECK_INTERVAL_MS);
                return;
            }
            if (!canShowNotification(LanServerService.this)) {
                LogStore.fail(Category.SYSTEM, "局域网通知不再可见，停止对外监听");
                disable(LanServerService.this);
                if (!destroyed && ControlManager.get().isLanServing()) {
                    notificationHandler.postDelayed(this, NOTIFICATION_CHECK_INTERVAL_MS);
                }
            } else {
                refreshCastNotification();
                notificationHandler.postDelayed(this, NOTIFICATION_CHECK_INTERVAL_MS);
            }
        }
    };
    private long ownerId;
    private volatile boolean destroyed;
    private boolean castNotificationShown;
    private String castNotificationSessionId;
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;
    private BroadcastReceiver notificationBlockReceiver;

    public interface StateListener {
        void onLanStateChanged();
    }

    public static void addStateListener(StateListener listener) {
        STATE_LISTENERS.add(listener);
    }

    public static void removeStateListener(StateListener listener) {
        STATE_LISTENERS.remove(listener);
    }

    private static void notifyStateChanged() {
        MAIN_HANDLER.post(() -> {
            for (StateListener listener : STATE_LISTENERS) listener.onLanStateChanged();
        });
    }

    private static void restoreLoopback(ControlManager manager) {
        // 通知失败常发生在开屏/设置页主线程，回环端口重建不能阻塞动画。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            HeavyTaskUtil.executeBigTask(manager::startServer);
        } else {
            manager.startServer();
        }
    }

    public static void start(Context context) {
        start(context, null);
    }

    /** Result is delivered after the notification and LAN listener are both ready. */
    public static void start(Context context, @Nullable ResultReceiver result) {
        if (!SystemConfig.isLanServerEnabled()) {
            report(result, 0);
            return;
        }
        try {
            Intent intent = new Intent(context.getApplicationContext(), LanServerService.class);
            if (result != null) intent.putExtra(EXTRA_START_RESULT, result);
            ContextCompat.startForegroundService(context.getApplicationContext(),
                    intent);
        } catch (RuntimeException unavailable) {
            if (isForegroundReady() && ControlManager.get().isLanServing()
                    && canShowNotification(context)) {
                report(result, START_ACTIVE);
                return;
            }
            boolean alreadyForeground = isForegroundReady();
            Context appContext = context.getApplicationContext();
            HeavyTaskUtil.executeBigTask(() -> {
                if (failClosed(appContext, "局域网前台服务启动失败: " + unavailable, 0)
                        && alreadyForeground) stop(appContext);
                report(result, 0);
            });
        }
    }

    public static boolean isForegroundReady() {
        return foregroundOwner != 0;
    }

    /** 投屏开始或结束时更新同一条常驻通知；进度上报不需要刷新通知。 */
    public static void refreshCastNotification() {
        MAIN_HANDLER.post(() -> {
            LanServerService service = activeInstance;
            if (service != null) service.refreshCastNotificationIfChanged();
        });
    }

    private boolean hasActiveBrowserCast() {
        return ControlManager.get().isLanServing()
                && ControlManager.get().activeBrowserPlaybackState() != null;
    }

    private void refreshCastNotificationIfChanged() {
        if (destroyed || foregroundOwner != ownerId || !canShowNotification(this)) return;
        boolean castActive = hasActiveBrowserCast();
        RemoteServer.BrowserCastDetail detail = ControlManager.get().activeBrowserCastDetail();
        String castSession = detail == null ? null : detail.sessionId;
        if (castActive == castNotificationShown
                && java.util.Objects.equals(castSession, castNotificationSessionId)) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        try {
            manager.notify(NOTIFICATION_ID, notification(castActive));
            castNotificationShown = castActive;
            castNotificationSessionId = castSession;
        } catch (RuntimeException unavailable) {
            LogStore.fail(Category.SYSTEM, "局域网投屏通知更新失败: " + unavailable);
        }
    }

    /** 只有通知可在通知栏展示时，才允许服务对局域网绑定。 */
    public static boolean canShowNotification(Context context) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return false;
            NotificationChannel channel = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                channel = manager.getNotificationChannel(CHANNEL_ID);
                if (channel == null) {
                    ensureChannel(context);
                    channel = manager.getNotificationChannel(CHANNEL_ID);
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) return false;
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // MIN 会在状态栏隐藏图标，无法充当持续开启的醒目提醒。
                return channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_LOW;
            }
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                "MBox 局域网服务", NotificationManager.IMPORTANCE_LOW));
    }

    private static void report(@Nullable ResultReceiver result, int code) {
        if (result != null) result.send(code, null);
    }

    private static boolean failClosed(Context context, String reason, long expectedOwner) {
        LogStore.fail(Category.SYSTEM, reason);
        ControlManager manager = ControlManager.get();
        synchronized (LanServerService.class) {
            if (expectedOwner != 0 && foregroundOwner != expectedOwner) return false;
            SystemConfig.setLanServerEnabled(false);
            if (manager.isLanServing()) manager.stopServer();
            if (manager.isLanServing()) {
                LogStore.fail(Category.SYSTEM, "局域网监听关闭失败，保留前台服务并重试");
                notifyStateChanged();
                return false;
            }
            foregroundOwner = 0;
        }
        notifyStateChanged();
        restoreLoopback(manager);
        return true;
    }

    public static void stop(Context context) {
        context.getApplicationContext().stopService(
                new Intent(context.getApplicationContext(), LanServerService.class));
    }

    /** 关闭对外监听并保留应用自身所需的回环服务。 */
    public static void disable(Context context) {
        ControlManager manager = ControlManager.get();
        synchronized (LanServerService.class) {
            SystemConfig.setLanServerEnabled(false);
            manager.stopServer();
            if (manager.isLanServing()) {
                LogStore.fail(Category.SYSTEM, "局域网监听关闭失败，保留通知并重试");
                notifyStateChanged();
                return;
            }
            // 尚在排队的 startForegroundService 必须先进入 onStartCommand 完成前台提升，
            // 再由开关关闭分支自行 stopSelf；这里抢先 stopService 会留下 fgRequired 超时。
            boolean alreadyForeground = foregroundOwner != 0;
            foregroundOwner = 0;
            if (alreadyForeground) stop(context);
        }
        notifyStateChanged();
        restoreLoopback(manager);
    }

    private void registerNotificationBlockReceiver() {
        if (notificationBlockReceiver != null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            notificationBlockReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (SystemConfig.isLanServerEnabled() && !canShowNotification(context)) {
                        LogStore.fail(Category.SYSTEM, "局域网通知已被系统关闭，停止对外监听");
                        disable(context);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED);
            filter.addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(notificationBlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(notificationBlockReceiver, filter);
            }
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final long startOwner;
        try {
            ensureChannel(this);
            castNotificationShown = hasActiveBrowserCast();
            RemoteServer.BrowserCastDetail detail = ControlManager.get().activeBrowserCastDetail();
            castNotificationSessionId = detail == null ? null : detail.sessionId;
            startForeground(NOTIFICATION_ID, notification(castNotificationShown));
            synchronized (LanServerService.class) {
                ownerId = NEXT_OWNER.incrementAndGet();
                foregroundOwner = ownerId;
                activeInstance = this;
                startOwner = ownerId;
            }
            notificationHandler.removeCallbacks(notificationCheck);
            notificationHandler.postDelayed(notificationCheck, NOTIFICATION_CHECK_INTERVAL_MS);
        } catch (RuntimeException unavailable) {
            // 连前台都进不去时先取消所有待处理的启动请求，不能让关闭端口的 I/O
            // 占着主线程，直到系统的前台服务启动计时器触发崩溃。
            stopSelf();
            ResultReceiver result = intent == null ? null : intent.getParcelableExtra(EXTRA_START_RESULT);
            HeavyTaskUtil.executeBigTask(() -> {
                failClosed(this, "局域网前台服务进入失败: " + unavailable, 0);
                report(result, 0);
            });
            return START_NOT_STICKY;
        }
        ResultReceiver result = intent == null ? null : intent.getParcelableExtra(EXTRA_START_RESULT);
        if (!SystemConfig.isLanServerEnabled()) {
            report(result, 0);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!canShowNotification(this)) {
            HeavyTaskUtil.executeBigTask(() -> {
                boolean closed = failClosed(this,
                        "局域网通知权限或通知频道不可用，已关闭局域网服务", startOwner);
                report(result, 0);
                if (closed) stopSelf(startId);
            });
            return START_NOT_STICKY;
        }
        registerNotificationBlockReceiver();
        // 通知先进入前台，再在共享执行器绑定端口；避免冷启动时阻塞开屏动画。
        HeavyTaskUtil.executeBigTask(() -> {
            String failure = null;
            try {
                ControlManager.get().startServerForStartup();
            } catch (RuntimeException unavailable) {
                failure = "局域网监听启动失败: " + unavailable;
            }
            if (destroyed || foregroundOwner != startOwner) {
                ControlManager.get().stopLanWhenNotificationGone();
                report(result, 0);
                return;
            }
            if (failure == null && (!ControlManager.get().isLanServing()
                    || !SystemConfig.isLanServerEnabled() || !canShowNotification(this))) {
                failure = "局域网监听或通知未就绪，已关闭局域网服务";
            }
            if (failure != null) {
                boolean closed = failClosed(this, failure, startOwner);
                report(result, 0);
                if (closed) stopSelf(startId);
                return;
            }
            synchronized (this) {
                if (!destroyed) {
                    try {
                        holdNetworkWhileServing();
                    } catch (RuntimeException unavailable) {
                        LogStore.fail(Category.SYSTEM, "局域网保活锁申请失败: " + unavailable);
                    }
                }
            }
            report(result, !destroyed && foregroundOwner == startOwner
                    && SystemConfig.isLanServerEnabled() && ControlManager.get().isLanServing()
                    ? START_ACTIVE : 0);
            notifyStateChanged();
            refreshCastNotification();
        });
        return START_STICKY;
    }

    private Notification notification(boolean castActive) {
        RemoteServer.BrowserCastDetail detail = castActive
                ? ControlManager.get().activeBrowserCastDetail() : null;
        Intent open;
        if (detail != null) {
            open = new Intent(this, DetailActivity.class);
            open.putExtra("id", detail.vodId);
            open.putExtra("sourceKey", detail.sourceKey);
            open.putExtra("vodName", detail.vodName);
            open.putExtra("browserCastSessionId", detail.sessionId);
        } else {
            open = new Intent(this, LanServiceActivity.class);
            open.putExtra(LanServiceActivity.EXTRA_FROM_NOTIFICATION, true);
        }
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = castActive ? "MBox 正在投屏" : "MBox 局域网服务已开启";
        String description = castActive ? (detail == null ? "点此返回投屏控制" : "点此返回播放详情")
                : "点此查看访问地址、配对码和已连接设备";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 默认可能延后展示前台服务通知；局域网监听需要立即可见的提醒。
            return new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.app_icon)
                    .setContentTitle(title)
                    .setContentText(description)
                    .setContentIntent(pending)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                    .build();
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle(title)
                .setContentText(description)
                .setContentIntent(pending)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
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
        destroyed = true;
        synchronized (LanServerService.class) {
            if (foregroundOwner == ownerId) foregroundOwner = 0;
            if (activeInstance == this) activeInstance = null;
        }
        notificationHandler.removeCallbacks(notificationCheck);
        if (notificationBlockReceiver != null) {
            unregisterReceiver(notificationBlockReceiver);
            notificationBlockReceiver = null;
        }
        synchronized (this) {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
            wifiLock = null;
            cpuLock = null;
        }
        // startServer/stopServer 串行持有 ControlManager 的锁，等待端口重绑时
        // 不能占住主线程，否则下一次 startForegroundService 的生命周期回调会超时。
        HeavyTaskUtil.executeBigTask(() -> ControlManager.get().stopLanWhenNotificationGone());
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}

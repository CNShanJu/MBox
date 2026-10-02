package com.github.tvbox.osc.state;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.CategoryLogger;
import com.github.tvbox.osc.log.LogStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 全局系统状态监控（项目级独立模块，基座层）。
 * <p>
 * 统一监听系统状态变化（网络 / 前后台 / 锁屏 / 横竖屏 / 电量 / 磁盘 / 时间），向所有订阅方
 * 广播——下载（Policy-Decider）、播放器、订阅页、任意页面需要"断网信号 / 横屏切换"等信号时
 * 订阅同一来源，不再各写一套监听。
 * <p>
 * 约束：不持有 Activity 引用（防泄漏）；事件在主线程派发；网络事件去抖合并抖动。
 */
public final class SystemStateMonitor {

    public static final String TYPE_NETWORK = "network";
    public static final String TYPE_FOREGROUND = "foreground";
    public static final String TYPE_SCREEN = "screen";
    public static final String TYPE_ORIENTATION = "orientation";
    public static final String TYPE_BATTERY = "battery";
    /** 电池百分比变化事件(低/充电判定之外,供 UI 电池图标);value=数字字符串(0-100) */
    public static final String TYPE_BATTERY_LEVEL = "battery_level";
    public static final String TYPE_DISK = "disk";
    public static final String TYPE_PERMISSION = "permission";
    public static final String TYPE_TIME = "time";

    public static final String VAL_NONE = "NONE";
    public static final String VAL_WIFI = "WIFI";
    public static final String VAL_CELLULAR = "CELLULAR";
    /**
     * 有可用链路但不属于 WiFi/蜂窝（VPN / 以太网 / 蓝牙共享等）。
     * <p>
     * 语义是"有没有可用网络"，不是"哪种传输方式"：原来只认 WIFI/CELLULAR，其它 transport 一律落回
     * {@link #VAL_NONE} → 这些用户被整条链路当成"没网"（无网络页永不自动返回、页面永不自动刷新）。
     */
    public static final String VAL_CONNECTED = "CONNECTED";
    public static final String VAL_ON = "ON";
    public static final String VAL_OFF = "OFF";
    public static final String VAL_FOREGROUND = "FOREGROUND";
    public static final String VAL_BACKGROUND = "BACKGROUND";
    public static final String VAL_PORTRAIT = "PORTRAIT";
    public static final String VAL_LANDSCAPE = "LANDSCAPE";
    public static final String VAL_LOW = "LOW";
    public static final String VAL_NORMAL = "NORMAL";
    public static final String VAL_CHARGING = "CHARGING";
    public static final String VAL_PERMISSION_GRANTED = "GRANTED";
    public static final String VAL_PERMISSION_REVOKED = "REVOKED";

    /** 低电量阈值：20% */
    private static final float LOW_BATTERY_RATIO = 0.2f;
    /** 磁盘告警阈值：512MB */
    private static final long MIN_FREE_DISK = 512L * 1024 * 1024;
    /** 磁盘轮询周期 */
    private static final long DISK_POLL_MS = 5 * 60 * 1000;
    /** 网络事件去抖：300ms 合并抖动 */
    private static final long NETWORK_DEBOUNCE_MS = 300;

    private static volatile SystemStateMonitor instance;

    public interface Listener {
        /** 主线程回调；e.type 为事件类型，e.value 为事件值 */
        void onChanged(SystemEvent e);
    }

    /** 按事件类型过滤的订阅表 */
    private final Map<String, List<Listener>> listeners = new ConcurrentHashMap<>();
    private final List<Listener> allListeners = new CopyOnWriteArrayList<>();

    private final SystemState state = new SystemState();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private CategoryLogger<SystemSubType> log;
    private ScheduledExecutorService diskTimer;
    /** 注入的 application context（独立模块，不依赖 app 类） */
    private Context appContext;

    private int startedActivityCount = 0;
    /** 低空间期间只记一次告警；磁盘事件仍按原周期派发。仅主线程访问。 */
    private boolean diskLowReported = false;
    private String pendingNetwork = null;
    /** 默认网络回调当前跟踪的网络；切网时忽略旧网络迟到的 onLost。 */
    private Network observedDefaultNetwork;
    /** 存储权限状态(前后台切换时检查,变化即广播) */
    private boolean permissionGranted = true;

    private SystemStateMonitor() {
    }

    public static SystemStateMonitor get() {
        return instance;
    }

    /** App 启动时调用一次（在 LogStore.init 之后，自身事件走 LogStore） */
    public static void init(Context context) {
        if (instance == null) {
            synchronized (SystemStateMonitor.class) {
                if (instance == null) {
                    instance = new SystemStateMonitor();
                    instance.start(context.getApplicationContext());
                }
            }
        }
    }
    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    /** 订阅（可指定要接收的事件类型；不传或传空 = 接收全部） */
    public void register(Listener l, String... types) {
        if (l == null) return;
        if (types == null || types.length == 0) {
            if (!allListeners.contains(l)) allListeners.add(l);
            return;
        }
        synchronized (listeners) {
            for (String t : types) {
                // 去重:同一 listener 重复 register 会收两次事件(调用方各自兜着很脆)
                List<Listener> list = listeners.computeIfAbsent(t, k -> new CopyOnWriteArrayList<>());
                if (!list.contains(l)) list.add(l);
            }
        }
    }

    public void unregister(Listener l) {
        allListeners.remove(l);
        synchronized (listeners) {
            for (List<Listener> list : listeners.values()) {
                list.remove(l);
            }
        }
    }

    /** 当前状态快照（页面打开时一次取全量，免轮询） */
    public SystemState getCurrentState() {
        return state;
    }

    /** 当前电池百分比(0-100;-1 未知)；UI 打开播放页时初始化电池图标用 */
    public int getBatteryPercent() {
        return state.batteryPercent;
    }

    /** 当前网络是否为移动网络（蜂窝）——需先 init 注入 context */
    public boolean isMobileNetwork() {
        try {
            if (appContext == null) return false;
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Throwable th) {
            return false;
        }
    }

    /** 当前网络是否为 WiFi——需先 init 注入 context */
    public boolean isWifiNetwork() {
        try {
            if (appContext == null) return false;
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Throwable th) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 启动：注册各 Source
    // ------------------------------------------------------------------

    private void start(Context context) {
        appContext = context.getApplicationContext();
        log = LogStore.get().register(Category.SYSTEM, SystemSubType.class);
        state.appForeground = true;
        state.screenOn = true;

        registerNetworkSource(appContext);
        registerForegroundSource(appContext);
        registerOrientationSource(appContext);
        registerScreenSource(appContext);
        registerBatterySource(appContext);
        registerTimeSource(appContext);
        startDiskTimer();
    }

    // ── 网络 ──

    private void registerNetworkSource(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (android.os.Build.VERSION.SDK_INT >= 24) {
                        // 能力信息由紧接着的 onCapabilitiesChanged 给出，别在回调里同步查询。
                        observedDefaultNetwork = network;
                        if (android.os.Build.VERSION.SDK_INT < 26) mainHandler.post(SystemStateMonitor.this::updateNetwork);
                    } else {
                        mainHandler.post(SystemStateMonitor.this::updateNetwork);
                    }
                }

                @Override
                public void onLost(Network network) {
                    if (android.os.Build.VERSION.SDK_INT >= 24) {
                        if (!network.equals(observedDefaultNetwork)) return;
                        observedDefaultNetwork = null;
                        updateNetwork(VAL_NONE);
                    } else {
                        mainHandler.post(SystemStateMonitor.this::updateNetwork);
                    }
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                    if (android.os.Build.VERSION.SDK_INT >= 24) {
                        if (network.equals(observedDefaultNetwork)) updateNetwork(transportOf(networkCapabilities));
                    } else {
                        mainHandler.post(SystemStateMonitor.this::updateNetwork);
                    }
                }
            };
            // 先排入初始快照，后续回调的状态更新才能按顺序覆盖它。
            updateNetwork();
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                cm.registerDefaultNetworkCallback(cb);
            } else {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                cm.registerNetworkCallback(request, cb);
            }
        } catch (Throwable th) {
            Log.e("SystemState", "网络监听注册失败", th);
        }
    }

    private void updateNetwork() {
        try {
            updateNetwork(currentTransport());
        } catch (Throwable th) {
            Log.e("SystemState", "网络状态读取失败", th);
        }
    }

    private void updateNetwork(String value) {
        mainHandler.post(() -> {
            if (value.equals(state.network)) return; // 无变化
            state.network = value;
            mainHandler.removeCallbacks(networkDebounce);
            pendingNetwork = value;
            mainHandler.postDelayed(networkDebounce, NETWORK_DEBOUNCE_MS);
        });
    }

    /**
     * 当前网络传输方式（"有没有可用网络"的事实源，不是"哪种传输"）。
     * <p>
     * WiFi/蜂窝照旧细分；VPN / 以太网 / 蓝牙共享等其它默认网络带 INTERNET 能力时算
     * {@link #VAL_CONNECTED}。非默认网络不能承载普通请求，不参与有网判定。
     */
    private String currentTransport() {
        ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Network active = cm.getActiveNetwork();
            if (active == null) return VAL_NONE;
            NetworkCapabilities nc = cm.getNetworkCapabilities(active);
            return transportOf(nc);
        }
        return hasUsableNetwork() ? VAL_CONNECTED : VAL_NONE;
    }

    private static String transportOf(NetworkCapabilities caps) {
        if (caps == null) return VAL_CONNECTED; // 切网瞬间快照未知，等待能力变化回调
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return VAL_NONE;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return VAL_WIFI;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return VAL_CELLULAR;
        return VAL_CONNECTED;
    }

    /**
     * App 默认网络是否具备 {@code NET_CAPABILITY_INTERNET}（与网络层快速失败守卫同一口径）。
     * <p>
     * 不要求"已验证可联网"：受限网络/切换瞬间仍可能请求成功，宁可漏判离线也不误杀。
     * 读不到（未 init / 权限 / 系统差异）一律返回 true —— 判定只是优化，不能因为自己读不到就把用户判成离线。
     */
    public static boolean hasUsableNetwork() {
        SystemStateMonitor m = instance;
        if (m == null) return true;
        Context ctx = m.appContext;
        if (ctx == null) return true;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps == null || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable th) {
            return true;
        }
    }

    /**
     * 当前是否没网 —— 页面侧的统一判定（别再各写一份：项目里曾有三份 {@code isOffline()}，
     * 其中两份的 catch 语义还相反）。未 init / 读不到一律按"有网"处理：误判离线的代价更大
     * （内容被藏起来、且没有"恢复"事件来救）。
     */
    public static boolean isOfflineNow() {
        return !hasUsableNetwork();
    }

    /** 订阅（未 init 时静默跳过，免调用方裸链式 NPE） */
    public static void registerSafe(Listener l, String... types) {
        SystemStateMonitor m = instance;
        if (m != null) m.register(l, types);
    }

    /** 退订（未 init 时静默跳过） */
    public static void unregisterSafe(Listener l) {
        SystemStateMonitor m = instance;
        if (m != null) m.unregister(l);
    }

    private final Runnable networkDebounce = () -> {
        if (pendingNetwork == null) return;
        String value = pendingNetwork;
        pendingNetwork = null;
        log.info(SystemSubType.NETWORK, "网络状态: " + value, null);
        emit(TYPE_NETWORK, value);
    };

    // ── 前后台（ActivityLifecycleCallbacks 统计）──

    private void registerForegroundSource(Context context) {
        try {
            Application app = (Application) context.getApplicationContext();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityStarted(Activity activity) {
                    boolean wasBackground = startedActivityCount == 0;
                    startedActivityCount++;
                    if (wasBackground) {
                        state.appForeground = true;
                        emit(TYPE_FOREGROUND, VAL_FOREGROUND);
                        checkStoragePermission();
                    }
                }

                @Override
                public void onActivityStopped(Activity activity) {
                    startedActivityCount = Math.max(0, startedActivityCount - 1);
                    if (startedActivityCount == 0) {
                        state.appForeground = false;
                        emit(TYPE_FOREGROUND, VAL_BACKGROUND);
                    }
                }

                @Override public void onActivityCreated(Activity a, android.os.Bundle s) { }
                @Override public void onActivityResumed(Activity a) { }
                @Override public void onActivityPaused(Activity a) { }
                @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle s) { }
                @Override public void onActivityDestroyed(Activity a) { }
            });
        } catch (Throwable th) {
            Log.e("SystemState", "前后台监听注册失败", th);
        }
    }

    /** 存储权限变化检测(Android 10+ MANAGE_EXTERNAL_STORAGE;权限撤销时业务模块据此暂停任务) */
    private void checkStoragePermission() {
        boolean granted = android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager();
        if (granted == permissionGranted) return;
        permissionGranted = granted;
        log.info(SystemSubType.PERMISSION, granted ? "存储权限已授予" : "存储权限被撤销", null);
        emit(TYPE_PERMISSION, granted ? VAL_PERMISSION_GRANTED : VAL_PERMISSION_REVOKED);
    }

    // ── 横竖屏（DisplayManager）──

    private void registerOrientationSource(Context context) {
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return;
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayChanged(int displayId) {
                    updateOrientation();
                }

                @Override public void onDisplayAdded(int displayId) { }
                @Override public void onDisplayRemoved(int displayId) { }
            }, mainHandler);
            updateOrientation();
        } catch (Throwable th) {
            Log.e("SystemState", "横竖屏监听注册失败", th);
        }
    }

    private void updateOrientation() {
        try {
            DisplayManager dm = appContext.getSystemService(DisplayManager.class);
            if (dm == null) return;
            Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return;
            int rot = display.getRotation();
            String value = (rot == android.view.Surface.ROTATION_0 || rot == android.view.Surface.ROTATION_180)
                    ? VAL_PORTRAIT : VAL_LANDSCAPE;
            if (value.equals(state.orientation)) return;
            state.orientation = value;
            emit(TYPE_ORIENTATION, value);
        } catch (Throwable th) {
            Log.e("SystemState", "横竖屏状态读取失败", th);
        }
    }

    // ── 锁屏 / 亮屏 ──

    private void registerScreenSource(Context context) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent == null ? "" : intent.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        state.screenOn = true;
                        emit(TYPE_SCREEN, VAL_ON);
                    } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        state.screenOn = false;
                        emit(TYPE_SCREEN, VAL_OFF);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            context.registerReceiver(receiver, filter);
        } catch (Throwable th) {
            Log.e("SystemState", "锁屏监听注册失败", th);
        }
    }

    // ── 电量 / 充电 ──

    private void registerBatterySource(Context context) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    updateBattery(intent);
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            context.registerReceiver(receiver, filter);
            updateBattery(null);
        } catch (Throwable th) {
            Log.e("SystemState", "电量监听注册失败", th);
        }
    }

    private void updateBattery(Intent intent) {
        try {
            Intent battery = intent;
            if (battery == null || !Intent.ACTION_BATTERY_CHANGED.equals(battery.getAction())) {
                Intent sticky = appContext.registerReceiver(null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (sticky != null) battery = sticky;
            }
            if (battery == null) return;
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean low = scale > 0 && ((float) level / scale) < LOW_BATTERY_RATIO;
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            // 百分比变化事件:供 UI 电池图标(替代 EventBus 电量广播)
            if (scale > 0 && level >= 0) {
                int pct = (int) Math.round(level * 100f / scale);
                if (pct != state.batteryPercent) {
                    state.batteryPercent = pct;
                    emit(TYPE_BATTERY_LEVEL, String.valueOf(pct));
                }
            }
            if (low != state.batteryLow) {
                state.batteryLow = low;
                log.warn(SystemSubType.BATTERY, low ? "低电量: " + level + "%" : "电量恢复正常: " + level + "%", null);
                emit(TYPE_BATTERY, low ? VAL_LOW : VAL_NORMAL);
            }
            if (charging != state.charging) {
                state.charging = charging;
                emit(TYPE_BATTERY, charging ? VAL_CHARGING : VAL_NORMAL);
            }
        } catch (Throwable th) {
            Log.e("SystemState", "电量状态读取失败", th);
        }
    }

    // ── 时间 / 时区 ──

    private void registerTimeSource(Context context) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    emit(TYPE_TIME, VAL_ON);
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            context.registerReceiver(receiver, filter);
        } catch (Throwable th) {
            Log.e("SystemState", "时间监听注册失败", th);
        }
    }

    // ── 磁盘（定时轮询）──

    private void startDiskTimer() {
        try {
            diskTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tvbox-disk");
                t.setDaemon(true);
                return t;
            });
            diskTimer.scheduleWithFixedDelay(this::checkDisk, 30, DISK_POLL_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable th) {
            Log.e("SystemState", "磁盘轮询启动失败", th);
        }
    }

    private void checkDisk() {
        try {
            // 测量统一走中控层 StorageSpace(全应用一处 new StatFs);轮询间隔远大于它的缓存 TTL,
            // 所以这里每次拿到的都是新采样,不需要额外强制刷新
            final long free = StorageSpace.freeBytes(android.os.Environment.getDataDirectory());
            // 事件统一在主线程派发(本类对外的约定):原来在 tvbox-disk 线程直接 emit,
            // 与注释/其它事件源不一致,监听方若碰 UI 就是隐雷
            mainHandler.post(() -> {
                state.freeDiskBytes = free;
                if (free >= 0 && free < MIN_FREE_DISK) {
                    if (!diskLowReported) {
                        diskLowReported = true;
                        log.warn(SystemSubType.DISK, "磁盘可用空间不足: " + (free / 1024 / 1024) + "MB", null);
                    }
                    emit(TYPE_DISK, "LOW:" + free);
                } else if (free >= MIN_FREE_DISK) {
                    diskLowReported = false;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 派发
    // ------------------------------------------------------------------

    private void emit(String type, String value) {
        SystemEvent e = new SystemEvent(type, value);
        for (Listener l : allListeners) {
            try {
                l.onChanged(e);
            } catch (Throwable ignored) {
            }
        }
        List<Listener> list = listeners.get(type);
        if (list != null) {
            for (Listener l : list) {
                try {
                    l.onChanged(e);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}

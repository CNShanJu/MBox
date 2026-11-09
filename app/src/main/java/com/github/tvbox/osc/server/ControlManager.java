package com.github.tvbox.osc.server;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.service.LanServerService;
import com.github.tvbox.osc.util.HeavyTaskUtil;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * @author pj567
 * @date :2021/1/4
 * @description:
 */
public class ControlManager {
    private static final int DEFAULT_PORT = 9978;
    private static final int PORT_LIMIT = 9999;
    private static final int STARTUP_PORT_ATTEMPTS = 5;
    private static final long STARTUP_PORT_RETRY_MS = 200L;
    private static final long HOME_RELEASE_GRACE_MS = 1000L;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile ControlManager instance;
    private volatile RemoteServer mServer = null;
    public static Context mContext;

    /** 局域网服务状态:未开启 */
    public static final int LAN_OFF = 0;
    /** 局域网服务状态:开关已开,但当前运行的服务实例仍是"仅本机"绑定 —— 需要重启应用才生效 */
    public static final int LAN_PENDING_RESTART = 1;
    /** 局域网服务状态:已开启且当前实例已绑定所有网卡,局域网设备可访问 */
    public static final int LAN_ACTIVE = 2;
    /**
     * 局域网服务状态:开关已关,但当前实例仍绑着所有网卡(例如配置被外部改动或关闭流程异常)。
     * 正常的设置页关闭操作会立即停服务并重建本机回环;此状态是残留暴露面的诊断兜底。
     */
    public static final int LAN_PENDING_CLOSE = 3;

    /**
     * 当前运行的 HTTP 服务实例是否按"局域网"绑定(构造时传 null hostname = 所有网卡)。
     * <p>为什么单独记而不是现读开关:开关是**重启生效**的,"开关=开"只代表用户意愿,
     * 不代表这个进程里的服务真的对外可达 —— 设置页要如实区分这两种情形(见 {@link #lanState()})。
     */
    private volatile boolean lanBound = false;
    /**
     * 默认端口被占的诊断快照:仅当本进程的服务不是在 {@link #DEFAULT_PORT} 上时才有值。
     * <p>
     * 为什么单独记:第三方源(jar/js)里大量写死 {@code http://127.0.0.1:9978/proxy?...},
     * 一旦本进程回退到 9979,这些源取播放地址就会打到别的进程/无人应答 → 超时 → 表现为
     * "网盘未返回播放地址""嗅探错误",而界面上看不出是端口问题。
     */
    private volatile int portFallbackFrom = -1;
    private volatile int portFallbackTo = -1;
    /** 占用者应用名/包名(按需识别,见 {@link #warmPortOccupantAsync()}) */
    private volatile String portOccupantLabel = "";
    private volatile String portOccupantPackage = "";
    private volatile boolean portOccupantProbing = false;
    private int homeHostCount;
    private long homeHostEpoch;

    private ControlManager() {

    }

    public static ControlManager get() {
        if (instance == null) {
            synchronized (ControlManager.class) {
                if (instance == null) {
                    instance = new ControlManager();
                }
            }
        }
        return instance;
    }

    public static void init(Context context) {
        mContext = context;
    }

    /** Activity 重建期间保留本机监听；最后一个主页销毁后再确认服务是否仍被占用。 */
    public synchronized void acquireHomeHost() {
        homeHostCount++;
        homeHostEpoch++;
    }

    public synchronized void releaseHomeHost() {
        if (homeHostCount == 0) return;
        homeHostCount--;
        long releasedEpoch = ++homeHostEpoch;
        if (homeHostCount != 0) return;
        MAIN_HANDLER.postDelayed(() -> HeavyTaskUtil.executeBigTask(() -> {
            synchronized (ControlManager.this) {
                if (homeHostCount == 0 && homeHostEpoch == releasedEpoch && !lanBound) {
                    stopServer();
                }
            }
        }), HOME_RELEASE_GRACE_MS);
    }

    /**
     * 服务基址。服务未起(或已停)时不再抛 NPE:给一个端口正确的默认基址
     * (App.onCreate 注入 ApiConfig.lanBase 时服务通常还没起,原来只能靠调用方 try/catch 兜)
     */
    public String getAddress(boolean local) {
        RemoteServer s = mServer;
        if (s != null && s.isStarting()) return local ? s.getLoadAddress() : s.getServerAddress();
        String host = (local || mContext == null) ? "127.0.0.1" : RemoteServer.getLocalIPAddress(mContext);
        return "http://" + host + ":" + RemoteServer.serverPort + "/";
    }

    /**
     * 局域网服务当前状态({@link #LAN_OFF} / {@link #LAN_PENDING_RESTART} / {@link #LAN_ACTIVE} /
     * {@link #LAN_PENDING_CLOSE}):按"开关 × 当前实例的真实绑定"如实回答,设置页据此说明
     * "开了但还要重启 / 已经能访问了 / 异常残留的对外监听"。
     */
    public int lanState() {
        boolean enabled = SystemConfig.isLanServerEnabled();
        RemoteServer s = mServer;
        boolean running = s != null && s.isStarting();
        if (enabled) return (running && lanBound) ? LAN_ACTIVE : LAN_PENDING_RESTART;
        // 关闭方向:实例还在且仍绑着所有网卡 → 端口此刻其实还开着,必须如实说明
        return (running && lanBound) ? LAN_PENDING_CLOSE : LAN_OFF;
    }

    /**
     * 局域网访问地址列表(形如 {@code http://192.168.1.23:9978/})。
     * <p>
     * 开关没开、或开了但还没重启时**也照常给**:设置页要告诉用户"开起来重启后从哪个地址进来",
     * 看不到地址正是"开了也不知道怎么访问"的根源。取不到内网地址(手机没连 Wi‑Fi)时返回空表,
     * 由界面明说"没取到局域网 IP";退一步把 Wi‑Fi 接口报的地址也补上(少见的非 RFC1918 内网网段)。
     */
    public java.util.List<String> getLanAccessUrls() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String ip : RemoteServer.getLanIpv4Addresses()) {
            String url = com.github.tvbox.osc.util.LanAddressRules.url(ip, RemoteServer.serverPort);
            if (!url.isEmpty()) out.add(url);
        }
        if (out.isEmpty() && mContext != null) {
            try {
                String ip = RemoteServer.getLocalIPAddress(mContext);
                String url = com.github.tvbox.osc.util.LanAddressRules.url(ip, RemoteServer.serverPort);
                if (!url.isEmpty()) out.add(url);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 当前运行的 HTTP 服务实例是否真的绑定了局域网网卡(而非仅 127.0.0.1) */
    public boolean isLanBound() {
        RemoteServer s = mServer;
        return s != null && s.isStarting() && lanBound;
    }

    // ------------------------------------------------------------------
    // 默认端口(9978)被占:诊断 / 提示 / 自动迁回
    // ------------------------------------------------------------------

    /** 同机兄弟包(正式版/调试版);与 AndroidManifest 的 <queries> 保持一致 */
    private static final String[] SIBLING_PACKAGES = {
            "com.github.tvbox.osc.mbox",
            "com.github.tvbox.osc.mbox.debug"
    };

    /** 当前本机服务是否因 {@link #DEFAULT_PORT} 被占而落在了其他端口上 */
    public boolean isOnFallbackPort() {
        return portFallbackFrom > 0;
    }

    /** 被占用、导致回退的默认端口(不在回退状态时为 -1) */
    public int portFallbackFrom() {
        return portFallbackFrom;
    }

    /** 端口回退的一句话说明,只进日志(用户可见的提示在播放失败时给,见 PlayFragment) */
    public String portFallbackNote() {
        if (!isOnFallbackPort()) return "";
        String who = portOccupantLabel.isEmpty() ? "" : "(" + portOccupantLabel + ")";
        return portFallbackFrom + " 被占用" + who + "，已改用 " + portFallbackTo;
    }

    /** 已识别到的占用者应用名(还没识别出来时为空串) */
    public String portOccupantLabel() {
        return portOccupantLabel;
    }

    /** 已识别到的占用者包名(认不出来时为空,只能把用户送到系统应用列表) */
    public String portOccupantPackage() {
        return portOccupantPackage;
    }

    /**
     * 按需识别"谁占着默认端口"。
     * <p>
     * 只在真正用得上这个端口的时候调用(起播前迁不回去、或播放已经失败),<b>开屏不做任何探测</b> ——
     * 没被用到的端口没必要查,查了也没地方提示。识别是 1~2 次本机 HTTP 探测(通常 ~50ms),
     * 结果缓存在字段里,播放失败的提示直接读。
     */
    public void warmPortOccupantAsync() {
        if (!isOnFallbackPort()) return;
        if (!portOccupantLabel.isEmpty() || portOccupantProbing) return;
        final int port = portFallbackFrom;
        synchronized (this) {
            if (!portOccupantLabel.isEmpty() || portOccupantProbing) return;
            portOccupantProbing = true;
        }
        com.github.tvbox.osc.util.HeavyTaskUtil.executeNewTask(() -> {
            try {
                identifyPortOccupant(port);
            } finally {
                portOccupantProbing = false;
            }
        });
    }

    /**
     * 默认端口空出来后迁回默认端口。
     * <p>
     * 只在"服务在跑 + 已回退 + 没有对外监听"时尝试:第三方源里写死的 {@code 127.0.0.1:9978}
     * 只认这个端口,停在别的端口等于这些源一直取不到地址;而迁端口会断掉正在走的 127.0.0.1
     * 连接,所以对外监听(投屏/局域网共享)期间不动 —— 调用方保证只在起播前这类没有活跃播放的时机调用。
     */
    public synchronized boolean tryReclaimPreferredPort() {
        if (!isOnFallbackPort()) return false;
        RemoteServer running = mServer;
        if (running == null || !running.isStarting()) return false;
        if (lanBound) return false;
        if (!isPreferredPortFree()) return false;
        Log.i("TVBox-Server", "默认端口 " + DEFAULT_PORT + " 已空出，本机服务迁回默认端口");
        boolean ok = startServerInternal(true) && RemoteServer.serverPort == DEFAULT_PORT;
        if (ok) clearPortFallback();
        return ok;
    }

    /**
     * 记下"默认端口被占、服务落在 tryPort 上"。
     * <p>
     * 只留痕(Logcat + 日志页),<b>不在开屏弹任何提示</b>:端口是给源取流用的,没在播放时占着也无所谓,
     * 提示要等播放失败、真的用得上它的时候再给(见 PlayFragment 的失败提示)。
     */
    private void recordPortFallback(int from, int to, IOException cause) {
        portFallbackFrom = from;
        portFallbackTo = to;
        portOccupantLabel = "";
        portOccupantPackage = "";
        String detail = from + " 被占用，本机服务回退到 " + to
                + "；源里写死 127.0.0.1:" + from + " 的代理地址会取不到地址"
                + (cause == null || cause.getMessage() == null ? "" : "，原因=" + cause.getMessage());
        Log.w("TVBox-Server", detail);
        LogStore.fail(Category.SYSTEM, detail);
    }

    private void clearPortFallback() {
        portFallbackFrom = -1;
        portFallbackTo = -1;
        portOccupantLabel = "";
        portOccupantPackage = "";
    }

    /**
     * 默认端口此刻能不能被本进程绑上(只做一次瞬时探测,不长期占着)。
     * <p>
     * 必须开 SO_REUSEADDR:占用方被强停/崩溃后,它的连接会在内核里留下 TIME_WAIT,
     * 此时已无监听、但不开复用的 bind 仍会 EADDRINUSE —— 探测就会误判"还占着",
     * 于是用户按提示关掉另一个应用、回来仍然不迁回端口(2026-11-09 实测踩到)。
     * 真正的服务端(NanoHTTPD)本来就是 reuse=true,这里要与它一致。
     */
    private static boolean isPreferredPortFree() {
        java.net.ServerSocket probe = null;
        try {
            probe = new java.net.ServerSocket();
            probe.setReuseAddress(true);
            probe.bind(new java.net.InetSocketAddress("127.0.0.1", DEFAULT_PORT), 1);
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 识别端口占用者,依次尝试:
     * ① procfs(能读 /proc/net/tcp* 的机型可以直接点名包名);
     * ② 新版本 MBox 的 {@code /api/port-id} 自报包名+应用名;
     * ③ 认局域网控制台首页标题(旧版本没有 ② 时靠它认出"是 MBox")。
     * 全程只用于提示,任何一步失败都只是识别不出来,不影响服务。
     */
    private void identifyPortOccupant(int port) {
        String source;
        String byUid = describePortOwnerByUid(port);
        if (!byUid.isEmpty()) {
            portOccupantLabel = byUid;
            source = "procfs";
        } else {
            String[] hello = probePortIdentity(port);
            if (hello != null) {
                portOccupantPackage = hello[0];
                portOccupantLabel = hello[1];
                source = "自报";
            } else {
                String home = probePortHome(port);
                if (!home.isEmpty()) {
                    portOccupantLabel = home;
                    source = "控制台首页";
                    // 首页只认得出"是 MBox";具体是哪个包,用同机兄弟包补上,
                    // 提示里的"去关闭"才能直达那个应用的信息页。
                    String[] siblingForPkg = findSiblingApp();
                    if (siblingForPkg != null) portOccupantPackage = siblingForPkg[0];
                } else {
                    // 前面三条都问不出来:最常见的是占用者已被系统冻结在后台 —— socket 还挂在内核里
                    // (LISTEN + 三次握手都成功),但进程不运行、不应答,procfs 又读不到 uid。
                    // 这时按"同机另一个 MBox"给出能操作的名字(见 AndroidManifest 的 <queries>)。
                    String[] sibling = findSiblingApp();
                    if (sibling != null) {
                        portOccupantPackage = sibling[0];
                        portOccupantLabel = sibling[1];
                        source = "同机兄弟包(可能已冻结)";
                    } else {
                        source = "未识别";
                    }
                }
            }
        }
        Log.i("TVBox-Server", "端口占用者识别: port=" + port + "，方式=" + source
                + "，应用=" + portOccupantLabel + "，包名=" + portOccupantPackage);
        LogStore.log(Category.SYSTEM, "本机服务端口占用者(" + source + "): "
                + (portOccupantLabel.isEmpty() ? "<未识别>" : portOccupantLabel + " " + portOccupantPackage));
    }

    /**
     * 同机另一个 MBox(正式版/调试版)。两边的本机服务都默认绑 9978,谁先起谁占;
     * 被抢的那个往往已冻结在后台,既不应答也读不到 uid,只能按"装没装对方"来给名字。
     */
    private static String[] findSiblingApp() {
        if (mContext == null) return null;
        String self = mContext.getPackageName();
        android.content.pm.PackageManager pm = mContext.getPackageManager();
        for (String pkg : SIBLING_PACKAGES) {
            if (pkg.equals(self)) continue; // 自己不可能占自己的端口
            try {
                android.content.pm.PackageInfo info = pm.getPackageInfo(pkg, 0);
                String label = pkg;
                try {
                    if (info.applicationInfo != null)
                        label = String.valueOf(info.applicationInfo.loadLabel(pm));
                } catch (Throwable ignored) {
                }
                return new String[]{pkg, label};
            } catch (Throwable ignored) {
                // 没装/不可见(后者由 AndroidManifest 的 <queries> 兜住):看下一个
            }
        }
        return null;
    }

    /** 问一句"你是谁":新版本 MBox 的 /api/port-id 会自报 {包名}|{应用名} */
    private static String[] probePortIdentity(int port) {
        String body = httpGetLocal(port, "/api/port-id");
        if (body == null) return null;
        body = body.trim();
        // 旧版本没有这个路由时会回退到控制台首页(HTML):必须严格校验格式,
        // 否则会把首页里 JS 的 "|" 当成身份分隔符,识别出垃圾身份。
        if (body.isEmpty() || body.length() > 200
                || body.indexOf('<') >= 0 || body.indexOf('\n') >= 0) return null;
        int sep = body.indexOf('|');
        if (sep <= 0) return null;
        String pkg = body.substring(0, sep).trim();
        if (!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) return null;
        String label = body.substring(sep + 1).trim();
        return new String[]{pkg, label.isEmpty() ? pkg : label};
    }

    /** 认控制台首页:本仓局域网控制台标题是 "MBox · 局域网",旧版本没有 /api/port-id 时靠它 */
    private static String probePortHome(int port) {
        String body = httpGetLocal(port, "/");
        return body != null && body.contains("MBox") ? "MBox" : "";
    }

    /** 对 127.0.0.1 发一次极短超时的 GET(只用于端口占用诊断;失败一律返回 null) */
    private static String httpGetLocal(int port, String path) {
        java.net.HttpURLConnection conn = null;
        java.io.InputStream in = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:" + port + path).openConnection();
            conn.setConnectTimeout(400);
            conn.setReadTimeout(400);
            conn.setUseCaches(false);
            conn.setRequestProperty("Connection", "close");
            if (conn.getResponseCode() != 200) return null;
            in = conn.getInputStream();
            if (in == null) return null;
            byte[] buf = new byte[2048];
            int read = in.read(buf);
            return read <= 0 ? null
                    : new String(buf, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (conn != null) conn.disconnect();
        }
    }

    /** 读 procfs 的 LISTEN 项拿 uid,再用 PackageManager 反查包名(需要能读 /proc/net/tcp*) */
    private static String describePortOwnerByUid(int port) {
        int uid = -1;
        for (String table : new String[]{"/proc/net/tcp6", "/proc/net/tcp"}) {
            try (java.io.BufferedReader reader =
                         new java.io.BufferedReader(new java.io.FileReader(table))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    // sl local_address rem_address st tx_queue:rx_queue tr:tm->when retrnsmt uid ...
                    if (parts.length < 8 || !"0A".equalsIgnoreCase(parts[3])) continue;
                    String local = parts[1];
                    int colon = local.lastIndexOf(':');
                    if (colon <= 0) continue;
                    int listenPort;
                    try {
                        listenPort = Integer.parseInt(local.substring(colon + 1), 16);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (listenPort != port) continue;
                    try {
                        uid = Integer.parseInt(parts[7]);
                    } catch (Throwable ignored) {
                    }
                    break;
                }
            } catch (Throwable ignored) {
                // Android 10+ 应用侧普遍读不到 procfs:交给自报/首页识别
            }
            if (uid >= 0) break;
        }
        if (uid < 0) return "";
        if (uid == android.os.Process.myUid()) return "本进程的遗留监听";
        StringBuilder packages = new StringBuilder();
        try {
            if (mContext != null) {
                String[] names = mContext.getPackageManager().getPackagesForUid(uid);
                if (names != null) {
                    for (String name : names) {
                        if (name == null || name.isEmpty()) continue;
                        if (packages.length() > 0) packages.append(", ");
                        packages.append(name);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return packages.length() == 0 ? ("uid " + uid) : packages.toString();
    }

    /** 当前 HTTP 服务实例(可能为 null);局域网分享桥复用同一服务端 */
    public RemoteServer getServer() {
        return mServer;
    }

    /** 只在手机端展示的配对码；服务重建后仍沿用本次开启时生成的值。 */
    public String getPairingCode() {
        RemoteServer server = mServer;
        return server == null || !server.isStarting() || !lanBound ? "" : server.getPairingCode();
    }

    /** 用户主动更换配对码，旧设备会话同时撤销。 */
    public String rotatePairingCode() {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound
                ? server.rotatePairingCode() : "";
    }

    /** 从手机播放器向指定且仍在线的电脑网页推送当前视频。 */
    public boolean pushToBrowser(String deviceId, String title, String url) {
        return pushToBrowser(deviceId, title, url, null);
    }

    public boolean pushToBrowser(String deviceId, String title, String url,
                                 java.util.Map<String, String> headers) {
        return pushToBrowser(deviceId, title, url, headers, null);
    }

    /** 净化播放地址仍用于推送；原始来源仅供服务端中转清单子资源。 */
    public boolean pushToBrowser(String deviceId, String title, String url,
                                 java.util.Map<String, String> headers, String headerOrigin) {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound
                && server.publishBrowserPlayback(deviceId, title, url, headers, headerOrigin);
    }

    public String pushFailureMessage(String deviceId, String url) {
        if (!isLanServing()) return "局域网服务未运行，请重新开启后重试";
        if (LanCastUrlRules.browserUrl(url, RemoteServer.serverPort) == null) {
            return "当前播放地址无法供电脑访问，请换一个播放源";
        }
        for (RemoteServer.LanDevice device : connectedDevices()) {
            if (device.id.equals(deviceId) && "browser".equals(device.kind)) {
                return "推送未完成，请稍后重试";
            }
        }
        return "目标浏览器已离线，请在电脑上重新打开局域网页面";
    }

    public java.util.List<RemoteServer.LanDevice> connectedDevices() {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound
                ? server.connectedDevices() : java.util.Collections.emptyList();
    }

    public java.util.List<RemoteServer.LanDevice> pairedDevices() {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound
                ? server.pairedDevices() : java.util.Collections.emptyList();
    }

    /** 当前实例确实监听局域网；设置开关刚变更而尚未重启时仍按实际绑定状态判断。 */
    public boolean isLanServing() {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound;
    }

    /** 当前本机 HTTP 监听是否已完成端口绑定。 */
    public boolean isLocalServing() {
        RemoteServer server = mServer;
        return server != null && server.isStarting();
    }

    public boolean kickDevice(String id) {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound && server.kickDevice(id);
    }

    public void setEpisodeCast(String owner, String deviceId, java.util.List<String> episodes,
                               int selectedIndex, RemoteServer.NextEpisodeHandler handler) {
        RemoteServer server = mServer;
        if (server != null && server.isStarting() && lanBound)
            server.setEpisodeCast(owner, deviceId, episodes, selectedIndex, handler);
    }

    /** 保存当前浏览器投屏的详情定位，供局域网入口返回同一轮播放。 */
    public RemoteServer.BrowserCastDetail setBrowserCastDetail(String owner, String sourceKey,
                                                                String vodId, String vodName,
                                                                String playFlag, int selectedIndex,
                                                                boolean reverseSort,
                                                                String episodeName,
                                                                String episodeUrl) {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound
                ? server.setBrowserCastDetail(owner, sourceKey, vodId, vodName,
                playFlag, selectedIndex, reverseSort, episodeName, episodeUrl) : null;
    }

    /** 详情页重建后仅重绑现有选集回调，不发布新媒体。 */
    public RemoteServer.BrowserCastDetail attachBrowserCastDetail(String owner, String sessionId,
                                                                   java.util.List<String> episodes,
                                                                   RemoteServer.NextEpisodeHandler handler) {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound
                ? server.attachBrowserCastDetail(owner, sessionId, episodes, handler) : null;
    }

    public boolean updateEpisodeCast(String owner, String title, String url, int selectedIndex,
                                     java.util.List<String> episodes,
                                     java.util.Map<String, String> headers) {
        return updateEpisodeCast(owner, title, url, selectedIndex, episodes, headers, null);
    }

    public boolean updateEpisodeCast(String owner, String title, String url, int selectedIndex,
                                     java.util.List<String> episodes,
                                     java.util.Map<String, String> headers, String headerOrigin) {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound
                && server.updateEpisodeCast(owner, title, url, selectedIndex, episodes, headers, headerOrigin);
    }

    public void markEpisodeAdvancing(String owner) {
        RemoteServer server = mServer;
        if (server != null) server.markEpisodeAdvancing(owner);
    }

    public void clearEpisodeCast() {
        RemoteServer server = mServer;
        if (server != null) server.clearEpisodeCast();
    }

    public void clearEpisodeCast(String owner) {
        RemoteServer server = mServer;
        if (server != null) server.clearEpisodeCast(owner);
    }

    /** 当前手机页面所投浏览器的实际播放状态；null 表示已断开或 owner 不匹配。 */
    public RemoteServer.BrowserPlaybackState browserPlaybackState(String owner) {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound
                ? server.browserPlaybackState(owner) : null;
    }

    /** 局域网入口读取当前浏览器投屏，不依赖详情页是否仍存在。 */
    public RemoteServer.BrowserPlaybackState activeBrowserPlaybackState() {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound
                ? server.activeBrowserPlaybackState() : null;
    }

    /** 活动投屏对应的详情定位；投屏结束、换设备或会话过期后为 null。 */
    public RemoteServer.BrowserCastDetail activeBrowserCastDetail() {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound
                ? server.activeBrowserCastDetail() : null;
    }

    /** 手机播放控件向当前浏览器投屏轮次发命令。 */
    public boolean controlBrowserPlayback(String owner, String action, long positionMs) {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound
                && server.controlBrowserPlayback(owner, action, positionMs);
    }

    /** 从局域网入口控制当前浏览器投屏。 */
    public boolean controlActiveBrowserPlayback(String action, long positionMs) {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound
                && server.controlActiveBrowserPlayback(action, positionMs);
    }

    /** 手机退出投屏时停止网页并撤销该轮媒体访问。 */
    public boolean stopBrowserPlayback(String owner) {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound
                && server.stopBrowserPlayback(owner);
    }

    /** 从局域网入口结束当前浏览器投屏。 */
    public boolean stopActiveBrowserPlayback() {
        RemoteServer server = mServer;
        return server != null && server.isStarting() && lanBound
                && server.stopActiveBrowserPlayback();
    }

    public synchronized void startServer() {
        startServerInternal(false);
    }

    /**
     * 开屏预取的后台启动入口。备份还原紧接着拉起新进程时，旧进程可能尚未释放 9978；
     * 先有限次等待默认端口，再尝试其他端口，避免固定本机订阅地址落到错误端口。
     */
    public synchronized boolean startServerForStartup() {
        return startServerInternal(Looper.myLooper() != Looper.getMainLooper());
    }

    private boolean startServerInternal(boolean retryPreferred) {
        // 对外监听必须晚于前台通知就绪；预取、Activity 重建等入口只能先开回环。
        boolean lanEnabled = SystemConfig.isLanServerEnabled()
                && LanServerService.isForegroundReady()
                && mContext != null
                && LanServerService.canShowNotification(mContext);
        RemoteServer running = mServer;
        if (running != null) {
            // 已在跑且绑定方式就是当前配置:复用(本方法是幂等的,首页每次 init 都会调用)
            if (running.isStarting() && lanBound == lanEnabled
                    && (!retryPreferred || RemoteServer.serverPort == DEFAULT_PORT)) {
                com.github.tvbox.osc.util.OkGoHelper.setLocalFileReadAccess(
                        RemoteServer.serverPort, running.getLocalReadToken());
                if (RemoteServer.serverPort == DEFAULT_PORT) clearPortFallback();
                return true;
            }
            if (running.isStarting() && retryPreferred
                    && RemoteServer.serverPort != DEFAULT_PORT) {
                Log.i("TVBox-Server", "刷新配置时重新尝试默认端口 " + DEFAULT_PORT);
            }
            // 已停(首页销毁过)或绑定方式变了(开关改动后重启应用):必须停旧实例再重建。
            // 历史实现只在 stopServer 里 stop 而不清引用,于是这里被 "mServer != null" 直接挡掉 ——
            // 同一个进程里重启应用(CLEAR_TASK 重启:备份还原后、开启局域网服务后)会让本机服务
            // 再也起不来(回环订阅/播放/proxy 全失效),是个潜伏已久的坑。
            try {
                running.stop();
            } catch (Throwable error) {
                Log.e("TVBox-Server", "停止旧服务失败", error);
            }
            if (running.isStarting()) {
                // 旧监听可能仍对外开放，保留实例和绑定状态供设置页如实显示。
                Log.e("TVBox-Server", "旧服务仍在监听，暂不重建本机服务");
                return false;
            }
            mServer = null;
            lanBound = false;
            com.github.tvbox.osc.util.OkGoHelper.clearLocalFileReadAccess();
        }
        // 默认仅绑定本机回环:本 App 的订阅/本地播放/代理全部走 127.0.0.1,无需对局域网开放端口。
        // 需要局域网文件共享/远程管理(web 控制台)时,显式开启 HawkConfig.LAN_SERVER_ENABLE 后重启生效。
        final int preferredPort = DEFAULT_PORT;
        int preferredAttempts = retryPreferred ? STARTUP_PORT_ATTEMPTS : 1;
        IOException preferredPortError = null;
        for (int attempt = 0; attempt < preferredAttempts + PORT_LIMIT - DEFAULT_PORT - 1; attempt++) {
            int tryPort = attempt < preferredAttempts
                    ? preferredPort : preferredPort + 1 + attempt - preferredAttempts;
            RemoteServer.serverPort = tryPort;
            RemoteServer candidate = new RemoteServer(lanEnabled ? null : "127.0.0.1", tryPort, mContext);
            candidate.setDataReceiver(new DataReceiver() {
                @Override
                public void onTextReceived(String text) {
                    // 历史遗留:曾广播 SearchReceiver 触发局域网推送搜索,但 SearchReceiver.onReceive
                    // 早已是空实现(SERVER_SEARCH 从无发送方),整条链路已死,删除。
                }

                @Override
                public void onApiReceived(String url) {
                    // 历史遗留:曾以 TYPE_API_URL_CHANGE 广播,全仓零订阅(未接线),删除
                }

                @Override
                public void onPushReceived(String url) {
                    // 历史遗留:曾以 TYPE_PUSH_URL 广播,全仓零订阅(InputRequestProcess 亦标注"暂未实现"),删除
                }
            });
            try {
                candidate.start();
                mServer = candidate;
                com.github.tvbox.osc.util.OkGoHelper.setLocalFileReadAccess(
                        tryPort, candidate.getLocalReadToken());
                lanBound = lanEnabled; // 记下本次实例的实际绑定方式(供 lanState 区分"已开但没重启")
                // server 就绪后注入局域网地址(:spider 模块 ApiConfig 用,替代直接依赖本类)
                try {
                    com.github.tvbox.osc.api.ApiConfig.setLanBase(candidate.getLoadAddress());
                } catch (Throwable ignored) {
                }
                // 端口回退可见性:9978 被占时这里静默 +1 重试,而第三方源里写死的
                // 127.0.0.1:9978 代理地址(do=js/do=m3u8 等)会因此连不上(ECONNREFUSED);
                // 应用侧以前不打任何日志,只能靠第三方 adjustPort 日志猜,这里补上实际端口。
                if (tryPort == preferredPort) {
                    clearPortFallback();
                    Log.i("TVBox-Server", "本机服务已启动: " + candidate.getLoadAddress());
                } else {
                    recordPortFallback(preferredPort, tryPort, preferredPortError);
                }
                if (lanEnabled) LogStore.success(Category.SYSTEM,
                        "局域网服务已启动 port=" + tryPort);
                return true;
            } catch (IOException ex) {
                if (tryPort == preferredPort && preferredPortError == null) preferredPortError = ex;
                try {
                    candidate.stop();
                } catch (Throwable stopError) {
                    Log.e("TVBox-Server", "端口绑定失败后关闭服务实例失败", stopError);
                }
                if (candidate.isStarting()) {
                    // 不能丢掉可能仍在监听的实例，供局域网状态和关闭路径如实处理。
                    mServer = candidate;
                    lanBound = lanEnabled;
                    return false;
                }
                if (attempt + 1 < preferredAttempts) {
                    try {
                        Thread.sleep(STARTUP_PORT_RETRY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        mServer = null;
        lanBound = false;
        RemoteServer.serverPort = DEFAULT_PORT;
        clearPortFallback();
        com.github.tvbox.osc.util.OkGoHelper.clearLocalFileReadAccess();
        Log.w("TVBox-Server", "本机服务启动失败:从 " + preferredPort + " 起连续端口都被占用");
        if (lanEnabled) LogStore.fail(Category.SYSTEM,
                "局域网服务启动失败 reason=port_unavailable");
        return false;
    }

    /**
     * 停止本机 HTTP 服务并<b>清掉实例引用</b>:下一次 {@link #startServer()} 才能按最新开关重建。
     * <p>
     * 清引用这一步不能省:首页销毁会走到这里,而 CLEAR_TASK 式的"重启应用"(备份还原后、开启
     * 局域网服务后)用的是同一个进程 —— 引用留着会让新首页的 startServer 判定"已存在"而直接返回,
     * 服务就一直是停的(订阅/本地播放/proxy 全部失效,直到用户手动杀掉进程)。
     */
    public synchronized void stopServer() {
        RemoteServer s = mServer;
        boolean wasLanBound = lanBound;
        if (s != null && s.isStarting()) {
            try {
                s.stop();
            } catch (Throwable error) {
                Log.e("TVBox-Server", "停止服务失败", error);
            }
            if (s.isStarting()) {
                // 关闭失败时不能把残留的对外监听伪装成“仅本机可访问”。
                return;
            }
        }
        if (mServer == s) {
            mServer = null;
            lanBound = false;
            com.github.tvbox.osc.util.OkGoHelper.clearLocalFileReadAccess();
        }
        if (wasLanBound) LogStore.log(Category.SYSTEM, "局域网服务已停止");
    }

    /** 服务通知消失后串行等待在途绑定，并关闭可能刚刚建成的对外监听。 */
    public synchronized void stopLanWhenNotificationGone() {
        if (!LanServerService.isForegroundReady() && lanBound) stopServer();
    }
}

package com.github.tvbox.osc.cast;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.config.CastReceiverConfig;
import com.github.tvbox.osc.util.HeavyTaskUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A small DLNA control point. It discovers UPnP MediaRenderers and asks a selected TV to
 * fetch a media URL. It does not depend on MBox's paired browser/remote-management server.
 */
public final class DlnaController implements AutoCloseable {
    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final long SEARCH_MS = 4000;
    private static final long ANNOUNCEMENT_MS = 70_000;
    private static final long ANNOUNCEMENT_RETRY_MS = 5000;
    private static final long DESCRIPTION_MS = 8000;
    private static final int MAX_ADVERTS = 24;
    private static final ExecutorService ANNOUNCEMENT_EXECUTOR = new ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingDeque<>(4), task -> {
                Thread thread = new Thread(task, "mbox-dlna-announcements");
                thread.setDaemon(true);
                return thread;
            });
    private static final int MAX_DESCRIPTION_BYTES = 128 * 1024;
    private static final int MAX_SOAP_BYTES = 16 * 1024;
    // Stop and a replacement SetURI/Play must never overtake each other on one renderer.
    private static final Object TRANSPORT_COMMANDS = new Object();
    private static final String M_SEARCH_PREFIX = "M-SEARCH * HTTP/1.1\r\n"
            + "HOST: 239.255.255.250:1900\r\n"
            + "MAN: \"ssdp:discover\"\r\n"
            + "MX: 2\r\nST: ";
    private static final String M_SEARCH_RENDERER = M_SEARCH_PREFIX
            + "urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";
    private static final String M_SEARCH_ALL = M_SEARCH_PREFIX + "ssdp:all\r\n\r\n";

    public static final class Device {
        public final String id;
        public final String name;
        public final String localAddress;
        private final URI control;
        private final String serviceType;
        private final Network network;

        private Device(DlnaRules.Description description, Network network, String localAddress) {
            id = description.id;
            name = description.name;
            this.localAddress = localAddress;
            control = description.control;
            serviceType = description.serviceType;
            this.network = network;
        }
    }

    public interface SearchCallback {
        void onDevices(List<Device> devices);
        void onFinished(String error);
        default void onWaitingForAnnouncements() { }
        default void onAnnouncementWaitFinished() { }
    }

    public interface CastCallback {
        void onResult(boolean success, String message);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object guard = new Object();
    private int searchEpoch;
    private boolean closed;
    private MulticastSocket searchSocket;
    private MulticastSocket notifySocket;
    private WifiManager.MulticastLock multicastLock;
    private HttpURLConnection searchConnection;

    public DlnaController(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    /** Search once; callbacks are delivered on the main thread as devices arrive. */
    public void search(@NonNull SearchCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback == null");
        final int epoch;
        synchronized (guard) {
            if (closed) return;
            searchEpoch++;
            stopSearchLocked();
            epoch = searchEpoch;
        }
        postSearch(epoch, () -> callback.onDevices(Collections.emptyList()));
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> discover(epoch, callback));
    }

    /** Read a user-entered receiver description over the physical LAN without SSDP. */
    public void connectManual(String address, @NonNull SearchCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback == null");
        List<URI> locations = DlnaRules.manualLocations(address);
        if (locations.isEmpty()) {
            main.post(() -> callback.onFinished("请输入同一局域网的 IPv4:端口，例如 192.168.1.10:8181"));
            return;
        }
        final int epoch;
        synchronized (guard) {
            if (closed) return;
            searchEpoch++;
            stopSearchLocked();
            epoch = searchEpoch;
        }
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            Network network = chooseLanNetwork();
            if (network == null) {
                postSearch(epoch, () -> callback.onFinished("请先连接与接收端相同的 Wi-Fi 或有线网络"));
                return;
            }
            int attempted = 0;
            for (URI location : locations) {
                if (!isCurrent(epoch)) return;
                attempted++;
                DlnaRules.Description description = readDescription(epoch, network,
                        new DlnaRules.Advert(location, "", false));
                if (description != null) {
                    Device device = new Device(description, network, localIpv4For(network));
                    CastReceiverConfig.rememberManual(address.trim(), description.id);
                    LogStore.log(Category.PLAYER, "投屏直连: 描述尝试=" + attempted + "，成功=1");
                    postSearch(epoch, () -> callback.onDevices(Collections.singletonList(device)));
                    postSearch(epoch, () -> callback.onFinished(null));
                    return;
                }
            }
            LogStore.log(Category.PLAYER, "投屏直连: 描述尝试=" + attempted + "，成功=0");
            postSearch(epoch, () -> callback.onFinished("未读到 DLNA 接收端，请检查 IP、端口及设备是否在线"));
        });
    }

    /** Send SetAVTransportURI then Play from the beginning. */
    public void cast(Device device, String title, String url, @NonNull CastCallback callback) {
        cast(device, title, url, 0, callback);
    }

    /**
     * Send SetAVTransportURI, optionally seek, then Play. A command already submitted continues
     * after close(). A renderer that does not support Seek can still play from the beginning.
     */
    public void cast(Device device, String title, String url, long positionMs,
                     @NonNull CastCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback == null");
        synchronized (guard) {
            if (closed) return;
        }
        if (device == null || device.control == null) {
            postCast(callback, false, "请先选择电视设备");
            return;
        }
        URI media = DlnaRules.mediaUri(url);
        if (media == null) {
            postCast(callback, false, "当前地址无法由电视访问，请换一个播放源");
            return;
        }
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            // The network transaction owns only immutable Device data. Closing a dialog cancels
            // search and UI delivery, but must not cut off a command already sent to the TV.
            String error;
            try {
                synchronized (TRANSPORT_COMMANDS) {
                    error = sendToRenderer(device, title, media, positionMs);
                }
            } catch (Exception failure) {
                LogStore.fail(Category.PLAYER, "投屏控制: 命令异常="
                        + failure.getClass().getSimpleName());
                error = "连接电视失败，请确认设备仍在线";
            }
            if (error == null) LogStore.success(Category.PLAYER, "投屏控制: 电视已接受地址和播放命令");
            else LogStore.fail(Category.PLAYER, "投屏控制: " + error);
            postCast(callback, error == null,
                    error == null ? "已向 " + device.name + " 发送播放命令" : error);
        });
    }

    /** Explicit cancellation only. The generation check runs inside the same gate as SetURI/Play. */
    static void stopIfCurrent(Device device, BooleanSupplier isCurrent, CastCallback callback) {
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            boolean success = false;
            String message;
            synchronized (TRANSPORT_COMMANDS) {
                if (device == null || !isCurrent.getAsBoolean()) {
                    message = "投屏状态已更新，本次停止已忽略";
                } else {
                    try {
                        int status = soapRequest(device, "Stop", "<InstanceID>0</InstanceID>");
                        success = status >= 200 && status < 300;
                        message = success ? "DLNA 投屏已停止"
                                : "电视未确认停止（HTTP " + status + "），手机媒体代理已关闭";
                    } catch (IOException | RuntimeException error) {
                        message = "电视未确认停止，手机媒体代理已关闭";
                    }
                    if (success) LogStore.success(Category.PLAYER, "DLNA 投屏取消: 电视已接受停止命令");
                    else LogStore.fail(Category.PLAYER, "DLNA 投屏取消: 电视未确认停止命令");
                }
            }
            // The owner cleans up the captured relay in this background callback.
            callback.onResult(success, message);
        });
    }

    @Override public void close() {
        synchronized (guard) {
            if (closed) return;
            closed = true;
            searchEpoch++;
            stopSearchLocked();
        }
    }

    private void discover(int epoch, SearchCallback callback) {
        DlnaRules.AdvertCandidates adverts = new DlnaRules.AdvertCandidates(MAX_ADVERTS);
        String error = null;
        Network network = chooseLanNetwork();
        String localAddress = localIpv4For(network);
        LinkedHashMap<String, Device> devices = new LinkedHashMap<>();
        CastReceiverConfig.Remembered remembered = CastReceiverConfig.lastManual();
        if (network != null && remembered != null) {
            for (URI location : DlnaRules.manualLocations(remembered.address)) {
                if (!isCurrent(epoch)) return;
                DlnaRules.Description description = readDescription(epoch, network,
                        new DlnaRules.Advert(location, "", false));
                if (description == null || !remembered.deviceId.equals(description.id)) continue;
                devices.put(description.id, new Device(description, network, localAddress));
                List<Device> snapshot = Collections.unmodifiableList(new ArrayList<>(devices.values()));
                postSearch(epoch, () -> callback.onDevices(snapshot));
                break;
            }
            LogStore.log(Category.PLAYER, "投屏发现: 已记住设备校验=" + !devices.isEmpty());
        }
        WifiManager.MulticastLock lock = null;
        MulticastSocket socket = null;
        MulticastSocket announcements = null;
        int searchPackets = 0;
        int notifyPackets = 0;
        int selfSearchEchoes = 0;
        int rejectedNotifies = 0;
        int sentPackets = 0;
        String searchStage = "初始化";
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("MBox:DLNA-Search");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            searchStage = "创建搜索套接字";
            socket = new MulticastSocket(0);
            socket.setTimeToLive(2);
            socket.setSoTimeout(125);
            if (network != null) network.bindSocket(socket);
            NetworkInterface lanInterface = lanInterfaceFor(network);
            if (lanInterface != null) {
                try { socket.setNetworkInterface(lanInterface); }
                catch (IOException ignored) { }
            }
            announcements = openNotifySocket(network, lanInterface);
            boolean announcementListening = announcements != null;
            synchronized (guard) {
                if (!isCurrentLocked(epoch)) return;
                searchSocket = socket;
                notifySocket = announcements;
                multicastLock = lock;
            }
            InetAddress group = InetAddress.getByName(SSDP_ADDRESS);
            byte[] specific = M_SEARCH_RENDERER.getBytes(StandardCharsets.US_ASCII);
            byte[] general = M_SEARCH_ALL.getBytes(StandardCharsets.US_ASCII);
            DatagramPacket rendererSearch = new DatagramPacket(specific, specific.length, group, SSDP_PORT);
            DatagramPacket allSearch = new DatagramPacket(general, general.length, group, SSDP_PORT);
            searchStage = "发送搜索";
            socket.send(rendererSearch);
            sentPackets++;
            socket.send(allSearch);
            sentPackets++;
            long begin = System.nanoTime();
            boolean sentAgain = false;
            while (isCurrent(epoch) && (System.nanoTime() - begin) / 1_000_000 < SEARCH_MS) {
                if (!sentAgain && (System.nanoTime() - begin) / 1_000_000 >= 1500) {
                    searchStage = "重发搜索";
                    socket.send(rendererSearch);
                    sentPackets++;
                    socket.send(allSearch);
                    sentPackets++;
                    sentAgain = true;
                }
                for (int index = 0; index < (announcementListening ? 2 : 1); index++) {
                    MulticastSocket receiver = index == 0 ? socket : announcements;
                    byte[] bytes = new byte[8192];
                    DatagramPacket response = new DatagramPacket(bytes, bytes.length);
                    searchStage = index == 0 ? "接收响应" : "接收公告";
                    try { receiver.receive(response); }
                    catch (SocketTimeoutException ignored) { continue; }
                    catch (IOException failed) {
                        if (index == 0) throw failed;
                        receiver.close();
                        announcementListening = false;
                        continue;
                    }
                    if (response.getLength() == bytes.length) continue;
                    String packet = new String(response.getData(), response.getOffset(),
                            response.getLength(), StandardCharsets.ISO_8859_1);
                    boolean notify = packet.regionMatches(true, 0, "NOTIFY ", 0, 7);
                    if (index == 0 && (packet.startsWith("HTTP/1.1 200")
                            || packet.startsWith("HTTP/1.0 200"))) searchPackets++;
                    if (index == 1) {
                        if (notify) notifyPackets++;
                        else if (localAddress != null
                                && localAddress.equals(response.getAddress().getHostAddress())
                                && packet.regionMatches(true, 0, "M-SEARCH ", 0, 9))
                            selfSearchEchoes++;
                    }
                    DlnaRules.Advert advert = DlnaRules.parseAdvert(packet, response.getAddress());
                    if (index == 1 && notify && advert == null) rejectedNotifies++;
                    adverts.add(advert);
                }
            }
        } catch (SecurityException denied) {
            error = "无法使用局域网多播，请检查应用网络权限";
        } catch (IOException failed) {
            if (isCurrent(epoch)) error = "搜索电视失败，请确认手机和电视在同一局域网";
        } finally {
            synchronized (guard) {
                if (searchSocket == socket) searchSocket = null;
                if (notifySocket == announcements) notifySocket = null;
                if (multicastLock == lock) multicastLock = null;
            }
            if (socket != null) socket.close();
            if (announcements != null) announcements.close();
            release(lock);
        }
        if (!isCurrent(epoch)) return;
        LogStore.log(Category.PLAYER, "投屏发现: 已发送=" + sentPackets
                + "，局域网绑定=" + (network != null)
                + "，主动响应=" + searchPackets
                + "，组播公告=" + notifyPackets + "，本机回环=" + selfSearchEchoes
                + "，公告拒绝=" + rejectedNotifies + "，合格地址=" + adverts.size()
                + "，公告监听=" + (announcements != null)
                + (error == null ? "" : "，失败阶段=" + searchStage));
        if (error != null) {
            String message = error;
            postSearch(epoch, () -> callback.onFinished(message));
            return;
        }
        long descriptionStart = System.nanoTime();
        for (DlnaRules.Advert advert : adverts.ordered()) {
            if (!isCurrent(epoch)) return;
            if ((System.nanoTime() - descriptionStart) / 1_000_000 >= DESCRIPTION_MS) break;
            DlnaRules.Description description = readDescription(epoch, network, advert);
            if (description == null || devices.containsKey(description.id)) continue;
            devices.put(description.id, new Device(description, network, localAddress));
            List<Device> snapshot = Collections.unmodifiableList(new ArrayList<>(devices.values()));
            postSearch(epoch, () -> callback.onDevices(snapshot));
        }
        LogStore.log(Category.PLAYER, "投屏发现: 描述成功=" + devices.size()
                + "，候选=" + adverts.size());
        postSearch(epoch, () -> callback.onFinished(null));
        if (network != null && isCurrent(epoch)) {
            postSearch(epoch, callback::onWaitingForAnnouncements);
            try {
                ANNOUNCEMENT_EXECUTOR.execute(() -> listenForAnnouncements(epoch, callback,
                        network, localAddress, devices));
            } catch (RejectedExecutionException busy) {
                postSearch(epoch, callback::onAnnouncementWaitFinished);
            }
        }
    }

    /** GDLNA can advertise only once a minute; listen while the cast dialog remains open. */
    private void listenForAnnouncements(int epoch, SearchCallback callback, Network network,
                                        String localAddress, LinkedHashMap<String, Device> devices) {
        WifiManager.MulticastLock lock = null;
        MulticastSocket socket = null;
        int announcements = 0;
        int accepted = 0;
        int added = 0;
        int generalAttempts = 0;
        int rendererAttempts = 0;
        try {
            if (!isCurrent(epoch)) return;
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("MBox:DLNA-Announcements");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            socket = openNotifySocket(network, lanInterfaceFor(network));
            if (socket == null) return;
            socket.setSoTimeout(1000);
            synchronized (guard) {
                if (!isCurrentLocked(epoch)) return;
                notifySocket = socket;
                multicastLock = lock;
            }
            Set<String> described = new HashSet<>();
            LinkedHashMap<String, Long> lastAttempt = new LinkedHashMap<>();
            long begin = System.nanoTime();
            while (isCurrent(epoch) && (System.nanoTime() - begin) / 1_000_000 < ANNOUNCEMENT_MS) {
                byte[] bytes = new byte[8192];
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                try { socket.receive(packet); }
                catch (SocketTimeoutException ignored) { continue; }
                catch (IOException closedSocket) { break; }
                if (packet.getLength() == bytes.length) continue;
                String message = new String(packet.getData(), packet.getOffset(),
                        packet.getLength(), StandardCharsets.ISO_8859_1);
                if (!message.regionMatches(true, 0, "NOTIFY ", 0, 7)) continue;
                announcements++;
                DlnaRules.Advert advert = DlnaRules.parseAdvert(message, packet.getAddress());
                if (advert == null) continue;
                String location = advert.location.toString();
                if (described.contains(location)) continue;
                long now = System.nanoTime();
                Long previous = lastAttempt.get(location);
                if (previous != null
                        && (now - previous) / 1_000_000 < ANNOUNCEMENT_RETRY_MS) continue;
                if (advert.renderer) {
                    if (rendererAttempts >= MAX_ADVERTS) continue;
                    rendererAttempts++;
                } else {
                    if (generalAttempts >= MAX_ADVERTS) continue;
                    generalAttempts++;
                }
                lastAttempt.put(location, now);
                accepted++;
                DlnaRules.Description description = readDescription(epoch, network, advert);
                if (description == null) continue;
                described.add(location);
                if (devices.containsKey(description.id)) continue;
                devices.put(description.id, new Device(description, network, localAddress));
                added++;
                List<Device> snapshot = Collections.unmodifiableList(new ArrayList<>(devices.values()));
                postSearch(epoch, () -> callback.onDevices(snapshot));
            }
        } catch (IOException | SecurityException ignored) {
            // Active M-SEARCH and manual connection remain available if multicast listening fails.
        } finally {
            synchronized (guard) {
                if (notifySocket == socket) notifySocket = null;
                if (multicastLock == lock) multicastLock = null;
            }
            if (socket != null) socket.close();
            release(lock);
            if (isCurrent(epoch)) {
                LogStore.log(Category.PLAYER, "投屏发现: 后续公告=" + announcements
                        + "，描述尝试=" + accepted + "，新增设备=" + added);
                postSearch(epoch, callback::onAnnouncementWaitFinished);
            }
        }
    }

    private DlnaRules.Description readDescription(int epoch, Network network,
                                                   DlnaRules.Advert advert) {
        HttpURLConnection connection = null;
        try {
            connection = open(network, advert.location);
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "text/xml, application/xml");
            synchronized (guard) {
                if (!isCurrentLocked(epoch)) return null;
                searchConnection = connection;
            }
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK
                    || connection.getContentLengthLong() > MAX_DESCRIPTION_BYTES) return null;
            byte[] xml;
            try (InputStream input = connection.getInputStream()) {
                xml = readLimited(input, MAX_DESCRIPTION_BYTES);
            }
            return DlnaRules.parseDescription(xml, advert.location, advert.usn);
        } catch (Exception ignored) {
            // A malformed or unreachable advertisement must not prevent other TVs appearing.
            return null;
        } finally {
            synchronized (guard) {
                if (searchConnection == connection) searchConnection = null;
            }
            if (connection != null) connection.disconnect();
        }
    }

    private static String sendToRenderer(Device device, String title, URI media,
                                         long positionMs) throws IOException {
        String didl = DlnaRules.metadata(title, media);
        String setUri = "<InstanceID>0</InstanceID><CurrentURI>" + DlnaRules.escape(media.toString())
                + "</CurrentURI><CurrentURIMetaData>" + DlnaRules.escape(didl)
                + "</CurrentURIMetaData>";
        int setStatus = soapRequest(device, "SetAVTransportURI", setUri);
        if (setStatus < 200 || setStatus >= 300) {
            return "电视未接受视频地址（HTTP " + setStatus + "）";
        }
        boolean retrySeekAfterPlay = false;
        String seekArguments = "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>"
                + "<Target>" + DlnaRules.seekTarget(positionMs) + "</Target>";
        if (positionMs > 0) {
            try {
                int seekStatus = soapRequest(device, "Seek", seekArguments);
                retrySeekAfterPlay = seekStatus < 200 || seekStatus >= 300;
            } catch (IOException ignored) {
                retrySeekAfterPlay = true;
            }
        }
        int playStatus = soapRequest(device, "Play",
                "<InstanceID>0</InstanceID><Speed>1</Speed>");
        if (playStatus < 200 || playStatus >= 300) {
            return "电视未接受播放命令（HTTP " + playStatus + "）";
        }
        if (retrySeekAfterPlay) {
            try {
                soapRequest(device, "Seek", seekArguments);
            } catch (IOException ignored) {
                // The TV can still play from the beginning when it does not support Seek.
            }
        }
        return null;
    }

    private static int soapRequest(Device device, String action, String inner) throws IOException {
        byte[] body = DlnaRules.soap(device.serviceType, action, inner)
                .getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open(device.network, device.control);
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(3500);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            connection.setRequestProperty("SOAPAction", "\"" + device.serviceType + "#" + action + "\"");
            connection.setRequestProperty("Connection", "close");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) {
                try (InputStream input = stream) {
                    readLimited(input, MAX_SOAP_BYTES);
                }
            }
            return status;
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(Network network, URI uri) throws IOException {
        URL url = uri.toURL();
        return (HttpURLConnection) (network == null ? url.openConnection() : network.openConnection(url));
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > maxBytes) throw new IOException("response too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private Network chooseLanNetwork() {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return null;
        try {
            Network active = manager.getActiveNetwork();
            if (isLanNetwork(manager, active) && localIpv4For(active) != null) return active;
            for (Network network : manager.getAllNetworks()) {
                if (isLanNetwork(manager, network) && localIpv4For(network) != null) return network;
            }
        } catch (SecurityException ignored) { }
        return null;
    }

    private static boolean isLanNetwork(ConnectivityManager manager, Network network) {
        if (network == null) return false;
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        return caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private NetworkInterface lanInterfaceFor(Network network) {
        String address = localIpv4For(network);
        if (address == null) return null;
        try { return NetworkInterface.getByInetAddress(InetAddress.getByName(address)); }
        catch (IOException ignored) { return null; }
    }

    /** Passive advertisements are optional; failure to bind UDP 1900 must not break active search. */
    private static MulticastSocket openNotifySocket(Network network, NetworkInterface lanInterface) {
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(SSDP_PORT));
            socket.setSoTimeout(125);
            if (network != null) network.bindSocket(socket);
            InetAddress group = InetAddress.getByName(SSDP_ADDRESS);
            if (lanInterface != null)
                socket.joinGroup(new InetSocketAddress(group, SSDP_PORT), lanInterface);
            else socket.joinGroup(group);
            return socket;
        } catch (IOException | RuntimeException ignored) {
            if (socket != null) socket.close();
            return null;
        }
    }

    private String localIpv4For(Network network) {
        if (network == null) return null;
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return null;
        try {
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) return null;
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address)
                    return address.getAddress().getHostAddress();
            }
        } catch (SecurityException ignored) { }
        return null;
    }

    private void postSearch(int epoch, Runnable callback) {
        main.post(() -> {
            if (isCurrent(epoch)) callback.run();
        });
    }

    private void postCast(CastCallback callback, boolean success, String message) {
        // A submitted SOAP command continues after the dialog closes. Deliver its outcome so
        // the caller can retain a successful media relay or revoke a failed one.
        main.post(() -> callback.onResult(success, message));
    }

    private boolean isCurrent(int epoch) {
        synchronized (guard) { return isCurrentLocked(epoch); }
    }

    private boolean isCurrentLocked(int epoch) {
        return !closed && searchEpoch == epoch;
    }

    private void stopSearchLocked() {
        if (searchSocket != null) {
            searchSocket.close();
            searchSocket = null;
        }
        if (notifySocket != null) {
            notifySocket.close();
            notifySocket = null;
        }
        if (searchConnection != null) {
            searchConnection.disconnect();
            searchConnection = null;
        }
        release(multicastLock);
        multicastLock = null;
    }

    private static void release(WifiManager.MulticastLock lock) {
        if (lock == null) return;
        try {
            if (lock.isHeld()) lock.release();
        } catch (RuntimeException ignored) { }
    }
}

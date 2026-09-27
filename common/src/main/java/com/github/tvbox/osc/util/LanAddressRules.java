package com.github.tvbox.osc.util;

/**
 * 局域网访问地址的筛选与展示口径(纯逻辑,可 JVM 单测)。
 *
 * <p>要回答的问题只有一个:<b>"设置页该告诉用户从哪个地址进来"</b>。手机/电视上能列出好几种 IPv4 ——
 * Wi‑Fi 的、以太网的、开热点时本机那个 192.168.43.1、还有蜂窝数据(rmnet/ccmni)、VPN(tun)、
 * Wi‑Fi Direct(p2p)这些"局域网里的别的设备<b>根本够不到</b>"的地址。全部列出去只会让用户试错,
 * 所以口径是两条:
 * <ol>
 *   <li><b>只认 RFC1918 内网地址</b>(10/8、172.16~31/12、192.168/16):家庭/办公局域网长这样;
 *       运营商给的地址(100.64/10 CGNAT、公网 IP)从局域网里访问是进不来的,直接排除;</li>
 *   <li><b>排除明确够不到的网卡</b>(蜂窝/VPN/隧道/Wi‑Fi Direct 等,见
 *       {@link #UNREACHABLE_IFACE_PREFIXES})—— <b>用排除法而不是允许名单</b>:
 *       各 ROM 的网卡名五花八门(wlan0/eth0/en0/swlan0/ap0/softap0…),允许名单会把没见过的
 *       正常网卡一并滤掉,宁可多留一个也<strong>不要</strong>把唯一可用的地址藏起来。</li>
 * </ol>
 *
 * <p>取不到任何地址时(没连 Wi‑Fi、只有蜂窝数据)返回空 —— 由界面明确说"没取到局域网 IP",
 * 而不是拿 0.0.0.0 或蜂窝地址糊弄用户。
 */
public final class LanAddressRules {

    /**
     * 明确"局域网里的其它设备够不到"的网卡名前缀(小写比较):
     * 蜂窝数据 {@code rmnet/ccmni/pdp/seth}、VPN 与隧道 {@code tun/tap/ppp/ip6tnl/sit}、
     * Wi‑Fi Direct {@code p2p}、占位网卡 {@code dummy}。
     */
    private static final String[] UNREACHABLE_IFACE_PREFIXES = {
            "rmnet", "ccmni", "pdp", "seth", "tun", "tap", "ppp", "ip6tnl", "sit", "p2p", "dummy",
    };

    /** 优先展示的网卡前缀(Wi‑Fi / 以太网 / 热点):同网段下这些地址才是别家设备能直连的 */
    private static final String[] PREFERRED_IFACE_PREFIXES = {
            "wlan", "swlan", "wifi", "eth", "en", "ap", "softap",
    };

    private LanAddressRules() {
    }

    /** 网卡是否"局域网可达"(不在 {@link #UNREACHABLE_IFACE_PREFIXES} 里;名字未知时按可达处理) */
    public static boolean isReachableInterface(String ifaceName) {
        if (ifaceName == null) return true;
        String n = ifaceName.trim().toLowerCase(java.util.Locale.ROOT);
        if (n.isEmpty()) return true;
        for (String bad : UNREACHABLE_IFACE_PREFIXES) {
            if (n.startsWith(bad)) return false;
        }
        return true;
    }

    /** 该网卡是否属于"优先展示"的常见局域网网卡(Wi‑Fi / 以太网 / 热点) */
    public static boolean isPreferredInterface(String ifaceName) {
        if (ifaceName == null) return false;
        String n = ifaceName.trim().toLowerCase(java.util.Locale.ROOT);
        for (String good : PREFERRED_IFACE_PREFIXES) {
            if (n.startsWith(good)) return true;
        }
        return false;
    }

    /**
     * 是否 RFC1918 内网 IPv4(10.0.0.0/8、172.16.0.0/12、192.168.0.0/16)。
     * <p>回环 127.x、链路本地 169.254.x、CGNAT 100.64/10、公网地址一律 <b>false</b>。
     */
    public static boolean isPrivateIpv4(String ip) {
        if (ip == null) return false;
        String[] parts = ip.trim().split("\\.", -1);
        if (parts.length != 4) return false;
        int[] v = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return false;
            for (int k = 0; k < p.length(); k++) {
                if (p.charAt(k) < '0' || p.charAt(k) > '9') return false;
            }
            try {
                v[i] = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return false;
            }
            if (v[i] > 255) return false;
        }
        if (v[0] == 10) return true;
        if (v[0] == 192 && v[1] == 168) return true;
        return v[0] == 172 && v[1] >= 16 && v[1] <= 31;
    }

    /** 这条 (网卡, 地址) 是否值得展示给用户:网卡局域网可达 <b>且</b> 地址是内网地址 */
    public static boolean isUsableLanIpv4(String ifaceName, String ip) {
        return isReachableInterface(ifaceName) && isPrivateIpv4(ip);
    }

    /**
     * 拼访问地址 {@code http://<ip>:<port>/};参数非法返回空串(界面据此跳过该条,不显示半截地址)。
     */
    public static String url(String ip, int port) {
        if (!isPrivateIpv4(ip)) return "";
        if (port <= 0 || port > 65535) return "";
        return "http://" + ip.trim() + ":" + port + "/";
    }
}

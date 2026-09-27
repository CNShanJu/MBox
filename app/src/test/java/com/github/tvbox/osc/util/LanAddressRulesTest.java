package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * "局域网访问地址该显示哪几个"的口径单测:内网地址判定 + 够不到的网卡排除。
 *
 * <p>这条口径直接决定设置页告诉用户的地址对不对:漏掉唯一可用的地址 → 用户照着连不上;
 * 收进蜂窝/VPN/Wi‑Fi Direct 的地址 → 用户试半天也连不上。所以两边都钉住:
 * 只认 RFC1918,且用"排除法"滤网卡(名字没见过的正常网卡要留下)。
 */
public class LanAddressRulesTest {

    @Test
    public void privateIpv4AcceptsTheThreeRfc1918Ranges() {
        assertTrue(LanAddressRules.isPrivateIpv4("10.0.0.1"));
        assertTrue(LanAddressRules.isPrivateIpv4("10.255.255.254"));
        assertTrue(LanAddressRules.isPrivateIpv4("172.16.0.1"));
        assertTrue(LanAddressRules.isPrivateIpv4("172.31.255.254"));
        assertTrue(LanAddressRules.isPrivateIpv4("192.168.1.23"));
        assertTrue(LanAddressRules.isPrivateIpv4("192.168.43.1")); // 手机开热点时本机地址
        assertTrue(LanAddressRules.isPrivateIpv4(" 192.168.1.23 "));
    }

    @Test
    public void privateIpv4RejectsEverythingElse() {
        assertFalse("172.15/172.32 不在 172.16/12 里", LanAddressRules.isPrivateIpv4("172.15.0.1"));
        assertFalse(LanAddressRules.isPrivateIpv4("172.32.0.1"));
        assertFalse("回环不是局域网地址", LanAddressRules.isPrivateIpv4("127.0.0.1"));
        assertFalse("链路本地 169.254 不是可用局域网地址", LanAddressRules.isPrivateIpv4("169.254.1.1"));
        assertFalse("CGNAT 100.64/10 从局域网进不来", LanAddressRules.isPrivateIpv4("100.64.0.1"));
        assertFalse(LanAddressRules.isPrivateIpv4("8.8.8.8"));
        assertFalse(LanAddressRules.isPrivateIpv4("0.0.0.0"));
        assertFalse(LanAddressRules.isPrivateIpv4("256.1.1.1"));
        assertFalse(LanAddressRules.isPrivateIpv4("192.168.1"));
        assertFalse(LanAddressRules.isPrivateIpv4("192.168.1.1.1"));
        assertFalse(LanAddressRules.isPrivateIpv4("192.168.1.a"));
        assertFalse(LanAddressRules.isPrivateIpv4(""));
        assertFalse(LanAddressRules.isPrivateIpv4(null));
    }

    @Test
    public void unreachableInterfacesAreFilteredOut() {
        assertFalse(LanAddressRules.isReachableInterface("rmnet_data0")); // 蜂窝
        assertFalse(LanAddressRules.isReachableInterface("ccmni0"));      // 联发科蜂窝
        assertFalse(LanAddressRules.isReachableInterface("tun0"));        // VPN
        assertFalse(LanAddressRules.isReachableInterface("ppp0"));
        assertFalse(LanAddressRules.isReachableInterface("p2p0"));        // Wi‑Fi Direct
        assertFalse(LanAddressRules.isReachableInterface("dummy0"));
    }

    @Test
    public void unknownInterfaceNamesAreKept() {
        // 排除法:各 ROM 网卡名五花八门,允许名单会把没见过的正常网卡一起滤掉 —— 宁可多留
        assertTrue(LanAddressRules.isReachableInterface("wlan0"));
        assertTrue(LanAddressRules.isReachableInterface("eth0"));
        assertTrue(LanAddressRules.isReachableInterface("swlan0"));   // 部分 ROM 的热点网卡
        assertTrue(LanAddressRules.isReachableInterface("ap0"));
        assertTrue(LanAddressRules.isReachableInterface("softap0"));
        assertTrue(LanAddressRules.isReachableInterface("someOemIface0"));
        assertTrue(LanAddressRules.isReachableInterface(null));
    }

    @Test
    public void wifiAndEthernetArePreferred() {
        assertTrue(LanAddressRules.isPreferredInterface("wlan0"));
        assertTrue(LanAddressRules.isPreferredInterface("eth0"));
        assertTrue(LanAddressRules.isPreferredInterface("SWLAN0"));
        assertFalse(LanAddressRules.isPreferredInterface("someOemIface0"));
        assertFalse(LanAddressRules.isPreferredInterface(null));
    }

    @Test
    public void usableRequiresBothReachableInterfaceAndPrivateIp() {
        assertTrue(LanAddressRules.isUsableLanIpv4("wlan0", "192.168.1.23"));
        assertTrue(LanAddressRules.isUsableLanIpv4("ap0", "192.168.43.1"));
        assertFalse("蜂窝网卡不给", LanAddressRules.isUsableLanIpv4("rmnet_data0", "10.1.2.3"));
        assertFalse("VPN 网卡不给", LanAddressRules.isUsableLanIpv4("tun0", "10.8.0.2"));
        assertFalse("公网地址不给", LanAddressRules.isUsableLanIpv4("wlan0", "203.0.113.9"));
    }

    @Test
    public void urlIsBuiltOnlyForValidInput() {
        assertEquals("http://192.168.1.23:9978/", LanAddressRules.url("192.168.1.23", 9978));
        assertEquals("http://10.0.0.5:8080/", LanAddressRules.url("10.0.0.5", 8080));
        assertEquals("非内网地址不拼地址", "", LanAddressRules.url("8.8.8.8", 9978));
        assertEquals("端口非法不拼地址", "", LanAddressRules.url("192.168.1.23", 0));
        assertEquals("", LanAddressRules.url("192.168.1.23", 70000));
        assertEquals("", LanAddressRules.url(null, 9978));
    }
}

package com.github.tvbox.osc.cast;

import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Dns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CastMediaDnsTest {
    private static InetAddress ip(int a, int b, int c, int d) throws Exception {
        return InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
    }

    @Test public void publicAnswerIsPinnedEvenIfResolverLaterRebindsToLan() throws Exception {
        InetAddress publicIp = ip(8, 8, 8, 8);
        InetAddress privateIp = ip(192, 168, 1, 1);
        AtomicInteger calls = new AtomicInteger();
        Dns changing = host -> Collections.singletonList(
                calls.getAndIncrement() == 0 ? publicIp : privateIp);
        CastMediaDns dns = new CastMediaDns(changing, "video.example");
        assertEquals(Collections.singletonList(publicIp), dns.lookup("video.example"));
        assertEquals(Collections.singletonList(publicIp), dns.lookup("video.example"));
        assertEquals(1, calls.get());
    }

    @Test public void publicNameCannotResolveToPrivateButSelectedPrivateIpCan() throws Exception {
        InetAddress privateIp = ip(192, 168, 1, 2);
        Dns privateAnswer = host -> Collections.singletonList(privateIp);
        CastMediaDns publicSource = new CastMediaDns(privateAnswer, "video.example");
        assertThrows(UnknownHostException.class, () -> publicSource.lookup("video.example"));

        CastMediaDns localSource = new CastMediaDns(privateAnswer, "192.168.1.2");
        assertEquals(Collections.singletonList(privateIp), localSource.lookup("192.168.1.2"));
        assertThrows(UnknownHostException.class, () -> localSource.lookup("cdn.example"));

        CastMediaDns localName = new CastMediaDns(privateAnswer, "nas.local");
        assertEquals(Collections.singletonList(privateIp), localName.lookup("nas.local"));
        assertThrows(UnknownHostException.class, () -> localName.lookup("cdn.example"));

        CastMediaDns purifiedLocalPlaylist = new CastMediaDns(privateAnswer,
                "127.0.0.1", "192.168.1.2");
        assertEquals(Collections.singletonList(privateIp),
                purifiedLocalPlaylist.lookup("192.168.1.2"));
        assertThrows(UnknownHostException.class,
                () -> purifiedLocalPlaylist.lookup("other-private.example"));
    }

    @Test public void mixedDnsAnswersDiscardPrivateAddresses() throws Exception {
        InetAddress publicIp = ip(1, 1, 1, 1);
        InetAddress privateIp = ip(127, 0, 0, 1);
        CastMediaDns dns = new CastMediaDns(host -> Arrays.asList(privateIp, publicIp),
                "video.example");
        assertEquals(Collections.singletonList(publicIp), dns.lookup("video.example"));
    }
}

package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.OkHttpClient;

public class PlaybackConnectionProbeTest {
    private static final InetAddress LOOPBACK = loopback();

    @Test
    public void connectedProbeSendsNoHttpRequestBytes() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, LOOPBACK)) {
            AtomicInteger firstByte = new AtomicInteger(Integer.MIN_VALUE);
            AtomicReference<IOException> serverError = new AtomicReference<>();
            Thread receiver = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    try {
                        firstByte.set(socket.getInputStream().read());
                    } catch (SocketTimeoutException ignored) {
                        firstByte.set(-2); // 连接仍开着，但没有任何请求字节。
                    }
                } catch (IOException error) {
                    serverError.set(error);
                }
            }, "probe-no-http-test");
            receiver.start();

            AtomicReference<PlaybackConnectionProbe.Result> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Call call = PlaybackConnectionProbe.probe(new OkHttpClient(),
                    "http://127.0.0.1:" + server.getLocalPort() + "/one-time?token=test",
                    (kind, error) -> {
                        result.set(kind);
                        done.countDown();
                    });
            assertNotNull(call);
            assertTrue(done.await(4, TimeUnit.SECONDS));
            receiver.join(3000);
            assertFalse(receiver.isAlive());
            assertEquals(PlaybackConnectionProbe.Result.CONNECTED, result.get());
            assertEquals(null, serverError.get());
            assertTrue("received HTTP request byte: " + firstByte.get(), firstByte.get() < 0);
        }
    }

    @Test
    public void brokenTlsHandshakeIsConnectionFailure() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, LOOPBACK)) {
            Thread receiver = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    socket.getInputStream().read(); // 等客户端发出 TLS ClientHello。
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                }
            }, "probe-tls-failure-test");
            receiver.start();

            AtomicReference<PlaybackConnectionProbe.Result> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(false).build();
            PlaybackConnectionProbe.probe(client,
                    "https://127.0.0.1:" + server.getLocalPort() + "/manifest.m3u8",
                    (kind, error) -> {
                        result.set(kind);
                        done.countDown();
                    });
            assertTrue(done.await(4, TimeUnit.SECONDS));
            receiver.join(3000);
            assertFalse(receiver.isAlive());
            assertEquals(PlaybackConnectionProbe.Result.CONNECTION_FAILED, result.get());
        }
    }

    @Test
    public void nativeIjkUsesSystemDnsWhenPlaybackDnsIsUnavailable() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, LOOPBACK)) {
            Thread receiver = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    socket.getInputStream().read();
                } catch (IOException ignored) {
                }
            }, "native-ijk-system-dns-test");
            receiver.start();

            OkHttpClient client = new OkHttpClient.Builder()
                    .dns(host -> { throw new UnknownHostException("playback DoH unavailable"); })
                    .build();
            AtomicReference<PlaybackConnectionProbe.Result> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            PlaybackConnectionProbe.probe(client,
                    "http://localhost:" + server.getLocalPort() + "/manifest.m3u8",
                    PlaybackConnectionProbe.Mode.IJK_NATIVE, (kind, error) -> {
                        result.set(kind);
                        done.countDown();
                    });
            assertTrue(done.await(4, TimeUnit.SECONDS));
            receiver.join(3000);
            assertFalse(receiver.isAlive());
            assertEquals(PlaybackConnectionProbe.Result.CONNECTED, result.get());
        }
    }

    @Test
    public void nativeIjkTcpRefusalIsConnectionFailure() throws Exception {
        int closedPort;
        try (ServerSocket server = new ServerSocket(0, 1, LOOPBACK)) {
            closedPort = server.getLocalPort();
        }
        AtomicReference<PlaybackConnectionProbe.Result> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        PlaybackConnectionProbe.probe(new OkHttpClient.Builder()
                        .retryOnConnectionFailure(false).build(),
                "http://127.0.0.1:" + closedPort + "/manifest.m3u8",
                PlaybackConnectionProbe.Mode.IJK_NATIVE, (kind, error) -> {
                    result.set(kind);
                    done.countDown();
                });
        assertTrue(done.await(4, TimeUnit.SECONDS));
        assertEquals(PlaybackConnectionProbe.Result.CONNECTION_FAILED, result.get());
    }

    @Test
    public void nativeIjkDoesNotTreatJavaTlsFailureAsUnreachable() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, LOOPBACK)) {
            Thread receiver = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    socket.getInputStream().read(); // TLS ClientHello
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                }
            }, "native-ijk-tls-test");
            receiver.start();

            AtomicReference<PlaybackConnectionProbe.Result> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            PlaybackConnectionProbe.probe(new OkHttpClient.Builder()
                            .retryOnConnectionFailure(false).build(),
                    "https://127.0.0.1:" + server.getLocalPort() + "/manifest.m3u8",
                    PlaybackConnectionProbe.Mode.IJK_NATIVE, (kind, error) -> {
                        result.set(kind);
                        done.countDown();
                    });
            assertTrue(done.await(4, TimeUnit.SECONDS));
            receiver.join(3000);
            assertFalse(receiver.isAlive());
            assertEquals(PlaybackConnectionProbe.Result.INCONCLUSIVE, result.get());
        }
    }

    @Test
    public void totalTimeoutRemainsInconclusive() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, LOOPBACK)) {
            Thread receiver = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    socket.getInputStream().read(); // TLS ClientHello 已发，但服务端不完成握手。
                    Thread.sleep(400);
                } catch (IOException | InterruptedException ignored) {
                }
            }, "probe-timeout-test");
            receiver.start();

            AtomicReference<PlaybackConnectionProbe.Result> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(false).build();
            PlaybackConnectionProbe.probe(client,
                    "https://127.0.0.1:" + server.getLocalPort() + "/manifest.m3u8",
                    (kind, error) -> {
                        result.set(kind);
                        done.countDown();
                    }, 150);
            assertTrue(done.await(3, TimeUnit.SECONDS));
            receiver.join(3000);
            assertFalse(receiver.isAlive());
            assertEquals(PlaybackConnectionProbe.Result.INCONCLUSIVE, result.get());
        }
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }
}

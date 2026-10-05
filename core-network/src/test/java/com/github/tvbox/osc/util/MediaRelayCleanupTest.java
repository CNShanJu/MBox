package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.ConnectionPool;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MediaRelayCleanupTest {
    @Test public void queuedCleanupNeverRunsOnSubmittingCall() {
        AtomicReference<Runnable> pending = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();

        MediaRelayCleanup.dispatchCleanup(pending::set, calls::incrementAndGet);

        assertEquals(0, calls.get());
        assertNotNull(pending.get());
        pending.get().run();
        assertEquals(1, calls.get());
    }

    @Test public void rejectedQueueDoesNotRunCleanupOnCaller() {
        AtomicInteger calls = new AtomicInteger();
        MediaRelayCleanup.dispatchCleanup(task -> {
            throw new RejectedExecutionException("expected test rejection");
        }, calls::incrementAndGet);
        assertEquals(0, calls.get());
    }

    @Test public void moduleWorkerKeepsEveryQueuedCloseBeyondOldCapacity() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(64);
        MediaRelayCleanup.closeResources(Collections.singletonList(() -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }), null);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 64; i++)
                MediaRelayCleanup.closeResources(Collections.singletonList(finished::countDown), null);
        } finally {
            release.countDown();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
    }

    @Test public void cleanupRunsOnExecutorThread() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Thread caller = Thread.currentThread();
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            MediaRelayCleanup.dispatchCleanup(worker, () -> {
                ranOn.set(Thread.currentThread());
                done.countDown();
            });
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertNotSame(caller, ranOn.get());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test public void closeResourcesSnapshotsAndContinuesAfterEachFailure() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> closedOn = new AtomicReference<>();
        AtomicInteger closed = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        List<Closeable> resources = new ArrayList<>(Arrays.asList(
                () -> {
                    closedOn.set(Thread.currentThread());
                    entered.countDown();
                    try { release.await(2, TimeUnit.SECONDS); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                },
                () -> { throw new IOException("expected test failure"); },
                () -> { throw new IllegalStateException("expected test failure"); },
                () -> {
                    closed.incrementAndGet();
                    finished.countDown();
                }));

        MediaRelayCleanup.closeResources(resources, null);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertNotSame(caller, closedOn.get());
        resources.clear(); // The queued cleanup must use its own snapshot.
        release.countDown();
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertEquals(1, closed.get());
    }

    @Test public void evictsOnlyPassedClientsConnectionPool() throws Exception {
        MediaRelayCleanup.evictConnections(null);
        try (KeepAliveServer first = new KeepAliveServer();
             KeepAliveServer second = new KeepAliveServer()) {
            ConnectionPool firstPool = new ConnectionPool(5, 5, TimeUnit.MINUTES);
            ConnectionPool secondPool = new ConnectionPool(5, 5, TimeUnit.MINUTES);
            OkHttpClient firstClient = new OkHttpClient.Builder().connectionPool(firstPool).build();
            OkHttpClient secondClient = new OkHttpClient.Builder().connectionPool(secondPool).build();
            requestOnce(firstClient, first.port());
            requestOnce(secondClient, second.port());
            assertEquals(1, firstPool.idleConnectionCount());
            assertEquals(1, secondPool.idleConnectionCount());

            MediaRelayCleanup.evictConnections(firstClient);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (firstPool.idleConnectionCount() != 0 && System.nanoTime() < deadline)
                Thread.sleep(10);

            assertEquals(0, firstPool.idleConnectionCount());
            assertEquals(1, secondPool.idleConnectionCount());
            secondPool.evictAll();
        }
    }

    @Test public void doesNotCancelActiveCallOnSharedDispatcher() throws Exception {
        try (DelayedServer server = new DelayedServer()) {
            Dispatcher shared = new Dispatcher();
            OkHttpClient oldClient = new OkHttpClient.Builder()
                    .dispatcher(shared).connectionPool(new ConnectionPool()).build();
            OkHttpClient activeClient = new OkHttpClient.Builder()
                    .dispatcher(shared).connectionPool(new ConnectionPool()).build();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<IOException> failure = new AtomicReference<>();
            activeClient.newCall(new Request.Builder()
                    .url("http://127.0.0.1:" + server.port() + "/active").build())
                    .enqueue(new Callback() {
                        @Override public void onFailure(Call call, IOException error) {
                            failure.set(error);
                            done.countDown();
                        }
                        @Override public void onResponse(Call call, Response response) {
                            response.close();
                            done.countDown();
                        }
                    });
            assertTrue(server.requestReceived.await(2, TimeUnit.SECONDS));
            MediaRelayCleanup.evictConnections(oldClient);
            server.sendResponse.countDown();
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(null, failure.get());
        }
    }

    private static void requestOnce(OkHttpClient client, int port) throws IOException {
        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/media").build();
        try (Response ignored = client.newCall(request).execute()) { }
    }

    private static final class KeepAliveServer implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread worker;
        private volatile Socket connection;

        KeepAliveServer() throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(this::respond, "media-cleanup-test-server");
            worker.setDaemon(true);
            worker.start();
        }

        int port() { return listener.getLocalPort(); }

        private void respond() {
            try (Socket socket = listener.accept()) {
                connection = socket;
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while ((line = input.readLine()) != null && !line.isEmpty()) { }
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n"
                        + "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                socket.getInputStream().read(); // Keep the connection reusable until eviction.
            } catch (IOException ignored) { }
        }

        @Override public void close() throws Exception {
            Socket socket = connection;
            if (socket != null) socket.close();
            listener.close();
            worker.join(2000);
        }
    }

    private static final class DelayedServer implements AutoCloseable {
        final CountDownLatch requestReceived = new CountDownLatch(1);
        final CountDownLatch sendResponse = new CountDownLatch(1);
        private final ServerSocket listener;
        private final Thread worker;
        private volatile Socket connection;

        DelayedServer() throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(this::respond, "media-cleanup-active-call-test");
            worker.setDaemon(true);
            worker.start();
        }

        int port() { return listener.getLocalPort(); }

        private void respond() {
            try (Socket socket = listener.accept()) {
                connection = socket;
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while ((line = input.readLine()) != null && !line.isEmpty()) { }
                requestReceived.countDown();
                if (!sendResponse.await(3, TimeUnit.SECONDS)) return;
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            } catch (IOException | InterruptedException ignored) { }
        }

        @Override public void close() throws Exception {
            sendResponse.countDown();
            Socket socket = connection;
            if (socket != null) socket.close();
            listener.close();
            worker.join(2000);
        }
    }
}

package com.github.tvbox.osc.util;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import okhttp3.OkHttpClient;

/** Releases idle connections owned by a scoped media client off the caller thread. */
public final class MediaRelayCleanup {
    private static final Logger LOG = Logger.getLogger(MediaRelayCleanup.class.getName());
    // A single worker serializes closes. The pending queue is intentionally unbounded so a
    // revoked session never loses its last references; a burst can temporarily grow this queue.
    private static final Executor CLEANUP_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "MBox-media-relay-cleanup");
                thread.setDaemon(true);
                return thread;
            });

    private MediaRelayCleanup() { }

    /** Never evict inline: closing an idle TLS connection can perform network I/O. */
    public static void evictConnections(OkHttpClient client) {
        closeResources(Collections.emptyList(), client);
    }

    /** Snapshot references now; close streams and the specified client's idle pool in the background. */
    public static void closeResources(Collection<? extends Closeable> resources, OkHttpClient client) {
        List<Closeable> snapshot = new ArrayList<>();
        if (resources != null) snapshot.addAll(resources);
        if (snapshot.isEmpty() && client == null) return;
        dispatchCleanup(CLEANUP_EXECUTOR, () -> {
            for (Closeable resource : snapshot) {
                if (resource == null) continue;
                try {
                    resource.close();
                } catch (IOException | RuntimeException error) {
                    logFailure("resource close", error);
                }
            }
            if (client != null) {
                try {
                    client.connectionPool().evictAll();
                } catch (RuntimeException error) {
                    logFailure("connection eviction", error);
                }
            }
        });
    }

    /** Package-private scheduling seam for JVM tests; rejection never falls back to the caller. */
    static void dispatchCleanup(Executor executor, Runnable cleanup) {
        if (executor == null || cleanup == null) return;
        try {
            executor.execute(() -> {
                try {
                    cleanup.run();
                } catch (RuntimeException error) {
                    logFailure("cleanup", error);
                }
            });
        } catch (RejectedExecutionException error) {
            LOG.warning("media relay cleanup dispatch rejected");
        }
    }

    private static void logFailure(String operation, Exception error) {
        LOG.warning("media relay " + operation + " failed: " + error.getClass().getSimpleName());
    }
}

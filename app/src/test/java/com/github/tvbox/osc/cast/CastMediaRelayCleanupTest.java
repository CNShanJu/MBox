package com.github.tvbox.osc.cast;

import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CastMediaRelayCleanupTest {
    @Test public void closeRevokesSessionBeforeClosingStreamsOffCallerThread() throws Exception {
        CastMediaRelay.Session session = new CastMediaRelay.Session(null,
                "http://127.0.0.1:12345", "old-token", 9978, 1);
        String path = session.register("https://video.example/movie.m3u8",
                Collections.emptyMap(), null, "https://video.example/movie.m3u8",
                CastMediaRules.MediaKind.HLS, null);
        String targetId = path.split("/")[3];
        CastMediaRelay.Session newer = new CastMediaRelay.Session(null,
                "http://127.0.0.1:12345", "new-token", 9978, 2);
        String newerPath = newer.register("https://video.example/other.m3u8",
                Collections.emptyMap(), null, "https://video.example/other.m3u8",
                CastMediaRules.MediaKind.HLS, null);
        String newerTargetId = newerPath.split("/")[3];
        CountDownLatch streamCloseStarted = new CountDownLatch(1);
        CountDownLatch allowStreamClose = new CountDownLatch(1);
        CountDownLatch streamCloseFinished = new CountDownLatch(1);
        CountDownLatch callerReturned = new CountDownLatch(1);
        AtomicInteger closeCount = new AtomicInteger();
        Closeable stream = () -> {
            streamCloseStarted.countDown();
            try {
                if (!allowStreamClose.await(5, TimeUnit.SECONDS))
                    throw new IOException("Timed out waiting to release test stream");
                closeCount.incrementAndGet();
                streamCloseFinished.countDown();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(error);
            }
        };
        session.streams.add(stream);

        Thread caller = new Thread(() -> {
            session.close();
            callerReturned.countDown();
        }, "simulated-cast-ui-caller");
        caller.start();
        try {
            assertTrue("Session.close must return without waiting for stream I/O",
                    callerReturned.await(2, TimeUnit.SECONDS));
            assertTrue(session.closed);
            assertNull(session.target(targetId));
            assertTrue(session.targets.isEmpty());
            assertTrue(session.idsByUrl.isEmpty());
            assertTrue(session.streams.isEmpty());
            assertNull(session.register("https://video.example/new.m3u8",
                    Collections.emptyMap(), null, "https://video.example/new.m3u8",
                    CastMediaRules.MediaKind.HLS, null));
            assertTrue(streamCloseStarted.await(5, TimeUnit.SECONDS));
            assertEquals(1, streamCloseFinished.getCount());
            session.close(); // A second revocation must not schedule another close.
        } finally {
            allowStreamClose.countDown();
            caller.join(2000);
        }
        assertFalse(caller.isAlive());
        assertTrue(streamCloseFinished.await(5, TimeUnit.SECONDS));
        assertEquals(1, closeCount.get());
        assertFalse(newer.closed);
        assertTrue(newer.target(newerTargetId) != null);
    }
}

package com.github.tvbox.osc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class RemoteServerPlaybackProtocolTest {
    @Test public void consecutivePlaybackCommandsRemainOrderedUntilAcknowledged() {
        RemoteServer.BrowserCommandQueue queue = new RemoteServer.BrowserCommandQueue();
        assertTrue(queue.enqueue(4, "play", 0));
        assertTrue(queue.enqueue(4, "seek", 860000));
        List<RemoteServer.BrowserPlaybackCommand> pending = queue.pending();
        assertEquals(2, pending.size());
        assertEquals("play", pending.get(0).action);
        assertEquals("seek", pending.get(1).action);
        assertTrue(pending.get(0).id < pending.get(1).id);

        assertTrue(queue.acknowledge(pending.get(0).id));
        assertEquals(1, queue.pending().size());
        assertEquals("seek", queue.pending().get(0).action);
        assertFalse(queue.acknowledge(pending.get(1).id + 1));
        assertEquals(1, queue.pending().size());
        assertTrue(queue.acknowledge(pending.get(1).id));
        assertTrue(queue.pending().isEmpty());
    }

    @Test public void fullQueueRejectsCommandInsteadOfOverwritingEarlierInput() {
        RemoteServer.BrowserCommandQueue queue = new RemoteServer.BrowserCommandQueue();
        for (int index = 0; index < 32; index++)
            assertTrue(queue.enqueue(7, "seek", index * 1000L));
        assertFalse(queue.enqueue(7, "pause", 0));
        assertEquals(32, queue.pending().size());
        assertEquals(0, queue.pending().get(0).positionMs);
        assertEquals(31000, queue.pending().get(31).positionMs);
        queue.clear();
        assertTrue(queue.enqueue(8, "play", 0));
        assertEquals(33, queue.pending().get(0).id);
    }

    @Test public void lateLeaveCannotRevokeNewRevisionOrAnotherDevice() {
        assertTrue(RemoteServer.currentCastRevision("browser-a", "browser-a", 5, 5));
        assertFalse(RemoteServer.currentCastRevision("browser-a", "browser-a", 6, 5));
        assertFalse(RemoteServer.currentCastRevision("browser-b", "browser-a", 5, 5));
        assertFalse(RemoteServer.currentCastRevision(null, "browser-a", 5, 5));
    }

    @Test public void cookieWritesRequireNumericHostAndMatchingOrigin() {
        assertTrue(RemoteServer.sameOriginForCookieWrite(
                "http://192.168.1.5:9978/cast.html", "192.168.1.5:9978", 9978));
        assertFalse(RemoteServer.sameOriginForCookieWrite(
                "http://192.168.1.5:9980/cast.html", "192.168.1.5:9978", 9978));
        assertFalse(RemoteServer.sameOriginForCookieWrite(
                "http://192.168.1.6:9978/cast.html", "192.168.1.5:9978", 9978));
        assertFalse(RemoteServer.sameOriginForCookieWrite(
                "http://attacker.example:9978/", "attacker.example:9978", 9978));
        assertFalse(RemoteServer.sameOriginForCookieWrite(
                "https://192.168.1.5:9978/", "192.168.1.5:9978", 9978));
    }
}

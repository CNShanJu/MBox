package com.github.tvbox.osc.util.player

import com.github.tvbox.osc.player.api.MediaPreloader
import com.github.tvbox.osc.spiderapi.PlayUrlResolverApi
import com.github.tvbox.osc.spiderapi.ResolveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.Executor

class NextEpisodePreloaderTest {
    private class QueueExecutor : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.add(command) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    private class Fixture : Closeable {
        val worker = QueueExecutor()
        val delivery = QueueExecutor()
        var enabled = true
        var resolved = 0
        var cancelled = 0
        val headers = linkedMapOf("User-Agent" to "original")
        val loaded = mutableListOf<String>()
        var loadedHeaders: Map<String, String>? = null
        var loadedStart = -1L
        val loader = NextEpisodePreloader(
            object : PlayUrlResolverApi by PlayUrlResolverApi.NONE {
                override fun resolveDirectPlayUrl(source: String, flag: String, url: String): ResolveResult {
                    resolved++
                    return ResolveResult("https://example.invalid/$source/$url.mp4", headers)
                }
            },
            object : MediaPreloader {
                override fun preload(url: String, headers: Map<String, String>?, startMs: Long, durationSec: Int) {
                    loaded.add(url)
                    loadedHeaders = headers
                    loadedStart = startMs
                    assertEquals(60, durationSec)
                }
                override fun cancel() { cancelled++ }
            }, worker, delivery, { enabled }
        )
        fun schedule(source: String = "a") { loader.schedule(source, "main", "next", 90_000L, 60) }
        override fun close() { loader.release() }
    }

    @Test
    fun repeatedPlayingStateSchedulesOnceAndCopiesHeaders() = Fixture().use { f ->
        f.schedule()
        f.schedule()
        f.worker.drain()
        f.headers["User-Agent"] = "changed"
        f.delivery.drain()
        assertEquals(1, f.resolved)
        assertEquals(1, f.loaded.size)
        assertEquals("original", f.loadedHeaders?.get("User-Agent"))
        assertEquals(90_000L, f.loadedStart)
    }

    @Test
    fun cancellationBeforeWorkerSkipsResolution() = Fixture().use { f ->
        f.schedule()
        f.loader.cancel()
        f.worker.drain()
        f.delivery.drain()
        assertEquals(0, f.resolved)
        assertTrue(f.loaded.isEmpty())
    }

    @Test
    fun sameRawUrlFromChangedSourceCannotAcceptStaleResult() = Fixture().use { f ->
        f.schedule("old")
        f.worker.drain()
        f.loader.cancel()
        f.schedule("new")
        f.worker.drain()
        f.delivery.drain()
        assertEquals(listOf("https://example.invalid/new/next.mp4"), f.loaded)
    }

    @Test
    fun disabledConfigurationRejectsPendingResult() = Fixture().use { f ->
        f.schedule()
        f.worker.drain()
        f.enabled = false
        f.delivery.drain()
        assertTrue(f.loaded.isEmpty())
    }

    @Test
    fun releaseRejectsPendingAndFutureRequests() = Fixture().use { f ->
        f.schedule()
        f.worker.drain()
        f.loader.release()
        f.delivery.drain()
        f.schedule()
        f.worker.drain()
        assertTrue(f.loaded.isEmpty())
        assertEquals(1, f.resolved)
    }

    @Test
    fun cancellingOnePageDoesNotCancelAnotherPage() = Fixture().use { first ->
        Fixture().use { second ->
            first.schedule()
            second.schedule()
            first.loader.cancel()
            first.worker.drain()
            second.worker.drain()
            first.delivery.drain()
            second.delivery.drain()
            assertTrue(first.loaded.isEmpty())
            assertEquals(1, second.loaded.size)
        }
    }
}

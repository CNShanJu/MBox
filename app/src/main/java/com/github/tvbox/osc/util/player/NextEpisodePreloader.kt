package com.github.tvbox.osc.util.player

import com.github.tvbox.osc.player.api.MediaPreloader
import com.github.tvbox.osc.player.api.PlayConfig
import com.github.tvbox.osc.spiderapi.PlayUrlResolverApi
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.function.BooleanSupplier

class NextEpisodePreloader(
    private val resolver: PlayUrlResolverApi,
    private val mediaPreloader: MediaPreloader,
    private val worker: Executor,
    private val delivery: Executor,
    private val enabled: BooleanSupplier
) {
    private data class Request(
        val sourceKey: String,
        val playFlag: String,
        val rawUrl: String,
        val startMs: Long,
        val durationSec: Int
    )

    private val generation = AtomicLong()
    private var scheduled: Request? = null
    private var released = false
    private val configListener = PlayConfig.Listener {
        if (!enabled.asBoolean) cancel()
    }

    init {
        PlayConfig.subscribe(configListener)
    }

    @Synchronized
    fun schedule(sourceKey: String?, playFlag: String?, rawUrl: String?, startMs: Long, durationSec: Int) {
        if (released || !enabled.asBoolean || sourceKey.isNullOrEmpty()
            || playFlag == null || rawUrl.isNullOrEmpty()) return
        val request = Request(sourceKey, playFlag, rawUrl, startMs.coerceAtLeast(0), durationSec)
        if (scheduled == request) return
        cancel()
        scheduled = request
        val expected = generation.get()
        try {
            worker.execute {
                if (generation.get() != expected) return@execute
                val result = try {
                    resolver.resolveDirectPlayUrl(request.sourceKey, request.playFlag, request.rawUrl)
                } catch (_: Exception) {
                    null
                } ?: return@execute
                val url = result.url ?: return@execute
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return@execute
                val headers = Collections.unmodifiableMap(LinkedHashMap(result.headers ?: emptyMap()))
                if (generation.get() != expected) return@execute
                delivery.execute deliver@{
                    synchronized(this) {
                        if (released || generation.get() != expected || !enabled.asBoolean) return@deliver
                        mediaPreloader.preload(url, headers, request.startMs, request.durationSec)
                    }
                }
            }
        } catch (_: RuntimeException) {
            cancel()
        }
    }

    @Synchronized
    fun cancel() {
        generation.incrementAndGet()
        scheduled = null
        mediaPreloader.cancel()
    }

    @Synchronized
    fun release() {
        released = true
        PlayConfig.unsubscribe(configListener)
        cancel()
    }
}

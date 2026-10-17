package com.github.tvbox.osc.player.internal

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.player.api.MediaPreloader
import java.util.concurrent.atomic.AtomicLong

/**
 * 仅 prepare、不播放的预载实现。prepare 仍可能初始化解码器并占用解码资源,
 * 因而缓冲完成、失败、取消和超时都必须释放播放器。
 * 媒体源由组合根注入,与主播放器共用缓存工厂;本类不读取配置或业务单例。
 */
@UnstableApi
class Media3Preloader(context: Context, private val sourceFactory: SourceFactory) : MediaPreloader {

    fun interface SourceFactory {
        fun create(url: String, headers: Map<String, String>?): MediaSource
    }

    private val appContext = context.applicationContext
    private val token = AtomicLong()

    // 仅在共享 handler 线程读写;UI/调用线程不得据此决定是否需要释放。
    private var player: ExoPlayer? = null
    private var timeout: Runnable? = null

    @Synchronized
    override fun preload(url: String, headers: Map<String, String>?, startMs: Long, durationSec: Int) {
        val requestToken = token.incrementAndGet()
        // 失效与两次 post 在同一实例锁内,取消/替换不能插入释放与创建之间。
        // 即便输入无效或后续复制请求头失败,上一轮也已经无条件排队释放。
        handler.post { releaseOnHandler() }
        if (url.isBlank() || durationSec <= 0) return
        val snapshot = try {
            headers?.toMap()
        } catch (_: Throwable) {
            return
        }
        val targetMs = MediaPreloadPolicy.targetMs(durationSec)
        handler.post {
            preloadOnHandler(requestToken, url, snapshot, startMs.coerceAtLeast(0L), targetMs)
        }
    }

    @Synchronized
    override fun cancel() {
        token.incrementAndGet()
        // 不能检查 player:当前可能正在 handler 内创建,释放必须排在创建任务之后。
        handler.post { releaseOnHandler() }
    }

    private fun preloadOnHandler(
        requestToken: Long,
        url: String,
        headers: Map<String, String>?,
        startMs: Long,
        targetMs: Int
    ) {
        if (token.get() != requestToken) return
        try {
            val source = sourceFactory.create(url, headers)
            if (token.get() != requestToken) return
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(targetMs, targetMs, targetMs, targetMs)
                .build()
            val p = ExoPlayer.Builder(appContext)
                .setLooper(handler.looper)
                .setLoadControl(loadControl)
                .build()
            // 先持有再检查失效:即使 build 期间取消,已入队的释放也能找到这个实例。
            player = p
            if (!isCurrent(p, requestToken)) return

            val deadline = Runnable {
                if (isCurrent(p, requestToken)) releaseOnHandler()
            }
            timeout = deadline
            handler.postDelayed(deadline, MediaPreloadPolicy.timeoutMs(targetMs))
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    checkDone(p, requestToken, targetMs)
                }

                override fun onIsLoadingChanged(isLoading: Boolean) {
                    checkDone(p, requestToken, targetMs)
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (isCurrent(p, requestToken)) releaseOnHandler()
                }
            })
            p.playWhenReady = false
            p.setMediaSource(source, startMs)
            if (!isCurrent(p, requestToken)) return
            p.prepare()
            if (!isCurrent(p, requestToken)) return
            checkDone(p, requestToken, targetMs)
            LogStore.log(Category.PLAYER, "媒体预载开始: 起算=${startMs}ms 目标=${targetMs}ms")
        } catch (_: Throwable) {
            // 预载只是优化;创建/装配失败不能影响当前播放或杀死共享 handler。
            releaseOnHandler()
        }
    }

    private fun isCurrent(p: ExoPlayer, requestToken: Long): Boolean =
        token.get() == requestToken && player === p

    private fun checkDone(p: ExoPlayer, requestToken: Long, targetMs: Int) {
        if (!isCurrent(p, requestToken)) return
        try {
            if (MediaPreloadPolicy.isComplete(
                    p.totalBufferedDuration, targetMs, p.playbackState, p.isLoading
                )
            ) {
                releaseOnHandler()
            }
        } catch (_: Throwable) {
            if (isCurrent(p, requestToken)) releaseOnHandler()
        }
    }

    private fun releaseOnHandler() {
        val oldPlayer = player
        val oldTimeout = timeout
        // 先摘掉引用,避免 release 的同步回调重入;任一清理步骤抛异常也不遗留本轮状态。
        player = null
        timeout = null
        try {
            if (oldTimeout != null) handler.removeCallbacks(oldTimeout)
        } catch (_: Throwable) {
        }
        try {
            // release 本身负责停止;不把 stop 与 release 放在同一 try 而被 stop 异常短路。
            oldPlayer?.release()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        // 进程内所有预载实例共享一个懒建 Looper;不共享 token/player/timeout。
        val handler: Handler by lazy {
            Handler(HandlerThread("media3-preload").apply { start() }.looper)
        }
    }
}

/** 纯数值/状态判定,可在 JVM 测试中使用,不会初始化 HandlerThread 或播放器。 */
internal object MediaPreloadPolicy {
    fun targetMs(durationSec: Int): Int =
        (durationSec.toLong() * 1_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    // 正常设置上限 300 秒,保留 20 秒余量;异常大的调用参数也不能无限占用预载资源。
    fun timeoutMs(targetMs: Int): Long =
        (targetMs.coerceAtLeast(0).toLong() + 20_000L).coerceAtMost(320_000L)

    fun isComplete(bufferedMs: Long, targetMs: Int, playbackState: Int, isLoading: Boolean): Boolean =
        bufferedMs >= targetMs || playbackState == Player.STATE_ENDED ||
            // 短片/尾段可能永远达不到目标,暂停预载也不会自然进入 ENDED。
            (playbackState == Player.STATE_READY && !isLoading)
}

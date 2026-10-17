package com.github.tvbox.osc.player.api

/** 每个实例独立管理一轮媒体预载;替换或取消不影响其他实例。 */
interface MediaPreloader {
    /** 从 [startMs] 起预载 [durationSec] 秒;请求头在入队前复制。 */
    fun preload(url: String, headers: Map<String, String>?, startMs: Long, durationSec: Int)

    /** 异步释放本实例的预载资源,也覆盖正在创建的播放器。 */
    fun cancel()
}

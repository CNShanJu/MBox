package com.github.tvbox.osc.util.subscription

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 只在顶层入口换代；后台只读令牌，不读取 Activity 状态。 */
internal class ImportSession {
    private val generation = AtomicLong()
    val current: Long get() = generation.get()
    fun begin(): Long = generation.incrementAndGet()
    fun invalidate() { generation.incrementAndGet() }
    fun isCurrent(token: Long): Boolean = generation.get() == token
}

internal class ImportOnce {
    private val completed = AtomicBoolean()
    fun claim(): Boolean = completed.compareAndSet(false, true)
}

/** 当前递归路径防环，而非全局 visited；共享子清单在上一分支退出后仍可展开。主线程使用。 */
internal class ImportExpansionGuard(
    private val maxDepth: Int = 32,
    private val maxExpansions: Int = 500
) {
    private val active = HashSet<String>()
    private var expansions = 0

    fun enter(key: String): Boolean {
        if (key in active || active.size >= maxDepth || expansions >= maxExpansions) return false
        active.add(key)
        expansions++
        return true
    }

    fun leave(key: String) { active.remove(key) }

    companion object {
        /** 在后台调用：clan 的别名/符号链接须以实际 canonical 文件判同。 */
        fun key(url: String, localRoot: File): String {
            if (url.startsWith("clan://localhost/")) {
                val relative = url.removePrefix("clan://localhost/")
                return "clan:" + File(localRoot, relative).canonicalPath
            }
            return httpKey(url)
        }

        // 与请求使用相同的 URL 解析器：统一主机名/IDN、默认端口、点路径并去除 fragment。
        fun httpKey(url: String): String = normalizeEscapes(url.trim()).toHttpUrlOrNull()
            ?.newBuilder()?.fragment(null)?.build()?.toString() ?: url.trim().substringBefore('#')

        // 仅解码 unreserved 字符；%2F 等保留字符不能与路径分隔符混同。
        private fun normalizeEscapes(url: String): String = Regex("%[0-9a-fA-F]{2}").replace(url) {
            val ch = it.value.substring(1).toInt(16).toChar()
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-._~")
                ch.toString() else it.value.uppercase(Locale.ROOT)
        }
    }
}

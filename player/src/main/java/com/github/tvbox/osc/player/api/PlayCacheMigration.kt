package com.github.tvbox.osc.player.api

/** 仅归一缓存键，不读取配置或执行 I/O；本机快照与备份边界共用同一优先级。 */
object PlayCacheMigration {
    const val LEGACY_KEY = "ijk_cache_play"
    const val ENABLED_KEY = "play_cache_enabled"
    const val WHILE_PLAY_KEY = "cache_while_play"

    data class Snapshot(val enabled: Boolean, val whilePlay: Boolean)

    /** null 表示缺失；显式新值优先，旧值只补缺，最后使用新装默认值。 */
    @JvmStatic
    fun resolve(enabled: Boolean?, whilePlay: Boolean?, legacy: Boolean?): Snapshot =
        Snapshot(enabled ?: legacy ?: false, whilePlay ?: legacy ?: true)

    /**
     * 只按传入备份归一，不混入本机值；缺失字段仍保持缺失，避免局部导入重置无关设置。
     * 旧键只作为输入，返回值仅含有效的新键；不修改调用方的 Map。
     */
    @JvmStatic
    fun normalizeImport(values: Map<String, *>): Map<String, Boolean> {
        val enabled = values[ENABLED_KEY] as? Boolean
        val whilePlay = values[WHILE_PLAY_KEY] as? Boolean
        val legacy = values[LEGACY_KEY] as? Boolean
        val snapshot = resolve(enabled, whilePlay, legacy)
        return buildMap {
            if (enabled != null || legacy != null) put(ENABLED_KEY, snapshot.enabled)
            if (whilePlay != null || legacy != null) put(WHILE_PLAY_KEY, snapshot.whilePlay)
        }
    }
}

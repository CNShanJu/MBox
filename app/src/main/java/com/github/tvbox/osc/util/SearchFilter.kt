package com.github.tvbox.osc.util

/**
 * 搜索结果行命中判定(自 FastSearchActivity.matchSearchResult 抽取;Java→Kotlin 化,改进.txt §八)。
 * 语义与旧实现逐字等价:
 * - name/查询词为 null 或空 → 不命中;
 * - 查询词去首尾空白后按空白切词,要求 name 包含每个非空词(空 token 恒通过);
 * - 仅空白组成的查询词 → 命中全部。
 */
object SearchFilter {

    @JvmStatic
    fun matches(name: String?, searchTitle: String?): Boolean {
        if (name.isNullOrEmpty() || searchTitle.isNullOrEmpty()) return false
        val parts = searchTitle.trim().split("\\s+".toRegex())
        var n = parts.size
        while (n > 0 && parts[n - 1].isEmpty()) n--
        if (n == 0) return true // 纯空白查询词:命中全部(与旧实现一致)
        for (i in 0 until n) {
            if (parts[i].isEmpty()) continue // 空 token 等价 name.contains("") 恒真
            if (!name.contains(parts[i])) return false
        }
        return true
    }
}

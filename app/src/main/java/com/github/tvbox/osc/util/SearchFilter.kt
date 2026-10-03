package com.github.tvbox.osc.util

/**
 * 搜索结果行命中判定(自 FastSearchActivity.matchSearchResult 抽取;Java→Kotlin 化,改进.txt §八)。
 * 语义与旧实现逐字等价:
 * - name/查询词为 null 或空 → 不命中;
 * - 查询词去首尾空白后按空白切词,要求 name 包含每个非空词(空 token 恒通过);
 * - 仅空白组成的查询词 → 命中全部。
 */
object SearchFilter {

    private val whitespace = "\\s+".toRegex()

    /** 同一轮搜索只拆一次关键词；空白查询仍匹配任意非空片名。 */
    @JvmStatic
    fun words(searchTitle: String?): List<String>? {
        if (searchTitle.isNullOrEmpty()) return null
        return searchTitle.trim().split(whitespace).filter { it.isNotEmpty() }
    }

    @JvmStatic
    fun matches(name: String?, searchTitle: String?): Boolean {
        return matchesWords(name, words(searchTitle))
    }

    @JvmStatic
    fun matchesWords(name: String?, words: List<String>?): Boolean {
        if (name.isNullOrEmpty() || words == null) return false
        for (word in words) {
            if (!name.contains(word)) return false
        }
        return true
    }
}

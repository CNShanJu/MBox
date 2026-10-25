package com.github.tvbox.osc.util.subscription

import com.github.tvbox.osc.bean.Subscription

/**
 * 订阅「批次」纯逻辑(不依赖 Android,可单测)。
 *
 * 一次多仓/批量导入产生的所有订阅共享同一个 [Subscription.getBatchId],
 * 订阅列表里以手风琴分组呈现:组头显示批次名、源个数、导入时间,方便整批删除/更新。
 *
 * 批次键的两种来源:
 *  - **本地包**:条目地址是解压目录下的 clan:// 地址,取目录基名做稳定键
 *    ([packageDirNameOf]);同一包(哪怕内容更新后换了摘要目录)基名不变,可对上批;
 *  - **其余导入**:由导入协调器直接分配 [newBatchId]。
 */
object SubscriptionBatches {

    private const val PACKAGE_MARKER = "/local_packages/"
    private const val BATCH_KEY_PREFIX = "pkg:"
    /** 解压目录名 = 基名 + "_" + 8 位十六进制摘要(见 LocalPackageImporter.digestOf) */
    private val DIR_DIGEST_SUFFIX = Regex("_[0-9a-f]{8}$")

    /** 新批次 id(同一批次的所有条目共享;默认取当前时间,测试可注入) */
    fun newBatchId(now: Long = System.currentTimeMillis()): String = "b$now"

    /**
     * 本地包条目 URL → 解压目录基名(形如 {@code TVBoxOSC_1a2b3c4d});非本地包地址返回 null。
     * 只认 {@code /local_packages/} 段后的第一段目录名,与 LocalPackageImporter.packageRoot 对齐。
     */
    fun packageDirNameOf(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val idx = url.indexOf(PACKAGE_MARKER)
        if (idx < 0) return null
        val dir = url.substring(idx + PACKAGE_MARKER.length).substringBefore('/')
        return dir.ifEmpty { null }
    }

    /**
     * 本地包条目 URL → 去掉摘要后缀的包基名(形如 {@code TVBoxOSC})。
     * 同名包更新后摘要目录会变,靠基名把新旧条目认成同一批(盲重新导入时对批用)。
     */
    fun packageBaseNameOf(url: String?): String? =
        packageDirNameOf(url)?.let { stripDirDigest(it) }

    /** 解压目录名去掉末尾的 {@code _<8 位十六进制>} 摘要后缀 */
    fun stripDirDigest(dirName: String): String = dirName.replace(DIR_DIGEST_SUFFIX, "")

    /**
     * 订阅 URL → 批次键:本地包条目用解压目录基名(一次导入的所有条目一致);
     * 其余地址返回 null(单条导入不入组,列表里保持平铺)。
     */
    fun batchKeyOf(url: String?): String? =
        packageDirNameOf(url)?.let { batchKeyOfDir(it) }

    /** 解压目录名 → 批次键 */
    fun batchKeyOfDir(dirName: String): String = BATCH_KEY_PREFIX + dirName

    /** 批次键 → 包目录名(仅包批次键有;其余返回 null) */
    fun packageDirOfBatchKey(batchId: String?): String? =
        batchId?.takeIf { it.startsWith(BATCH_KEY_PREFIX) }
            ?.removePrefix(BATCH_KEY_PREFIX)?.ifEmpty { null }

    /** 批次内成员(保持传入顺序) */
    fun membersOf(all: List<Subscription>, batchId: String?): List<Subscription> {
        if (batchId.isNullOrEmpty()) return emptyList()
        return all.filter { batchId == it.batchId }
    }

    /**
     * 重新导入后应清理的过期条目:同批次成员里 URL 不在新导入集合 [incomingUrls] 中的。
     * 批次为空、或新导入覆盖了全部旧成员时返回空表。
     */
    fun staleMembers(all: List<Subscription>, batchId: String?, incomingUrls: Set<String>): List<Subscription> {
        if (batchId.isNullOrEmpty()) return emptyList()
        return all.filter { batchId == it.batchId && it.url !in incomingUrls }
    }

    /**
     * 收尾清扫(整批更新用):本次导入涉及的**每个批次**里,地址不在新清单 [incomingUrls] 中的旧条目。
     * 包批次 additionally 按解压目录精确清扫 —— 老数据成员没有 batchId,靠目录认(见 [packageDirOfBatchKey])。
     */
    fun staleEntriesOf(all: List<Subscription>, batchIds: Collection<String>,
                       incomingUrls: Set<String>): List<Subscription> {
        val stale = LinkedHashSet<Subscription>()
        for (batchId in batchIds) {
            if (batchId.isEmpty()) continue
            stale.addAll(staleMembers(all, batchId, incomingUrls))
            val dir = packageDirOfBatchKey(batchId)
            if (dir != null) {
                for (s in all) {
                    if (s.url !in incomingUrls && packageDirNameOf(s.url) == dir) stale.add(s)
                }
            }
        }
        return stale.toList()
    }

    /** 批次展示名(空名/纯空白兜底为 [fallback]) */
    fun displayNameOf(batchName: String?, fallback: String = "未命名批次"): String {
        val trimmed = batchName?.trim()
        return if (trimmed.isNullOrEmpty()) fallback else trimmed
    }
}

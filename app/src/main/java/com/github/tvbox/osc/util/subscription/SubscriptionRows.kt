package com.github.tvbox.osc.util.subscription

import com.github.tvbox.osc.bean.Subscription

/**
 * 订阅列表的行模型(手风琴分组后的平铺结构):
 * 组头 [GroupHeader] + 订阅条目 [Item],组内条目默认展开、可整组收起。
 *
 * 分组键解析顺序(见 [groupKeyOf]):
 *  1. 条目持久化的 batchId(新导入写入);
 *  2. 由本地包地址派生的包目录键(兼容老数据:以前导入的包条目没有 batchId);
 *  3. 都不命中 = 平铺条目,不建组。
 */
sealed class SubscriptionRow {

    /** 订阅条目(复用 item_subscription 布局) */
    data class Item(val subscription: Subscription) : SubscriptionRow()

    /**
     * 批次组头。
     * @param batchKey 分组键(持久 batchId 或派生包键),收起状态按它记
     * @param batchName 展示名(已兜底,不会为空)
     * @param batchTime 组内最新导入时间(毫秒;0 = 未知)
     * @param count 组内源个数
     * @param expanded 当前是否展开
     */
    data class GroupHeader(
        val batchKey: String,
        val batchName: String,
        val batchTime: Long,
        val count: Int,
        val expanded: Boolean
    ) : SubscriptionRow()
}

/** 条目的分组键:持久 batchId 优先,其次本地包派生键;都没有返回 null(平铺) */
fun groupKeyOf(sub: Subscription): String? {
    val id = sub.batchId
    if (!id.isNullOrEmpty()) return id
    return SubscriptionBatches.batchKeyOf(sub.url)
}

/**
 * 把去重排序后的订阅列表展平成分组行:
 * 每个批次的条目**聚拢成一个块**,块占该组第一个成员原来的位置(平铺条目不受影响);
 * collapsedKeys 里的组只留组头,组内条目不占行。
 */
fun buildSubscriptionRows(
    subscriptions: List<Subscription>,
    collapsedKeys: Set<String> = emptySet()
): List<SubscriptionRow> {
    val groups = LinkedHashMap<String, GroupAccumulator>()
    for (sub in subscriptions) {
        val key = groupKeyOf(sub) ?: continue
        val acc = groups.getOrPut(key) { GroupAccumulator() }
        acc.members.add(sub)
        if (sub.batchName?.isNotBlank() == true && acc.nameFromMember == null) acc.nameFromMember = sub.batchName
        if (sub.batchTime > acc.latestTime) acc.latestTime = sub.batchTime
    }
    val out = ArrayList<SubscriptionRow>(subscriptions.size + groups.size)
    val emitted = HashSet<String>()
    for (sub in subscriptions) {
        val key = groupKeyOf(sub)
        if (key == null) {
            out.add(SubscriptionRow.Item(sub))
            continue
        }
        if (!emitted.add(key)) continue   // 同组后续成员在组块里一次出完
        val acc = groups.getValue(key)
        val collapsed = key in collapsedKeys
        out.add(SubscriptionRow.GroupHeader(
            batchKey = key,
            batchName = SubscriptionBatches.displayNameOf(
                acc.nameFromMember ?: SubscriptionBatches.packageBaseNameOf(acc.members.first().url)
            ),
            batchTime = acc.latestTime,
            count = acc.members.size,
            expanded = !collapsed
        ))
        if (!collapsed) for (member in acc.members) out.add(SubscriptionRow.Item(member))
    }
    return out
}

private class GroupAccumulator {
    val members = ArrayList<Subscription>()
    var nameFromMember: String? = null
    var latestTime = 0L
}

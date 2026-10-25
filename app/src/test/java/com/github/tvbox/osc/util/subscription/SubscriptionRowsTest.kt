package com.github.tvbox.osc.util.subscription

import com.github.tvbox.osc.bean.Subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRowsTest {

    private fun sub(name: String, url: String) = Subscription(name, url)

    private fun packageSub(name: String, dir: String, file: String = "api.json") =
        sub(name, "clan://localhost/Android/data/com.github.tvbox.osc/files/local_packages/$dir/$file")

    private fun batchSub(name: String, url: String, batchId: String, batchName: String? = null, batchTime: Long = 0L) =
        sub(name, url).apply {
            this.batchId = batchId
            this.batchName = batchName
            this.batchTime = batchTime
        }

    private fun rowsOf(subs: List<Subscription>, collapsed: Set<String> = emptySet()) =
        buildSubscriptionRows(subs, collapsed)

    @Test
    fun flatSubscriptionsStayFlatInOrder() {
        val a = sub("A", "https://a.com")
        val b = sub("B", "https://b.com")
        val rows = rowsOf(listOf(a, b))
        assertEquals(listOf(SubscriptionRow.Item(a), SubscriptionRow.Item(b)), rows)
    }

    @Test
    fun batchIdGroupsWithHeaderAtFirstMemberPosition() {
        val a = batchSub("A", "https://a.com", "b1", "多仓A", 100L)
        val flat = sub("F", "https://f.com")
        val b = batchSub("B", "https://b.com", "b1", "多仓A", 300L)
        // 组块聚拢在第一个成员处;平铺条目顺延
        val rows = rowsOf(listOf(a, flat, b))
        assertEquals(4, rows.size)
        val header = rows[0] as SubscriptionRow.GroupHeader
        assertEquals("b1", header.batchKey)
        assertEquals("多仓A", header.batchName)
        assertEquals(2, header.count)
        assertEquals(300L, header.batchTime)
        assertTrue(header.expanded)
        assertEquals(SubscriptionRow.Item(a), rows[1])
        assertEquals(SubscriptionRow.Item(b), rows[2])
        assertEquals(SubscriptionRow.Item(flat), rows[3])
    }

    @Test
    fun legacyPackageSubscriptionsGroupByDerivedKey() {
        // 老数据没有 batchId:按包目录键归组,组名兜底为包基名
        val a = packageSub("A", "TVBoxOSC_1a2b3c4d", "a.json")
        val b = packageSub("B", "TVBoxOSC_1a2b3c4d", "b.json")
        val c = packageSub("C", "FongMi_9f8e7d6c", "c.json")
        val rows = rowsOf(listOf(a, b, c))
        assertEquals(5, rows.size)
        val first = rows[0] as SubscriptionRow.GroupHeader
        assertEquals("pkg:TVBoxOSC_1a2b3c4d", first.batchKey)
        assertEquals("TVBoxOSC", first.batchName)
        assertEquals(2, first.count)
        val second = rows[3] as SubscriptionRow.GroupHeader
        assertEquals("pkg:FongMi_9f8e7d6c", second.batchKey)
        assertEquals("FongMi", second.batchName)
        assertEquals(1, second.count)
    }

    @Test
    fun batchIdTakesPriorityOverDerivedPackageKey() {
        val a = packageSub("A", "P_11111111", "a.json").apply { batchId = "custom" }
        val b = packageSub("B", "P_11111111", "b.json").apply { batchId = "custom" }
        val rows = rowsOf(listOf(a, b))
        assertEquals(3, rows.size)
        assertEquals("custom", (rows[0] as SubscriptionRow.GroupHeader).batchKey)
    }

    @Test
    fun collapsedGroupKeepsHeaderOnly() {
        val a = batchSub("A", "https://a.com", "b1", "多仓A")
        val b = batchSub("B", "https://b.com", "b1", "多仓A")
        val flat = sub("F", "https://f.com")
        val rows = rowsOf(listOf(a, b, flat), collapsed = setOf("b1"))
        assertEquals(2, rows.size)
        val header = rows[0] as SubscriptionRow.GroupHeader
        assertEquals("b1", header.batchKey)
        assertEquals(2, header.count)
        assertTrue(!header.expanded)
        assertEquals(SubscriptionRow.Item(flat), rows[1])
    }

    @Test
    fun singleMemberBatchStillGetsHeader() {
        val a = batchSub("A", "https://a.com", "b1", "单源批")
        val rows = rowsOf(listOf(a))
        assertEquals(2, rows.size)
        assertEquals(1, (rows[0] as SubscriptionRow.GroupHeader).count)
    }

    @Test
    fun headerNameFallsBackWhenBatchNameBlank() {
        val a = batchSub("A", "https://a.com", "b1", "  ")
        val b = batchSub("B", "https://b.com", "b1", null)
        val rows = rowsOf(listOf(a, b))
        assertEquals("未命名批次", (rows[0] as SubscriptionRow.GroupHeader).batchName)
    }

    @Test
    fun interleavedGroupsCollapseIntoContiguousBlocks() {
        // 两个批次的条目交错出现:各自聚拢成块,块占第一个成员的位置
        val a1 = batchSub("A1", "https://a1.com", "b1", "多仓A")
        val b1 = batchSub("B1", "https://b1.com", "b2", "多仓B")
        val a2 = batchSub("A2", "https://a2.com", "b1", "多仓A")
        val b2 = batchSub("B2", "https://b2.com", "b2", "多仓B")
        val rows = rowsOf(listOf(a1, b1, a2, b2))
        assertEquals(
            listOf(
                SubscriptionRow.GroupHeader("b1", "多仓A", 0L, 2, true),
                SubscriptionRow.Item(a1),
                SubscriptionRow.Item(a2),
                SubscriptionRow.GroupHeader("b2", "多仓B", 0L, 2, true),
                SubscriptionRow.Item(b1),
                SubscriptionRow.Item(b2)
            ),
            rows
        )
    }

    @Test
    fun emptyListBuildsEmptyRows() {
        assertTrue(rowsOf(emptyList()).isEmpty())
    }
}

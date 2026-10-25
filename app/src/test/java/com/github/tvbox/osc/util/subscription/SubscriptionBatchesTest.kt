package com.github.tvbox.osc.util.subscription

import com.github.tvbox.osc.bean.Subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionBatchesTest {

    private fun sub(name: String, url: String, origin: String = Subscription.ORIGIN_DIRECT) =
        Subscription(name, url, origin)

    private fun packageSub(name: String, dir: String, file: String = "api.json") =
        sub(name, "clan://localhost/Android/data/com.github.tvbox.osc/files/local_packages/$dir/$file")

    @Test
    fun newBatchIdIsTimestampPrefixedAndMonotonic() {
        assertEquals("b1700000000000", SubscriptionBatches.newBatchId(1700000000000L))
        assertTrue(SubscriptionBatches.newBatchId(1L) != SubscriptionBatches.newBatchId(2L))
    }

    @Test
    fun packageDirNameOfOnlyMatchesLocalPackagesSegment() {
        assertEquals("TVBoxOSC_1a2b3c4d", SubscriptionBatches.packageDirNameOf(
            "clan://localhost/Android/data/com.github.tvbox.osc/files/local_packages/TVBoxOSC_1a2b3c4d/x/api.json"))
        // 目录基名后直接结束(没有文件段)也算命中
        assertEquals("FongMi_9f8e7d6c", SubscriptionBatches.packageDirNameOf(
            "clan://localhost/storage/emulated/0/Android/data/com.github.tvbox.osc/files/local_packages/FongMi_9f8e7d6c"))
        // 非本地包地址一律不认
        assertNull(SubscriptionBatches.packageDirNameOf("https://example.com/api.json"))
        assertNull(SubscriptionBatches.packageDirNameOf(
            "clan://localhost/Android/data/com.github.tvbox.osc/files/subscription_import/abc_1234.txt"))
        assertNull(SubscriptionBatches.packageDirNameOf(null))
        assertNull(SubscriptionBatches.packageDirNameOf(""))
        assertNull(SubscriptionBatches.packageDirNameOf(
            "clan://localhost/Android/data/com.github.tvbox.osc/files/local_packages/"))
    }

    @Test
    fun packageBaseNameOfStripsEightHexDigestSuffix() {
        assertEquals("TVBoxOSC", SubscriptionBatches.packageBaseNameOf(
            "clan://localhost/x/local_packages/TVBoxOSC_1a2b3c4d/a.json"))
        // 基名自带下划线:只脱末尾 8 位十六进制
        assertEquals("道长仓库_2024", SubscriptionBatches.packageBaseNameOf(
            "clan://localhost/x/local_packages/道长仓库_2024_deadbeef/a.json"))
        // 后缀不是 8 位十六进制时保持原样
        assertEquals("TVBoxOSC_xyz", SubscriptionBatches.packageBaseNameOf(
            "clan://localhost/x/local_packages/TVBoxOSC_xyz/a.json"))
        assertNull(SubscriptionBatches.packageBaseNameOf("https://example.com"))
    }

    @Test
    fun batchKeyOfPrefixesPackageDirName() {
        assertEquals("pkg:TVBoxOSC_1a2b3c4d", SubscriptionBatches.batchKeyOf(
            "clan://localhost/x/local_packages/TVBoxOSC_1a2b3c4d/a.json"))
        assertNull(SubscriptionBatches.batchKeyOf("clan://localhost/x/subscription_import/a.txt"))
        assertNull(SubscriptionBatches.batchKeyOf("https://example.com"))
    }

    @Test
    fun batchKeyDirRoundTripAndDirOfKey() {
        assertEquals("pkg:P_11111111", SubscriptionBatches.batchKeyOfDir("P_11111111"))
        assertEquals("P_11111111", SubscriptionBatches.packageDirOfBatchKey("pkg:P_11111111"))
        assertNull(SubscriptionBatches.packageDirOfBatchKey("b1700000000000"))
        assertNull(SubscriptionBatches.packageDirOfBatchKey("pkg:"))
        assertNull(SubscriptionBatches.packageDirOfBatchKey(null))
    }

    @Test
    fun stripDirDigestRemovesTrailingEightHexSuffix() {
        assertEquals("TVBoxOSC", SubscriptionBatches.stripDirDigest("TVBoxOSC_1a2b3c4d"))
        assertEquals("道长仓库_2024", SubscriptionBatches.stripDirDigest("道长仓库_2024_deadbeef"))
        assertEquals("TVBoxOSC_xyz", SubscriptionBatches.stripDirDigest("TVBoxOSC_xyz"))
    }

    @Test
    fun staleEntriesOfSweepsBatchMembersAndPackageDirs() {
        // 批内带 batchId 的成员
        val member = packageSub("A", "P_11111111", "a.json").apply { batchId = "pkg:P_11111111" }
        // 老数据:同目录但没有 batchId
        val legacy = packageSub("OLD", "P_11111111", "old.json")
        // 别的包的条目:不能扫
        val other = packageSub("B", "Q_22222222", "b.json").apply { batchId = "pkg:Q_22222222" }
        // 普通网络批次的成员
        val web = sub("W", "https://w.com").apply { batchId = "b1" }
        val all = listOf(member, legacy, other, web)
        val incoming = setOf(member.url)
        // member 地址在 incoming 里保留;legacy 不在 → 扫;other/web 不动
        assertEquals(listOf(legacy), SubscriptionBatches.staleEntriesOf(all, listOf("pkg:P_11111111"), incoming))
        // 非包批次:只按成员扫
        assertEquals(emptyList<Subscription>(),
            SubscriptionBatches.staleEntriesOf(all, listOf("b1"), setOf("https://w.com")))
        assertEquals(listOf(web),
            SubscriptionBatches.staleEntriesOf(all, listOf("b1"), emptySet()))
        // 多个批次一次扫
        assertEquals(listOf(legacy, web),
            SubscriptionBatches.staleEntriesOf(all, listOf("pkg:P_11111111", "b1"), incoming))
    }

    @Test
    fun membersOfFiltersByBatchIdAndIgnoresBlank() {
        val a = packageSub("A", "P_11111111").apply { batchId = "b1" }
        val b = packageSub("B", "P_11111111").apply { batchId = "b1" }
        val c = sub("C", "https://c.com")
        val all = listOf(a, b, c)
        assertEquals(listOf(a, b), SubscriptionBatches.membersOf(all, "b1"))
        assertTrue(SubscriptionBatches.membersOf(all, null).isEmpty())
        assertTrue(SubscriptionBatches.membersOf(all, "").isEmpty())
        assertTrue(SubscriptionBatches.membersOf(all, "b2").isEmpty())
    }

    @Test
    fun staleMembersKeepsCoveredAndDropsVanished() {
        val a = packageSub("A", "P_11111111", "a.json").apply { batchId = "b1" }
        val b = packageSub("B", "P_11111111", "b.json").apply { batchId = "b1" }
        val c = packageSub("C", "P_22222222", "c.json").apply { batchId = "b2" }
        val all = listOf(a, b, c)
        // B 的地址不在新导入集合里 → 过期;A 仍在;别的批次不受影响
        assertEquals(listOf(b), SubscriptionBatches.staleMembers(all, "b1", setOf(a.url)))
        assertTrue(SubscriptionBatches.staleMembers(all, "b1", setOf(a.url, b.url)).isEmpty())
        assertTrue(SubscriptionBatches.staleMembers(all, null, emptySet()).isEmpty())
        assertTrue(SubscriptionBatches.staleMembers(all, "b9", emptySet()).isEmpty())
    }

    @Test
    fun displayNameOfTrimsAndFallsBack() {
        assertEquals("道长仓库", SubscriptionBatches.displayNameOf("  道长仓库  "))
        assertEquals("未命名批次", SubscriptionBatches.displayNameOf(null))
        assertEquals("未命名批次", SubscriptionBatches.displayNameOf("   "))
        assertEquals("兜底", SubscriptionBatches.displayNameOf("", "兜底"))
    }
}

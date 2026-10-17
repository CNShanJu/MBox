package com.github.tvbox.osc.config

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import com.github.tvbox.osc.player.api.PlayCacheMigration
import com.github.tvbox.osc.player.api.PlayConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

/** 与 PrefsDataStore 同包以复用 editor fake；不初始化 Android/DataStore 磁盘环境。 */
class PlayCacheMigrationTest {
    private val editorField = PrefsDataStore::class.java.getDeclaredField("editor").apply { isAccessible = true }
    private val cacheField = PrefsDataStore::class.java.getDeclaredField("cache").apply { isAccessible = true }
    private var oldEditor: Any? = null
    private var oldCache: Any? = null
    private lateinit var disk: FakeStore

    private val legacy = PlayCacheMigration.LEGACY_KEY
    private val enabled = PlayCacheMigration.ENABLED_KEY
    private val whilePlay = PlayCacheMigration.WHILE_PLAY_KEY

    @Before
    fun installStore() {
        oldEditor = editorField.get(null)
        oldCache = cacheField.get(null)
        seed(emptyMap())
    }

    @After
    fun restoreStore() {
        editorField.set(null, oldEditor)
        cacheField.set(null, oldCache)
    }

    @Test
    fun allMissingFalseTrueCombinationsUseNewThenLegacyThenDefaults() {
        val options = listOf(null, false, true)
        for (old in options) for (master in options) for (whileValue in options) {
            val initial = linkedMapOf<String, Boolean>()
            old?.let { initial[legacy] = it }
            master?.let { initial[enabled] = it }
            whileValue?.let { initial[whilePlay] = it }
            seed(initial)
            val expectedMaster = master ?: old ?: false
            val expectedWhile = whileValue ?: old ?: true

            assertEquals(PlayCacheMigration.Snapshot(expectedMaster, expectedWhile),
                PlayCacheMigration.resolve(master, whileValue, old))
            repeat(3) { assertSelection(expectedMaster, expectedWhile) }
            assertEquals(0, disk.attempts) // getter 热路径不能落盘
            assertEquals(initial, disk.snapshot())

            assertTrue(PlayConfig.migrateLegacyCacheConfig())
            assertEquals(mapOf(enabled to expectedMaster, whilePlay to expectedWhile), disk.snapshot())
            assertFalse(PrefsDataStore.contains(legacy))
            assertSelection(expectedMaster, expectedWhile)
            val attempts = disk.attempts
            assertTrue(PlayConfig.migrateLegacyCacheConfig())
            assertEquals(attempts, disk.attempts) // 迁移完成后幂等，无重复写盘
        }
    }

    @Test
    fun oldBackupOverridesExistingNewSettingsInBothDirections() {
        for (old in listOf(false, true)) {
            seed(mapOf(enabled to !old, whilePlay to !old))
            val backup = mapOf<String, Any>(legacy to old)
            PlayConfig.importConfig(backup)
            assertSelection(old, old)
            assertEquals(mapOf(enabled to old, whilePlay to old), disk.snapshot())
            assertEquals(mapOf(legacy to old), backup) // 不污染调用方备份
            assertEquals(1, disk.attempts) // 两个新键一次提交
        }
    }

    @Test
    fun incomingNewKeysWinConflictsRegardlessOfMapOrder() {
        for (old in listOf(false, true)) {
            val entries = listOf(legacy to old, enabled to !old, whilePlay to !old)
            for (ordered in listOf(entries, entries.reversed())) {
                seed(mapOf(enabled to old, whilePlay to old))
                PlayConfig.importConfig(ordered.toMap())
                assertSelection(!old, !old)
                assertEquals(mapOf(enabled to !old, whilePlay to !old), disk.snapshot())
            }
        }
    }

    @Test
    fun incomingLegacyOnlyFillsMissingIncomingNewKey() {
        for (old in listOf(false, true)) {
            seed(mapOf(enabled to old, whilePlay to !old))
            PlayConfig.importConfig(mapOf(legacy to old, enabled to !old))
            assertSelection(!old, old)

            seed(mapOf(enabled to !old, whilePlay to old))
            PlayConfig.importConfig(mapOf(legacy to old, whilePlay to !old))
            assertSelection(old, !old)
        }
    }

    @Test
    fun partialOrEmptyBackupDoesNotResetMissingFields() {
        seed(mapOf(enabled to true, whilePlay to false))
        PlayConfig.importConfig(emptyMap())
        PlayConfig.importConfig(mapOf(legacy to "false", enabled to 1, whilePlay to "true"))
        assertSelection(true, false)
        assertEquals(0, disk.attempts)

        PlayConfig.importConfig(mapOf(enabled to false))
        assertSelection(false, false)
        PlayConfig.importConfig(mapOf(whilePlay to true))
        assertSelection(false, true)
        assertFalse(PrefsDataStore.contains(legacy))
    }

    @Test
    fun equalValueSettersStillPersistMissingKeysAndRetireLegacy() {
        seed(mapOf(legacy to true))
        PlayConfig.setPlayCacheEnabled(true)
        assertEquals(mapOf(enabled to true, whilePlay to true), disk.snapshot())

        seed(mapOf(legacy to false))
        PlayConfig.setCacheWhilePlay(false)
        assertEquals(mapOf(enabled to false, whilePlay to false), disk.snapshot())

        seed(emptyMap())
        PlayConfig.setPlayCacheEnabled(false)
        assertEquals(mapOf(enabled to false, whilePlay to true), disk.snapshot())
    }

    @Test
    fun whilePlayChangesDoNotRewriteLegacyOrChangeMaster() {
        seed(mapOf(legacy to true))
        var events = 0
        val listener = PlayConfig.Listener {
            events++
            assertSelection(true, false)
        }
        PlayConfig.subscribe(listener)
        try {
            PlayConfig.setCacheWhilePlay(false)
            assertSelection(true, false)
            assertEquals(mapOf(enabled to true, whilePlay to false), disk.snapshot())
            assertEquals(1, events)
            PlayConfig.setCacheWhilePlay(false)
            assertEquals(1, events)
        } finally {
            PlayConfig.unsubscribe(listener)
        }
    }

    @Test
    fun exportOnlyIncludesNormalizedNewCacheKeysWithoutWriting() {
        for (old in listOf(null, false, true)) {
            seed(if (old == null) emptyMap() else mapOf(legacy to old))
            val exported = PlayConfig.exportConfig()
            assertFalse(exported.containsKey(legacy))
            assertEquals(old ?: false, exported[enabled])
            assertEquals(old ?: true, exported[whilePlay])
            assertEquals(0, disk.attempts)
        }
    }

    @Test
    fun failedMigrationWriteKeepsLegacyAndRetriesSafely() {
        seed(mapOf(legacy to false, enabled to true))
        val before = disk.snapshot()
        disk.failures.add(1)
        assertFalse(PlayConfig.migrateLegacyCacheConfig())
        assertEquals(before, disk.snapshot())
        assertTrue(PrefsDataStore.contains(legacy))
        assertFalse(PrefsDataStore.contains(whilePlay))
        assertEquals(1, disk.attempts) // 写入失败后不尝试删除
        assertSelection(true, false)

        assertTrue(PlayConfig.migrateLegacyCacheConfig())
        assertEquals(mapOf(enabled to true, whilePlay to false), disk.snapshot())
    }

    @Test
    fun failedImportDoesNotPartiallyApplyOrDeleteLegacyOrNotify() {
        seed(mapOf(legacy to true, enabled to false, whilePlay to true))
        val before = disk.snapshot()
        disk.failures.add(1)
        var events = 0
        val listener = PlayConfig.Listener { events++ }
        PlayConfig.subscribe(listener)
        try {
            PlayConfig.importConfig(mapOf(legacy to false))
            assertEquals(before, disk.snapshot())
            assertSelection(false, true)
            assertTrue(PrefsDataStore.contains(legacy))
            assertEquals(1, disk.attempts)
            assertEquals(0, events)
        } finally {
            PlayConfig.unsubscribe(listener)
        }
    }

    @Test
    fun failedDeletionRetainsPersistedNewValuesAndReportsIncompleteMigration() {
        seed(mapOf(legacy to true))
        disk.failures.add(2)
        assertFalse(PlayConfig.migrateLegacyCacheConfig())
        assertEquals(mapOf(legacy to true, enabled to true, whilePlay to true), disk.snapshot())
        assertSelection(true, true)
        assertTrue(PrefsDataStore.contains(legacy))
        assertTrue(PlayConfig.migrateLegacyCacheConfig())
        assertEquals(3, disk.attempts) // 重试只删除旧键，不重写新键
        assertEquals(mapOf(enabled to true, whilePlay to true), disk.snapshot())
    }

    @Test
    fun uninitializedStoreCannotClaimMigrationSucceededOrDropLegacy() {
        seed(mapOf(legacy to false))
        editorField.set(null, null)
        assertFalse(PlayConfig.migrateLegacyCacheConfig())
        assertTrue(PrefsDataStore.contains(legacy))
        assertFalse(PrefsDataStore.contains(enabled))
        assertFalse(PrefsDataStore.contains(whilePlay))
        assertEquals(mapOf(legacy to false), disk.snapshot())
    }

    @Test
    fun migrationParticipatesInExistingRollbackScopeWithoutNesting() {
        seed(mapOf(legacy to false))
        assertThrows(IllegalStateException::class.java) {
            PrefsDataStore.runWithRollback<Unit> {
                assertTrue(PlayConfig.migrateLegacyCacheConfig())
                throw IllegalStateException("later import failed")
            }
        }
        assertEquals(mapOf(legacy to false), disk.snapshot())
        assertTrue(PrefsDataStore.contains(legacy))
        assertFalse(PrefsDataStore.contains(enabled))
        assertFalse(PrefsDataStore.contains(whilePlay))
    }

    private fun seed(values: Map<String, Boolean>) {
        disk = FakeStore(values)
        cacheField.set(null, ConcurrentHashMap<String, Any>(values))
        editorField.set(null, disk)
    }

    private fun assertSelection(master: Boolean, whileValue: Boolean) {
        assertEquals(master, PlayConfig.isPlayCacheEnabled())
        assertEquals(whileValue, PlayConfig.isCacheWhilePlay())
    }

    private class FakeStore(initial: Map<String, Boolean>) : PrefsDataStore.EditStore {
        private var state: Preferences = emptyPreferences().toMutablePreferences().apply {
            initial.forEach { (key, value) -> this[booleanPreferencesKey(key)] = value }
        }.toPreferences()
        var attempts = 0
        val failures = mutableSetOf<Int>()

        override fun update(edit: Function<Preferences, MutablePreferences>) {
            attempts++
            if (attempts in failures) throw IllegalStateException("injected write $attempts")
            state = edit.apply(state).toPreferences()
        }

        fun snapshot(): Map<String, Any> = state.asMap().entries.associate { it.key.name to it.value }
    }
}

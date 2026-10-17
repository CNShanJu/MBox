package com.github.tvbox.osc.ui.dialog

import com.github.tvbox.osc.util.subscription.withoutLiveHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveHistorySnapshotTest {
    @Test
    fun openingHistoryWithUnconfirmedDraftDoesNotAddOrReorderIt() {
        val stored = arrayListOf("current", "older")
        val snapshot = LiveHistorySnapshot(stored)
        val visible = snapshot.entries("unconfirmed draft")

        assertEquals(listOf("current", "older"), visible)
        assertEquals(listOf("current", "older"), snapshot.entries())
        assertEquals(listOf("current", "older"), stored)
    }

    @Test
    fun selectingHistoryThenCancellingLeavesHistoryUntouched() {
        val stored = arrayListOf("current", "older")
        val snapshot = LiveHistorySnapshot(stored)
        val selectedInput = snapshot.entries("current").single()

        assertEquals("older", selectedInput)
        assertEquals(listOf("current", "older"), snapshot.entries())
        assertEquals(listOf("current", "older"), stored)
    }

    @Test
    fun confirmedDeletionPreservesHiddenCurrentAndNewerHostEntries() {
        var stored = arrayListOf("current", "older")
        val editor = LiveHistorySnapshot(stored)
        val drawer = LiveHistorySnapshot(editor.entries("current"))
        stored.add("added after opening")
        val deleted = mutableListOf<String>()

        assertTrue(drawer.deleteConfirmed("older") { url ->
            editor.deleteConfirmed(url) { target ->
                deleted += target
                stored = withoutLiveHistoryEntry(stored, target)
                true
            }
        })

        assertEquals(listOf("older"), deleted)
        assertEquals(listOf("current", "added after opening"), stored)
        assertEquals(listOf("current"), editor.entries())
        assertTrue(drawer.entries().isEmpty())
    }

    @Test
    fun failedCommitLeavesSnapshotUnchanged() {
        val snapshot = LiveHistorySnapshot(listOf("current", "older"))
        var calls = 0

        assertFalse(snapshot.deleteConfirmed("older") {
            calls++
            false
        })

        assertEquals(1, calls)
        assertEquals(listOf("current", "older"), snapshot.entries())
    }

    @Test
    fun hiddenOrUnknownRowCannotTriggerDeletion() {
        val drawer = LiveHistorySnapshot(LiveHistorySnapshot(listOf("current", "older")).entries("current"))
        var calls = 0
        val delete: (String) -> Boolean = { calls++; true }

        assertFalse(drawer.deleteConfirmed("current", delete))
        assertFalse(drawer.deleteConfirmed("unconfirmed draft", delete))
        assertEquals(0, calls)
        assertEquals(listOf("older"), drawer.entries())
    }

    @Test
    fun callerAndReturnedListsCannotMutateSnapshot() {
        val input = arrayListOf("current", "older")
        val snapshot = LiveHistorySnapshot(input)
        input.clear()
        snapshot.entries().toMutableList().clear()

        assertEquals(listOf("current", "older"), snapshot.entries())
        assertTrue(snapshot.deleteConfirmed("older") { true })
        assertEquals(listOf("current"), snapshot.entries())
        assertTrue(input.isEmpty())
    }
}

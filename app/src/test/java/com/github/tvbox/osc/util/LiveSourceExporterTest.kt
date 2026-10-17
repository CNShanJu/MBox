package com.github.tvbox.osc.util

import com.github.tvbox.osc.util.LiveSourceExporter.CurrentSource
import com.github.tvbox.osc.util.LiveSourceExporter.Origin
import com.github.tvbox.osc.util.LiveSourceExporter.Snapshot
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSourceExporterTest {
    private val userUrl = "https://user.example/live.m3u"
    private val subscriptionUrl = "https://subscription.example/live.m3u"

    @Test
    fun subscriptionCurrentIsNotExportedAsUserSource() {
        val payload = payload(listOf(userUrl), CurrentSource(subscriptionUrl, Origin.SUBSCRIPTION))

        assertEquals(listOf(userUrl), urls(payload))
        assertFalse(payload.has("current"))
    }

    @Test
    fun userHistorySurvivesMatchingSubscriptionUrl() {
        val snapshot = Snapshot(listOf(subscriptionUrl), mapOf(subscriptionUrl to "自建同址"),
            CurrentSource(subscriptionUrl, Origin.SUBSCRIPTION))
        val payload = LiveSourceExporter.payload(snapshot, 123L)

        assertEquals(listOf(subscriptionUrl), urls(payload))
        assertEquals("自建同址", payload.getAsJsonArray("items")[0].asJsonObject["name"].asString)
        assertFalse(payload.has("current"))
    }

    @Test
    fun confirmedUserAndDefaultCurrentCanBeAddedWithoutHistory() {
        for (origin in listOf(Origin.USER, Origin.DEFAULT)) {
            val payload = payload(emptyList(), CurrentSource(userUrl, origin))
            assertEquals(listOf(userUrl), urls(payload))
            assertEquals(userUrl, payload["current"].asString)
        }
    }

    @Test
    fun unknownCurrentIsNotGuessedFromItsUrl() {
        val payload = payload(emptyList(), CurrentSource(userUrl, Origin.UNKNOWN))

        assertTrue(urls(payload).isEmpty())
        assertFalse(payload.has("current"))
    }

    @Test
    fun emptyOrMissingCurrentDoesNotCreateDanglingPointer() {
        for (current in listOf(null, CurrentSource("  ", Origin.DEFAULT))) {
            val payload = payload(listOf(userUrl), current)
            assertEquals(listOf(userUrl), urls(payload))
            assertFalse(payload.has("current"))
        }
    }

    @Test
    fun normalizesAndDeduplicatesWhilePreservingUserOrder() {
        val payload = payload(listOf(" $userUrl ", "", userUrl, subscriptionUrl),
            CurrentSource(" $userUrl ", Origin.USER))

        assertEquals(listOf(userUrl, subscriptionUrl), urls(payload))
        assertEquals(userUrl, payload["current"].asString)
        assertEquals("user.example/live.m3u", payload.getAsJsonArray("items")[0].asJsonObject["name"].asString)
    }

    @Test
    fun snapshotDoesNotObserveLaterConfigCollectionChanges() {
        val history = arrayListOf(userUrl)
        val names = linkedMapOf(userUrl to "原名称")
        val snapshot = Snapshot(history, names, CurrentSource(userUrl, Origin.USER))
        history.clear()
        names[userUrl] = "后来的名称"

        val payload = LiveSourceExporter.payload(snapshot, 123L)
        assertEquals(listOf(userUrl), urls(payload))
        assertEquals("原名称", payload.getAsJsonArray("items")[0].asJsonObject["name"].asString)
        assertEquals(123L, payload["createdAt"].asLong)
        assertEquals(1, payload["schema"].asInt)
        assertEquals("live_sources", payload["category"].asString)
    }

    @Test
    fun everyEmittedCurrentBelongsToExportedItems() {
        for (origin in Origin.values()) {
            for (history in listOf(emptyList(), listOf(userUrl), listOf(subscriptionUrl))) {
                val payload = payload(history, CurrentSource(userUrl, origin))
                if (payload.has("current")) assertTrue(payload["current"].asString in urls(payload))
            }
        }
    }

    private fun payload(history: List<String>, current: CurrentSource?): JsonObject =
        LiveSourceExporter.payload(Snapshot(history, emptyMap(), current), 123L)

    private fun urls(payload: JsonObject): List<String> =
        payload.getAsJsonArray("items").map { it.asJsonObject["url"].asString }
}

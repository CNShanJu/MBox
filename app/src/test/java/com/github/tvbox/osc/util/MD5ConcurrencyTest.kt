package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MD5ConcurrencyTest {
    @Test
    fun concurrentCallsDoNotShareDigestState() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val work = (0 until 16).map { lane ->
                Callable {
                    repeat(500) { index ->
                        val value = "lane-$lane-value-$index-".repeat(32)
                        val expected = MessageDigest.getInstance("MD5").digest(value.toByteArray())
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                        assertEquals(expected, MD5.encode(value))
                        assertEquals(expected, MD5.string2MD5(value))
                    }
                }
            }
            executor.invokeAll(work).forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun preservesKnownDigestsAndStringByteConvention() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", MD5.encode(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", MD5.encode("abc"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", MD5.string2MD5("abc"))
    }
}

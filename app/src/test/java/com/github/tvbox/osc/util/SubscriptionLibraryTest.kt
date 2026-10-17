package com.github.tvbox.osc.util

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.reflect.Modifier

class SubscriptionLibraryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun contentDigestKeepsFirstSixteenSha256HexCharacters() {
        assertEquals("ba7816bf8f01cfea", SubscriptionLibrary.contentDigest("abc"))
        assertEquals("e3b0c44298fc1c14", SubscriptionLibrary.contentDigest(""))
    }

    @Test
    fun sanitizeKeepsLegacyReplacementAndExtensionRules() {
        assertEquals("a_b_c_d_e_f_g_h_i_j__k.json",
            SubscriptionLibrary.sanitizeImportName("a/b\\c:d*e?f\"g<h>i|j\u0000 k.json"))
        assertEquals("订阅.JSON", SubscriptionLibrary.sanitizeImportName("订阅.JSON"))
        assertEquals("list.m3u.txt", SubscriptionLibrary.sanitizeImportName("list.m3u"))
        assertEquals("a".repeat(80), SubscriptionLibrary.sanitizeImportName("a".repeat(90)))
    }

    @Test
    fun sanitizeKeepsJavaTrimRatherThanKotlinWhitespaceRules() {
        assertEquals("name.txt", SubscriptionLibrary.sanitizeImportName("\u001fname\u001f"))
        assertEquals("\u00a0name\u00a0.txt", SubscriptionLibrary.sanitizeImportName("\u00a0name\u00a0"))
    }

    @Test
    fun readImportTextKeepsUtf8AndFailureConventions() {
        val file = temporary.newFile("subscription.txt")
        file.writeText("订阅\n频道", Charsets.UTF_8)
        assertEquals("订阅\n频道", SubscriptionLibrary.readImportText(file))
        assertNull(SubscriptionLibrary.readImportText(null))
        assertNull(SubscriptionLibrary.readImportText(File(temporary.root, "missing.txt")))
        RandomAccessFile(file, "rw").use { it.setLength(SubscriptionLibrary.MAX_JUDGE_BYTES + 1) }
        assertNull(SubscriptionLibrary.readImportText(file))
    }

    @Test
    fun javaEntrypointsStayStaticAndDirectoryMethodsDeclareIOException() {
        val type = SubscriptionLibrary::class.java
        val entrypoints = listOf("importDir", "ensureImportDir", "clanPathOf", "libraryFileOf",
            "writeLibraryFile", "deleteLibraryFileOf", "saveJsonFile", "importCopyOf",
            "readImportText", "queryDisplayName", "isUnderPrimaryStorage", "contentDigest", "sanitizeImportName")
        for (name in entrypoints) {
            assertTrue(name, type.declaredMethods.any { it.name == name && Modifier.isStatic(it.modifiers) })
        }
        for (name in listOf("importDir", "ensureImportDir")) {
            val method = type.getDeclaredMethod(name, Context::class.java)
            assertTrue(name, IOException::class.java in method.exceptionTypes)
        }
    }
}

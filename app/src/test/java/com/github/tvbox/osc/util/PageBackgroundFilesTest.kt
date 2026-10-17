package com.github.tvbox.osc.util

import com.github.tvbox.osc.util.cache.BackgroundFileRules
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

class PageBackgroundFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory by lazy { temporary.newFolder("page_bg") }

    @Test
    fun importingMultipleDraftsNeverRemovesCurrentOrAnotherSessionsDraft() {
        val current = File(directory, "custom_bg_123.webp").apply { writeText("current") }
        val otherDraft = File(directory, "custom_bg_other.webp").apply { writeText("other draft") }
        val source = temporary.newFile("source.webp").apply { writeText("new image") }
        val drafts = (1..100).map { PageBackgroundFiles.copyDraft(source, directory) }

        assertEquals(100, drafts.map { it.name }.toSet().size)
        drafts.forEach {
            UUID.fromString(it.name.removePrefix("custom_bg_").removeSuffix(".webp"))
            assertEquals("new image", it.readText())
        }
        assertEquals("current", current.readText())
        assertEquals("other draft", otherDraft.readText())
        assertEquals("new image", source.readText())
        assertEquals(102, directory.listFiles()!!.size)
    }

    @Test
    fun copyingAnExistingManagedImageKeepsItsSource() {
        val current = File(directory, "custom_bg.webp").apply { writeText("saved image") }
        val draft = PageBackgroundFiles.copyDraft(current, directory)
        assertNotEquals(current.canonicalPath, draft.canonicalPath)
        assertEquals(current.readText(), draft.readText())
        // 取消或销毁时无需做文件清理，配置引用的原图一直存在。
        assertTrue(current.isFile)
    }

    @Test
    fun partialWriteFailureRemovesOnlyItsStagingFile() {
        val current = File(directory, "custom_bg_current.webp").apply { writeText("current") }
        try {
            PageBackgroundFiles.stageDraft(directory) { staging ->
                staging.writeText("partial bytes")
                throw IOException("simulated copy failure")
            }
            fail("copy should fail")
        } catch (_: IOException) {
            assertEquals(listOf(current.name), directory.list()!!.toList())
            assertEquals("current", current.readText())
        }
    }

    @Test
    fun emptyCopyIsNotPublishedAndDoesNotLeaveHalfFile() {
        try {
            PageBackgroundFiles.stageDraft(directory) { }
            fail("empty image should fail")
        } catch (_: IOException) {
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun missingSourceDoesNotLeaveHalfFile() {
        try {
            PageBackgroundFiles.copyDraft(File(temporary.root, "missing.webp"), directory)
            fail("missing source should fail")
        } catch (_: IOException) {
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun inProgressCopyCannotBeListedOrDeletedAsAnImage() {
        val themeDir = temporary.newFolder("theme_bg")
        val draft = PageBackgroundFiles.stageDraft(directory) { staging ->
            staging.writeText("complete only after callback returns")
            assertFalse(BackgroundFileRules.isPageImageName(staging.name))
            assertNull(BackgroundFileRules.managedFile(temporary.root, directory, themeDir, staging))
        }
        assertTrue(BackgroundFileRules.isPageImageName(draft.name))
        assertEquals(listOf(draft.name), directory.list()!!.toList())
    }
}

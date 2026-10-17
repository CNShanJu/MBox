package com.github.tvbox.osc.util.cache

import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class BackgroundFileRulesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pageDir by lazy { temporary.newFolder("page_bg") }
    private val themeDir by lazy { temporary.newFolder("theme_bg") }

    private fun image(directory: File, name: String): File = File(directory, name).apply { writeText("image") }
    private fun managed(file: File): File? =
        BackgroundFileRules.managedFile(temporary.root, pageDir, themeDir, file)
    private fun protection(
        current: List<String?> = emptyList(), theme: List<String?> = emptyList(), draft: List<String?> = emptyList()
    ) = BackgroundFileRules.Protection(current, theme, draft)
    private fun delete(file: File, read: () -> BackgroundFileRules.Protection = { protection() }): Boolean =
        BackgroundFileRules.deleteUnreferenced(temporary.root, pageDir, themeDir, file, read)

    @Test
    fun allowsOnlyImageNamesIncludingLegacyAndUniqueCopies() {
        for (name in listOf("custom_bg.webp", "custom_bg_123.png", "custom_bg_abc-def.webp", "CUSTOM_BG_1.JPEG")) {
            assertTrue(name, BackgroundFileRules.isPageImageName(name))
            assertNotNull(managed(image(pageDir, name)))
        }
        for (name in listOf("custom_bg_notes.txt", "custom_bg_1.webp.tmp", "custom_bg_backup", "custom_bgarbitrary.webp", "photo.webp")) {
            val file = image(pageDir, name)
            assertFalse(name, BackgroundFileRules.isPageImageName(name))
            assertNull(managed(file))
            assertFalse(delete(file))
            assertTrue(file.exists())
        }
        assertFalse(BackgroundFileRules.isPageImageName(null))
        assertNotNull(managed(image(themeDir, "abc123.webp")))
        assertNotNull(managed(image(themeDir, "old-theme.JPG")))
        assertNull(managed(image(themeDir, "abc123.webp.tmp")))
        assertNull(managed(image(themeDir, "metadata.json")))
    }

    @Test
    fun rejectsOutsideSiblingNestedDirectoryAndTraversalPaths() {
        val outside = image(temporary.root, "custom_bg_outside.webp")
        val sibling = image(temporary.newFolder("page_bg_backup"), "custom_bg_sibling.webp")
        val nested = image(File(pageDir, "nested").apply { mkdir() }, "custom_bg_nested.webp")
        val directory = File(pageDir, "custom_bg_directory.webp").apply { mkdir() }
        val traversal = File(pageDir, "../custom_bg_outside.webp")
        val safe = image(pageDir, "custom_bg_safe.webp")
        val reentry = File(pageDir, "../page_bg/custom_bg_safe.webp")
        for (file in listOf(outside, sibling, nested, directory, traversal, reentry)) {
            assertNull(file.path, managed(file))
            assertFalse(delete(file))
        }
        assertNull(managed(File("page_bg/custom_bg_relative.webp")))
        assertNull(managed(File(pageDir, "custom_bg_missing.webp")))
        assertTrue(outside.exists())
        assertTrue(safe.exists())
    }

    @Test
    fun protectsSavedCurrentThemeAndUnsavedParentDraftInEitherDirectory() {
        val current = image(pageDir, "custom_bg_current.webp")
        val theme = image(themeDir, "theme.webp")
        val draft = image(pageDir, "custom_bg_draft.webp")
        val snapshot = protection(listOf(current.path), listOf(theme.path), listOf(draft.path))
        assertTrue(snapshot.isCurrentOrDraft(current))
        assertTrue(snapshot.isCurrentOrDraft(draft))
        assertTrue(snapshot.isTheme(theme))
        for (file in listOf(current, theme, draft)) assertFalse(delete(file) { snapshot })
        // 当前设置也可能直接引用主题图库；草稿也可以来自主题图库。
        assertFalse(delete(theme) { protection(current = listOf(theme.path)) })
        assertFalse(delete(theme) { protection(draft = listOf(theme.path)) })
        assertFalse(delete(current) { protection(theme = listOf(current.path)) })
    }

    @Test
    fun canonicalAliasesStillProtectAndSnapshotDoesNotShareMutableDraftCollection() {
        val draft = image(pageDir, "custom_bg_draft.webp")
        val paths = mutableListOf<String?>(File(pageDir, "./${draft.name}").path)
        val snapshot = protection(draft = paths)
        paths.clear()
        assertTrue(snapshot.protects(draft))
        assertFalse(delete(draft) { snapshot })
    }

    @Test
    fun deletionUsesFreshCurrentThemeAndDraftNotTheEarlierOrphanSnapshot() {
        val file = image(pageDir, "custom_bg_reused.webp")
        val stale = protection()
        assertFalse(stale.protects(file))
        for (fresh in listOf(
            protection(current = listOf(file.path)),
            protection(theme = listOf(file.path)),
            protection(draft = listOf(file.path))
        )) {
            var reads = 0
            assertFalse(delete(file) { reads++; fresh })
            assertEquals(1, reads)
            assertTrue(file.isFile)
        }
    }

    @Test
    fun eachDeletionReadsProtectionAgainDuringBatch() {
        val first = image(pageDir, "custom_bg_first.webp")
        val second = image(pageDir, "custom_bg_second.webp")
        var reads = 0
        val read = {
            reads++
            if (reads == 1) protection() else protection(current = listOf(second.path))
        }
        assertTrue(delete(first, read))
        assertFalse(delete(second, read))
        assertEquals(2, reads)
        assertFalse(first.exists())
        assertTrue(second.exists())
    }

    @Test
    fun failedReferenceReadNeverEnablesDeletion() {
        val file = image(pageDir, "custom_bg_keep.webp")
        assertFalse(delete(file) { throw IOException("reference read failed") })
        assertTrue(file.exists())
    }

    @Test
    fun confirmedReplacementProtectsNewImageAndOnlyExplicitCleanupRemovesOldCopy() {
        val old = image(pageDir, "custom_bg_old.webp")
        val draft = image(pageDir, "custom_bg_new.webp")
        val beforeConfirm = protection(current = listOf(old.path), draft = listOf(draft.path))
        assertFalse(delete(old) { beforeConfirm })
        assertFalse(delete(draft) { beforeConfirm })
        val confirmed = protection(current = listOf(draft.path))
        assertTrue(old.exists())
        assertFalse(delete(draft) { confirmed })
        assertTrue(delete(old) { confirmed })
        assertTrue(draft.exists())
    }

    @Test
    fun abandonedDraftIsOnlyRemovedByExplicitCleanupNotByCancellation() {
        val current = image(pageDir, "custom_bg_current.webp")
        val cancelledDraft = image(pageDir, "custom_bg_cancelled.webp")
        val afterCancel = protection(current = listOf(current.path))
        assertTrue(cancelledDraft.exists())
        assertFalse(delete(current) { afterCancel })
        assertTrue(delete(cancelledDraft) { afterCancel })
        assertTrue(current.exists())
    }

    @Test
    fun rejectsImageSymlinkEvenWhenItsTargetIsManaged() {
        val target = image(pageDir, "custom_bg_target.webp")
        val link = File(pageDir, "custom_bg_link.webp")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (unsupported: Exception) {
            assumeNoException("host does not permit symbolic links", unsupported)
        }
        assertNull(managed(link))
        assertFalse(delete(link))
        assertTrue(target.exists())
    }

    @Test
    fun rejectsManagementDirectoryRedirectedOutsideFilesRoot() {
        // 与正式目录同名的外部目录也不能成为删除目标。
        val outsideRoot = temporary.newFolder("outside")
        val outside = File(outsideRoot, "page_bg").apply { mkdir() }
        val target = image(outside, "custom_bg_target.webp")
        val link = File(temporary.root, "page_bg")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (unsupported: Exception) {
            assumeNoException("host does not permit symbolic links", unsupported)
        }
        val candidate = File(link, target.name)
        assertNull(BackgroundFileRules.managedFile(temporary.root, link, themeDir, candidate))
        assertFalse(BackgroundFileRules.deleteUnreferenced(temporary.root, link, themeDir, candidate) { protection() })
        assertTrue(target.exists())
    }
}

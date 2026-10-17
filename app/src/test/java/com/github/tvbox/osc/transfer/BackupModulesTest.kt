package com.github.tvbox.osc.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class BackupModulesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun protectionDataProbePropagatesModuleFailure() {
        assertFalse(BackupModules.anyHasData { false })
        assertTrue(BackupModules.anyHasData { it.id == "room" })
        val error = IOException("unreadable subscription directory")
        assertEquals(error, assertThrows(IOException::class.java) {
            BackupModules.anyHasData { if (it.id == "subs") throw error else false }
        })
    }

    @Test fun nestedSubscriptionFilesCreateAllParentsAndRestoreSuccessfully() {
        val source = temporary.newFolder("source")
        val nested = File(source, "pack/deep/config.json")
        assertTrue(nested.parentFile.mkdirs())
        nested.writeText("nested content")
        File(source, "root.json").writeText("root content")
        val target = File(temporary.root, "target")
        assertEquals(2, SubsBackupFiles.mergeTree(source, target))
        assertEquals("nested content", File(target, "pack/deep/config.json").readText())
        assertEquals("root content", File(target, "root.json").readText())
        val archive = BackupTestFiles.zip(temporary.root, "subs.zip", listOf("local_packages/pack/deep/config.json" to nested))
        val restored = File(temporary.root, "restored")
        assertEquals(1, SubsBackupFiles.importArchive(archive, mapOf(BackupLimits.PACKAGE_ROOT to restored)))
        assertEquals("nested content", File(restored, "pack/deep/config.json").readText())
        SubsBackupFiles.clearDirectory(target)
        assertTrue(target.isDirectory)
        assertTrue(target.listFiles()!!.isEmpty())
    }

    @Test fun copyFailsForUncreatableParentsAndFileDirectoryConflicts() {
        val source = temporary.newFolder("source")
        val nested = File(source, "pack/deep/file")
        assertTrue(nested.parentFile.mkdirs())
        nested.writeText("content")
        val blockedParent = temporary.newFile("blocked-parent")
        assertThrows(IOException::class.java) { SubsBackupFiles.mergeTree(source, File(blockedParent, "target")) }
        val target = temporary.newFolder("target")
        File(target, "pack").writeText("not a directory")
        assertThrows(IOException::class.java) { SubsBackupFiles.mergeTree(source, target) }
        assertEquals("not a directory", File(target, "pack").readText())
        val otherSource = temporary.newFolder("other-source")
        File(otherSource, "file").writeText("new content")
        val otherTarget = temporary.newFolder("other-target")
        assertTrue(File(otherTarget, "file").mkdir())
        assertThrows(IOException::class.java) { SubsBackupFiles.mergeTree(otherSource, otherTarget) }
        assertTrue(File(otherTarget, "file").isDirectory)
    }

    @Test fun copyFailureIsNotReportedAsSuccessfulArchiveImport() {
        val data = temporary.newFile("data")
        data.writeText("source")
        val subs = BackupTestFiles.zip(temporary.root, "subs.zip",
            listOf("subscription_import/nested/config.json" to data, "local_packages/pack/config.json" to data))
        val imported = temporary.newFolder("imported")
        val packages = temporary.newFile("packages-conflict")
        assertThrows(IOException::class.java) {
            SubsBackupFiles.importArchive(subs, mapOf(BackupLimits.IMPORT_ROOT to imported, BackupLimits.PACKAGE_ROOT to packages))
        }
        assertTrue(packages.isFile)
    }

    @Test fun writingToReadOnlyFilePropagatesIOException() {
        val source = temporary.newFolder("source")
        File(source, "config").writeText("new")
        val target = temporary.newFolder("target")
        val locked = File(target, "config")
        locked.writeText("old")
        assertTrue(locked.setReadOnly())
        try {
            // root 用户可能不受文件权限约束，这种环境不能构造真实写入拒绝。
            assumeFalse(locked.canWrite())
            assertThrows(IOException::class.java) { SubsBackupFiles.mergeTree(source, target) }
            assertEquals("old", locked.readText())
        } finally {
            locked.setWritable(true)
        }
    }

    @Test fun unreadableDirectoriesAndDeletionFailuresAreNeverTreatedAsEmpty() {
        val directory = temporary.newFolder("unreadable")
        val unreadable = object : File(directory.path) {
            override fun listFiles(): Array<File>? = null
        }
        assertThrows(IOException::class.java) { SubsBackupFiles.hasAnyFile(unreadable) }
        assertThrows(IOException::class.java) { SubsBackupFiles.clearDirectory(unreadable) }
        assertThrows(IOException::class.java) { SubsBackupFiles.mergeTree(unreadable, File(temporary.root, "target")) }
        val file = File(directory, "undeletable")
        file.writeText("keep")
        val undeletable = object : File(file.path) {
            override fun delete(): Boolean = false
        }
        val failingDelete = object : File(directory.path) {
            override fun listFiles(): Array<File> = arrayOf(undeletable)
        }
        assertThrows(IOException::class.java) { SubsBackupFiles.clearDirectory(failingDelete) }
        assertEquals("keep", file.readText())
        val conflict = temporary.newFile("root-conflict")
        assertThrows(IOException::class.java) { SubsBackupFiles.hasAnyFile(conflict) }
        assertThrows(IOException::class.java) { SubsBackupFiles.clearDirectory(conflict) }
    }

    @Test fun pathsOutsideSourceOrDeletionRootAreRejected() {
        val directory = temporary.newFolder("source")
        val outside = temporary.newFile("outside")
        outside.writeText("untouched")
        val escaped = object : File(directory.path) {
            override fun listFiles(): Array<File> = arrayOf(outside)
        }
        assertThrows(IOException::class.java) { SubsBackupFiles.hasAnyFile(escaped) }
        assertThrows(IOException::class.java) { SubsBackupFiles.clearDirectory(escaped) }
        assertThrows(IOException::class.java) { SubsBackupFiles.mergeTree(escaped, File(temporary.root, "target")) }
        assertEquals("untouched", outside.readText())
        assertThrows(IOException::class.java) { SubsBackupFiles.mergeTree(directory, File(directory, "nested")) }
    }
}

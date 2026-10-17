package com.github.tvbox.osc.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.Deflater

class BackupContentHashTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun binaryDigestConsumesOnlySmallChunksAndEnforcesActualLimit() {
        val length = 33L * 1024 * 1024
        var remaining = length
        var reads = 0
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = object : InputStream() {
            override fun read(): Int = throw AssertionError("bulk reads required")
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                assertTrue(count <= 64 * 1024)
                if (remaining == 0L) return -1
                val read = minOf(count.toLong(), remaining).toInt()
                bytes.fill(0, offset, offset + read)
                remaining -= read
                reads++
                return read
            }
        }
        assertEquals(length, BackupContentHash.consume(stream, BackupLimits.ROOM, length) { bytes, count ->
            digest.update(bytes, 0, count)
        })
        assertTrue(reads > 500)
        val file = temporary.newFile("binary")
        RandomAccessFile(file, "rw").use { it.setLength(length) }
        assertEquals(hex(digest.digest()), BackupContentHash.fileSha256(file))
        assertThrows(IOException::class.java) {
            BackupContentHash.consume(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2) { _, _ -> }
        }
        var accessed = false
        val neverRead = object : InputStream() {
            override fun read(): Int { accessed = true; return -1 }
        }
        assertThrows(IOException::class.java) { BackupContentHash.consume(neverRead, 2, 3) { _, _ -> } }
        assertTrue(!accessed)
    }

    @Test fun fileHashRejectsOversizeBinaryBeforeAllocatingOrReading() {
        val file = temporary.newFile("oversize")
        RandomAccessFile(file, "rw").use { it.setLength(BackupLimits.ROOM + 1) }
        assertThrows(IOException::class.java) { BackupContentHash.fileSha256(file) }
    }

    @Test fun nestedZipOrderTimesCompressionAndDirectoryMarkersDoNotChangeContentHash() {
        val firstData = temporary.newFile("a")
        firstData.writeText("source-one")
        val secondData = temporary.newFile("b")
        secondData.writeText("source-two")
        val files = listOf("subscription_import/a.json" to firstData, "local_packages/pkg/b.json" to secondData)
        val first = BackupTestFiles.zip(temporary.root, "first-subs.zip", files, 1_800_000_000_000L)
        val second = BackupTestFiles.zip(temporary.root, "second-subs.zip",
            files.reversed() + ("local_packages/pkg/" to secondData), 1_810_000_000_000L, Deflater.NO_COMPRESSION)
        assertNotEquals(BackupContentHash.fileSha256(first), BackupContentHash.fileSha256(second))
        assertEquals(BackupContentHash.subsSha256(first), BackupContentHash.subsSha256(second))
        assertEquals(BackupContentHash.sha256(outer(first, "first.zip")), BackupContentHash.sha256(outer(second, "second.zip")))
        secondData.writeText("changed")
        val changed = BackupTestFiles.zip(temporary.root, "changed-subs.zip", files)
        assertNotEquals(BackupContentHash.sha256(outer(first, "before.zip")), BackupContentHash.sha256(outer(changed, "after.zip")))
    }

    @Test fun nestedArchiveRejectsIllegalPathsDuplicatesAndFileDirectoryConflicts() {
        val data = temporary.newFile("data")
        data.writeText("content")
        val invalid = listOf(
            "../escaped", "/subscription_import/a", "C:/subscription_import/a", "subscription_import/../a",
            "subscription_import/./a", "subscription_import//a", "subscription_import\\a", "unknown/a", "local_packages"
        )
        for ((index, path) in invalid.withIndex()) {
            val zip = BackupTestFiles.zip(temporary.root, "invalid-$index.zip", listOf(path to data))
            rejectNested(zip, "outer-invalid-$index.zip")
        }
        val conflictFiles = listOf("local_packages/dir" to data, "local_packages/dir/file" to data)
        rejectNested(BackupTestFiles.zip(temporary.root, "conflict.zip", conflictFiles), "outer-conflict.zip")
        rejectNested(BackupTestFiles.zip(temporary.root, "reverse.zip", conflictFiles.reversed()), "outer-reverse.zip")
        val duplicate = BackupTestFiles.zip(temporary.root, "duplicate.zip",
            listOf("subscription_import/entry.txt" to data, "subscription_import/other.txt" to data))
        BackupTestFiles.renameEntry(duplicate, "subscription_import/other.txt", "subscription_import/entry.txt")
        rejectNested(duplicate, "outer-duplicate.zip")
        val notZip = temporary.newFile("not-zip")
        notZip.writeText("raw strings are not subscription archives")
        rejectNested(notZip, "outer-not-zip.zip")
    }

    @Test fun nestedCentralDirectoryIsValidatedAndTemporaryFilesAreCleaned() {
        val data = temporary.newFile("data")
        data.writeText("same data")
        val duplicate = BackupTestFiles.zip(temporary.root, "central-duplicate.zip",
            listOf("subscription_import/entry.txt" to data, "subscription_import/other.txt" to data))
        BackupTestFiles.renameEntry(duplicate, "subscription_import/other.txt", "subscription_import/entry.txt", centralOnly = true)
        rejectNested(duplicate, "outer-central.zip")
        val truncated = BackupTestFiles.zip(temporary.root, "truncated.zip", listOf("subscription_import/file" to data))
        RandomAccessFile(truncated, "rw").use { it.setLength(it.length() - 22) }
        rejectNested(truncated, "outer-truncated.zip")
        assertTrue(temporary.root.listFiles()!!.none { it.name.startsWith("backup-subs-") })
    }

    @Test fun nestedArchiveBoundsFileCountAndDirectoryCount() {
        val data = temporary.newFile("data")
        data.writeText("small")
        val files = (0..BackupLimits.SUB_FILES).map { "subscription_import/$it" to data }
        rejectNested(BackupTestFiles.zip(temporary.root, "too-many.zip", files), "outer-many.zip")
        val directories = (0..BackupLimits.SUB_ENTRIES).map { "subscription_import/$it/" to data }
        rejectNested(BackupTestFiles.zip(temporary.root, "too-many-dirs.zip", directories), "outer-many-dirs.zip")
    }

    @Test fun compressedNestedArchiveBoundsActualFileAndTotalExpansion() {
        val data = temporary.newFile("zeros")
        RandomAccessFile(data, "rw").use { it.setLength(BackupLimits.SUB_FILE + 1) }
        val oversized = BackupTestFiles.zip(temporary.root, "too-big.zip", listOf("local_packages/big" to data))
        assertTrue(oversized.length() < 1024 * 1024)
        rejectNested(oversized, "outer-big.zip")
        RandomAccessFile(data, "rw").use { it.setLength(BackupLimits.SUB_FILE) }
        val total = BackupTestFiles.zip(temporary.root, "too-much.zip", (0..4).map { "local_packages/$it" to data })
        rejectNested(total, "outer-total.zip")
    }

    @Test fun preferencesHaveTheirOwn32MiBLimit() {
        val prefs = temporary.newFile("prefs")
        RandomAccessFile(prefs, "rw").use { it.setLength(BackupLimits.PREFS + 1) }
        val zip = BackupTestFiles.zip(temporary.root, "large-prefs.zip", listOf("prefs.json" to prefs))
        assertThrows(IOException::class.java) { BackupContentHash.sha256(zip) }
        BackupTestFiles.patchCentralSize(zip, "prefs.json", 1)
        assertThrows(IOException::class.java) { BackupContentHash.sha256(zip) }
    }

    private fun rejectNested(subs: File, name: String) {
        assertThrows(IOException::class.java) { BackupContentHash.subsSha256(subs) }
        val zip = outer(subs, name)
        assertThrows(IOException::class.java) { BackupContentHash.sha256(zip) }
    }

    private fun outer(subs: File, name: String): File =
        BackupTestFiles.zip(temporary.root, name, listOf("subs.zip" to subs))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

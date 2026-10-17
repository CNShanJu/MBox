package com.github.tvbox.osc.transfer

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun createsFirstBackupDirectoryAndRejectsFileConflict() {
        val directory = File(temporary.root, "first/backup")
        BackupArchiveFiles.ensureDirectory(directory)
        assertTrue(directory.isDirectory)
        BackupArchiveFiles.ensureDirectory(directory)
        val conflict = temporary.newFile("conflict")
        assertThrows(IOException::class.java) { BackupArchiveFiles.ensureDirectory(conflict) }
        assertThrows(IOException::class.java) { BackupArchiveFiles.ensureDirectory(File(conflict, "backup")) }
        assertTrue(conflict.isFile)
    }

    @Test fun failedExportsCannotSilentlyOmitProtectionData() {
        val target = File(temporary.root, "prefs.json")
        assertNull(BackupArchiveFiles.exportEntry(PrefsBackupModule, target, { false }, { false }))
        assertThrows(IOException::class.java) {
            BackupArchiveFiles.exportEntry(PrefsBackupModule, target, { true }, { false })
        }
        var probes = 0
        assertThrows(IOException::class.java) {
            BackupArchiveFiles.exportEntry(PrefsBackupModule, target, { ++probes > 1 }, { false })
        }
        assertThrows(IOException::class.java) {
            BackupArchiveFiles.exportEntry(PrefsBackupModule, target, { false }, { target.writeText("{}"); false })
        }
        assertTrue(target.delete())
        assertThrows(IOException::class.java) {
            BackupArchiveFiles.exportEntry(PrefsBackupModule, target, { true }, { true })
        }
        assertNotNull(BackupArchiveFiles.exportEntry(PrefsBackupModule, target, { true }, {
            target.writeText("{\"a\":1}")
            true
        }))
    }

    @Test fun acceptsCurrentSchemaAndNormalizesValidSha256Case() {
        val json = BackupTestFiles.manifest("prefs", size = 0, sha256 = "AB".repeat(32))
        val entry = BackupArchiveFiles.parseManifest(json.toByteArray()).single()
        assertEquals(0L, entry.size)
        assertEquals("ab".repeat(32), entry.sha256)
    }

    @Test fun rejectsInvalidSchemaModulePathSizeAndHash() {
        val valid = BackupTestFiles.manifest("prefs")
        val invalid = listOf(
            valid.replace("\"schema\":2", "\"schema\":2.5"),
            valid.replace("\"schema\":2", "\"schema\":2.0"),
            valid.replace("\"schema\":2", "\"schema\":\"2\""),
            valid.replace("\"schema\":2", "\"schema\":1"),
            valid.replace("\"schema\":2", "\"schema\":2,\"schema\":2"),
            valid.replace("\"prefs\"", "\"unknown\""),
            valid.replace("prefs.json", "../prefs.json"),
            valid.replace("prefs.json", "/prefs.json"),
            valid.replace("prefs.json", "C:/prefs.json"),
            valid.replace("prefs.json", "room.db"),
            valid.replace("\"size\":1", "\"size\":-1"),
            valid.replace("\"size\":1", "\"size\":1.5"),
            valid.replace("\"size\":1", "\"size\":1e0"),
            valid.replace("\"size\":1", "\"size\":\"1\""),
            valid.replace("\"size\":1", "\"size\":9223372036854775808"),
            valid.replace("\"size\":1", "\"size\":null"),
            valid.replace("a".repeat(64), "g".repeat(64)),
            valid.replace("a".repeat(64), "a".repeat(63)),
            valid.replace("\"modules\":[", "\"modules\":{} ,\"extra\":["),
            valid + "{}"
        )
        for (json in invalid) assertThrows(json, IOException::class.java) {
            BackupArchiveFiles.parseManifest(json.toByteArray())
        }
        for (module in BackupModules.all) {
            BackupArchiveFiles.parseManifest(BackupTestFiles.manifest(module.id, size = module.maxBytes).toByteArray())
            assertThrows(IOException::class.java) {
                BackupArchiveFiles.parseManifest(BackupTestFiles.manifest(module.id, size = module.maxBytes + 1).toByteArray())
            }
        }
        val module = valid.substringAfter("\"modules\":[").removeSuffix("]}")
        assertThrows(IOException::class.java) {
            BackupArchiveFiles.parseManifest(valid.replace(module, "$module,$module").toByteArray())
        }
    }

    @Test fun boundsBothDeclaredAndActualManifestBeforeExtraction() {
        val oversized = temporary.newFile("oversized.json")
        oversized.writeText(" ".repeat(BackupLimits.MANIFEST.toInt() + 1))
        val zip = BackupTestFiles.zip(temporary.root, "huge.zip", listOf("manifest.json" to oversized))
        val payload = File(temporary.root, "payload")
        assertThrows(IOException::class.java) { BackupArchiveFiles.extractValidated(zip, payload) }
        assertFalse(payload.exists())
        BackupTestFiles.patchCentralSize(zip, "manifest.json", 1)
        assertThrows(IOException::class.java) { BackupArchiveFiles.extractValidated(zip, payload) }
        assertFalse(payload.exists())
    }

    @Test fun rejectsUnknownDuplicateMissingAndConflictingOuterEntriesWithoutOverwritingInput() {
        val data = temporary.newFile("data")
        data.writeText("{}")
        val manifest = temporary.newFile("header")
        manifest.writeText(BackupTestFiles.manifest("prefs", size = data.length(), sha256 = BackupContentHash.fileSha256(data)))
        val good = listOf("manifest.json" to manifest, "prefs.json" to data)
        val badNames = listOf("backup.zip", "../prefs.json", "/prefs.json", "C:/prefs.json", "prefs.json/", "room.db")
        for ((index, name) in badNames.withIndex()) {
            val zip = BackupTestFiles.zip(temporary.root, "bad-$index.zip", good + (name to data))
            val hash = BackupContentHash.archiveSha256(zip)
            val payload = File(temporary.root, "payload-$index")
            assertThrows(IOException::class.java) { BackupArchiveFiles.extractValidated(zip, payload) }
            assertFalse(payload.exists())
            assertEquals(hash, BackupContentHash.archiveSha256(zip))
        }
        val duplicate = BackupTestFiles.zip(temporary.root, "duplicate.zip", good + ("xrefs.json" to data))
        BackupTestFiles.renameEntry(duplicate, "xrefs.json", "prefs.json")
        assertThrows(IOException::class.java) {
            BackupArchiveFiles.extractValidated(duplicate, File(temporary.root, "duplicate-payload"))
        }
        val duplicateManifest = BackupTestFiles.zip(temporary.root, "duplicate-header.zip", good + ("xanifest.json" to manifest))
        BackupTestFiles.renameEntry(duplicateManifest, "xanifest.json", "manifest.json")
        assertThrows(IOException::class.java) {
            BackupArchiveFiles.extractValidated(duplicateManifest, File(temporary.root, "duplicate-header-payload"))
        }
        val missing = BackupTestFiles.zip(temporary.root, "missing.zip", listOf("manifest.json" to manifest))
        assertThrows(IOException::class.java) {
            BackupArchiveFiles.extractValidated(missing, File(temporary.root, "missing-payload"))
        }
    }

    @Test fun binaryModulesLargerThan32MiBRoundTripAndHashWithoutByteArrays() {
        val binary = temporary.newFile("binary")
        BackupTestFiles.writeRepeated(binary, 33L * 1024 * 1024)
        val subs = BackupTestFiles.zip(temporary.root, "nested.zip",
            listOf("local_packages/pack/deep/data.bin" to binary), compression = Deflater.NO_COMPRESSION)
        assertTrue(subs.length() > BackupLimits.PREFS)
        val header = temporary.newFile("header.json")
        val modules = listOf(RoomBackupModule to binary, SubsBackupModule to subs).map { (module, file) ->
            mapOf("id" to module.id, "entry" to module.entryName, "size" to file.length(),
                "sha256" to BackupContentHash.fileSha256(file))
        }
        header.writeText(Gson().toJson(mapOf("schema" to 2, "fileName" to BackupArchive.FILE_NAME, "modules" to modules)))
        val zip = BackupTestFiles.zip(temporary.root, "large.zip",
            listOf("manifest.json" to header, "room.db" to binary, "subs.zip" to subs))
        val payload = File(temporary.root, "payload")
        assertEquals(2, BackupArchiveFiles.extractValidated(zip, payload).size)
        assertEquals(binary.length(), File(payload, "room.db").length())
        assertEquals(BackupContentHash.fileSha256(binary), BackupContentHash.fileSha256(File(payload, "room.db")))
        assertEquals(BackupContentHash.fileSha256(subs), BackupContentHash.fileSha256(File(payload, "subs.zip")))
        assertEquals(64, BackupContentHash.sha256(zip).length)
    }

    @Test fun invalidNestedArchiveAndChecksumNeverProduceAValidatedRestore() {
        val data = temporary.newFile("invalid-subs")
        data.writeText("not a zip")
        val header = temporary.newFile("header")
        header.writeText(BackupTestFiles.manifest("subs", size = data.length(), sha256 = BackupContentHash.fileSha256(data)))
        val zip = BackupTestFiles.zip(temporary.root, "bad-subs.zip", listOf("manifest.json" to header, "subs.zip" to data))
        assertThrows(IOException::class.java) {
            BackupArchiveFiles.extractValidated(zip, File(temporary.root, "payload"))
        }
        header.writeText(BackupTestFiles.manifest("prefs", size = data.length()))
        val mismatch = BackupTestFiles.zip(temporary.root, "mismatch.zip", listOf("manifest.json" to header, "prefs.json" to data))
        assertThrows(IOException::class.java) {
            BackupArchiveFiles.extractValidated(mismatch, File(temporary.root, "mismatch-payload"))
        }
    }
}

internal object BackupTestFiles {
    fun manifest(id: String, size: Long = 1, sha256: String = "a".repeat(64)): String {
        val module = BackupModules.byId(id) ?: error("unknown fixture module")
        return "{\"schema\":2,\"fileName\":\"mbox-backup.zip\",\"modules\":[{\"id\":\"$id\",\"entry\":\"${module.entryName}\",\"size\":$size,\"sha256\":\"$sha256\"}]}"
    }

    fun zip(directory: File, name: String, files: List<Pair<String, File>>, time: Long = 1_800_000_000_000L,
            compression: Int = Deflater.DEFAULT_COMPRESSION): File {
        val target = File(directory, name)
        ZipOutputStream(target.outputStream()).use { output ->
            output.setLevel(compression)
            for ((path, file) in files) {
                output.putNextEntry(ZipEntry(path).apply { this.time = time })
                if (!path.endsWith('/')) file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        return target
    }

    fun writeRepeated(file: File, size: Long) {
        val buffer = ByteArray(64 * 1024) { (it % 251).toByte() }
        file.outputStream().use { output ->
            var remaining = size
            while (remaining > 0) {
                val count = minOf(remaining, buffer.size.toLong()).toInt()
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    fun patchCentralSize(file: File, name: String, size: Int) {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in 0..bytes.size - 46) {
            if (buffer.getInt(offset) != 0x02014b50) continue
            val length = buffer.getShort(offset + 28).toInt() and 65535
            if (offset + 46 + length > bytes.size) continue
            if (String(bytes, offset + 46, length, Charsets.UTF_8) != name) continue
            buffer.putInt(offset + 24, size)
            file.writeBytes(bytes)
            return
        }
        error("fixture entry missing")
    }

    fun renameEntry(file: File, from: String, to: String, centralOnly: Boolean = false) {
        val old = from.toByteArray()
        val replacement = to.toByteArray()
        check(old.size == replacement.size)
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var changed = 0
        for (offset in 0..bytes.size - old.size) {
            if (centralOnly && (offset < 46 || buffer.getInt(offset - 46) != 0x02014b50)) continue
            if (old.indices.all { bytes[offset + it] == old[it] }) {
                replacement.copyInto(bytes, offset)
                changed++
            }
        }
        check(changed == if (centralOnly) 1 else 2)
        file.writeBytes(bytes)
    }
}

package com.github.tvbox.osc.transfer

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object BackupContentHash {
    private val VERSION = "mbox-backup-content-v3".toByteArray(StandardCharsets.US_ASCII)
    private val HEX = "0123456789abcdef".toCharArray()
    private val GSON = Gson()

    @JvmStatic
    fun archiveSha256(backupZip: File?): String {
        val file = requireReadable(backupZip)
        return hashFile(file, BackupLimits.ARCHIVE)
    }

    @JvmStatic
    fun sha256(backupZip: File?): String {
        val file = requireReadable(backupZip)
        val digest = newDigest()
        digest.update(VERSION)
        ZipFile(file).use { zip ->
            val entries = BackupArchiveFiles.indexEntries(zip)
            if (entries.keys.none { it != BackupArchive.ENTRY_MANIFEST }) throw IOException("备份 ZIP 缺少数据条目")
            var total = 0L
            for ((name, entry) in entries.toSortedMap()) {
                val fingerprint = when (name) {
                    BackupArchive.ENTRY_MANIFEST -> {
                        zip.getInputStream(entry).use { input ->
                            total += consume(input, BackupLimits.MANIFEST, entry.size, entry.crc) { _, _ -> }
                        }
                        null
                    }
                    PrefsBackupModule.entryName -> {
                        val bytes = readEntry(zip, entry, BackupLimits.PREFS)
                        total += bytes.size
                        val canonical = canonicalPrefs(bytes)
                        Fingerprint(name, canonical.size.toLong(), newDigest().digest(canonical))
                    }
                    SubsBackupModule.entryName -> {
                        // 有界暂存以复用还原时的 ZipFile 视图，不能只校验本地头而漏掉中央目录中的重复条目。
                        val staged = File.createTempFile("backup-subs-", ".tmp", file.absoluteFile.parentFile)
                        var failure: Throwable? = null
                        try {
                            zip.getInputStream(entry).use { input ->
                                staged.outputStream().use { output ->
                                    total += consume(input, BackupLimits.SUBS, entry.size, entry.crc) { buffer, size ->
                                        output.write(buffer, 0, size)
                                    }
                                }
                            }
                            val nested = subsDigest(staged)
                            Fingerprint(name, nested.size.toLong(), nested)
                        } catch (error: Throwable) {
                            failure = error
                            throw error
                        } finally {
                            if (!staged.delete()) {
                                val cleanup = IOException("无法清理订阅摘要临时文件")
                                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                            }
                        }
                    }
                    else -> {
                        val entryDigest = newDigest()
                        val count = zip.getInputStream(entry).use { input ->
                            consume(input, BackupLimits.ROOM, entry.size, entry.crc) { buffer, size ->
                                entryDigest.update(buffer, 0, size)
                            }
                        }
                        total += count
                        Fingerprint(name, count, entryDigest.digest())
                    }
                }
                if (total > BackupLimits.ARCHIVE) throw IOException("备份解压总量超限")
                if (fingerprint != null) feed(digest, fingerprint)
            }
        }
        return hex(digest.digest())
    }

    @JvmStatic
    fun fileSha256(file: File): String = hashFile(file, BackupLimits.ROOM)

    private fun hashFile(file: File, max: Long): String {
        if (!file.isFile) throw IOException("备份文件不存在")
        val length = file.length()
        val digest = newDigest()
        file.inputStream().use { input ->
            consume(input, max, length) { buffer, count -> digest.update(buffer, 0, count) }
        }
        if (file.length() != length) throw IOException("备份文件读取期间发生变化")
        return hex(digest.digest())
    }

    internal fun subsSha256(file: File): String = hex(subsDigest(file))

    private fun subsDigest(file: File): ByteArray {
        if (!file.isFile || file.length() == 0L || file.length() > BackupLimits.SUBS)
            throw IOException("订阅源备份不存在或过大")
        val index = SubsEntries()
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                index.accept(entry)
                zip.getInputStream(entry).use { input ->
                    index.read(entry, input)
                }
            }
        }
        return index.digest()
    }

    internal fun validatePrefs(file: File) {
        file.inputStream().use { canonicalPrefs(readBounded(it, BackupLimits.PREFS, file.length())) }
    }

    internal fun readEntry(zip: ZipFile, entry: ZipEntry, max: Long): ByteArray =
        zip.getInputStream(entry).use { readBounded(it, max, entry.size, entry.crc) }

    internal fun readBounded(source: InputStream, max: Long, size: Long = -1, crc: Long = -1): ByteArray {
        ByteArrayOutputStream().use { output ->
            consume(source, max, size, crc) { buffer, count -> output.write(buffer, 0, count) }
            return output.toByteArray()
        }
    }

    internal fun consume(source: InputStream, max: Long, size: Long = -1, crc: Long = -1,
                         sink: (ByteArray, Int) -> Unit): Long {
        val checked = CheckedInput(source, max, size, crc)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = checked.read(buffer)
            if (read == -1) break
            sink(buffer, read)
        }
        checked.verify()
        return checked.count
    }

    private fun requireReadable(file: File?): File {
        if (file == null) throw IOException("备份 ZIP 不存在")
        BackupArchiveFiles.requireArchive(file)
        return file
    }

    private fun canonicalPrefs(bytes: ByteArray): ByteArray {
        val parsed = try {
            JsonParser.parseString(String(bytes, StandardCharsets.UTF_8))
        } catch (error: RuntimeException) {
            throw IOException("备份设置 JSON 无效", error)
        }
        if (!parsed.isJsonObject) throw IOException("备份设置不是 JSON 对象")
        return canonical(parsed).toByteArray(StandardCharsets.UTF_8)
    }

    private fun canonical(value: JsonElement): String {
        if (value.isJsonObject) {
            return value.asJsonObject.keySet().sorted().joinToString(",", "{", "}") { key ->
                GSON.toJson(key) + ":" + canonical(value.asJsonObject.get(key))
            }
        }
        if (value.isJsonArray) return value.asJsonArray.joinToString(",", "[", "]") { canonical(it) }
        return value.toString()
    }

    private data class Fingerprint(val name: String, val size: Long, val hash: ByteArray)

    private class SubsEntries {
        private val seen = HashSet<String>()
        private val paths = HashMap<String, Boolean>()
        private val fingerprints = ArrayList<Fingerprint>()
        private var fileCount = 0
        private var total = 0L

        fun accept(entry: ZipEntry) {
            if (seen.size >= BackupLimits.SUB_ENTRIES) throw IOException("订阅 ZIP 条目数量超限")
            val name = entry.name
            if (name.isEmpty() || name.length > 1024 || name.contains('\\') || name.contains(':') || name.contains('\u0000'))
                throw IOException("订阅 ZIP 条目路径非法")
            val path = if (entry.isDirectory) name.dropLast(1) else name
            val parts = path.split('/')
            if (parts.size > 64 || parts.any { it.isEmpty() || it == "." || it == ".." || it.endsWith('.') || it.endsWith(' ') })
                throw IOException("订阅 ZIP 条目路径非法")
            if (parts.first() != BackupLimits.IMPORT_ROOT && parts.first() != BackupLimits.PACKAGE_ROOT)
                throw IOException("订阅 ZIP 包含未知目录")
            if (!entry.isDirectory && parts.size == 1) throw IOException("订阅 ZIP 根条目不是目录")
            val key = path.lowercase(Locale.ROOT)
            if (!seen.add(key)) throw IOException("订阅 ZIP 条目重复")
            if (paths[key]?.let { it != entry.isDirectory } == true) throw IOException("订阅 ZIP 文件与目录冲突")
            paths[key] = entry.isDirectory
            var parent = key.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                if (paths[parent] == false) throw IOException("订阅 ZIP 父目录与文件冲突")
                paths[parent] = true
                parent = parent.substringBeforeLast('/', "")
            }
            if (!entry.isDirectory && ++fileCount > BackupLimits.SUB_FILES) throw IOException("订阅 ZIP 文件数量超限")
            val max = if (entry.isDirectory) 0L else BackupLimits.SUB_FILE
            if (entry.size < 0 || entry.size > max) throw IOException("订阅 ZIP 条目尺寸超限")
        }

        fun read(entry: ZipEntry, source: InputStream) {
            val digest = newDigest()
            val max = if (entry.isDirectory) 0L else minOf(BackupLimits.SUB_FILE, BackupLimits.SUBS - total)
            val count = consume(source, max, entry.size, entry.crc) { buffer, size -> digest.update(buffer, 0, size) }
            total += count
            if (!entry.isDirectory) fingerprints.add(Fingerprint(entry.name, count, digest.digest()))
        }

        fun digest(): ByteArray {
            if (fingerprints.isEmpty()) throw IOException("订阅 ZIP 缺少文件")
            val digest = newDigest()
            for (fingerprint in fingerprints.sortedBy { it.name }) feed(digest, fingerprint)
            return digest.digest()
        }
    }

    private class CheckedInput(private val source: InputStream, private val max: Long,
                               private val size: Long, private val expectedCrc: Long) : InputStream() {
        var count = 0L
            private set
        private val crc = CRC32()

        init {
            if (max < 0 || size < -1 || size > max) throw IOException("备份条目声明尺寸超限")
        }

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) == -1) -1 else byte[0].toInt() and 255
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = source.read(buffer, offset, length)
            if (read > 0) {
                if (read > max - count) throw IOException("备份条目实际尺寸超限")
                count += read
                crc.update(buffer, offset, read)
            }
            return read
        }

        fun verify() {
            if (size >= 0 && size != count) throw IOException("备份条目实际尺寸不符")
            if (expectedCrc >= 0 && expectedCrc != crc.value) throw IOException("备份条目 CRC 不符")
        }
    }

    private fun feed(digest: MessageDigest, fingerprint: Fingerprint) {
        val name = fingerprint.name.toByteArray(StandardCharsets.UTF_8)
        digest.update(longBytes(name.size.toLong()))
        digest.update(name)
        digest.update(longBytes(fingerprint.size))
        digest.update(fingerprint.hash)
    }

    private fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun longBytes(value: Long): ByteArray {
        val bytes = ByteArray(8)
        var rest = value
        for (index in 7 downTo 0) {
            bytes[index] = rest.toByte()
            rest = rest ushr 8
        }
        return bytes
    }

    private fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            chars[index * 2] = HEX[(bytes[index].toInt() ushr 4) and 15]
            chars[index * 2 + 1] = HEX[bytes[index].toInt() and 15]
        }
        return String(chars)
    }
}

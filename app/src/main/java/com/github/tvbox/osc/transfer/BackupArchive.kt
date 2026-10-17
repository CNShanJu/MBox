package com.github.tvbox.osc.transfer

import android.content.Context
import com.github.tvbox.osc.config.PrefsDataStore
import com.github.tvbox.osc.data.AppDataManager
import com.github.tvbox.osc.share.ShareArchives
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class BackupArchive(private val context: Context) {
    companion object {
        const val ENTRY_MANIFEST = "manifest.json"
        const val FILE_NAME = "mbox-backup.zip"
        private val GSON = Gson()
    }

    fun create(backupDirectory: File): File {
        BackupArchiveFiles.ensureDirectory(backupDirectory)
        val staging = File(backupDirectory, "staging_" + UUID.randomUUID())
        if (!staging.mkdir()) throw IOException("无法准备备份临时目录")
        val temporary = File(backupDirectory, "mbox_backup_" + UUID.randomUUID() + ".tmp")
        try {
            val modules = withDataLock {
                BackupModules.all.mapNotNull { module ->
                    BackupArchiveFiles.exportEntry(module, File(staging, module.entryName),
                        { module.hasData(context) }, { module.export(context, File(staging, module.entryName)) })
                }
            }
            if (modules.isEmpty()) throw IOException("当前无数据可备份")
            val manifest = File(staging, ENTRY_MANIFEST)
            manifest.writeText(manifestJson(modules), StandardCharsets.UTF_8)
            val entries = LinkedHashMap<String, File>()
            for (entry in modules) entries[entry.entry] = File(staging, entry.entry)
            entries[ENTRY_MANIFEST] = manifest
            ShareArchives.zip(entries, temporary)
            if (temporary.length() > BackupLimits.ARCHIVE) throw IOException("备份 ZIP 过大")
            val saved = File(backupDirectory, uniqueName(backupDirectory))
            if (!temporary.renameTo(saved)) throw IOException("备份文件落盘失败")
            return saved
        } finally {
            temporary.delete()
            deleteTree(staging)
        }
    }

    fun restore(backupZip: File): String = restoreInternal(backupZip)

    fun restoreSystem(backupZip: File): String = restoreInternal(backupZip)

    private fun restoreInternal(backupZip: File): String {
        BackupArchiveFiles.requireArchive(backupZip)
        val work = File(context.cacheDir, "restore_" + UUID.randomUUID())
        if (!work.mkdir()) throw IOException("无法准备还原临时目录")
        try {
            val copy = File(work, "backup.zip")
            backupZip.inputStream().use { input ->
                copy.outputStream().use { output ->
                    BackupContentHash.consume(input, BackupLimits.ARCHIVE, backupZip.length()) { buffer, count ->
                        output.write(buffer, 0, count)
                    }
                }
            }
            // 输入副本与解压产物分离，恶意条目不能覆盖仍在读取的 ZIP。
            val payload = File(work, "payload")
            val modules = BackupArchiveFiles.extractValidated(copy, payload)
            modules.firstOrNull { it.id == RoomBackupModule.id }?.let {
                AppDataManager.validateBackup(File(payload, it.entry))
            }
            return withDataLock {
                for (module in BackupModules.all) module.clear(context)
                for (entry in modules) {
                    val module = BackupModules.byId(entry.id) ?: throw IOException("未知备份模块: " + entry.id)
                    module.import(context, File(payload, entry.entry))
                }
                modules.joinToString("/") { it.id } + " 已恢复"
            }
        } finally {
            deleteTree(work)
        }
    }

    private fun <T> withDataLock(action: () -> T): T {
        try {
            // 与配置导入保持 Room → 订阅文件 → Prefs 的顺序，且订阅清空和写回不可被插入。
            return AppDataManager.runOnDb<T> {
                SubscriptionImportFiles.runLocked<T> {
                    PrefsDataStore.runWithRollback<T> { action() }
                }
            }
        } catch (error: RuntimeException) {
            throw (error.cause as? IOException ?: error)
        }
    }

    private fun manifestJson(entries: List<BackupEntry>): String {
        val header = linkedMapOf<String, Any>(
            "schema" to 2,
            "fileName" to FILE_NAME,
            "createdAt" to System.currentTimeMillis(),
            "appVersion" to appVersion(),
            "modules" to entries.map { entry ->
                linkedMapOf("id" to entry.id, "entry" to entry.entry, "size" to entry.size, "sha256" to entry.sha256)
            }
        )
        return GSON.toJson(header)
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (ignored: Exception) {
        ""
    }

    private fun uniqueName(directory: File): String {
        val base = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(Date())
        var candidate = "$base.zip"
        var index = 2
        while (File(directory, candidate).exists()) {
            candidate = "$base-$index.zip"
            index++
        }
        return candidate
    }

    private fun deleteTree(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteTree(it) }
        file.delete()
    }
}

internal data class BackupEntry(val id: String, val entry: String, val size: Long, val sha256: String)

internal object BackupArchiveFiles {
    private val INTEGER = Regex("0|[1-9][0-9]*")
    private val SHA256 = Regex("[0-9a-fA-F]{64}")

    fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建备份目录: $directory")
        if (!directory.isDirectory) throw IOException("备份目录与普通文件冲突: $directory")
    }

    fun requireArchive(file: File) {
        if (!file.isFile || file.length() == 0L || file.length() > BackupLimits.ARCHIVE)
            throw IOException("备份 ZIP 不存在或过大")
    }

    fun exportEntry(module: BackupModule, target: File, hasData: () -> Boolean, export: () -> Boolean): BackupEntry? {
        val hadData = hasData()
        if (!export()) {
            if (hadData || hasData() || target.exists()) throw IOException("备份模块导出失败: " + module.id)
            return null
        }
        if (!target.isFile || target.length() > module.maxBytes) throw IOException("备份模块产物无效或过大: " + module.id)
        return BackupEntry(module.id, module.entryName, target.length(), BackupContentHash.fileSha256(target))
    }

    fun indexEntries(zip: ZipFile): Map<String, ZipEntry> {
        val result = LinkedHashMap<String, ZipEntry>()
        val entries = zip.entries()
        var total = 0L
        while (entries.hasMoreElements()) {
            if (result.size >= BackupModules.all.size + 1) throw IOException("备份 ZIP 条目过多")
            val entry = entries.nextElement()
            val max = if (entry.name == BackupArchive.ENTRY_MANIFEST) BackupLimits.MANIFEST
                else BackupModules.all.firstOrNull { it.entryName == entry.name }?.maxBytes
                    ?: throw IOException("未知备份 ZIP 条目: " + entry.name)
            if (entry.isDirectory || result.put(entry.name, entry) != null) throw IOException("备份 ZIP 条目重复或冲突")
            if (entry.size < 0 || entry.size > max) throw IOException("备份条目尺寸无效或过大: " + entry.name)
            total += entry.size
            if (total > BackupLimits.ARCHIVE) throw IOException("备份解压总量超限")
        }
        return result
    }

    fun extractValidated(backupZip: File, payload: File): List<BackupEntry> {
        requireArchive(backupZip)
        val modules = ZipFile(backupZip).use { zip ->
            val entries = indexEntries(zip)
            val manifest = entries[BackupArchive.ENTRY_MANIFEST] ?: throw IOException("备份缺少入口文件")
            val parsed = parseManifest(BackupContentHash.readEntry(zip, manifest, BackupLimits.MANIFEST))
            val expected = parsed.map { it.entry }.toSet() + BackupArchive.ENTRY_MANIFEST
            if (entries.keys != expected) throw IOException("备份 ZIP 条目与清单不符")
            for (module in parsed) {
                if (entries.getValue(module.entry).size != module.size) throw IOException("备份条目尺寸与清单不符")
            }
            parsed
        }
        if (!payload.mkdir()) throw IOException("无法准备独立还原目录")
        try {
            ShareArchives.extract(backupZip, payload, BackupLimits.ROOM, BackupLimits.ARCHIVE)
        } catch (failure: SecurityException) {
            throw IOException("备份 ZIP 内容非法或超限", failure)
        }
        for (entry in modules) {
            val file = File(payload, entry.entry)
            if (!file.isFile || file.length() != entry.size) throw IOException("备份条目尺寸不符: " + entry.entry)
            if (BackupContentHash.fileSha256(file) != entry.sha256) throw IOException("备份条目校验失败: " + entry.entry)
            when (entry.id) {
                SubsBackupModule.id -> BackupContentHash.subsSha256(file)
                PrefsBackupModule.id -> BackupContentHash.validatePrefs(file)
            }
        }
        return modules
    }

    fun parseManifest(bytes: ByteArray): List<BackupEntry> {
        if (bytes.size > BackupLimits.MANIFEST) throw IOException("备份入口文件过大")
        try {
            JsonReader(StringReader(String(bytes, StandardCharsets.UTF_8))).use { reader ->
                reader.isLenient = false
                reader.beginObject()
                val seen = HashSet<String>()
                var schema: Long? = null
                var fileName: String? = null
                var modules: List<BackupEntry>? = null
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (!seen.add(key)) throw IOException("备份入口字段重复: $key")
                    when (key) {
                        "schema" -> schema = readInteger(reader)
                        "fileName" -> fileName = readString(reader)
                        "createdAt" -> readInteger(reader)
                        "appVersion" -> readString(reader)
                        "modules" -> modules = readModules(reader)
                        else -> throw IOException("未知备份入口字段: $key")
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("备份入口文件包含多余内容")
                if (schema != 2L) throw IOException("备份格式不受支持(仅支持当前版本备份)")
                if (fileName != BackupArchive.FILE_NAME) throw IOException("备份入口文件标识不符")
                return modules?.takeIf { it.isNotEmpty() } ?: throw IOException("备份中无可恢复数据")
            }
        } catch (error: RuntimeException) {
            throw IOException("备份入口文件无效", error)
        }
    }

    private fun readModules(reader: JsonReader): List<BackupEntry> {
        reader.beginArray()
        val result = ArrayList<BackupEntry>()
        val ids = HashSet<String>()
        while (reader.hasNext()) {
            if (result.size >= BackupModules.all.size) throw IOException("备份模块过多")
            reader.beginObject()
            val fields = HashSet<String>()
            var id: String? = null
            var entry: String? = null
            var size: Long? = null
            var sha256: String? = null
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (!fields.add(key)) throw IOException("备份模块字段重复: $key")
                when (key) {
                    "id" -> id = readString(reader)
                    "entry" -> entry = readString(reader)
                    "size" -> size = readInteger(reader)
                    "sha256" -> sha256 = readString(reader)
                    else -> throw IOException("未知备份模块字段: $key")
                }
            }
            reader.endObject()
            val module = id?.let { BackupModules.byId(it) } ?: throw IOException("未知备份模块: $id")
            if (!ids.add(module.id)) throw IOException("备份模块重复: $id")
            if (entry != module.entryName) throw IOException("备份模块条目名不符: $id")
            val count = size ?: throw IOException("备份模块缺少尺寸")
            if (count > module.maxBytes) throw IOException("备份模块尺寸超限: $id")
            val hash = sha256?.takeIf { SHA256.matches(it) } ?: throw IOException("备份模块摘要无效")
            result.add(BackupEntry(module.id, module.entryName, count, hash.lowercase(Locale.ROOT)))
        }
        reader.endArray()
        return result
    }

    private fun readString(reader: JsonReader): String {
        if (reader.peek() != JsonToken.STRING) throw IOException("备份入口字段必须为字符串")
        return reader.nextString()
    }

    private fun readInteger(reader: JsonReader): Long {
        if (reader.peek() != JsonToken.NUMBER) throw IOException("备份入口字段必须为整数")
        val text = reader.nextString()
        if (!INTEGER.matches(text)) throw IOException("备份入口整数无效")
        return text.toLongOrNull() ?: throw IOException("备份入口整数超限")
    }
}

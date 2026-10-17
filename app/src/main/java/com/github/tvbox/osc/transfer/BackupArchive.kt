package com.github.tvbox.osc.transfer

import android.content.Context
import com.github.tvbox.osc.config.PrefsDataStore
import com.github.tvbox.osc.data.AppDataManager
import com.github.tvbox.osc.share.ShareArchives
import com.github.tvbox.osc.share.ShareManifest
import com.github.tvbox.osc.share.domain.DataTransferHub
import com.github.tvbox.osc.share.domain.ImportMode
import com.github.tvbox.osc.share.domain.ImportProtection
import com.github.tvbox.osc.share.domain.TransferHook
import com.github.tvbox.osc.share.domain.TransferSession
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

/**
 * 本地备份归档：创建与还原统一走 share 的 {@link DataTransferHub} 调度。
 *
 * <p>导出 = 调度器把三个备份数据域（prefs / room / subs，见 {@link BackupModules} 的
 * DataDomain 适配）各自打进归档，没有数据的域不产出条目；还原 = 覆盖模式分发
 * （{@link ImportMode#OVERWRITE}：备份里没有的数据域，还原后本机也没有）。
 *
 * <p>归档格式有两代，还原都支持：
 * <ul>
 *   <li>新格式 = share 统一归档（{@link ShareManifest} 清单 + 各域条目文件）；</li>
 *   <li>旧格式 = schema 2 严格清单（逐条目 SHA-256），由 {@link BackupArchiveFiles} 校验后
 *       转换成调度器清单分发，老版本写的备份继续可还原。</li>
 * </ul>
 *
 * <p>定制化入口：还原接受 {@link TransferHook}，调用方可在通用流程前插入自己的逻辑 ——
 * 本地备份的"覆写前先做 1 小时保护快照"就是这样接入的（见 LocalBackupRepository）。
 */
class BackupArchive(private val context: Context) {
    companion object {
        const val ENTRY_MANIFEST = "manifest.json"
        const val FILE_NAME = "mbox-backup.zip"
        private val BACKUP_DOMAINS = linkedSetOf(
                PrefsBackupModule.id, RoomBackupModule.id, SubsBackupModule.id)
    }

    private val hub = ConfigDomains.hub()

    fun create(backupDirectory: File): File {
        BackupArchiveFiles.ensureDirectory(backupDirectory)
        val temporary = File(backupDirectory, "mbox_backup_" + UUID.randomUUID() + ".tmp")
        var archive: File? = null
        try {
            // 导出期间占住 Room 线程 + 订阅文件锁，与配置导入互斥（原 withDataLock 语义）。
            archive = BackupDataLock.withDataLock { hub.exportArchive(BACKUP_DOMAINS, FILE_NAME, true) }
            copyBounded(archive, temporary)
            if (temporary.length() > BackupLimits.ARCHIVE) throw IOException("备份 ZIP 过大")
            val saved = File(backupDirectory, uniqueName(backupDirectory))
            if (!temporary.renameTo(saved)) throw IOException("备份文件落盘失败")
            return saved
        } finally {
            archive?.delete()
            temporary.delete()
        }
    }

    /**
     * 还原备份（覆盖模式，经 share 统一调度）。
     *
     * @param hook 定制化钩子，可为 {@code null}；本地备份还原传入"保护快照"钩子
     */
    fun restore(backupZip: File, hook: TransferHook?): String {
        BackupArchiveFiles.requireArchive(backupZip)
        val work = File(context.cacheDir, "restore_" + UUID.randomUUID())
        if (!work.mkdir()) throw IOException("无法准备还原临时目录")
        try {
            // 输入副本与解压产物分离，恶意条目不能覆盖仍在读取的 ZIP。
            val copy = File(work, "backup.zip")
            copyBounded(backupZip, copy)
            val payload = File(work, "payload")
            val manifest = ShareArchives.manifest(copy)
            val restored: List<String>
            val dispatchManifest: ShareManifest
            if (manifest != null && isCurrentFormat(manifest)) {
                // 新格式：解压 + 条目与域内容预校验后交调度器分发。
                ShareArchives.check(copy, manifest)
                ShareArchives.extract(copy, payload, BackupLimits.ARCHIVE, BackupLimits.ARCHIVE)
                validatePayload(payload, manifest.domains())
                dispatchManifest = manifest
                restored = manifest.domains()
            } else {
                // 旧格式：严格清单校验（含逐条目 SHA-256 与 Room 预校验）后转换成调度器清单。
                val entries = BackupArchiveFiles.extractValidated(copy, payload)
                dispatchManifest = ShareManifest(ShareManifest.SCHEMA_CURRENT, FILE_NAME, 0, "",
                        System.currentTimeMillis(), appVersion(), 0, entries.map { it.id })
                restored = entries.map { it.id }
            }
            hub.importValidated(payload, dispatchManifest, BACKUP_DOMAINS, ImportMode.OVERWRITE,
                    hook, BackupTransferLocks())
            return restored.joinToString("/") + " 已恢复"
        } finally {
            deleteTree(work)
        }
    }

    /** 新格式 = share 统一归档清单（当前 schema + 备份文件名 + 数据域全部是已注册备份域）。 */
    private fun isCurrentFormat(manifest: ShareManifest): Boolean =
            manifest.schema() == ShareManifest.SCHEMA_CURRENT
                    && FILE_NAME == manifest.fileName()
                    && manifest.domains().isNotEmpty()
                    && BACKUP_DOMAINS.containsAll(manifest.domains())

    /** 新格式解压后的条目校验：条目集合必须与清单一致，且各域内容先过自己的预校验。 */
    private fun validatePayload(payload: File, ids: List<String>) {
        val expected = ids.map { hub.domain(it).entryName() }.toSet() + ENTRY_MANIFEST
        val files = payload.listFiles() ?: throw IOException("备份包解压失败")
        if (files.size != expected.size) throw IOException("备份包条目不完整或包含额外文件")
        for (file in files) {
            if (!file.isFile || file.name !in expected) throw IOException("备份包包含未知条目")
        }
        // 与旧格式一致：room 先 validateBackup 再动本机数据，损坏的备份在清空前就失败。
        for (id in ids) {
            when (id) {
                RoomBackupModule.id ->
                    AppDataManager.validateBackup(File(payload, RoomBackupModule.entryName))
                SubsBackupModule.id ->
                    BackupContentHash.subsSha256(File(payload, SubsBackupModule.entryName))
                PrefsBackupModule.id ->
                    BackupContentHash.validatePrefs(File(payload, PrefsBackupModule.entryName))
            }
        }
    }

    private fun copyBounded(source: File, target: File) {
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                BackupContentHash.consume(input, BackupLimits.ARCHIVE, source.length()) { buffer, count ->
                    output.write(buffer, 0, count)
                }
            }
        }
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

/**
 * Room 线程 → 订阅文件锁 → Prefs 回滚作用域：备份导出与还原共用的锁序（与配置导入一致，
 * 保证备份/还原和配置导入不会并发交错写同一批数据）。
 */
internal object BackupDataLock {
    fun <T> withDataLock(action: () -> T): T {
        return try {
            AppDataManager.runOnDb<T> {
                SubscriptionImportFiles.runLocked<T> {
                    PrefsDataStore.runWithRollback<T> { action() }
                }
            }
        } catch (error: RuntimeException) {
            throw (error.cause as? IOException ?: error)
        }
    }
}

/**
 * 备份还原的导入保护：只做锁串行（{@link BackupDataLock}）。
 *
 * 快照与回滚不在这里 —— 备份链路用 SystemBackupStore 的"还原前 1 小时保护快照"兜底
 * （由调用方以 {@link TransferHook} 插入）；默认保护的文件快照不覆盖 room 域的整库替换，
 * 对备份还原没有意义，这正是"下游可重写导入保护"的落地。
 */
internal class BackupTransferLocks : ImportProtection {
    override fun runProtected(session: TransferSession, action: ImportProtection.ImportAction): List<String> =
            BackupDataLock.withDataLock { action.run() }
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

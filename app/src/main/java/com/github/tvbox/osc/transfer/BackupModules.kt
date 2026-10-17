package com.github.tvbox.osc.transfer

import android.content.Context
import com.github.tvbox.osc.config.PrefsDataStore
import com.github.tvbox.osc.data.AppDataManager
import com.github.tvbox.osc.share.ShareArchives
import com.github.tvbox.osc.util.LocalPackageImporter
import com.github.tvbox.osc.util.SubscriptionLibrary
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID

/**
 * 备份/还原的模块契约(重构后备份体系的唯一抽象)。
 *
 * 约定(用户口径):
 * - 备份 = 遍历各模块 [export],有数据的模块在 zip 里产出**自己那一个**条目文件;没数据就不产出;
 * - 入口文件(manifest.json)只登记"实际产出了条目"的模块,还原时以它为准分发;
 * - 还原 = 先遍历**所有**模块 [clear](把本机该模块数据清空,保证"备份里没有=还原后也没有"),
 *   再对入口文件里登记的模块逐个 [import];
 * - [hasData] 同时是"还原前保护快照是否需要做"的依据:整机一点数据都没有就不做临时备份。
 */
interface BackupModule {
    val id: String
    val entryName: String
    val maxBytes: Long
    fun hasData(context: Context): Boolean
    fun export(context: Context, target: File): Boolean
    fun clear(context: Context)
    fun import(context: Context, source: File)
}

object BackupModules {
    val all: List<BackupModule> = listOf(PrefsBackupModule, RoomBackupModule, SubsBackupModule)

    fun byId(id: String): BackupModule? = all.firstOrNull { it.id == id }

    fun anyHasData(context: Context): Boolean = anyHasData { it.hasData(context) }

    internal fun anyHasData(check: (BackupModule) -> Boolean): Boolean = all.any(check)
}

object PrefsBackupModule : BackupModule {
    override val id = "prefs"
    override val entryName = "prefs.json"
    override val maxBytes = BackupLimits.PREFS

    override fun hasData(context: Context): Boolean = PrefsDataStore.exportJson() != "{}"

    override fun export(context: Context, target: File): Boolean {
        val json = PrefsDataStore.exportJson()
        if (json == "{}") return false
        target.writeText(json, StandardCharsets.UTF_8)
        return true
    }

    override fun clear(context: Context) {
        // 私有键、配对码、SSL 与日志配置继续由可转移键规则排除。
        PrefsDataStore.replaceTransferableJson("{}")
    }

    override fun import(context: Context, source: File) {
        val text = source.inputStream().use {
            String(BackupContentHash.readBounded(it, maxBytes, source.length()), StandardCharsets.UTF_8)
        }
        if (PrefsDataStore.importJson(text) < 0) throw IOException("设置数据写入失败")
    }
}

object RoomBackupModule : BackupModule {
    override val id = "room"
    override val entryName = "room.db"
    override val maxBytes = BackupLimits.ROOM

    override fun hasData(context: Context): Boolean = AppDataManager.hasDatabaseFile()

    override fun export(context: Context, target: File): Boolean {
        if (!AppDataManager.backup(target)) return false
        AppDataManager.validateBackup(target)
        return true
    }

    override fun clear(context: Context) {
        // 备份中缺少 room 域时必须恢复缺失态，而非保留旧记录。
        AppDataManager.restoreMissingDatabase()
    }

    override fun import(context: Context, source: File) {
        if (!AppDataManager.restore(source)) throw IOException("播放历史/收藏还原失败")
    }
}

object SubsBackupModule : BackupModule {
    override val id = "subs"
    override val entryName = "subs.zip"
    override val maxBytes = BackupLimits.SUBS

    override fun hasData(context: Context): Boolean = SubscriptionImportFiles.runLocked<Boolean> {
        dirs(context).values.map { SubsBackupFiles.hasAnyFile(it) }.any { it }
    }

    override fun export(context: Context, target: File): Boolean = SubscriptionImportFiles.runLocked<Boolean> {
        val directories = dirs(context).filterValues { SubsBackupFiles.hasAnyFile(it) }
        if (directories.isEmpty()) return@runLocked false
        ShareArchives.zipDirectories(directories, target, BackupLimits.SUB_FILE, BackupLimits.SUBS, BackupLimits.SUB_FILES)
        BackupContentHash.subsSha256(target)
        true
    }

    override fun clear(context: Context) {
        SubscriptionImportFiles.runLocked<Unit> {
            for (dir in dirs(context).values) SubsBackupFiles.clearDirectory(dir)
        }
    }

    override fun import(context: Context, source: File) {
        SubscriptionImportFiles.runLocked<Unit> {
            SubsBackupFiles.importArchive(source, dirs(context))
        }
    }

    private fun dirs(context: Context): Map<String, File> {
        val imported = SubscriptionLibrary.importDir(context)
        // 目录访问器把普通文件冲突也表示为 null，仍检查预期路径才能区分冲突和无数据。
        val packages = LocalPackageImporter.packageRootDir(context)
            ?: File(imported.parentFile, BackupLimits.PACKAGE_ROOT)
        return linkedMapOf(BackupLimits.IMPORT_ROOT to imported, BackupLimits.PACKAGE_ROOT to packages)
    }
}

internal object BackupLimits {
    const val ARCHIVE = 1024L * 1024 * 1024
    const val MANIFEST = 256L * 1024
    const val PREFS = 32L * 1024 * 1024
    const val ROOM = 512L * 1024 * 1024
    const val SUBS = 256L * 1024 * 1024
    const val SUB_FILE = 64L * 1024 * 1024
    const val SUB_FILES = 500
    const val SUB_ENTRIES = 1024
    const val IMPORT_ROOT = "subscription_import"
    const val PACKAGE_ROOT = "local_packages"
}

internal object SubsBackupFiles {
    fun hasAnyFile(dir: File): Boolean = io {
        val root = checkedRoot(dir)
        if (!dir.exists()) return@io false
        var found = false
        walk(root, dir) { if (it.isFile) found = true }
        found
    }

    fun clearDirectory(dir: File) = io {
        val root = checkedRoot(dir)
        if (!dir.exists()) return@io
        if (!dir.isDirectory) throw IOException("订阅目录与普通文件冲突: $dir")
        val directory = if (dir.absoluteFile == root) dir else root
        for (child in children(directory)) deleteTree(root, child)
    }

    fun mergeTree(source: File, target: File) = io {
        val sourceRoot = checkedRoot(source)
        val targetRoot = checkedRoot(target)
        if (sourceRoot == targetRoot || sourceRoot.path.startsWith(targetRoot.path + File.separator)
            || targetRoot.path.startsWith(sourceRoot.path + File.separator)) throw IOException("订阅复制目录重叠")
        ensureDirectory(target)
        walk(sourceRoot, source) { child ->
            val relative = child.absolutePath.substring(sourceRoot.path.length + 1)
            val dest = File(targetRoot, relative)
            checkedChild(targetRoot, dest)
            if (child.isDirectory) {
                ensureDirectory(dest)
            } else {
                ensureDirectory(dest.parentFile ?: throw IOException("订阅目标缺少父目录"))
                if (dest.exists() && !dest.isFile) throw IOException("订阅目标与目录冲突: $dest")
                child.inputStream().use { input ->
                    dest.outputStream().use { output ->
                        BackupContentHash.consume(input, BackupLimits.SUB_FILE, child.length()) { buffer, count ->
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
    }

    fun importArchive(source: File, targets: Map<String, File>) {
        BackupContentHash.subsSha256(source)
        val extractDir = File(source.parentFile, "subs_extract_" + UUID.randomUUID())
        if (!extractDir.mkdir()) throw IOException("无法创建订阅还原目录")
        try {
            try {
                ShareArchives.extract(source, extractDir, BackupLimits.SUB_FILE, BackupLimits.SUBS)
            } catch (failure: SecurityException) {
                throw IOException("订阅源备份内容超限或非法", failure)
            }
            for (root in children(extractDir)) {
                val target = targets[root.name] ?: throw IOException("订阅源备份包含未知目录: " + root.name)
                if (!root.isDirectory) throw IOException("订阅源根条目不是目录")
                mergeTree(root, target)
            }
        } finally {
            extractDir.deleteRecursively()
        }
    }

    private fun checkedRoot(dir: File): File {
        val parent = dir.absoluteFile.parentFile ?: throw IOException("订阅目录不能是文件系统根目录")
        val expected = File(parent.canonicalFile, dir.name)
        if (dir.canonicalFile != expected) throw IOException("订阅目录包含路径逃逸: $dir")
        return expected
    }

    private fun checkedChild(root: File, file: File) {
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(root.path + File.separator) || canonical != file.absoluteFile)
            throw IOException("订阅文件包含路径逃逸: $file")
    }

    private fun children(dir: File): Array<File> = dir.listFiles() ?: throw IOException("无法读取订阅目录: $dir")

    private fun ensureDirectory(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建订阅目录: $dir")
        if (!dir.isDirectory) throw IOException("订阅目录与普通文件冲突: $dir")
    }

    private fun walk(root: File, directory: File, visit: (File) -> Unit) {
        if (!directory.isDirectory) throw IOException("订阅目录不存在或与普通文件冲突: $directory")
        val pending = ArrayDeque<File>()
        pending.add(if (directory.absoluteFile == root) directory else root)
        var entries = 0
        var files = 0
        var bytes = 0L
        while (pending.isNotEmpty()) {
            for (child in children(pending.removeFirst())) {
                if (++entries > BackupLimits.SUB_ENTRIES) throw IOException("订阅文件条目过多")
                checkedChild(root, child)
                if (child.isDirectory) {
                    pending.add(child)
                } else if (child.isFile) {
                    if (++files > BackupLimits.SUB_FILES || child.length() > BackupLimits.SUB_FILE)
                        throw IOException("订阅文件数量或尺寸超限")
                    bytes += child.length()
                    if (bytes > BackupLimits.SUBS) throw IOException("订阅文件总量超限")
                } else {
                    throw IOException("订阅文件不可读: $child")
                }
                visit(child)
            }
        }
    }

    private fun deleteTree(root: File, file: File) {
        checkedChild(root, file)
        if (file.isDirectory) for (child in children(file)) deleteTree(root, child)
        if (!file.delete()) throw IOException("删除订阅文件失败: $file")
    }

    private inline fun <T> io(action: () -> T): T = try {
        action()
    } catch (failure: SecurityException) {
        throw IOException("订阅文件不可访问", failure)
    }
}

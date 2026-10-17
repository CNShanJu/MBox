package com.github.tvbox.osc.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.github.tvbox.osc.bean.Subscription
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.transfer.SubscriptionImportFiles
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale

/**
 * 订阅内部库文件工具(订阅页文件操作的原样抽出):
 * 应用专属导入目录下的读写删除、clan:// 地址换算、导入副本与内容摘要。
 * 写入统一持 [SubscriptionImportFiles] 锁;删/读只认应用内部目录,防目录穿越。
 */
object SubscriptionLibrary {

    /** 本地导入文件内容判定的读取上限:订阅配置不会这么大,超限就不判定 */
    const val MAX_JUDGE_BYTES = 2L * 1024 * 1024
    /** 清单条目内嵌内容上限(导出清单里的本地条目),超限判定为无效 */
    private const val MAX_LIBRARY_FILE_BYTES = 8L * 1024 * 1024

    /** 应用专属导入目录路径；创建留给持锁的实际写入点。 */
    @JvmStatic
    @Throws(IOException::class)
    fun importDir(context: Context?): File {
        val external = context!!.getExternalFilesDir(null) ?: throw IOException("应用存储目录不可用")
        return File(external, "subscription_import")
    }

    /** 只在持有 SubscriptionImportFiles 锁的写入路径调用。 */
    @JvmStatic
    @Throws(IOException::class)
    fun ensureImportDir(context: Context?): File {
        val dir = importDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建订阅目录")
        return dir
    }

    /** 内部订阅文件 → clan:// 订阅地址(clan 本地服务按主存储相对路径取文件) */
    @JvmStatic
    fun clanPathOf(file: File?): String =
        "clan://localhost" + file!!.absolutePath.replaceFirst(Regex("^/storage/emulated/0"), "")

    /**
     * clan:// 地址 → 内部订阅库里的文件；**只认本应用内部目录下的路径**(防止删订阅时误删别处文件,或
     * 导出时读到不该读的文件);不在内部目录内返回 null。
     */
    @JvmStatic
    fun libraryFileOf(context: Context?, url: String?): File? {
        val prefix = "clan://localhost/"
        if (url == null || !url.startsWith(prefix)) return null
        return try {
            val file = File("/storage/emulated/0/" + url.substring(prefix.length)).canonicalFile
            val dir = importDir(context).canonicalFile
            if (file.path.startsWith(dir.path + File.separator)) file else null
        } catch (e: IOException) {
            null
        }
    }

    /**
     * 把文本内容落成内部订阅库文件(导出清单里的本地条目导入时用):
     * 文件名优先沿用清单里的 file(同名内容直接复用),否则按"名字_内容摘要"生成。
     */
    @JvmStatic
    fun writeLibraryFile(context: Context?, suggestedName: String?, displayName: String?, content: String?): File? {
        return try {
            val bytes = content!!.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_LIBRARY_FILE_BYTES) return null
            val suggested = suggestedName ?: ""
            val display = displayName ?: ""
            val base = sanitizeImportName(when {
                suggested.any { !Character.isWhitespace(it) } -> suggested
                display.any { !Character.isWhitespace(it) } -> display
                else -> "导入订阅"
            })
            val dot = base.lastIndexOf('.')
            val stem = if (dot > 0) base.substring(0, dot) else base
            val ext = if (dot > 0) base.substring(dot) else ".json"
            val fileName = stem + "_" + contentDigest(content) + ext
            SubscriptionImportFiles.runLocked {
                val file = File(ensureImportDir(context), fileName)
                val atomic = AtomicFile(file)
                val output = atomic.startWrite()
                try {
                    output.write(bytes)
                    atomic.finishWrite(output)
                } catch (failure: Throwable) {
                    atomic.failWrite(output)
                    throw failure
                }
                file
            }
        } catch (t: Throwable) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 写入内部订阅文件失败 (" + exceptionType(t) + ")")
            null
        }
    }

    /** 删除订阅时清掉它的内部副本(仅本地导入/JSON 导入的 clan:// 条目有);非内部文件空操作 */
    @JvmStatic
    fun deleteLibraryFileOf(context: Context?, sub: Subscription?) {
        if (sub == null) return
        try {
            SubscriptionImportFiles.runLocked {
                val file = libraryFileOf(context, sub.url)
                if (file != null && file.isFile) file.delete()
                null
            }
        } catch (t: Throwable) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 删除订阅内部文件失败 (" + exceptionType(t) + ")")
        }
    }

    /**
     * 未识别的 JSON(单源规则配置、整份配置等)按"名字_内容摘要"存成应用专属目录下的 json 文件;
     * 失败返回 null(已记日志),由调用方提示用户。
     */
    @JvmStatic
    fun saveJsonFile(context: Context?, name: String?, text: String?): File? {
        return try {
            val base = sanitizeImportName(name)
            val dot = base.lastIndexOf('.')
            val stem = if (dot > 0) base.substring(0, dot) else base
            val fileName = stem + "_" + contentDigest(text) + ".json"
            SubscriptionImportFiles.runLocked {
                val file = File(ensureImportDir(context), fileName)
                if (!file.exists()) {
                    FileOutputStream(file).use { out ->
                        out.write(text!!.toByteArray(StandardCharsets.UTF_8))
                    }
                }
                file
            }
        } catch (t: Throwable) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: JSON 导入保存本地文件失败 (" + exceptionType(t) + ")")
            null
        }
    }

    /**
     * 把 SAF 选中的文件内容复制进应用专属导入目录。
     * 使用 URI 摘要作为副本名,重复选择同一文件时复用已有副本。
     */
    @JvmStatic
    @JvmOverloads
    fun importCopyOf(context: Context?, uri: Uri?, displayName: String?, cancelled: () -> Boolean = { false }): File? {
        return try {
            if (cancelled()) return null
            val base = sanitizeImportName(displayName)
            val dot = base.lastIndexOf('.')
            val stem = if (dot > 0) base.substring(0, dot) else base
            val ext = if (dot > 0) base.substring(dot) else ""
            val fileName = stem + "_" + uriDigest(uri) + ext
            val existing = SubscriptionImportFiles.runLocked {
                if (cancelled()) return@runLocked null
                val file = File(importDir(context), fileName)
                if (file.exists()) file else null
            }
            if (existing != null) return existing
            // SAF providers can block on remote reads; stage outside the directory lock.
            val staged = File.createTempFile("subscription_copy_", ".tmp", context!!.cacheDir)
            try {
                val input = context.contentResolver.openInputStream(uri!!) ?: return null
                input.use {
                    FileOutputStream(staged).use { output -> copyStream(it, output, cancelled) }
                }
                SubscriptionImportFiles.runLocked {
                    if (cancelled()) return@runLocked null
                    val target = File(ensureImportDir(context), fileName)
                    if (!target.exists()) {
                        val atomic = AtomicFile(target)
                        val output = atomic.startWrite()
                        try {
                            FileInputStream(staged).use { stageIn -> copyStream(stageIn, output, cancelled) }
                            if (cancelled()) throw IOException("导入已取消")
                            atomic.finishWrite(output)
                        } catch (failure: Throwable) {
                            atomic.failWrite(output)
                            throw failure
                        }
                    }
                    target
                }
            } finally {
                staged.delete()
            }
        } catch (t: Throwable) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 本地文件复制失败 (" + exceptionType(t) + ")")
            null
        }
    }

    /**
     * 读本地导入文件文本用于内容判定;文件过大(订阅配置不可能这么大)或读不出返回 null,
     * 调用方按旧逻辑"原样当 clan:// 订阅加入",不因判定失败阻断导入。
     */
    @JvmStatic
    fun readImportText(file: File?): String? {
        if (file == null || file.length() > MAX_JUDGE_BYTES) return null
        return try {
            FileInputStream(file).use { input ->
                val buf = ByteArray(file.length().toInt())
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n <= 0) break
                    off += n
                }
                String(buf, 0, off, StandardCharsets.UTF_8)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 查询所选文档的显示名(取不到时用 uri 末段兜底) */
    @JvmStatic
    fun queryDisplayName(context: Context?, uri: Uri?): String? {
        return try {
            val resolver = context!!.contentResolver
            val cursor = resolver.query(uri!!, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?: return null
            try {
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } finally {
                cursor.close()
            }
        } catch (t: Throwable) {
            uri!!.lastPathSegment
        }
    }

    @JvmStatic
    fun isUnderPrimaryStorage(file: File?): Boolean {
        return try {
            val root = Environment.getExternalStorageDirectory().canonicalFile
            val candidate = file!!.canonicalFile
            candidate == root || candidate.path.startsWith(root.path + File.separator)
        } catch (t: Throwable) {
            false
        }
    }

    /** 文本内容摘要(导入副本命名去重用:同一文件/同一 JSON 重复导入复用已有副本) */
    @JvmStatic
    fun contentDigest(text: String?): String {
        val digest = try {
            MessageDigest.getInstance("SHA-256").digest(text!!.toByteArray(StandardCharsets.UTF_8))
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        }
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format(Locale.ROOT, "%02x", b))
        return sb.substring(0, 16)
    }

    private fun uriDigest(uri: Uri?): String = contentDigest(uri!!.toString())

    /** 清理文件名中不能出现在真实路径的字符,并确保带 txt/json 扩展名 */
    @JvmStatic
    fun sanitizeImportName(displayName: String?): String {
        var name = displayName!!.replace(Regex("[/\\\\:*?\"<>|\\u0000\\s]"), "_").trim { it <= ' ' }
        if (!name.lowercase(Locale.ROOT).endsWith(".txt") && !name.lowercase(Locale.ROOT).endsWith(".json")) {
            name += ".txt"
        }
        if (name.length > 80) name = name.substring(0, 80)
        return name
    }

    @Throws(IOException::class)
    private fun copyStream(input: InputStream, out: OutputStream, cancelled: () -> Boolean) {
        val buffer = ByteArray(8192)
        while (true) {
            if (cancelled()) throw IOException("导入已取消")
            val n = input.read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
    }

    private fun exceptionType(error: Throwable?): String = error?.javaClass?.simpleName ?: "未知错误"
}

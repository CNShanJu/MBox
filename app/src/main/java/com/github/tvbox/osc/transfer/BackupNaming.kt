package com.github.tvbox.osc.transfer

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地备份的命名规则与展示名。
 *
 * <ul>
 *   <li><b>文件名</b> = 数据内容摘要的前 {@link ID_LENGTH} 位。摘要由
 *       {@link BackupContentHash#sha256} 算（排除清单里的时间戳，只认各数据域的内容），
 *       所以同一份数据无论备份几次都会落到同一个文件名上 —— 去重是命名规则自带的，
 *       不需要额外的"已备份过"记录。</li>
 *   <li><b>名字</b>只用于展示，写在同名侧车文件 `xxx.zip.name` 里。改名不动文件、不动数据，
 *       也不影响还原（还原链路只读 ZIP 本身）。</li>
 * </ul>
 */
object BackupNaming {
    /**
     * 摘要取前 12 个十六进制字符（48 bit）。按备份的体量（同一台机器几十份）碰撞概率可以忽略，
     * 又比 64 位短得多，文件名仍然好念好认。
     */
    const val ID_LENGTH = 12

    /** 名字侧车文件后缀：`a1b2c3d4e5f6.zip` → `a1b2c3d4e5f6.zip.name` */
    private const val NAME_SUFFIX = ".name"

    /** 导出中途的临时文件后缀；只有 rename 成功的才会变成 .zip，残留的可以直接删。 */
    const val TEMP_SUFFIX = ".tmp"

    /** 名字长度上限：item 是单行省略，过长只会让人看不清，40 字足够描述场景。 */
    private const val MAX_NAME = 40

    const val TIME_PATTERN = "yyyy-MM-dd-HH-mm"

    /** 旧版备份用时间做文件名（`2026-10-17-22-12.zip`），没有侧车名字时原样展示。 */
    private val LEGACY_TIME = Regex("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}")

    /** 内容摘要 → 备份文件名。 */
    @JvmStatic
    fun fileName(contentId: String): String = contentId.take(ID_LENGTH).lowercase(Locale.ROOT) + ".zip"

    /** 时间命名格式（勾选"使用系统时间做名称"时给的名字）。 */
    @JvmStatic
    fun timeName(now: Long): String = SimpleDateFormat(TIME_PATTERN, Locale.US).format(Date(now))

    /** 用户输入的名字：去空白、限长；全空白返回空串（由调用方判为"没起名"）。 */
    @JvmStatic
    fun sanitize(raw: String?): String = raw?.trim()?.take(MAX_NAME) ?: ""

    @JvmStatic
    fun isBackup(file: File): Boolean =
            file.isFile && file.name.endsWith(".zip", ignoreCase = true)

    private fun nameFile(zip: File): File = File(zip.parentFile, zip.name + NAME_SUFFIX)

    @JvmStatic
    fun readName(zip: File): String? {
        val file = nameFile(zip)
        if (!file.isFile || file.length() <= 0 || file.length() > 4_096) return null
        return try {
            sanitize(file.readText(StandardCharsets.UTF_8)).ifEmpty { null }
        } catch (ignored: Exception) {
            // 名字只是展示信息，读不到就按无名字处理，不影响备份与还原
            null
        }
    }

    /** 写入/覆盖展示名；写失败会抛出，让调用方把"改名没成功"如实告诉用户。 */
    @Throws(IOException::class)
    @JvmStatic
    fun writeName(zip: File, name: String) {
        val clean = sanitize(name)
        if (clean.isEmpty()) throw IOException("备份名称不能为空")
        try {
            nameFile(zip).writeText(clean, StandardCharsets.UTF_8)
        } catch (error: Exception) {
            throw IOException("备份名称写入失败", error)
        }
    }

    @JvmStatic
    fun deleteName(zip: File) {
        nameFile(zip).delete()
    }

    /**
     * 列表里显示的名字：
     * 用户起过的名字 → 旧版时间文件名 → 按文件修改时间兜底（hash 文件名本身不可读，
     * 给不出更好的东西，但至少能看出"哪一份更新"）。
     */
    @JvmStatic
    fun displayName(zip: File): String {
        readName(zip)?.let { return it }
        val base = zip.name.removeSuffix(".zip")
        if (LEGACY_TIME.matches(base)) return base
        return "备份 " + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(zip.lastModified()))
    }
}

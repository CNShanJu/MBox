package com.github.tvbox.osc.util

import java.io.File
import java.io.IOException
import java.util.UUID

/** 只拥有本次暂存文件；发布后的草稿与旧图统一交给显式缓存清理。 */
internal object PageBackgroundFiles {
    @JvmStatic
    fun copyDraft(source: File, directory: File): File = stageDraft(directory) { staging ->
        source.inputStream().use { input ->
            staging.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
    }

    /** 暂存扩展名不属于可清理图片；写入和关闭成功后才发布完整图片。 */
    fun stageDraft(directory: File, write: (File) -> Unit): File {
        val staging = File.createTempFile("bg_import_", ".tmp", directory)
        try {
            write(staging)
            if (!staging.isFile || staging.length() <= 0) throw IOException("图片复制失败")
            val target = File(directory, "custom_bg_${UUID.randomUUID()}.webp")
            // 不覆盖任何已有文件，包括其他编辑会话尚未确认的草稿。
            if (target.exists() || !staging.renameTo(target)) throw IOException("图片保存失败")
            return target
        } finally {
            staging.delete()
        }
    }
}

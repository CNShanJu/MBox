package com.github.tvbox.osc.util

import java.io.File
import java.io.IOException
import java.util.UUID

/** 与 Android 无关的暂存所有权规则；只清理本次创建且尚未发布的文件。 */
internal object ImportFileStaging {
    fun copyToTemp(directory: File, copy: (File) -> Unit): File {
        val target = File.createTempFile("subscription_import_", ".zip", directory)
        try {
            copy(target)
            return target
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    fun <T> withDirectory(root: File, name: String, action: (File) -> T): T {
        val staging = File(root, name + "_tmp_" + UUID.randomUUID())
        if (!staging.mkdirs()) throw IOException("无法创建解压目录")
        try {
            return action(staging)
        } finally {
            // rename 成功后原暂存路径已不存在，绝不删除正式目录。
            staging.deleteRecursively()
        }
    }

    /** 调用者必须持 SubscriptionImportFiles 共用锁。scan 只接收最终路径，避免改写泄漏暂存路径。 */
    fun <T> publishValidated(
        staging: File, target: File, cancelled: () -> Boolean,
        scan: (File) -> T, valid: (T) -> Boolean
    ): T {
        checkActive(cancelled)
        if (target.exists()) {
            val existing = scan(target)
            checkActive(cancelled)
            if (!valid(existing)) throw IOException("已有本地包不可用，请先处理原订阅")
            return existing
        }
        if (!staging.renameTo(target)) throw IOException("无法发布本地包")
        var published = false
        try {
            val result = scan(target)
            checkActive(cancelled)
            if (!valid(result)) throw IOException("本地包中未找到可用的订阅配置")
            published = true
            return result
        } finally {
            // 此时仍持锁且没有向调用方暴露目录；只回滚本轮 rename 出来的目录。
            if (!published) target.deleteRecursively()
        }
    }

    fun checkActive(cancelled: () -> Boolean) {
        if (cancelled()) throw IOException("导入已取消")
    }
}

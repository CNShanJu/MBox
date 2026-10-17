package com.github.tvbox.osc.util.cache

import android.content.Context
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.storage.theme.ThemeBackgroundLibrary
import com.github.tvbox.osc.storage.theme.ThemeStore
import com.github.tvbox.osc.util.PageBackgroundStore
import java.io.File

/**
 * 背景图「本地缓存」盘点与清理(供「本地缓存」二级页使用)。
 *
 * 背景图在应用私有目录里落在两处:
 * <ul>
 *   <li>全局底图 {@code filesDir/page_bg/}({@link PageBackgroundStore}):当前这张是「使用中」,
 *       换图/恢复默认留下的旧副本是遗留;</li>
 *   <li>主题背景图库 {@code filesDir/theme_bg/}({@link ThemeBackgroundLibrary}):被任何主题引用的图算「主题」,
 *       孤儿(未被引用的残留)是遗留。</li>
 * </ul>
 * 只有「遗留」(orphan)可以删除 —— 生效图、主题引用与父页尚未确认的草稿均受保护。
 * 磁盘 IO 务必在后台线程调用。
 */
class BackgroundCache @JvmOverloads constructor(
    context: Context,
    protectedDraftPaths: Collection<String> = emptyList()
) {

    /** CURRENT 同时包含父页正在预览的草稿，不额外引入可删除的草稿分类。 */
    enum class Kind { CURRENT, THEME, ORPHAN }

    /** 一张本地背景图 + 它的归属(决定能否删除 / 显示什么角标)与原始尺寸(瀑布流等比展示用) */
    class Image(val file: File, val kind: Kind) {
        /** 原始像素宽高(解码失败为 0,展示时退回固定比例) */
        var width: Int = 0
            internal set
        var height: Int = 0
            internal set

        /** 等比高宽比(height/width);拿不到尺寸时按 3:4 兜底 */
        val aspectRatio: Float
            get() = if (width > 0 && height > 0) height.toFloat() / width else 4f / 3f

        val deletable: Boolean get() = kind == Kind.ORPHAN
    }

    private val app: Context = context.applicationContext
    private val pageDir: File = PageBackgroundStore.dir(app)
    private val themeDir = File(app.filesDir, ThemeBackgroundLibrary.DIR)
    // 入参是父页发出的不可变快照；不持有父 Activity 或其可变草稿。
    private val draftPaths = protectedDraftPaths.toSet()

    private fun readProtection(): BackgroundFileRules.Protection {
        val current = listOf(PageBackgroundStore.currentUserPath(), SystemConfig.getPageBackgroundPath())
        val themePaths = ThemeStore.referencedBackgrounds().map { File(themeDir, it).absolutePath } +
                SystemConfig.getThemeDefaultBackground()
        return BackgroundFileRules.Protection(current, themePaths, draftPaths)
    }

    /** 列出本地保存的所有背景图(当前/草稿 → 主题 → 遗留,同组内按文件名排序) */
    fun listImages(): List<Image> {
        val images = ArrayList<Image>()
        val protection = readProtection()
        for (directory in listOf(pageDir, themeDir)) {
            directory.listFiles()?.forEach { file ->
                val managed = BackgroundFileRules.managedFile(app.filesDir, pageDir, themeDir, file)
                    ?: return@forEach
                val kind = when {
                    protection.isCurrentOrDraft(managed) -> Kind.CURRENT
                    protection.isTheme(managed) -> Kind.THEME
                    else -> Kind.ORPHAN
                }
                images.add(Image(managed, kind))
            }
        }

        // 解码图片头拿原始尺寸(只读 bounds,不解整张;瀑布流等比缩放要用)
        for (image in images) decodeSize(image)

        return images.sortedWith(compareBy({ it.kind.ordinal }, { it.file.name }))
    }

    private fun decodeSize(image: Image) {
        try {
            val opts = android.graphics.BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeFile(image.file.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                image.width = opts.outWidth
                image.height = opts.outHeight
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 列表 kind 仅作展示，实际删除逐项重读引用并复查路径；返回真正删除的数量。 */
    @JvmOverloads
    fun delete(images: Collection<Image>, isCancelled: () -> Boolean = { false }): Int {
        var removed = 0
        for (image in images) {
            if (isCancelled()) break
            // 与主题保存串行，避免主题引用在复查与删除之间发生变化。
            val deleted = ThemeStore.runWithStorageLock {
                if (isCancelled()) false else BackgroundFileRules.deleteUnreferenced(
                    app.filesDir, pageDir, themeDir, image.file, ::readProtection
                )
            }
            if (deleted) removed++
        }
        return removed
    }
}

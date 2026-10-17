package com.github.tvbox.osc.util.cache

import java.io.File

/** 不依赖 Android 的路径与引用规则，盘点和实际删除共用。 */
internal object BackgroundFileRules {
    private val imageName = Regex("[A-Za-z0-9_-]+\\.(webp|png|jpg|jpeg)", RegexOption.IGNORE_CASE)
    private val pageName = Regex("custom_bg(?:_[A-Za-z0-9_-]+)?\\.(webp|png|jpg|jpeg)", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun isPageImageName(name: String?): Boolean = name != null && pageName.matches(name)

    /** 只接受两处管理目录的直接子文件，不接受穿越、目录别名/符号链接、临时文件或任意前缀文件。 */
    fun managedFile(filesDir: File, pageDir: File, themeDir: File, file: File): File? {
        if (!file.isAbsolute || file.path.replace('\\', '/').split('/').any { it == ".." }) return null
        val root = filesDir.canonicalFile
        val parent = file.parentFile?.canonicalFile ?: return null
        val allowed = when (parent) {
            pageDir.canonicalFile -> isPageImageName(file.name) && parent == File(root, pageDir.name)
            themeDir.canonicalFile -> imageName.matches(file.name) && parent == File(root, themeDir.name)
            else -> false
        }
        if (!allowed) return null
        val canonical = file.canonicalFile
        if (canonical != File(parent, file.name) || !canonical.isFile) return null
        return canonical
    }

    class Protection(
        currentPaths: Collection<String?>,
        themePaths: Collection<String?>,
        draftPaths: Collection<String?>
    ) {
        private val current = canonicalPaths(currentPaths)
        private val themes = canonicalPaths(themePaths)
        private val drafts = canonicalPaths(draftPaths)

        fun isCurrentOrDraft(file: File): Boolean = file.canonicalPath.let { it in current || it in drafts }
        fun isTheme(file: File): Boolean = file.canonicalPath in themes
        fun protects(file: File): Boolean = isCurrentOrDraft(file) || isTheme(file)
    }

    private fun canonicalPaths(paths: Collection<String?>): Set<String> = paths.mapNotNull { path ->
        if (path.isNullOrBlank()) null else File(path).takeIf { it.isAbsolute }?.canonicalPath
    }.toSet()

    /** 不接收列表 kind：每张删除前重新取保护集合；读取或路径校验失败时一律保留。 */
    fun deleteUnreferenced(
        filesDir: File, pageDir: File, themeDir: File, file: File,
        readProtection: () -> Protection
    ): Boolean = runCatching {
        val protection = readProtection()
        val managed = managedFile(filesDir, pageDir, themeDir, file) ?: return false
        if (protection.protects(managed)) return false
        managed.delete()
    }.getOrDefault(false)
}
